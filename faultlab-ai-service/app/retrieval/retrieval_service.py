import logging
from typing import Any

from app.retrieval.base import BaseRunbookRetriever
from app.retrieval.keyword_runbook_retriever import KeywordRunbookRetriever
from app.retrieval.milvus_runbook_retriever import MilvusRunbookRetriever
from app.retrieval.models import RunbookChunk
from app.schemas import DiagnosisRequest

logger = logging.getLogger(__name__)


class RetrievalService:
    def __init__(
        self,
        primary_retriever: BaseRunbookRetriever | None = None,
        fallback_retriever: BaseRunbookRetriever | None = None,
    ) -> None:
        self.primary_retriever = primary_retriever or MilvusRunbookRetriever()
        self.fallback_retriever = fallback_retriever or KeywordRunbookRetriever()

    def retrieve_runbooks(
        self,
        request: DiagnosisRequest,
        trace_summary: dict[str, Any],
        top_k: int = 3,
    ) -> list[RunbookChunk]:
        primary_type = type(self.primary_retriever).__name__
        try:
            chunks = self.primary_retriever.retrieve(request, trace_summary, top_k=top_k)
            self._log_results(primary_type, chunks)
            if chunks:
                return chunks
            logger.info(
                "RetrievalService fallback reason=primary_empty retriever_type=%s",
                primary_type,
            )
        except Exception as exc:
            logger.warning(
                "RetrievalService fallback reason=primary_exception retriever_type=%s error=%s",
                primary_type,
                exc,
            )

        fallback_type = type(self.fallback_retriever).__name__
        try:
            chunks = self.fallback_retriever.retrieve(request, trace_summary, top_k=top_k)
            self._log_results(fallback_type, chunks)
            return chunks
        except Exception as exc:
            logger.warning(
                "RetrievalService failed retriever_type=%s error=%s",
                fallback_type,
                exc,
            )
            return []

    def _log_results(self, retriever_type: str, chunks: list[RunbookChunk]) -> None:
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
