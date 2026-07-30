from abc import ABC, abstractmethod
from typing import Any

from app.retrieval.models import RunbookChunk
from app.schemas import DiagnosisRequest


class BaseRunbookRetriever(ABC):
    @abstractmethod
    def retrieve(
        self,
        request: DiagnosisRequest,
        trace_summary: dict[str, Any],
        top_k: int = 3,
    ) -> list[RunbookChunk]:
        raise NotImplementedError
