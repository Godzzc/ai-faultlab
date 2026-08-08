import argparse
import json
import sys
from pathlib import Path

ROOT_DIR = Path(__file__).resolve().parents[1]
if str(ROOT_DIR) not in sys.path:
    sys.path.insert(0, str(ROOT_DIR))

from app.evaluation.models import RetrievalEvalSummary  # noqa: E402
from app.evaluation.regression_checker import (  # noqa: E402
    RetrievalRegressionChecker,
    load_thresholds,
)
from app.evaluation.retrieval_evaluator import evaluate_retrievers  # noqa: E402


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Run the RAG retrieval regression gate.")
    parser.add_argument(
        "--retriever",
        choices=["bm25", "hybrid", "milvus"],
        required=True,
        help="Retriever to evaluate and check.",
    )
    parser.add_argument(
        "--top-k",
        type=int,
        default=None,
        help="Top K retrieval results used for metrics. Defaults to the threshold file value.",
    )
    parser.add_argument(
        "--thresholds",
        default="evaluation/rag_eval_thresholds.json",
        help="Path to regression thresholds JSON.",
    )
    return parser.parse_args(argv)


def print_evaluation_summary(summary: RetrievalEvalSummary) -> None:
    print("Evaluation summary:")
    print(
        f"  retrieverName={summary.retriever_name} "
        f"caseCount={summary.case_count} "
        f"hitAtK={summary.hit_at_k:.4f} "
        f"recallAtK={summary.recall_at_k:.4f} "
        f"mrr={summary.mrr:.4f}"
    )


def print_regression_result(result: dict) -> None:
    print("Regression check result:")
    print(
        f"  retrieverName={result['retrieverName']} "
        f"topK={result['topK']} "
        f"passed={result['passed']}"
    )
    print(
        f"  hitAtK={result['hitAtK']:.4f} threshold={result['minHitAtK']:.4f}"
    )
    print(
        f"  recallAtK={result['recallAtK']:.4f} threshold={result['minRecallAtK']:.4f}"
    )
    print(f"  mrr={result['mrr']:.4f} threshold={result['minMRR']:.4f}")
    print(f"  failedMetrics={result['failedMetrics']}")
    print(f"  message={result['message']}")


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)
    thresholds = load_thresholds(args.thresholds)
    threshold = thresholds.get(args.retriever)
    if threshold is None:
        print(f"No regression threshold configured for retriever={args.retriever}", file=sys.stderr)
        return 1

    top_k = args.top_k if args.top_k is not None else threshold.top_k
    threshold = threshold.model_copy(update={"top_k": top_k})
    report = evaluate_retrievers(args.retriever, top_k)
    if report.get("error"):
        print(
            f"Evaluation failed for retriever={args.retriever}: {report['error']}",
            file=sys.stderr,
        )
        return 1

    summary = RetrievalEvalSummary.model_validate(report)
    result = RetrievalRegressionChecker().check(summary, threshold)
    result_dict = result.model_dump(by_alias=True)

    print_evaluation_summary(summary)
    print_regression_result(result_dict)
    print(json.dumps({"summary": report, "regression": result_dict}, ensure_ascii=False, indent=2))
    return 0 if result.passed else 1


if __name__ == "__main__":
    raise SystemExit(main())
