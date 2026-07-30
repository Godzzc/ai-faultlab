from fastapi import FastAPI, HTTPException

from app.config import settings
from app.retrieval.runbook_indexer import RunbookIndexer
from app.schemas import DiagnosisRequest, DiagnosisResponse
from app.workflow import run_diagnosis_workflow

app = FastAPI(title=settings.service_name)


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
def index_runbooks() -> dict[str, int | str]:
    try:
        indexed_count = RunbookIndexer().index_runbooks()
        return {
            "indexedCount": indexed_count,
            "collectionName": settings.milvus_collection_name,
            "status": "success",
        }
    except Exception as exc:
        raise HTTPException(
            status_code=500,
            detail=f"Runbook indexing failed: {exc}",
        ) from exc
