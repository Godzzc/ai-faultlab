from fastapi import FastAPI

from app.config import settings
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
