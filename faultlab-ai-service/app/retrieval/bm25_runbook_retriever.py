import math
from collections import Counter
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from app.retrieval.keyword_runbook_retriever import KeywordRunbookRetriever
from app.retrieval.models import RunbookChunk
from app.retrieval.query_builder import build_retrieval_query
from app.retrieval.tokenizer import token_set, tokenize_any
from app.schemas import DiagnosisRequest

DEFAULT_K1 = 1.5
DEFAULT_B = 0.75


@dataclass(frozen=True)
class QuerySignals:
    terms: list[str]
    metric_names: set[str]
    evidence_keys: set[str]
    section_intents: dict[str, float]


class Bm25RunbookRetriever(KeywordRunbookRetriever):
    def __init__(
        self,
        runbooks_dir: Path | str | None = None,
        k1: float = DEFAULT_K1,
        b: float = DEFAULT_B,
    ) -> None:
        super().__init__(runbooks_dir)
        self.k1 = k1
        self.b = b
        self.chunks: list[RunbookChunk] = []
        self.tokenized_docs: list[list[str]] = []
        self.doc_lengths: list[int] = []
        self.avg_doc_length = 0.0
        self.term_document_frequency: dict[str, int] = {}
        self.term_idf: dict[str, float] = {}
        self.term_frequency_by_doc: list[Counter[str]] = []

    def retrieve(
        self,
        request: DiagnosisRequest,
        trace_summary: dict[str, Any],
        top_k: int = 3,
    ) -> list[RunbookChunk]:
        self._build_bm25_index(self._load_chunks())
        if not self.chunks:
            return []

        query_signals = self._extract_query_signals(request, trace_summary)
        if not query_signals.terms:
            return []

        fault_type = self._request_fault_type(request)
        candidate_indexes = self._candidate_indexes(fault_type)
        if not candidate_indexes:
            return []

        scored_chunks = [
            self._with_bm25_score(doc_index, query_signals, fault_type)
            for doc_index in candidate_indexes
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
            terms=self._dedupe_terms(token_set(query_text)),
            metric_names=self._metric_names(request),
            evidence_keys=self._evidence_keys(request),
            section_intents=self._section_intents(request),
        )

    def _build_bm25_index(self, chunks: list[RunbookChunk]) -> None:
        self.chunks = chunks
        self.tokenized_docs = [self._document_tokens(chunk) for chunk in chunks]
        self.doc_lengths = [len(tokens) for tokens in self.tokenized_docs]
        self.avg_doc_length = sum(self.doc_lengths) / len(self.doc_lengths) if self.doc_lengths else 0.0
        self.term_document_frequency = self._document_frequency(self.tokenized_docs)
        self.term_idf = {
            term: self._idf(len(self.tokenized_docs), document_frequency)
            for term, document_frequency in self.term_document_frequency.items()
        }
        self.term_frequency_by_doc = [Counter(tokens) for tokens in self.tokenized_docs]

    def _candidate_indexes(self, fault_type: str) -> list[int]:
        if not fault_type:
            return list(range(len(self.chunks)))
        return [
            index
            for index, chunk in enumerate(self.chunks)
            if chunk.faultType == fault_type
        ]

    def _document_tokens(self, chunk: RunbookChunk) -> list[str]:
        values = [
            chunk.faultType,
            chunk.title,
            chunk.section,
            chunk.keywords,
            chunk.content,
        ]
        return [token for value in values for token in tokenize_any(value)]

    def _document_frequency(self, doc_terms: list[list[str]]) -> dict[str, int]:
        frequencies: dict[str, int] = {}
        for terms in doc_terms:
            for term in set(terms):
                frequencies[term] = frequencies.get(term, 0) + 1
        return frequencies

    def _idf(self, document_count: int, document_frequency: int) -> float:
        if document_count <= 0 or document_frequency <= 0:
            return 0.0
        return math.log(1 + (document_count - document_frequency + 0.5) / (document_frequency + 0.5))

    def _with_bm25_score(
        self,
        doc_index: int,
        query_signals: QuerySignals,
        fault_type: str,
    ) -> RunbookChunk:
        chunk = self.chunks[doc_index]
        term_counts = self.term_frequency_by_doc[doc_index]
        title_tokens = token_set(chunk.title)
        section_tokens = token_set(chunk.section)
        keyword_tokens = token_set(chunk.keywords)
        content_tokens = token_set(chunk.content)
        doc_length = self.doc_lengths[doc_index] if doc_index < len(self.doc_lengths) else 0

        score = 0.0
        for term in query_signals.terms:
            tf = term_counts.get(term, 0)
            if tf <= 0:
                continue
            denominator = tf + self.k1 * (
                1 - self.b + self.b * doc_length / max(self.avg_doc_length, 1.0)
            )
            normalized_tf = (tf * (self.k1 + 1)) / denominator if denominator else 0.0
            term_score = self.term_idf.get(term, 0.0) * normalized_tf
            score += self._boosted_term_score(term_score, term, title_tokens, section_tokens, keyword_tokens)

        score += self._domain_boost(
            chunk,
            query_signals,
            fault_type,
            section_tokens,
            keyword_tokens,
            content_tokens,
        )

        metadata = {
            **(chunk.metadata or {}),
            "retrievalSource": "bm25",
            "bm25Score": score,
            "bm25K1": self.k1,
            "bm25B": self.b,
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

    def _boosted_term_score(
        self,
        term_score: float,
        term: str,
        title_tokens: set[str],
        section_tokens: set[str],
        keyword_tokens: set[str],
    ) -> float:
        if term in keyword_tokens:
            return term_score * 2.6
        if term in section_tokens:
            return term_score * 2.4
        if term in title_tokens:
            return term_score * 1.5
        return term_score

    def _domain_boost(
        self,
        chunk: RunbookChunk,
        query_signals: QuerySignals,
        fault_type: str,
        section_tokens: set[str],
        keyword_tokens: set[str],
        content_tokens: set[str],
    ) -> float:
        score = 0.0
        if fault_type and chunk.faultType == fault_type:
            score += 4.0

        searchable_tokens = keyword_tokens | content_tokens | section_tokens
        metric_hits = query_signals.metric_names & searchable_tokens
        evidence_key_hits = query_signals.evidence_keys & searchable_tokens
        if metric_hits:
            score += min(4.5, 1.15 * len(metric_hits))
        if evidence_key_hits:
            score += min(3.0, 0.75 * len(evidence_key_hits))

        score += query_signals.section_intents.get(chunk.section, 0.0)
        return score

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
        self.chunks = [chunk]
        self.tokenized_docs = [terms]
        self.doc_lengths = [len(terms)]
        self.avg_doc_length = average_length
        self.term_document_frequency = document_frequency
        self.term_idf = {
            term: self._idf(document_count, frequency)
            for term, frequency in document_frequency.items()
        }
        self.term_frequency_by_doc = [Counter(terms)]
        return self._with_bm25_score(0, query_signals, fault_type)

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
            for token in tokenize_any(value)
        }

        suggestions = request.rule_result.suggestions if request.rule_result else []
        intents = {
            "鏍稿績鎸囨爣": 0.0,
            "甯歌鍘熷洜": 0.0,
            "鎺掓煡姝ラ": 0.0,
            "淇寤鸿": 0.0,
            "椋庨櫓鎻愮ず": 0.0,
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
            intents["甯歌鍘熷洜"] += 1.25
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
            intents["淇寤鸿"] += 1.35
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
            intents["鏍稿績鎸囨爣"] += 1.2
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
            intents["鎺掓煡姝ラ"] += 1.15
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
            intents["椋庨櫓鎻愮ず"] += 1.25
        return {section: boost for section, boost in intents.items() if boost > 0}
