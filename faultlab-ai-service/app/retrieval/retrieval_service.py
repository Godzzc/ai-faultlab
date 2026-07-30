import logging
from typing import Any

from app.retrieval.base import BaseRunbookRetriever
from app.retrieval.keyword_runbook_retriever import KeywordRunbookRetriever
from app.retrieval.models import RunbookChunk
from app.schemas import DiagnosisRequest

logger = logging.getLogger(__name__)


class RetrievalService:
    def __init__(self, retriever: BaseRunbookRetriever | None = None) -> None:
        self.retriever = retriever or KeywordRunbookRetriever()

    def retrieve_runbooks(
        self,
        request: DiagnosisRequest,
        trace_summary: dict[str, Any],
        top_k: int = 3,
    ) -> list[RunbookChunk]:
        retriever_type = type(self.retriever).__name__
        try:
            chunks = self.retriever.retrieve(request, trace_summary, top_k=top_k)
            logger.info(
                "RetrievalService retriever_type=%s retrieved_count=%s",
                retriever_type,
                len(chunks),
            )
            for chunk in chunks:
                logger.info(
                    "RetrievalService chunk docId=%s section=%s score=%s",
                    chunk.docId,
                    chunk.section,
                    chunk.score,
                )
            return chunks
        except Exception as exc:
            logger.warning(
                "RetrievalService failed retriever_type=%s error=%s",
                retriever_type,
                exc,
            )
            return []
