from typing import Any

from fastapi import FastAPI, HTTPException
from pydantic import BaseModel

from app.config import settings
from app.retrieval.runbook_indexer import RunbookIndexer
from app.schemas import DiagnosisRequest, DiagnosisResponse
from app.workflow import run_diagnosis_workflow

app = FastAPI(title=settings.service_name)


class RunbookIndexRequest(BaseModel):
    forceRebuild: bool = False


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
