from app.config import settings
from app.retrieval.base import BaseRunbookRetriever
from app.retrieval.bm25_runbook_retriever import Bm25RunbookRetriever
from app.retrieval.debug_models import (
    RetrievalDebugChunk,
    RetrievalDebugRequest,
    RetrievalDebugResponse,
)
from app.retrieval.fusion import reciprocal_rank_fusion
from app.retrieval.milvus_runbook_retriever import MilvusRunbookRetriever
from app.retrieval.models import RunbookChunk
from app.retrieval.query_builder import build_retrieval_query, request_fault_type
from app.retrieval.reranker import LightweightRunbookReranker
from app.workflow import build_trace_summary


class RetrievalDebugService:
    def __init__(
        self,
        vector_retriever: BaseRunbookRetriever | None = None,
        bm25_retriever: BaseRunbookRetriever | None = None,
        reranker: LightweightRunbookReranker | None = None,
        rrf_k: int | None = None,
        vector_top_k: int | None = None,
        bm25_top_k: int | None = None,
    ) -> None:
        self.vector_retriever = vector_retriever or MilvusRunbookRetriever()
        self.bm25_retriever = bm25_retriever or Bm25RunbookRetriever()
        self.reranker = reranker or LightweightRunbookReranker()
        self.rrf_k = rrf_k if rrf_k is not None else settings.hybrid_rrf_k
        self.vector_top_k = vector_top_k if vector_top_k is not None else settings.hybrid_vector_top_k
        self.bm25_top_k = bm25_top_k if bm25_top_k is not None else settings.hybrid_bm25_top_k

    def debug(self, request: RetrievalDebugRequest) -> RetrievalDebugResponse:
        top_k = request.top_k or settings.retrieval_top_k
        trace_summary = build_trace_summary(request)
        query_text = build_retrieval_query(request, trace_summary)
        fault_type = request_fault_type(request)
        warnings: list[str] = []

        vector_results = self._retrieve_stage(
            "vector",
            self.vector_retriever,
            request,
            trace_summary,
            max(top_k, self.vector_top_k),
            warnings,
        )
        bm25_results = self._retrieve_stage(
            "bm25",
            self.bm25_retriever,
            request,
            trace_summary,
            max(top_k, self.bm25_top_k),
            warnings,
        )

        fusion_results = reciprocal_rank_fusion(
            [results for results in [vector_results, bm25_results] if results],
            k=self.rrf_k,
        )
        rerank_results = (
            self.reranker.rerank(fusion_results, request, trace_summary, top_k=len(fusion_results))
            if fusion_results
            else []
        )
        final_results = rerank_results[:top_k]
        fallback_reasons = [
            item.split(":", 1)[0]
            for item in warnings
            if item.startswith(("vector_exception", "bm25_exception"))
        ]

        return RetrievalDebugResponse(
            queryText=query_text,
            faultType=fault_type,
            vectorResults=self._to_debug_chunks(vector_results, request.include_content),
            bm25Results=self._to_debug_chunks(bm25_results, request.include_content),
            fusionResults=self._to_debug_chunks(fusion_results, request.include_content),
            rerankResults=self._to_debug_chunks(rerank_results, request.include_content),
            finalResults=self._to_debug_chunks(final_results, request.include_content),
            fallbackReason=",".join(fallback_reasons) if fallback_reasons else None,
            warnings=warnings,
        )

    def _retrieve_stage(
        self,
        stage_name: str,
        retriever: BaseRunbookRetriever,
        request: RetrievalDebugRequest,
        trace_summary: dict,
        top_k: int,
        warnings: list[str],
    ) -> list[RunbookChunk]:
        try:
            return retriever.retrieve(request, trace_summary, top_k=top_k)
        except Exception as exc:
            warnings.append(f"{stage_name}_exception: {exc}")
            return []

    def _to_debug_chunks(
        self,
        chunks: list[RunbookChunk],
        include_content: bool,
    ) -> list[RetrievalDebugChunk]:
        return [
            RetrievalDebugChunk(
                docId=chunk.docId,
                title=chunk.title,
                faultType=chunk.faultType,
                section=chunk.section,
                score=chunk.score,
                content=chunk.content if include_content else "",
                keywords=chunk.keywords,
                metadata=dict(chunk.metadata or {}),
            )
            for chunk in chunks
        ]
