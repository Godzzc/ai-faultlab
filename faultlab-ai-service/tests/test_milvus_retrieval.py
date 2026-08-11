from fastapi.testclient import TestClient

from app import main
from app.config import settings
from app.embedding_client import EmbeddingClient
from app.main import app
from app.retrieval.index_state_store import IndexStateStore
from app.retrieval.milvus_runbook_retriever import MilvusRunbookRetriever
from app.retrieval.milvus_store import MilvusStore, stable_chunk_id
from app.retrieval.models import RunbookChunk
from app.retrieval.runbook_indexer import RunbookIndexer, RunbookIndexResult
from app.schemas import DiagnosisRequest


client = TestClient(app)


def mq_chunk(section="Core Metrics"):
    return RunbookChunk(
        docId="mq-backlog",
        title="MQ backlog runbook",
        faultType="MQ_BACKLOG",
        section=section,
        content="Check publishCount, consumeCount, backlogCount, and avgConsumeMs.",
        keywords=["RabbitMQ", "publishCount", "consumeCount", "backlogCount"],
        score=0.92,
    )


def write_runbook(runbooks_dir, name="mq-backlog.md", content_suffix=""):
    runbooks_dir.mkdir(parents=True, exist_ok=True)
    path = runbooks_dir / name
    path.write_text(
        "\n".join([
            "---",
            "docId: mq-backlog",
            "title: MQ backlog runbook",
            "faultType: MQ_BACKLOG",
            "keywords: RabbitMQ, publishCount, consumeCount",
            "---",
            "",
            "# MQ backlog runbook",
            "",
            "## Core Metrics",
            "Check publishCount and consumeCount.",
            content_suffix,
        ]),
        encoding="utf-8",
    )
    return path


class FakeEmbeddingClient:
    def __init__(self, fail_on_text=""):
        self.fail_on_text = fail_on_text
        self.calls = []

    def embed_text(self, text):
        self.calls.append(text)
        if self.fail_on_text and self.fail_on_text in text:
            raise RuntimeError("embedding failed")
        return [0.2] * 1024


class FakeMilvusStore:
    collection_name = settings.milvus_collection_name

    def __init__(self):
        self.upserted = []
        self.deleted_doc_ids = []
        self.deleted_chunk_ids = []

    def upsert_chunks(self, chunks, embeddings):
        self.upserted.append((chunks, embeddings))
        return len(chunks)

    def delete_by_doc_id(self, doc_id):
        self.deleted_doc_ids.append(doc_id)
        return 1

    def delete_by_chunk_ids(self, chunk_ids):
        self.deleted_chunk_ids.append(chunk_ids)
        return len(chunk_ids)

    def chunk_to_entity(self, chunk, embedding):
        return MilvusStore().chunk_to_entity(chunk, embedding)


def build_request():
    return DiagnosisRequest.model_validate({
        "experiment": {
            "experimentId": "exp_xxx",
            "scenarioCode": "MQ_BACKLOG",
            "status": "RUNNING",
            "traceId": "trace_xxx",
        },
        "metrics": [
            {
                "metricName": "publishCount",
                "metricValue": "10",
                "metricUnit": "count",
                "component": "RabbitMQ",
            }
        ],
        "traceTree": {
            "traceId": "trace_xxx",
            "roots": [],
        },
        "ruleResult": {
            "experimentId": "exp_xxx",
            "faultType": "MQ_BACKLOG",
            "faultName": "MQ backlog",
            "confidence": 0.85,
            "matched": True,
            "reason": "publish rate is higher than consume rate",
            "evidence": ["publishCount=10", "consumeCount=1"],
            "suggestions": ["Check consumer delay"],
        },
    })


def test_embedding_client_uses_text_embedding_v4_and_dimension(monkeypatch):
    calls = []

    class FakeEmbeddings:
        def create(self, **kwargs):
            calls.append(kwargs)
            item = type("EmbeddingItem", (), {"embedding": [0.1] * settings.embedding_dimension})()
            return type("EmbeddingResponse", (), {"data": [item]})()

    class FakeOpenAI:
        def __init__(self, **kwargs):
            self.embeddings = FakeEmbeddings()

    monkeypatch.setattr("app.embedding_client.OpenAI", FakeOpenAI)
    monkeypatch.setattr(settings, "dashscope_api_key", "test-key")

    vector = EmbeddingClient().embed_text("mq backlog")

    assert len(vector) == settings.embedding_dimension
    assert calls[0]["dimensions"] == 1024
    assert calls[0]["encoding_format"] == "float"


def test_embedding_client_can_be_mocked_for_batch_embeddings(monkeypatch):
    class FakeEmbeddings:
        def create(self, **kwargs):
            return type("EmbeddingResponse", (), {
                "data": [
                    type("EmbeddingItem", (), {"embedding": [0.1, 0.2]})(),
                    type("EmbeddingItem", (), {"embedding": [0.3, 0.4]})(),
                ]
            })()

    class FakeOpenAI:
        def __init__(self, **kwargs):
            self.embeddings = FakeEmbeddings()

    monkeypatch.setattr("app.embedding_client.OpenAI", FakeOpenAI)
    monkeypatch.setattr(settings, "dashscope_api_key", "test-key")

    assert EmbeddingClient().embed_texts(["a", "b"]) == [[0.1, 0.2], [0.3, 0.4]]


def test_milvus_store_collection_schema_uses_configured_dimension(monkeypatch):
    created_fields = []

    class FakeDataType:
        VARCHAR = "VARCHAR"
        FLOAT_VECTOR = "FLOAT_VECTOR"

    class FakeFieldSchema:
        def __init__(self, **kwargs):
            created_fields.append(kwargs)

    class FakeCollectionSchema:
        def __init__(self, **kwargs):
            self.kwargs = kwargs

    class FakeCollection:
        def __init__(self, name, schema=None):
            self.name = name
            self.schema = schema

        def has_index(self):
            return False

        def create_index(self, **kwargs):
            self.index = kwargs

        def load(self):
            self.loaded = True

    class FakeUtility:
        @staticmethod
        def has_collection(name):
            return False

    monkeypatch.setattr(MilvusStore, "_connect", lambda self: None)
    monkeypatch.setattr(
        MilvusStore,
        "_milvus_types",
        lambda self: (FakeUtility, FakeCollection, FakeFieldSchema, FakeCollectionSchema, FakeDataType),
    )

    MilvusStore(dimension=1024).ensure_collection()

    embedding_fields = [field for field in created_fields if field.get("name") == "embedding"]
    assert embedding_fields[0]["dim"] == 1024


def test_milvus_store_delete_by_doc_id_uses_doc_id_filter(monkeypatch):
    calls = []

    class FakeCollection:
        def __init__(self, name):
            self.name = name

        def delete(self, expr):
            calls.append(expr)
            return {"delete_count": 2}

        def flush(self):
            pass

    monkeypatch.setattr(MilvusStore, "ensure_collection", lambda self: None)
    monkeypatch.setattr(MilvusStore, "_milvus_types", lambda self: (None, FakeCollection, None, None, None))

    deleted_count = MilvusStore().delete_by_doc_id("mq-backlog")

    assert deleted_count == 2
    assert calls == ['docId == "mq-backlog"']


def test_milvus_store_delete_by_chunk_ids_uses_id_filter(monkeypatch):
    calls = []

    class FakeCollection:
        def __init__(self, name):
            self.name = name

        def delete(self, expr):
            calls.append(expr)
            return {"delete_count": 2}

        def flush(self):
            pass

    monkeypatch.setattr(MilvusStore, "ensure_collection", lambda self: None)
    monkeypatch.setattr(MilvusStore, "_milvus_types", lambda self: (None, FakeCollection, None, None, None))

    deleted_count = MilvusStore().delete_by_chunk_ids(["id-a", "id-b"])

    assert deleted_count == 2
    assert calls == ['id in ["id-a", "id-b"]']


def test_runbook_indexer_prepares_milvus_entities():
    chunk = mq_chunk()
    store = MilvusStore()
    indexer = RunbookIndexer(
        embedding_client=object(),
        milvus_store=store,
        chunk_loader=object(),
        state_store=IndexStateStore(state_path="unused.json"),
    )

    entity = indexer.prepare_entities([chunk], [[0.1] * 1024])[0]

    assert entity["id"] == stable_chunk_id(chunk)
    assert entity["docId"] == "mq-backlog"
    assert entity["embedding"] == [0.1] * 1024


def test_index_state_store_missing_file_returns_empty(tmp_path):
    store = IndexStateStore(tmp_path / "missing.json")

    assert store.load_state() == {"documents": {}}


def test_index_state_store_saves_and_reads_state(tmp_path):
    store = IndexStateStore(tmp_path / "state.json")
    state = {"documents": {"mq-backlog": {"contentHash": "abc"}}}

    store.save_state(state)

    assert store.load_state() == state


def test_index_state_store_corrupt_json_returns_empty(tmp_path, caplog):
    path = tmp_path / "state.json"
    path.write_text("{bad json", encoding="utf-8")

    assert IndexStateStore(path).load_state() == {"documents": {}}
    assert "Failed to load runbook index state" in caplog.text


def test_runbook_indexer_first_run_writes_chunks_and_state(tmp_path):
    runbooks_dir = tmp_path / "runbooks"
    write_runbook(runbooks_dir)
    state_store = IndexStateStore(tmp_path / "state.json")
    milvus_store = FakeMilvusStore()
    indexer = RunbookIndexer(
        embedding_client=FakeEmbeddingClient(),
        milvus_store=milvus_store,
        runbooks_dir=runbooks_dir,
        state_store=state_store,
    )

    result = indexer.index_runbooks()

    assert result.indexedCount == 1
    assert result.skippedCount == 0
    assert result.deletedCount == 1
    assert result.failedCount == 0
    assert result.indexedDocuments == ["mq-backlog"]
    assert state_store.load_state()["documents"]["mq-backlog"]["chunkIds"]


def test_runbook_indexer_second_run_skips_unchanged_document(tmp_path):
    runbooks_dir = tmp_path / "runbooks"
    write_runbook(runbooks_dir)
    state_store = IndexStateStore(tmp_path / "state.json")

    first_store = FakeMilvusStore()
    RunbookIndexer(
        embedding_client=FakeEmbeddingClient(),
        milvus_store=first_store,
        runbooks_dir=runbooks_dir,
        state_store=state_store,
    ).index_runbooks()

    second_store = FakeMilvusStore()
    result = RunbookIndexer(
        embedding_client=FakeEmbeddingClient(),
        milvus_store=second_store,
        runbooks_dir=runbooks_dir,
        state_store=state_store,
    ).index_runbooks()

    assert result.indexedCount == 0
    assert result.skippedCount == 1
    assert second_store.upserted == []
    assert second_store.deleted_doc_ids == []


def test_runbook_indexer_force_rebuild_reindexes_unchanged_document(tmp_path):
    runbooks_dir = tmp_path / "runbooks"
    write_runbook(runbooks_dir)
    state_store = IndexStateStore(tmp_path / "state.json")
    RunbookIndexer(
        embedding_client=FakeEmbeddingClient(),
        milvus_store=FakeMilvusStore(),
        runbooks_dir=runbooks_dir,
        state_store=state_store,
    ).index_runbooks()

    milvus_store = FakeMilvusStore()
    result = RunbookIndexer(
        embedding_client=FakeEmbeddingClient(),
        milvus_store=milvus_store,
        runbooks_dir=runbooks_dir,
        state_store=state_store,
    ).index_runbooks(force_rebuild=True)

    assert result.forceRebuild is True
    assert result.indexedCount == 1
    assert result.skippedCount == 0
    assert milvus_store.deleted_doc_ids == ["mq-backlog"]


def test_runbook_indexer_reindexes_when_document_content_changes(tmp_path):
    runbooks_dir = tmp_path / "runbooks"
    path = write_runbook(runbooks_dir)
    state_store = IndexStateStore(tmp_path / "state.json")
    RunbookIndexer(
        embedding_client=FakeEmbeddingClient(),
        milvus_store=FakeMilvusStore(),
        runbooks_dir=runbooks_dir,
        state_store=state_store,
    ).index_runbooks()

    path.write_text(path.read_text(encoding="utf-8") + "\nchanged", encoding="utf-8")
    milvus_store = FakeMilvusStore()
    result = RunbookIndexer(
        embedding_client=FakeEmbeddingClient(),
        milvus_store=milvus_store,
        runbooks_dir=runbooks_dir,
        state_store=state_store,
    ).index_runbooks()

    assert result.indexedCount == 1
    assert result.skippedCount == 0
    assert milvus_store.deleted_doc_ids == ["mq-backlog"]


def test_runbook_indexer_removes_state_when_runbook_file_deleted(tmp_path):
    runbooks_dir = tmp_path / "runbooks"
    path = write_runbook(runbooks_dir)
    state_store = IndexStateStore(tmp_path / "state.json")
    RunbookIndexer(
        embedding_client=FakeEmbeddingClient(),
        milvus_store=FakeMilvusStore(),
        runbooks_dir=runbooks_dir,
        state_store=state_store,
    ).index_runbooks()
    path.unlink()

    milvus_store = FakeMilvusStore()
    result = RunbookIndexer(
        embedding_client=FakeEmbeddingClient(),
        milvus_store=milvus_store,
        runbooks_dir=runbooks_dir,
        state_store=state_store,
    ).index_runbooks()

    assert result.deletedCount == 1
    assert milvus_store.deleted_doc_ids == ["mq-backlog"]
    assert state_store.load_state()["documents"] == {}


def test_runbook_indexer_embedding_failure_continues_other_documents(tmp_path):
    runbooks_dir = tmp_path / "runbooks"
    write_runbook(runbooks_dir, name="mq-backlog.md")
    (runbooks_dir / "thread.md").write_text(
        "\n".join([
            "---",
            "docId: thread-pool-saturation",
            "title: Thread pool runbook",
            "faultType: THREAD_POOL_SATURATION",
            "keywords: activeThreadCount",
            "---",
            "",
            "# Thread pool runbook",
            "",
            "## Core Metrics",
            "activeThreadCount queueSize",
        ]),
        encoding="utf-8",
    )
    milvus_store = FakeMilvusStore()
    result = RunbookIndexer(
        embedding_client=FakeEmbeddingClient(fail_on_text="thread-pool-saturation"),
        milvus_store=milvus_store,
        runbooks_dir=runbooks_dir,
        state_store=IndexStateStore(tmp_path / "state.json"),
    ).index_runbooks()

    assert result.indexedCount == 1
    assert result.failedCount == 1
    assert result.status == "partial_success"
    assert result.indexedDocuments == ["mq-backlog"]
    assert result.failedDocuments == ["thread-pool-saturation"]


def test_runbook_index_api_returns_indexed_count(monkeypatch):
    class FakeIndexer:
        def index_runbooks(self, force_rebuild=False):
            assert force_rebuild is False
            return RunbookIndexResult(indexedCount=12, forceRebuild=force_rebuild)

    monkeypatch.setattr(main, "RunbookIndexer", FakeIndexer)

    response = client.post("/ai/runbooks/index")

    assert response.status_code == 200
    body = response.json()
    assert body["indexedCount"] == 12
    assert body["skippedCount"] == 0
    assert body["deletedCount"] == 0
    assert body["failedCount"] == 0
    assert body["forceRebuild"] is False


def test_runbook_index_api_accepts_force_rebuild(monkeypatch):
    class FakeIndexer:
        def index_runbooks(self, force_rebuild=False):
            assert force_rebuild is True
            return RunbookIndexResult(indexedCount=3, forceRebuild=force_rebuild)

    monkeypatch.setattr(main, "RunbookIndexer", FakeIndexer)

    response = client.post("/ai/runbooks/index", json={"forceRebuild": True})

    assert response.status_code == 200
    assert response.json()["forceRebuild"] is True


def test_milvus_runbook_retriever_returns_store_chunks():
    class FakeEmbeddingClient:
        def __init__(self):
            self.text = ""

        def embed_text(self, text):
            self.text = text
            return [0.1] * 1024

    class FakeStore:
        def search(self, embedding, fault_type="", top_k=3):
            assert embedding == [0.1] * 1024
            assert fault_type == "MQ_BACKLOG"
            assert top_k == 3
            return [mq_chunk()]

    embedding_client = FakeEmbeddingClient()
    retriever = MilvusRunbookRetriever(
        embedding_client=embedding_client,
        milvus_store=FakeStore(),
    )

    chunks = retriever.retrieve(build_request(), {"nodeCount": 1}, top_k=3)

    assert chunks == [mq_chunk()]
    assert "publish rate is higher than consume rate" in embedding_client.text


def test_milvus_runbook_retriever_query_contains_rule_and_metrics():
    retriever = MilvusRunbookRetriever(
        embedding_client=object(),
        milvus_store=object(),
    )

    query = retriever.build_query(build_request(), {"nodeCount": 2, "slowSpans": []})

    assert "MQ_BACKLOG" in query
    assert "publish rate is higher than consume rate" in query
    assert "publishCount=10count" in query
    assert "trace nodeCount=2" in query
