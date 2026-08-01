from collections import defaultdict
from datetime import datetime

from app.evaluation.models import (
    RetrievedRunbookRef,
    RetrievalEvalResult,
    RetrievalEvalSummary,
)

FAULT_TYPE_PREFIXES = {
    "mq_": "MQ_BACKLOG",
    "thread_pool_": "THREAD_POOL_SATURATION",
    "idempotency_": "IDEMPOTENCY_CONFLICT",
}


class RetrievalEvaluationReportGenerator:
    def generate_markdown_report(self, summaries: list[RetrievalEvalSummary]) -> str:
        lines: list[str] = [
            "# RAG Retrieval Evaluation Report",
            "",
            *self._overview(summaries),
            "",
            *self._overall_metrics(summaries),
            "",
            *self._retriever_comparison(summaries),
            "",
            *self._metrics_by_fault_type(summaries),
            "",
            *self._case_details(summaries),
            "",
            *self._miss_cases(summaries),
            "",
            *self._optimization_suggestions(summaries),
            "",
        ]
        return "\n".join(lines)

    def infer_fault_type(self, case_id: str) -> str:
        for prefix, fault_type in FAULT_TYPE_PREFIXES.items():
            if case_id.startswith(prefix):
                return fault_type
        return "UNKNOWN"

    def _overview(self, summaries: list[RetrievalEvalSummary]) -> list[str]:
        retrievers = ", ".join(summary.retriever_name for summary in summaries) or "none"
        case_count = max((summary.case_count for summary in summaries), default=0)
        top_k = self._top_k(summaries)
        return [
            "## Overview",
            "",
            f"- Generated At: {datetime.now().isoformat(timespec='seconds')}",
            f"- Retrievers: {retrievers}",
            f"- Case Count: {case_count}",
            f"- TopK: {top_k}",
            "- Metrics: Hit@K checks whether any expected reference is retrieved; Recall@K measures expected reference coverage; MRR measures the first hit rank.",
        ]

    def _overall_metrics(self, summaries: list[RetrievalEvalSummary]) -> list[str]:
        lines = [
            "## Overall Metrics",
            "",
            "| Retriever | Case Count | Hit@K | Recall@K | MRR |",
            "| --- | --- | --- | --- | --- |",
        ]
        lines.extend(
            f"| {self._cell(summary.retriever_name)} | {summary.case_count} | "
            f"{summary.hit_at_k:.2f} | {summary.recall_at_k:.2f} | {summary.mrr:.2f} |"
            for summary in summaries
        )
        return lines

    def _retriever_comparison(self, summaries: list[RetrievalEvalSummary]) -> list[str]:
        lines = ["## Retriever Comparison", ""]
        if len(summaries) < 2:
            lines.append("- Only one retriever summary was provided; cross-retriever comparison is not available.")
            return lines

        lines.append(f"- Highest Hit@K: {self._metric_winners(summaries, 'hit_at_k')}.")
        lines.append(f"- Highest Recall@K: {self._metric_winners(summaries, 'recall_at_k')}.")
        lines.append(f"- Highest MRR: {self._metric_winners(summaries, 'mrr')}.")

        hybrid = self._summary_by_name(summaries, "hybrid")
        if not hybrid:
            lines.append("- Hybrid summary is not available, so hybrid comparison is skipped.")
            return lines

        for baseline_name in ("bm25", "milvus"):
            baseline = self._summary_by_name(summaries, baseline_name)
            if not baseline:
                continue
            better_metrics = [
                label
                for attr, label in [
                    ("hit_at_k", "Hit@K"),
                    ("recall_at_k", "Recall@K"),
                    ("mrr", "MRR"),
                ]
                if getattr(hybrid, attr) > getattr(baseline, attr)
            ]
            tied_metrics = [
                label
                for attr, label in [
                    ("hit_at_k", "Hit@K"),
                    ("recall_at_k", "Recall@K"),
                    ("mrr", "MRR"),
                ]
                if getattr(hybrid, attr) == getattr(baseline, attr)
            ]
            if better_metrics:
                lines.append(f"- Hybrid is higher than {baseline_name} on {', '.join(better_metrics)}.")
            elif tied_metrics and len(tied_metrics) == 3:
                lines.append(f"- Hybrid is tied with {baseline_name} on all tracked metrics.")
            else:
                lines.append(f"- Hybrid is not higher than {baseline_name} on the tracked metrics.")
        return lines

    def _metrics_by_fault_type(self, summaries: list[RetrievalEvalSummary]) -> list[str]:
        lines = [
            "## Metrics By Fault Type",
            "",
            "| Fault Type | Retriever | Case Count | Hit@K | Recall@K | MRR |",
            "| --- | --- | --- | --- | --- | --- |",
        ]
        for summary in summaries:
            groups: dict[str, list[RetrievalEvalResult]] = defaultdict(list)
            for result in summary.results:
                groups[self.infer_fault_type(result.case_id)].append(result)
            for fault_type in sorted(groups):
                results = groups[fault_type]
                count = len(results)
                hit_at_k = sum(1 for result in results if result.hit) / max(count, 1)
                recall_at_k = sum(result.recall for result in results) / max(count, 1)
                mrr = sum(result.reciprocal_rank for result in results) / max(count, 1)
                lines.append(
                    f"| {fault_type} | {self._cell(summary.retriever_name)} | {count} | "
                    f"{hit_at_k:.2f} | {recall_at_k:.2f} | {mrr:.2f} |"
                )
        return lines

    def _case_details(self, summaries: list[RetrievalEvalSummary]) -> list[str]:
        lines = [
            "## Case Details",
            "",
            "| Case ID | Retriever | Hit | Reciprocal Rank | Recall | Expected | Retrieved |",
            "| --- | --- | --- | --- | --- | --- | --- |",
        ]
        for summary in summaries:
            for result in summary.results:
                lines.append(
                    f"| {self._cell(result.case_id)} | {self._cell(result.retriever_name)} | "
                    f"{result.hit} | {result.reciprocal_rank:.2f} | {result.recall:.2f} | "
                    f"{self._cell(self._format_expected(result))} | "
                    f"{self._cell(self._format_retrieved(result.retrieved))} |"
                )
        return lines

    def _miss_cases(self, summaries: list[RetrievalEvalSummary]) -> list[str]:
        lines = [
            "## Miss Cases",
            "",
            "| Case ID | Retriever | Reason | Expected | Retrieved |",
            "| --- | --- | --- | --- | --- |",
        ]
        miss_count = 0
        for summary in summaries:
            for result in summary.results:
                if result.hit and result.recall >= 1.0:
                    continue
                miss_count += 1
                lines.append(
                    f"| {self._cell(result.case_id)} | {self._cell(result.retriever_name)} | "
                    f"{self._miss_reason(result)} | {self._cell(self._format_expected(result))} | "
                    f"{self._cell(self._format_retrieved(result.retrieved))} |"
                )
        if miss_count == 0:
            lines.append("| None | - | - | - | - |")
        return lines

    def _optimization_suggestions(self, summaries: list[RetrievalEvalSummary]) -> list[str]:
        lines = ["## Optimization Suggestions", ""]
        suggestions: set[str] = set()
        weak_fault_types: dict[str, int] = defaultdict(int)

        for summary in summaries:
            for result in summary.results:
                if result.hit and result.recall >= 1.0:
                    continue
                reason = self._miss_reason(result)
                if reason == "Wrong document retrieved":
                    suggestions.add("- Add or refine Runbook keywords for cases where the expected document is not recalled.")
                if reason == "Correct document but wrong section":
                    suggestions.add("- Adjust section titles or add section-level keywords for cases where the document is correct but the section is wrong.")
                if reason == "Partial recall":
                    suggestions.add("- Review topK and section coverage for cases with partial recall.")
                if result.retriever_name == "bm25":
                    suggestions.add("- For BM25-like retrieval, add domain metric names, English aliases, and field names to Runbook keywords and section text.")
                if result.retriever_name == "milvus":
                    suggestions.add("- For Milvus retrieval, improve query construction and chunk wording so semantically relevant sections are easier to match.")
                weak_fault_types[self.infer_fault_type(result.case_id)] += 1

        hybrid = self._summary_by_name(summaries, "hybrid")
        if hybrid:
            for baseline_name in ("bm25", "milvus"):
                baseline = self._summary_by_name(summaries, baseline_name)
                if baseline and not self._hybrid_is_better(hybrid, baseline):
                    suggestions.add("- If hybrid does not improve over a single retriever, check RRF k, rerank weights, and topK settings.")

        for fault_type, miss_count in weak_fault_types.items():
            if fault_type != "UNKNOWN" and miss_count >= 2:
                suggestions.add(f"- Expand evaluation cases and Runbook content for {fault_type}, which has multiple miss or partial-recall cases.")

        if not suggestions:
            lines.append("- No miss cases were found in the provided summaries. Keep this report as a regression baseline.")
            return lines

        lines.extend(sorted(suggestions))
        return lines

    def _metric_winners(self, summaries: list[RetrievalEvalSummary], attr: str) -> str:
        best = max(getattr(summary, attr) for summary in summaries)
        winners = [summary.retriever_name for summary in summaries if getattr(summary, attr) == best]
        if len(winners) > 1:
            return f"{', '.join(winners)} tied at {best:.2f}"
        return f"{winners[0]} at {best:.2f}"

    def _summary_by_name(
        self,
        summaries: list[RetrievalEvalSummary],
        retriever_name: str,
    ) -> RetrievalEvalSummary | None:
        for summary in summaries:
            if summary.retriever_name == retriever_name:
                return summary
        return None

    def _hybrid_is_better(
        self,
        hybrid: RetrievalEvalSummary,
        baseline: RetrievalEvalSummary,
    ) -> bool:
        return (
            hybrid.hit_at_k > baseline.hit_at_k
            or hybrid.recall_at_k > baseline.recall_at_k
            or hybrid.mrr > baseline.mrr
        )

    def _miss_reason(self, result: RetrievalEvalResult) -> str:
        if not result.retrieved:
            return "No chunks retrieved"
        if result.hit and result.recall < 1.0:
            return "Partial recall"

        expected_doc_ids = {item.doc_id for item in result.expected}
        expected_keys = {(item.doc_id, item.section) for item in result.expected}
        retrieved_doc_ids = {item.doc_id for item in result.retrieved}
        retrieved_keys = {(item.doc_id, item.section) for item in result.retrieved}
        if expected_keys & retrieved_keys:
            return "Partial recall"
        if expected_doc_ids & retrieved_doc_ids:
            return "Correct document but wrong section"
        return "Wrong document retrieved"

    def _format_expected(self, result: RetrievalEvalResult) -> str:
        return ", ".join(f"{item.doc_id}#{item.section}" for item in result.expected)

    def _format_retrieved(self, retrieved: list[RetrievedRunbookRef]) -> str:
        if not retrieved:
            return "None"
        return ", ".join(
            f"{item.doc_id}#{item.section}({item.score:.4f})"
            for item in retrieved
        )

    def _top_k(self, summaries: list[RetrievalEvalSummary]) -> int:
        for summary in summaries:
            for result in summary.results:
                return result.top_k
        return 0

    def _cell(self, value: object) -> str:
        return str(value).replace("|", "\\|").replace("\n", " ")
