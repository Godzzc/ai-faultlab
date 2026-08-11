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
    "\u6838\u5fc3\u6307\u6807",
    "\u98ce\u9669\u63d0\u793a",
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
        evidence_keys = self._evidence_keys(request)
        section_intents = self._section_intents(request)

        reranked = [
            self._rerank_chunk(chunk, fault_type, evidence_terms, metric_names, evidence_keys, section_intents)
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
        evidence_keys: set[str],
        section_intents: dict[str, float],
    ) -> RunbookChunk:
        score = chunk.score
        reasons: list[str] = []

        if fault_type and chunk.faultType == fault_type:
            score += 2.25
            reasons.append("faultType")
        if any(section in chunk.section for section in IMPORTANT_SECTIONS):
            score += 0.25
            reasons.append("section")

        content_tokens = self._tokens(chunk.content)
        keyword_tokens = self._tokens(" ".join(chunk.keywords))
        evidence_hits = evidence_terms & content_tokens
        metric_hits = metric_names & (keyword_tokens | content_tokens)
        evidence_key_hits = evidence_keys & (keyword_tokens | content_tokens)
        if evidence_hits:
            score += min(1.2, 0.25 * len(evidence_hits))
            reasons.append("evidence")
        if metric_hits:
            score += min(1.25, 0.35 * len(metric_hits))
            reasons.append("metrics")
        if evidence_key_hits:
            score += min(1.0, 0.25 * len(evidence_key_hits))
            reasons.append("evidence_key")
        section_boost = section_intents.get(chunk.section, 0.0)
        if section_boost:
            score += section_boost
            reasons.append("section_intent")

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

    def _evidence_keys(self, request: DiagnosisRequest) -> set[str]:
        if not request.rule_result:
            return set()
        keys: set[str] = set()
        for item in request.rule_result.evidence or []:
            text = str(item or "")
            if "=" not in text:
                continue
            key = text.split("=", 1)[0].strip().lower()
            if key:
                keys.add(key)
        return keys

    def _section_intents(self, request: DiagnosisRequest) -> dict[str, float]:
        values: list[Any] = [request.experiment.scenario_code]
        if request.rule_result:
            values.extend([
                request.rule_result.fault_type,
                request.rule_result.fault_name,
                request.rule_result.reason,
                request.rule_result.evidence,
                request.rule_result.suggestions,
            ])
        values.extend(metric.model_dump(by_alias=True) for metric in request.metrics or [])
        tokens = {
            token.lower()
            for value in values
            for token in self._tokens_from_any(value)
        }
        suggestions = request.rule_result.suggestions if request.rule_result else []
        intents = {
            "\u6838\u5fc3\u6307\u6807": 0.0,
            "\u5e38\u89c1\u539f\u56e0": 0.0,
            "\u6392\u67e5\u6b65\u9aa4": 0.0,
            "\u4fee\u590d\u5efa\u8bae": 0.0,
            "\u98ce\u9669\u63d0\u793a": 0.0,
            "Core Metrics": 0.0,
            "Troubleshooting": 0.0,
            "Fix": 0.0,
        }
        if tokens & {
            "reason",
            "cause",
            "root",
            "slow",
            "slowsqlcount",
            "downstream",
            "blocking",
            "hashmismatchcount",
            "missingkeycount",
            "unstablekeycount",
        }:
            intents["\u5e38\u89c1\u539f\u56e0"] += 1.65
        if suggestions or tokens & {
            "suggestion",
            "suggestions",
            "fix",
            "remediation",
            "increase",
            "optimize",
            "configure",
            "split",
            "isolate",
            "reuse",
            "add",
        }:
            intents["\u4fee\u590d\u5efa\u8bae"] += 1.75
            intents["Fix"] += 1.75
        if tokens & {
            "metricname",
            "metricvalue",
            "evidence",
            "publishcount",
            "backlogcount",
            "activethreadcount",
            "rejectedtaskcount",
            "hashmismatchcount",
            "redissetnxfailcount",
            "duplicatecount",
        }:
            intents["\u6838\u5fc3\u6307\u6807"] += 0.65
            intents["Core Metrics"] += 0.65
        if tokens & {
            "check",
            "inspect",
            "verify",
            "diagnose",
            "setnx",
            "processing",
            "downstream",
            "queuecapacity",
            "rejectedexecutionhandler",
        }:
            intents["\u6392\u67e5\u6b65\u9aa4"] += 1.45
            intents["Troubleshooting"] += 1.45
        if tokens & {
            "risk",
            "deadlettercount",
            "dlq",
            "retrycount",
            "redelivercount",
            "rejectedtaskcount",
            "rediserrorcount",
            "redistimeoutms",
            "queuecapacity",
            "hashmismatchcount",
            "dbuniqueindexexists",
        }:
            intents["\u98ce\u9669\u63d0\u793a"] += 1.6
        return {section: boost for section, boost in intents.items() if boost > 0}

    def _tokens(self, value: str) -> set[str]:
        return {token.lower() for token in TOKEN_PATTERN.findall(value or "")}

    def _tokens_from_any(self, value: Any) -> set[str]:
        if value is None:
            return set()
        if isinstance(value, dict):
            tokens: set[str] = set()
            for key, item in value.items():
                tokens.update(self._tokens(str(key)))
                tokens.update(self._tokens_from_any(item))
            return tokens
        if isinstance(value, list | tuple | set):
            tokens: set[str] = set()
            for item in value:
                tokens.update(self._tokens_from_any(item))
            return tokens
        return self._tokens(str(value))
