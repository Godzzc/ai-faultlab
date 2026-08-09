from typing import Any

from fastapi import FastAPI, HTTPException
from pydantic import BaseModel

from app.config import settings
from app.evaluation.retrieval_evaluator import evaluate_retrievers
from app.retrieval.debug_models import RetrievalDebugRequest, RetrievalDebugResponse
from app.retrieval.debug_service import RetrievalDebugService
from app.retrieval.runbook_indexer import RunbookIndexer
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
    try:
        force_rebuild = request.forceRebuild if request else False
        return RunbookIndexer().index_runbooks(force_rebuild=force_rebuild).to_dict()
    except Exception as exc:
        raise HTTPException(
            status_code=500,
            detail=f"Runbook indexing failed: {exc}",
        ) from exc


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
