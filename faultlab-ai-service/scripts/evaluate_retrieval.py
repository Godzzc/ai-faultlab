import argparse
import json
import sys
from pathlib import Path

ROOT_DIR = Path(__file__).resolve().parents[1]
if str(ROOT_DIR) not in sys.path:
    sys.path.insert(0, str(ROOT_DIR))

from app.evaluation.report_generator import RetrievalEvaluationReportGenerator  # noqa: E402
from app.evaluation.retrieval_evaluator import (  # noqa: E402
    evaluate_retrievers,
    summary_dicts_to_models,
)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Evaluate Runbook retrieval quality.")
    parser.add_argument(
        "--retriever",
        choices=["bm25", "hybrid", "milvus", "all"],
        default="hybrid",
        help="Retriever to evaluate.",
    )
    parser.add_argument(
        "--top-k",
        type=int,
        default=3,
        help="Top K retrieval results used for metrics.",
    )
    parser.add_argument(
        "--report",
        action="store_true",
        help="Generate a Markdown evaluation report instead of JSON output.",
    )
    parser.add_argument(
        "--output",
        help="Write the Markdown report to this file. Only used with --report.",
    )
    return parser.parse_args()


def print_summary(report: dict) -> None:
    summaries = report.get("summaries") or [report]
    for summary in summaries:
        retriever_name = summary.get("retrieverName", "unknown")
        if summary.get("error"):
            print(f"[{retriever_name}] error: {summary['error']}")
            continue

        print(
            f"[{retriever_name}] "
            f"caseCount={summary['caseCount']} "
            f"hitAtK={summary['hitAtK']:.4f} "
            f"recallAtK={summary['recallAtK']:.4f} "
            f"mrr={summary['mrr']:.4f}"
        )
        for result in summary.get("results", []):
            retrieved = ", ".join(
                f"{item.get('docId')}#{item.get('section')}({item.get('score'):.4f})"
                for item in result.get("retrieved", [])
            )
            print(
                f"  - {result['caseId']} "
                f"hit={result['hit']} "
                f"recall={result['recall']:.4f} "
                f"rr={result['reciprocalRank']:.4f} "
                f"retrieved=[{retrieved}]"
            )


def main() -> None:
    args = parse_args()
    try:
        report = evaluate_retrievers(args.retriever, args.top_k)
    except ValueError as exc:
        raise SystemExit(str(exc)) from exc

    if args.report:
        summaries = summary_dicts_to_models(report.get("summaries") or [report])
        markdown_report = RetrievalEvaluationReportGenerator().generate_markdown_report(summaries)
        if args.output:
            output_path = Path(args.output)
            output_path.parent.mkdir(parents=True, exist_ok=True)
            output_path.write_text(markdown_report, encoding="utf-8")
            print(f"Markdown report written to {output_path}")
        else:
            print(markdown_report)
        return

    print_summary(report)
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
