from typing import Any

from fastapi import FastAPI, HTTPException
from pydantic import BaseModel

from app.config import settings
from app.evaluation.retrieval_evaluator import evaluate_retrievers
from app.retrieval.debug_models import RetrievalDebugRequest, RetrievalDebugResponse
from app.retrieval.debug_service import RetrievalDebugService
from app.retrieval.runbook_indexer import RunbookIndexer
from app.runbooks.index_task_models import RunbookIndexTask, RunbookIndexTaskRequest, RunbookIndexTaskRunResponse
from app.runbooks.index_task_service import RunbookIndexTaskService
from app.runbooks.models import (
    RunbookDeleteResponse,
    RunbookDocumentDetail,
    RunbookDocumentSummary,
    RunbookSaveRequest,
    RunbookSaveResponse,
    RunbookServiceError,
)
from app.runbooks.service import RunbookManagementService
from app.schemas import DiagnosisRequest, DiagnosisResponse
from app.workflow import run_diagnosis_workflow

app = FastAPI(title=settings.service_name)


class RunbookIndexRequest(BaseModel):
    forceRebuild: bool = False


class RunbookEvaluateRequest(BaseModel):
    retriever: str = "hybrid"
    topK: int = 3
    report: bool = False


@app.get("/ai/health")
def health() -> dict[str, str]:
    return {
        "service": settings.service_name,
        "status": "UP",
    }


@app.post("/ai/diagnosis/generate", response_model=DiagnosisResponse)
def generate_diagnosis(request: DiagnosisRequest) -> DiagnosisResponse:
    return run_diagnosis_workflow(request)


@app.post("/ai/runbooks/index")
def index_runbooks(request: RunbookIndexRequest | None = None) -> dict[str, Any]:
    force_rebuild = request.forceRebuild if request else False
    response = RunbookIndexTaskService(indexer=RunbookIndexer()).create_and_run_index_task(
        RunbookIndexTaskRequest(forceRebuild=force_rebuild)
    )
    legacy_result = response.result or {
        "status": response.task.status.value.lower(),
        "collectionName": response.task.collection_name,
        "indexedCount": response.task.indexed_count,
        "skippedCount": response.task.skipped_count,
        "deletedCount": response.task.deleted_count,
        "failedCount": response.task.failed_count,
        "indexedDocuments": response.task.indexed_documents,
        "skippedDocuments": response.task.skipped_documents,
        "failedDocuments": response.task.failed_documents,
        "forceRebuild": response.task.force_rebuild,
    }
    return {
        **legacy_result,
        "taskId": response.task.task_id,
    }


@app.post("/ai/runbooks/index-tasks", response_model=RunbookIndexTaskRunResponse)
def create_runbook_index_task(
    request: RunbookIndexTaskRequest | None = None,
) -> RunbookIndexTaskRunResponse:
    return RunbookIndexTaskService().create_and_run_index_task(request)


@app.get("/ai/runbooks/index-tasks", response_model=list[RunbookIndexTask])
def list_runbook_index_tasks(limit: int = 20) -> list[RunbookIndexTask]:
    return RunbookIndexTaskService().list_tasks(limit=limit)


@app.get("/ai/runbooks/index-tasks/{task_id}", response_model=RunbookIndexTask)
def get_runbook_index_task(task_id: str) -> RunbookIndexTask:
    task = RunbookIndexTaskService().get_task(task_id)
    if not task:
        raise HTTPException(status_code=404, detail=f"Runbook index task not found: {task_id}")
    return task


@app.get("/ai/runbooks", response_model=list[RunbookDocumentSummary])
def list_runbooks() -> list[RunbookDocumentSummary]:
    return RunbookManagementService().list_runbooks()


@app.post("/ai/runbooks", response_model=RunbookSaveResponse)
def create_runbook(request: RunbookSaveRequest) -> RunbookSaveResponse:
    try:
        return RunbookManagementService().create_runbook(request)
    except RunbookServiceError as exc:
        raise HTTPException(status_code=exc.status_code, detail=str(exc)) from exc


@app.get("/ai/runbooks/{doc_id}", response_model=RunbookDocumentDetail)
def get_runbook(doc_id: str) -> RunbookDocumentDetail:
    try:
        return RunbookManagementService().get_runbook(doc_id)
    except RunbookServiceError as exc:
        raise HTTPException(status_code=exc.status_code, detail=str(exc)) from exc


@app.put("/ai/runbooks/{doc_id}", response_model=RunbookSaveResponse)
def update_runbook(doc_id: str, request: RunbookSaveRequest) -> RunbookSaveResponse:
    try:
        return RunbookManagementService().update_runbook(doc_id, request)
    except RunbookServiceError as exc:
        raise HTTPException(status_code=exc.status_code, detail=str(exc)) from exc


@app.delete("/ai/runbooks/{doc_id}", response_model=RunbookDeleteResponse)
def delete_runbook(doc_id: str) -> RunbookDeleteResponse:
    try:
        return RunbookManagementService().delete_runbook(doc_id)
    except RunbookServiceError as exc:
        raise HTTPException(status_code=exc.status_code, detail=str(exc)) from exc


@app.post("/ai/runbooks/evaluate")
def evaluate_runbooks(request: RunbookEvaluateRequest | None = None) -> dict[str, Any]:
    payload = request or RunbookEvaluateRequest()
    try:
        return evaluate_retrievers(payload.retriever, payload.topK, report=payload.report)
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc


@app.post("/ai/runbooks/retrieve/debug", response_model=RetrievalDebugResponse)
def debug_retrieve_runbooks(request: RetrievalDebugRequest) -> RetrievalDebugResponse:
    try:
        return RetrievalDebugService().debug(request)
    except Exception as exc:
        raise HTTPException(
            status_code=500,
            detail=f"Runbook retrieval debug failed: {exc}",
        ) from exc
