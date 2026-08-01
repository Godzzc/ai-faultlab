import json
from pathlib import Path
from typing import Any

from app.evaluation.models import (
    ExpectedRunbookRef,
    RetrievedRunbookRef,
    RetrievalEvalCase,
    RetrievalEvalResult,
    RetrievalEvalSummary,
)
from app.retrieval.base import BaseRunbookRetriever
from app.retrieval.bm25_runbook_retriever import Bm25RunbookRetriever
from app.retrieval.hybrid_runbook_retriever import HybridRunbookRetriever
from app.retrieval.milvus_runbook_retriever import MilvusRunbookRetriever
from app.retrieval.models import RunbookChunk
from app.schemas import DiagnosisRequest
from app.workflow import build_trace_summary

DEFAULT_TOP_K = 3
SUPPORTED_RETRIEVERS = ("bm25", "hybrid", "milvus")


class RetrievalEvaluator:
    def __init__(
        self,
        cases_path: Path | str | None = None,
    ) -> None:
        self.cases_path = Path(cases_path) if cases_path else self.default_cases_path()

    @staticmethod
    def default_cases_path() -> Path:
        return Path(__file__).resolve().parents[2] / "evaluation" / "rag_eval_cases.json"

    def load_cases(self) -> list[RetrievalEvalCase]:
        raw_cases = json.loads(self.cases_path.read_text(encoding="utf-8"))
        return [RetrievalEvalCase.model_validate(item) for item in raw_cases]

    def evaluate(
        self,
        retriever_name: str,
        retriever: BaseRunbookRetriever,
        top_k: int = DEFAULT_TOP_K,
    ) -> RetrievalEvalSummary:
        cases = self.load_cases()
        results = [
            self.evaluate_case(case, retriever_name, retriever, top_k=top_k)
            for case in cases
        ]
        case_count = len(results)
        return RetrievalEvalSummary(
            retrieverName=retriever_name,
            caseCount=case_count,
            hitAtK=sum(1 for result in results if result.hit) / max(case_count, 1),
            recallAtK=sum(result.recall for result in results) / max(case_count, 1),
            mrr=sum(result.reciprocal_rank for result in results) / max(case_count, 1),
            results=results,
        )

    def evaluate_case(
        self,
        case: RetrievalEvalCase,
        retriever_name: str,
        retriever: BaseRunbookRetriever,
        top_k: int = DEFAULT_TOP_K,
    ) -> RetrievalEvalResult:
        request = self.case_to_request(case)
        trace_summary = build_trace_summary(request)
        retrieved_chunks = retriever.retrieve(request, trace_summary, top_k=top_k)
        top_chunks = retrieved_chunks[:top_k]
        expected_keys = self.expected_keys(case.expected)
        hit_count, reciprocal_rank = self.match_metrics(top_chunks, expected_keys)

        return RetrievalEvalResult(
            caseId=case.case_id,
            retrieverName=retriever_name,
            topK=top_k,
            hit=hit_count > 0,
            reciprocalRank=reciprocal_rank,
            recall=hit_count / max(len(expected_keys), 1),
            expected=case.expected,
            retrieved=[
                RetrievedRunbookRef(
                    docId=chunk.docId,
                    section=chunk.section,
                    score=chunk.score,
                )
                for chunk in top_chunks
            ],
        )

    def case_to_request(self, case: RetrievalEvalCase) -> DiagnosisRequest:
        query = case.query or {}
        rule_result = query.get("ruleResult") or {}
        metrics = query.get("metrics") or []
        fault_type = rule_result.get("faultType") or case.scenario_code
        return DiagnosisRequest.model_validate({
            "experiment": {
                "experimentId": case.case_id,
                "scenarioCode": case.scenario_code,
                "status": "EVALUATING",
                "traceId": f"trace_{case.case_id}",
            },
            "metrics": metrics,
            "traceTree": {
                "traceId": f"trace_{case.case_id}",
                "roots": [],
            },
            "ruleResult": {
                "experimentId": case.case_id,
                "faultType": fault_type,
                "faultName": rule_result.get("faultName", fault_type),
                "confidence": rule_result.get("confidence", 1.0),
                "matched": rule_result.get("matched", True),
                "reason": rule_result.get("reason", ""),
                "evidence": rule_result.get("evidence") or [],
                "suggestions": rule_result.get("suggestions") or [],
            },
        })

    def expected_keys(self, expected: list[ExpectedRunbookRef]) -> set[tuple[str, str]]:
        return {
            (item.doc_id, item.section)
            for item in expected
            if item.doc_id and item.section
        }

    def match_metrics(
        self,
        retrieved_chunks: list[RunbookChunk],
        expected_keys: set[tuple[str, str]],
    ) -> tuple[int, float]:
        seen_hits: set[tuple[str, str]] = set()
        reciprocal_rank = 0.0
        for rank, chunk in enumerate(retrieved_chunks, start=1):
            key = (chunk.docId, chunk.section)
            if key not in expected_keys:
                continue
            seen_hits.add(key)
            if reciprocal_rank == 0.0:
                reciprocal_rank = 1.0 / rank
        return len(seen_hits), reciprocal_rank


def create_retriever(retriever_name: str) -> BaseRunbookRetriever:
    if retriever_name == "bm25":
        return Bm25RunbookRetriever()
    if retriever_name == "hybrid":
        return HybridRunbookRetriever()
    if retriever_name == "milvus":
        return MilvusRunbookRetriever()
    raise ValueError(f"Unsupported retriever: {retriever_name}")


def normalize_retriever_names(retriever: str) -> list[str]:
    selected = (retriever or "hybrid").lower()
    if selected == "all":
        return list(SUPPORTED_RETRIEVERS)
    if selected in SUPPORTED_RETRIEVERS:
        return [selected]
    raise ValueError(f"Unsupported retriever: {retriever}")


def evaluate_retrievers(
    retriever: str = "hybrid",
    top_k: int = DEFAULT_TOP_K,
    evaluator: RetrievalEvaluator | None = None,
) -> dict[str, Any]:
    evaluator = evaluator or RetrievalEvaluator()
    top_k = top_k or DEFAULT_TOP_K
    names = normalize_retriever_names(retriever)
    outputs: list[dict[str, Any]] = []

    for name in names:
        try:
            summary = evaluator.evaluate(name, create_retriever(name), top_k=top_k)
            outputs.append(summary.model_dump(by_alias=True))
        except Exception as exc:
            outputs.append({
                "retrieverName": name,
                "error": str(exc),
            })

    if (retriever or "hybrid").lower() == "all":
        return {"summaries": outputs}
    return outputs[0]
