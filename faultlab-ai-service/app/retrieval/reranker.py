import logging
import re
from dataclasses import replace
from typing import Any

from app.retrieval.models import RunbookChunk
from app.schemas import DiagnosisRequest

logger = logging.getLogger(__name__)

TOKEN_PATTERN = re.compile(r"[A-Za-z0-9_\-.]+")
IMPORTANT_SECTIONS = (
    "\u6392\u67e5\u6b65\u9aa4",
    "\u4fee\u590d\u5efa\u8bae",
    "\u5e38\u89c1\u539f\u56e0",
    "Core Metrics",
    "Troubleshooting",
    "Fix",
)


class LightweightRunbookReranker:
    def rerank(
        self,
        chunks: list[RunbookChunk],
        request: DiagnosisRequest,
        trace_summary: dict[str, Any],
        top_k: int = 3,
    ) -> list[RunbookChunk]:
        fault_type = self._request_fault_type(request)
        evidence_terms = self._evidence_terms(request)
        metric_names = {metric.metric_name.lower() for metric in request.metrics if metric.metric_name}

        reranked = [
            self._rerank_chunk(chunk, fault_type, evidence_terms, metric_names)
            for chunk in chunks
        ]
        reranked = [chunk for chunk in reranked if chunk.score > 0]
        reranked.sort(key=lambda chunk: chunk.score, reverse=True)
        final_chunks = reranked[:top_k]
        for chunk in final_chunks:
            logger.info(
                "LightweightRunbookReranker final chunk docId=%s section=%s score=%s",
                chunk.docId,
                chunk.section,
                chunk.score,
            )
        return final_chunks

    def _rerank_chunk(
        self,
        chunk: RunbookChunk,
        fault_type: str,
        evidence_terms: set[str],
        metric_names: set[str],
    ) -> RunbookChunk:
        score = chunk.score
        reasons: list[str] = []

        if fault_type and chunk.faultType == fault_type:
            score += 2.0
            reasons.append("faultType")
        if any(section in chunk.section for section in IMPORTANT_SECTIONS):
            score += 1.0
            reasons.append("section")

        content_tokens = self._tokens(chunk.content)
        keyword_tokens = self._tokens(" ".join(chunk.keywords))
        evidence_hits = evidence_terms & content_tokens
        metric_hits = metric_names & keyword_tokens
        if evidence_hits:
            score += min(2.0, 0.5 * len(evidence_hits))
            reasons.append("evidence")
        if metric_hits:
            score += min(2.0, 0.75 * len(metric_hits))
            reasons.append("metrics")

        sources = set((chunk.metadata or {}).get("sources") or [])
        if {"milvus", "bm25"}.issubset(sources):
            score += 1.5
            reasons.append("dual_source")

        metadata = {
            **(chunk.metadata or {}),
            "rerankScore": score,
            "rerankReasons": reasons,
        }
        return replace(chunk, score=score, metadata=metadata)

    def _request_fault_type(self, request: DiagnosisRequest) -> str:
        if request.rule_result and request.rule_result.fault_type:
            return request.rule_result.fault_type
        return request.experiment.scenario_code or ""

    def _evidence_terms(self, request: DiagnosisRequest) -> set[str]:
        if not request.rule_result:
            return set()
        return self._tokens(" ".join(request.rule_result.evidence or []))

    def _tokens(self, value: str) -> set[str]:
        return {token.lower() for token in TOKEN_PATTERN.findall(value or "")}
