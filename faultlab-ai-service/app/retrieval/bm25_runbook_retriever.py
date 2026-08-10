import math
from collections import Counter
from dataclasses import dataclass
from typing import Any

from app.retrieval.keyword_runbook_retriever import KeywordRunbookRetriever, TOKEN_PATTERN
from app.retrieval.models import RunbookChunk
from app.retrieval.query_builder import build_retrieval_query
from app.schemas import DiagnosisRequest


@dataclass(frozen=True)
class QuerySignals:
    terms: list[str]
    metric_names: set[str]
    evidence_keys: set[str]
    section_intents: dict[str, float]


class Bm25RunbookRetriever(KeywordRunbookRetriever):
    def retrieve(
        self,
        request: DiagnosisRequest,
        trace_summary: dict[str, Any],
        top_k: int = 3,
    ) -> list[RunbookChunk]:
        chunks = self._load_chunks()
        fault_type = self._request_fault_type(request)
        if fault_type:
            chunks = [chunk for chunk in chunks if chunk.faultType == fault_type]
        if not chunks:
            return []

        query_signals = self._extract_query_signals(request, trace_summary)
        if not query_signals.terms:
            return []

        doc_terms = [self._content_terms(chunk) for chunk in chunks]
        document_frequency = self._document_frequency(doc_terms)
        average_length = sum(len(terms) for terms in doc_terms) / max(len(doc_terms), 1)

        scored_chunks = [
            self._with_bm25_like_score(
                chunk,
                terms,
                query_signals,
                document_frequency,
                len(chunks),
                average_length,
                fault_type,
            )
            for chunk, terms in zip(chunks, doc_terms, strict=True)
        ]
        matched_chunks = [chunk for chunk in scored_chunks if chunk.score > 0]
        matched_chunks.sort(key=lambda chunk: chunk.score, reverse=True)
        return matched_chunks[:top_k]

    def _extract_query_terms(
        self,
        request: DiagnosisRequest,
        trace_summary: dict[str, Any],
    ) -> list[str]:
        return self._extract_query_signals(request, trace_summary).terms

    def _extract_query_signals(
        self,
        request: DiagnosisRequest,
        trace_summary: dict[str, Any],
    ) -> QuerySignals:
        query_text = build_retrieval_query(request, trace_summary)
        return QuerySignals(
            terms=self._dedupe_terms(self._tokens_from_value(query_text)),
            metric_names=self._metric_names(request),
            evidence_keys=self._evidence_keys(request),
            section_intents=self._section_intents(request),
        )

    def _content_terms(self, chunk: RunbookChunk) -> list[str]:
        values = [
            chunk.title,
            chunk.section,
            " ".join(chunk.keywords),
            chunk.faultType,
            chunk.content,
        ]
        return [
            token.lower()
            for value in values
            for token in TOKEN_PATTERN.findall(str(value or ""))
        ]

    def _document_frequency(self, doc_terms: list[list[str]]) -> dict[str, int]:
        frequencies: dict[str, int] = {}
        for terms in doc_terms:
            for term in set(terms):
                frequencies[term] = frequencies.get(term, 0) + 1
        return frequencies

    def _with_bm25_like_score(
        self,
        chunk: RunbookChunk,
        terms: list[str],
        query_signals: QuerySignals,
        document_frequency: dict[str, int],
        document_count: int,
        average_length: float,
        fault_type: str,
    ) -> RunbookChunk:
        term_counts = Counter(terms)
        title_tokens = self._tokens_from_value(chunk.title)
        section_tokens = self._tokens_from_value(chunk.section)
        keyword_tokens = self._tokens_from_value(" ".join(chunk.keywords))
        content_tokens = self._tokens_from_value(chunk.content)
        content_length = max(len(terms), 1)
        k1 = 1.5
        b = 0.75

        score = 0.0
        for term in query_signals.terms:
            tf = term_counts.get(term, 0)
            if tf <= 0:
                continue
            df = document_frequency.get(term, 0)
            idf = math.log(1 + (document_count - df + 0.5) / (df + 0.5))
            normalized_tf = (tf * (k1 + 1)) / (
                tf + k1 * (1 - b + b * content_length / max(average_length, 1))
            )
            term_score = idf * normalized_tf
            # Keep field boosts explicit: front matter keywords and section titles
            # should guide strict docId+section matching, while content hits still matter.
            if term in keyword_tokens:
                term_score *= 3.2
            elif term in section_tokens:
                term_score *= 2.8
            elif term in title_tokens:
                term_score *= 1.8
            score += term_score

        if fault_type and chunk.faultType == fault_type:
            score += 6.0

        metric_hits = query_signals.metric_names & (keyword_tokens | content_tokens | section_tokens)
        evidence_key_hits = query_signals.evidence_keys & (keyword_tokens | content_tokens | section_tokens)
        if metric_hits:
            score += min(4.5, 1.15 * len(metric_hits))
        if evidence_key_hits:
            score += min(3.0, 0.75 * len(evidence_key_hits))

        section_intent_boost = query_signals.section_intents.get(chunk.section, 0.0)
        if section_intent_boost:
            score += section_intent_boost

        # Light normalization prevents verbose sections from winning purely by
        # carrying more terms, without erasing useful dense keyword matches.
        score = score / (1 + content_length / 650)

        metadata = {
            **(chunk.metadata or {}),
            "retrievalSource": "bm25",
            "bm25Score": score,
        }
        return RunbookChunk(
            docId=chunk.docId,
            title=chunk.title,
            faultType=chunk.faultType,
            section=chunk.section,
            content=chunk.content,
            keywords=chunk.keywords,
            score=score,
            source="bm25",
            metadata=metadata,
        )

    def _dedupe_terms(self, terms: set[str]) -> list[str]:
        return sorted(term for term in terms if term)

    def _metric_names(self, request: DiagnosisRequest) -> set[str]:
        return {
            metric.metric_name.lower()
            for metric in request.metrics or []
            if metric.metric_name
        }

    def _evidence_keys(self, request: DiagnosisRequest) -> set[str]:
        keys: set[str] = set()
        if not request.rule_result:
            return keys
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
            for token in self._tokens_from_value(value)
        }

        suggestions = request.rule_result.suggestions if request.rule_result else []
        intents = {
            "核心指标": 0.0,
            "常见原因": 0.0,
            "排查步骤": 0.0,
            "修复建议": 0.0,
            "风险提示": 0.0,
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
            intents["常见原因"] += 1.25
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
            intents["修复建议"] += 1.35
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
            intents["核心指标"] += 1.2
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
            intents["排查步骤"] += 1.15
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
            intents["风险提示"] += 1.25
        return {section: boost for section, boost in intents.items() if boost > 0}
