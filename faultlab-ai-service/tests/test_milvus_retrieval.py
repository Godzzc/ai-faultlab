from fastapi.testclient import TestClient

from app import main
from app.config import settings
from app.embedding_client import EmbeddingClient
from app.main import app
from app.retrieval.milvus_runbook_retriever import MilvusRunbookRetriever
from app.retrieval.milvus_store import MilvusStore, stable_chunk_id
from app.retrieval.models import RunbookChunk
from app.retrieval.runbook_indexer import RunbookIndexer
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
    assert calls[0]["model"] == "text-embedding-v4"
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


def test_runbook_indexer_prepares_milvus_entities():
    chunk = mq_chunk()
    store = MilvusStore()
    indexer = RunbookIndexer(
        embedding_client=object(),
        milvus_store=store,
        chunk_loader=object(),
    )

    entity = indexer.prepare_entities([chunk], [[0.1] * 1024])[0]

    assert entity["id"] == stable_chunk_id(chunk)
    assert entity["docId"] == "mq-backlog"
    assert entity["embedding"] == [0.1] * 1024


def test_runbook_indexer_skips_chunk_when_embedding_fails():
    class FakeLoader:
        def _load_chunks(self):
            return [mq_chunk("A"), mq_chunk("B")]

    class FakeEmbeddingClient:
        def embed_text(self, text):
            if "section: A" in text:
                raise RuntimeError("embedding failed")
            return [0.2] * 1024

    class FakeStore:
        def __init__(self):
            self.chunks = []
            self.embeddings = []

        def upsert_chunks(self, chunks, embeddings):
            self.chunks = chunks
            self.embeddings = embeddings
            return len(chunks)

    store = FakeStore()
    indexer = RunbookIndexer(
        embedding_client=FakeEmbeddingClient(),
        milvus_store=store,
        chunk_loader=FakeLoader(),
    )

    assert indexer.index_runbooks() == 1
    assert store.chunks[0].section == "B"


def test_runbook_index_api_returns_indexed_count(monkeypatch):
    class FakeIndexer:
        def index_runbooks(self):
            return 12

    monkeypatch.setattr(main, "RunbookIndexer", FakeIndexer)

    response = client.post("/ai/runbooks/index")

    assert response.status_code == 200
    assert response.json() == {
        "indexedCount": 12,
        "collectionName": settings.milvus_collection_name,
        "status": "success",
    }


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
