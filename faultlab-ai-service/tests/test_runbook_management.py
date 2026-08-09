from pathlib import Path

from fastapi.testclient import TestClient

from app import main
from app.main import app
from app.retrieval.index_state_store import IndexStateStore
from app.retrieval.runbook_indexer import RunbookIndexResult
from app.runbooks.index_task_models import RunbookIndexTaskRequest, RunbookIndexTaskStatus
from app.runbooks.index_task_service import RunbookIndexTaskService
from app.runbooks.index_task_store import RunbookIndexTaskStore
from app.runbooks.models import RunbookSaveRequest
from app.runbooks.service import RunbookManagementService, RunbookValidationError

client = TestClient(app)


def write_runbook(runbooks_dir: Path, doc_id="mq-backlog", title="MQ backlog") -> Path:
    runbooks_dir.mkdir(parents=True, exist_ok=True)
    path = runbooks_dir / f"{doc_id}.md"
    path.write_text(
        "\n".join([
            "---",
            f"docId: {doc_id}",
            f"title: {title}",
            "faultType: MQ_BACKLOG",
            "keywords: RabbitMQ, publishCount, consumeCount",
            "---",
            "",
            f"# {title}",
            "",
            "## Core Metrics",
            "Check publishCount and consumeCount.",
            "",
            "## Fix",
            "Increase consumers.",
            "",
        ]),
        encoding="utf-8",
    )
    return path


class FakeIndexer:
    def __init__(self, result=None, exception=None):
        self.result = result or RunbookIndexResult(indexedCount=1, indexedDocuments=["mq-backlog"])
        self.exception = exception
        self.calls = []

    def index_runbooks(self, force_rebuild=False):
        self.calls.append(force_rebuild)
        if self.exception:
            raise self.exception
        return self.result


def save_request(doc_id="mq-backlog", raw_markdown=""):
    return RunbookSaveRequest.model_validate({
        "docId": doc_id,
        "title": "MQ backlog",
        "faultType": "MQ_BACKLOG",
        "keywords": ["RabbitMQ", "publishCount"],
        "rawMarkdown": raw_markdown or "# MQ backlog\n\n## Core Metrics\n\npublishCount consumeCount",
    })


def test_list_runbooks_returns_local_markdown(tmp_path):
    runbooks_dir = tmp_path / "runbooks"
    write_runbook(runbooks_dir)
    service = RunbookManagementService(runbooks_dir, IndexStateStore(tmp_path / "state.json"))

    items = service.list_runbooks()

    assert len(items) == 1
    assert items[0].doc_id == "mq-backlog"
    assert items[0].section_count == 2


def test_get_runbook_returns_detail_sections_and_content_hash(tmp_path):
    runbooks_dir = tmp_path / "runbooks"
    write_runbook(runbooks_dir)
    service = RunbookManagementService(runbooks_dir, IndexStateStore(tmp_path / "state.json"))

    detail = service.get_runbook("mq-backlog")

    assert detail.doc_id == "mq-backlog"
    assert detail.sections[0].section == "Core Metrics"
    assert detail.content_hash
    assert detail.raw_markdown


def test_invalid_doc_id_is_rejected(tmp_path):
    service = RunbookManagementService(tmp_path / "runbooks", IndexStateStore(tmp_path / "state.json"))

    try:
        service.get_runbook("../bad")
    except RunbookValidationError as exc:
        assert "docId" in str(exc)
    else:
        raise AssertionError("invalid docId should fail")


def test_missing_doc_id_returns_not_found_error(tmp_path):
    service = RunbookManagementService(tmp_path / "runbooks", IndexStateStore(tmp_path / "state.json"))

    try:
        service.get_runbook("missing-runbook")
    except Exception as exc:
        assert getattr(exc, "status_code", None) == 404
    else:
        raise AssertionError("missing runbook should fail")


def test_create_runbook_writes_markdown(tmp_path):
    runbooks_dir = tmp_path / "runbooks"
    service = RunbookManagementService(runbooks_dir, IndexStateStore(tmp_path / "state.json"))

    response = service.create_runbook(save_request("mq-backlog"))

    assert response.status == "created"
    assert (runbooks_dir / "mq-backlog.md").exists()
    assert "docId: mq-backlog" in (runbooks_dir / "mq-backlog.md").read_text(encoding="utf-8")


def test_update_runbook_updates_markdown(tmp_path):
    runbooks_dir = tmp_path / "runbooks"
    write_runbook(runbooks_dir)
    service = RunbookManagementService(runbooks_dir, IndexStateStore(tmp_path / "state.json"))

    response = service.update_runbook(
        "mq-backlog",
        save_request("mq-backlog", "# MQ backlog\n\n## Updated\n\nnew content"),
    )

    assert response.status == "updated"
    assert "Updated" in (runbooks_dir / "mq-backlog.md").read_text(encoding="utf-8")


def test_delete_runbook_deletes_markdown_without_milvus(tmp_path):
    runbooks_dir = tmp_path / "runbooks"
    write_runbook(runbooks_dir)
    service = RunbookManagementService(runbooks_dir, IndexStateStore(tmp_path / "state.json"))

    response = service.delete_runbook("mq-backlog")

    assert response.status == "deleted"
    assert not (runbooks_dir / "mq-backlog.md").exists()


def test_index_task_store_add_get_and_list(tmp_path):
    store = RunbookIndexTaskStore(tmp_path / "tasks.json")
    service = RunbookIndexTaskService(store, FakeIndexer())

    response = service.create_and_run_index_task(RunbookIndexTaskRequest(forceRebuild=True))

    assert store.get_task(response.task.task_id).task_id == response.task.task_id
    assert store.list_tasks()[0].task_id == response.task.task_id


def test_index_task_store_corrupt_json_returns_empty(tmp_path):
    path = tmp_path / "tasks.json"
    path.write_text("{bad json", encoding="utf-8")

    assert RunbookIndexTaskStore(path).load_tasks() == []


def test_index_task_service_success_status(tmp_path):
    service = RunbookIndexTaskService(RunbookIndexTaskStore(tmp_path / "tasks.json"), FakeIndexer())

    response = service.create_and_run_index_task(RunbookIndexTaskRequest())

    assert response.task.status == RunbookIndexTaskStatus.SUCCESS
    assert response.task.indexed_count == 1


def test_index_task_service_partial_success_when_failed_count_positive(tmp_path):
    result = RunbookIndexResult(
        indexedCount=1,
        failedCount=1,
        indexedDocuments=["mq-backlog"],
        failedDocuments=["broken"],
    )
    service = RunbookIndexTaskService(
        RunbookIndexTaskStore(tmp_path / "tasks.json"),
        FakeIndexer(result=result),
    )

    response = service.create_and_run_index_task(RunbookIndexTaskRequest())

    assert response.task.status == RunbookIndexTaskStatus.PARTIAL_SUCCESS
    assert response.task.failed_count == 1


def test_index_task_service_failed_when_indexer_raises(tmp_path):
    service = RunbookIndexTaskService(
        RunbookIndexTaskStore(tmp_path / "tasks.json"),
        FakeIndexer(exception=RuntimeError("index failed")),
    )

    response = service.create_and_run_index_task(RunbookIndexTaskRequest())

    assert response.task.status == RunbookIndexTaskStatus.FAILED
    assert "index failed" in response.task.error_message


def test_post_index_tasks_returns_task_id(monkeypatch, tmp_path):
    class FakeService:
        def create_and_run_index_task(self, request=None):
            return RunbookIndexTaskService(
                RunbookIndexTaskStore(tmp_path / "api-tasks.json"),
                FakeIndexer(),
            ).create_and_run_index_task(request)

    monkeypatch.setattr(main, "RunbookIndexTaskService", FakeService)

    response = client.post("/ai/runbooks/index-tasks", json={"forceRebuild": False})

    assert response.status_code == 200
    assert response.json()["task"]["taskId"]


def test_get_index_tasks_returns_task_list(monkeypatch, tmp_path):
    task_response = RunbookIndexTaskService(
        RunbookIndexTaskStore(tmp_path / "api-tasks.json"),
        FakeIndexer(),
    ).create_and_run_index_task(RunbookIndexTaskRequest())

    class FakeService:
        def list_tasks(self, limit=20):
            return [task_response.task]

    monkeypatch.setattr(main, "RunbookIndexTaskService", FakeService)

    response = client.get("/ai/runbooks/index-tasks")

    assert response.status_code == 200
    assert response.json()[0]["taskId"] == task_response.task.task_id


def test_get_index_task_returns_task_detail(monkeypatch, tmp_path):
    task_response = RunbookIndexTaskService(
        RunbookIndexTaskStore(tmp_path / "api-tasks.json"),
        FakeIndexer(),
    ).create_and_run_index_task(RunbookIndexTaskRequest())

    class FakeService:
        def get_task(self, task_id):
            return task_response.task

    monkeypatch.setattr(main, "RunbookIndexTaskService", FakeService)

    response = client.get(f"/ai/runbooks/index-tasks/{task_response.task.task_id}")

    assert response.status_code == 200
    assert response.json()["taskId"] == task_response.task.task_id


def test_legacy_index_endpoint_returns_old_fields_and_task_id(monkeypatch, tmp_path):
    class FakeLegacyIndexer(FakeIndexer):
        pass

    monkeypatch.setattr(main, "RunbookIndexer", FakeLegacyIndexer)
    monkeypatch.setattr(
        main,
        "RunbookIndexTaskService",
        lambda indexer=None: RunbookIndexTaskService(
            RunbookIndexTaskStore(tmp_path / "api-tasks.json"),
            indexer,
        ),
    )

    response = client.post("/ai/runbooks/index", json={"forceRebuild": False})

    assert response.status_code == 200
    body = response.json()
    assert body["status"] == "success"
    assert body["indexedCount"] == 1
    assert body["taskId"]
