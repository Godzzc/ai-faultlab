import math
from collections import Counter
from typing import Any

from app.retrieval.keyword_runbook_retriever import KeywordRunbookRetriever, TOKEN_PATTERN
from app.retrieval.models import RunbookChunk
from app.schemas import DiagnosisRequest


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

        query_terms = self._extract_query_terms(request, trace_summary)
        if not query_terms:
            return []

        doc_terms = [self._content_terms(chunk) for chunk in chunks]
        document_frequency = self._document_frequency(doc_terms)
        average_length = sum(len(terms) for terms in doc_terms) / max(len(doc_terms), 1)

        scored_chunks = [
            self._with_bm25_like_score(
                chunk,
                terms,
                query_terms,
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
        values: list[Any] = []
        if request.rule_result:
            values.extend([
                request.rule_result.fault_type,
                request.rule_result.fault_name,
                request.rule_result.reason,
                request.rule_result.evidence,
            ])
        values.append(request.experiment.scenario_code)
        values.append([metric.model_dump(by_alias=True) for metric in request.metrics])
        values.append(trace_summary)
        return list(self._tokens_from_value(values))

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
        query_terms: list[str],
        document_frequency: dict[str, int],
        document_count: int,
        average_length: float,
        fault_type: str,
    ) -> RunbookChunk:
        term_counts = Counter(terms)
        title_tokens = self._tokens_from_value(chunk.title)
        section_tokens = self._tokens_from_value(chunk.section)
        keyword_tokens = self._tokens_from_value(" ".join(chunk.keywords))
        content_length = max(len(terms), 1)
        k1 = 1.5
        b = 0.75

        score = 0.0
        for term in query_terms:
            tf = term_counts.get(term, 0)
            if tf <= 0:
                continue
            df = document_frequency.get(term, 0)
            idf = math.log(1 + (document_count - df + 0.5) / (df + 0.5))
            normalized_tf = (tf * (k1 + 1)) / (
                tf + k1 * (1 - b + b * content_length / max(average_length, 1))
            )
            term_score = idf * normalized_tf
            if term in keyword_tokens:
                term_score *= 3.0
            elif term in title_tokens or term in section_tokens:
                term_score *= 2.0
            score += term_score

        if fault_type and chunk.faultType == fault_type:
            score += 5.0
        score = score / (1 + content_length / 500)

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
