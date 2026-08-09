from datetime import datetime
from time import perf_counter
from uuid import uuid4

from app.retrieval.runbook_indexer import RunbookIndexer, RunbookIndexResult
from app.runbooks.index_task_models import (
    RunbookIndexTask,
    RunbookIndexTaskRequest,
    RunbookIndexTaskRunResponse,
    RunbookIndexTaskStatus,
)
from app.runbooks.index_task_store import RunbookIndexTaskStore


class RunbookIndexTaskService:
    def __init__(
        self,
        task_store: RunbookIndexTaskStore | None = None,
        indexer: RunbookIndexer | None = None,
    ) -> None:
        self.task_store = task_store or RunbookIndexTaskStore()
        self.indexer = indexer or RunbookIndexer()

    def create_and_run_index_task(
        self,
        request: RunbookIndexTaskRequest | None = None,
    ) -> RunbookIndexTaskRunResponse:
        payload = request or RunbookIndexTaskRequest()
        task = RunbookIndexTask(
            taskId=str(uuid4()),
            status=RunbookIndexTaskStatus.PENDING,
            forceRebuild=payload.force_rebuild,
            requestedDocIds=payload.doc_ids,
        )
        self.task_store.add_task(task)

        started = datetime.now().astimezone()
        start_counter = perf_counter()
        task.status = RunbookIndexTaskStatus.RUNNING
        task.started_at = started.isoformat()
        self.task_store.update_task(task)

        result_dict: dict | None = None
        try:
            result = self.indexer.index_runbooks(force_rebuild=payload.force_rebuild)
            result_dict = result.to_dict()
            self._apply_result(task, result)
        except Exception as exc:
            task.status = RunbookIndexTaskStatus.FAILED
            task.error_message = str(exc)
        finally:
            finished = datetime.now().astimezone()
            task.finished_at = finished.isoformat()
            task.duration_ms = int((perf_counter() - start_counter) * 1000)
            self.task_store.update_task(task)

        return RunbookIndexTaskRunResponse(task=task, result=result_dict)

    def list_tasks(self, limit: int = 20) -> list[RunbookIndexTask]:
        return self.task_store.list_tasks(limit=limit)

    def get_task(self, task_id: str) -> RunbookIndexTask | None:
        return self.task_store.get_task(task_id)

    def _apply_result(self, task: RunbookIndexTask, result: RunbookIndexResult) -> None:
        task.collection_name = result.collectionName
        task.indexed_count = result.indexedCount
        task.skipped_count = result.skippedCount
        task.deleted_count = result.deletedCount
        task.failed_count = result.failedCount
        task.indexed_documents = result.indexedDocuments
        task.skipped_documents = result.skippedDocuments
        task.failed_documents = result.failedDocuments

        if result.failedCount == 0:
            task.status = RunbookIndexTaskStatus.SUCCESS
            return
        successful_activity = result.indexedCount + result.skippedCount + result.deletedCount
        task.status = (
            RunbookIndexTaskStatus.PARTIAL_SUCCESS
            if successful_activity > 0
            else RunbookIndexTaskStatus.FAILED
        )
        if task.status == RunbookIndexTaskStatus.FAILED:
            task.error_message = "Runbook indexing failed for all processed documents."
