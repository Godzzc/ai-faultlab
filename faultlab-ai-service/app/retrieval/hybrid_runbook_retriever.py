import logging
from concurrent.futures import ThreadPoolExecutor
from typing import Any

from app.config import settings
from app.retrieval.base import BaseRunbookRetriever
from app.retrieval.bm25_runbook_retriever import Bm25RunbookRetriever
from app.retrieval.fusion import reciprocal_rank_fusion
from app.retrieval.milvus_runbook_retriever import MilvusRunbookRetriever
from app.retrieval.models import RunbookChunk
from app.retrieval.reranker import LightweightRunbookReranker
from app.schemas import DiagnosisRequest

logger = logging.getLogger(__name__)


class HybridRunbookRetriever(BaseRunbookRetriever):
    def __init__(
        self,
        vector_retriever: BaseRunbookRetriever | None = None,
        bm25_retriever: BaseRunbookRetriever | None = None,
        reranker: LightweightRunbookReranker | None = None,
        rrf_k: int | None = None,
        vector_top_k: int | None = None,
        bm25_top_k: int | None = None,
        rerank_enabled: bool | None = None,
    ) -> None:
        self.vector_retriever = vector_retriever or MilvusRunbookRetriever()
        self.bm25_retriever = bm25_retriever or Bm25RunbookRetriever()
        self.reranker = reranker or LightweightRunbookReranker()
        self.rrf_k = rrf_k if rrf_k is not None else settings.hybrid_rrf_k
        self.vector_top_k = vector_top_k if vector_top_k is not None else settings.hybrid_vector_top_k
        self.bm25_top_k = bm25_top_k if bm25_top_k is not None else settings.hybrid_bm25_top_k
        self.rerank_enabled = settings.rerank_enabled if rerank_enabled is None else rerank_enabled

    def retrieve(
        self,
        request: DiagnosisRequest,
        trace_summary: dict[str, Any],
        top_k: int = 3,
    ) -> list[RunbookChunk]:
        vector_results: list[RunbookChunk] = []
        bm25_results: list[RunbookChunk] = []
        fallback_reasons: list[str] = []

        with ThreadPoolExecutor(max_workers=2) as executor:
            logger.info(
                "HybridRunbookRetriever calling vector retriever=%s",
                type(self.vector_retriever).__name__,
            )
            logger.info(
                "HybridRunbookRetriever calling bm25 retriever=%s",
                type(self.bm25_retriever).__name__,
            )
            futures = {
                "vector": executor.submit(
                    self.vector_retriever.retrieve,
                    request,
                    trace_summary,
                    self.vector_top_k,
                ),
                "bm25": executor.submit(
                    self.bm25_retriever.retrieve,
                    request,
                    trace_summary,
                    self.bm25_top_k,
                ),
            }
            for name, future in futures.items():
                try:
                    result = future.result()
                    if name == "vector":
                        vector_results = result
                    else:
                        bm25_results = result
                except Exception as exc:
                    fallback_reasons.append(f"{name}_exception")
                    logger.warning("HybridRunbookRetriever %s retrieval failed: %s", name, exc)

        logger.info("HybridRunbookRetriever vector result count=%s", len(vector_results))
        logger.info("HybridRunbookRetriever bm25 result count=%s", len(bm25_results))
        if fallback_reasons:
            logger.warning(
                "HybridRunbookRetriever fallback reason=%s",
                ",".join(fallback_reasons),
            )

        if not vector_results and not bm25_results:
            logger.info("HybridRunbookRetriever fused result count=0")
            logger.info("HybridRunbookRetriever final result count=0")
            return []

        fused = reciprocal_rank_fusion(
            [results for results in [vector_results, bm25_results] if results],
            k=self.rrf_k,
        )
        logger.info("HybridRunbookRetriever RRF fusion fused result count=%s", len(fused))

        if self.rerank_enabled:
            logger.info("HybridRunbookRetriever lightweight rerank enabled")
            final_chunks = self.reranker.rerank(fused, request, trace_summary, top_k=top_k)
        else:
            final_chunks = fused[:top_k]
        logger.info("HybridRunbookRetriever final result count=%s", len(final_chunks))
        return final_chunks
