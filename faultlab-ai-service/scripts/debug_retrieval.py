import argparse
import json
import sys
from pathlib import Path

ROOT_DIR = Path(__file__).resolve().parents[1]
if str(ROOT_DIR) not in sys.path:
    sys.path.insert(0, str(ROOT_DIR))

from app.evaluation.retrieval_evaluator import RetrievalEvaluator  # noqa: E402
from app.retrieval.debug_models import RetrievalDebugRequest  # noqa: E402
from app.retrieval.debug_service import RetrievalDebugService  # noqa: E402


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Debug one Runbook retrieval evaluation case.")
    parser.add_argument("--case-id", required=True, help="Case ID from evaluation/rag_eval_cases.json.")
    parser.add_argument("--top-k", type=int, default=3, help="Final top K results.")
    parser.add_argument("--no-content", action="store_true", help="Omit chunk content from output.")
    parser.add_argument("--output", help="Write debug JSON to this file.")
    parser.add_argument(
        "--cases",
        default="evaluation/rag_eval_cases.json",
        help="Path to RAG evaluation cases JSON.",
    )
    return parser.parse_args(argv)


def load_case_request(
    case_id: str,
    cases_path: Path | str = "evaluation/rag_eval_cases.json",
    top_k: int = 3,
    include_content: bool = True,
) -> RetrievalDebugRequest:
    evaluator = RetrievalEvaluator(cases_path)
    for case in evaluator.load_cases():
        if case.case_id != case_id:
            continue
        diagnosis_request = evaluator.case_to_request(case)
        payload = diagnosis_request.model_dump(by_alias=True)
        payload["topK"] = top_k
        payload["includeContent"] = include_content
        return RetrievalDebugRequest.model_validate(payload)
    raise ValueError(f"Evaluation case not found: {case_id}")


def print_stage(name: str, chunks: list[dict]) -> None:
    print(f"{name}: count={len(chunks)}")
    for index, chunk in enumerate(chunks, start=1):
        print(
            f"  {index}. {chunk.get('docId')}#{chunk.get('section')} "
            f"score={chunk.get('score'):.4f} metadata={chunk.get('metadata')}"
        )


def print_debug_result(result: dict) -> None:
    print("queryText:")
    print(result["queryText"])
    print(f"faultType={result.get('faultType')}")
    print(f"warnings={result.get('warnings')}")
    print_stage("vectorResults", result.get("vectorResults", []))
    print_stage("bm25Results", result.get("bm25Results", []))
    print_stage("fusionResults", result.get("fusionResults", []))
    print_stage("rerankResults", result.get("rerankResults", []))
    print_stage("finalResults", result.get("finalResults", []))


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)
    try:
        request = load_case_request(
            args.case_id,
            cases_path=args.cases,
            top_k=args.top_k,
            include_content=not args.no_content,
        )
        response = RetrievalDebugService().debug(request)
    except ValueError as exc:
        print(str(exc), file=sys.stderr)
        return 1

    result = response.model_dump(by_alias=True)
    print_debug_result(result)
    if args.output:
        output_path = Path(args.output)
        output_path.parent.mkdir(parents=True, exist_ok=True)
        output_path.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
        print(f"Debug JSON written to {output_path}")
    else:
        print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
