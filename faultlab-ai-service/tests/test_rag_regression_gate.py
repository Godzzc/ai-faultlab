import importlib.util
import json
from pathlib import Path

from app.evaluation.models import RetrievalEvalSummary
from app.evaluation.regression_checker import (
    RetrievalRegressionChecker,
    RetrievalRegressionThreshold,
    load_thresholds,
)

THRESHOLDS_PATH = Path(__file__).resolve().parents[1] / "evaluation" / "rag_eval_thresholds.json"


def summary(
    hit_at_k=0.8,
    recall_at_k=0.7,
    mrr=0.6,
    retriever_name="bm25",
):
    return RetrievalEvalSummary.model_validate({
        "retrieverName": retriever_name,
        "caseCount": 5,
        "hitAtK": hit_at_k,
        "recallAtK": recall_at_k,
        "mrr": mrr,
        "results": [],
    })


def threshold():
    return RetrievalRegressionThreshold.model_validate({
        "topK": 3,
        "minHitAtK": 0.6,
        "minRecallAtK": 0.45,
        "minMRR": 0.45,
    })


def load_cli_module():
    script_path = Path(__file__).resolve().parents[1] / "scripts" / "check_rag_regression.py"
    spec = importlib.util.spec_from_file_location("check_rag_regression_script", script_path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def test_regression_checker_passes_when_all_metrics_meet_threshold():
    result = RetrievalRegressionChecker().check(summary(), threshold())

    assert result.passed is True
    assert result.failed_metrics == []


def test_regression_checker_fails_when_hit_at_k_is_below_threshold():
    result = RetrievalRegressionChecker().check(summary(hit_at_k=0.5), threshold())

    assert result.passed is False
    assert result.failed_metrics == ["hitAtK"]


def test_regression_checker_fails_when_recall_at_k_is_below_threshold():
    result = RetrievalRegressionChecker().check(summary(recall_at_k=0.4), threshold())

    assert result.passed is False
    assert result.failed_metrics == ["recallAtK"]


def test_regression_checker_fails_when_mrr_is_below_threshold():
    result = RetrievalRegressionChecker().check(summary(mrr=0.4), threshold())

    assert result.passed is False
    assert result.failed_metrics == ["mrr"]


def test_regression_checker_records_multiple_failed_metrics():
    result = RetrievalRegressionChecker().check(
        summary(hit_at_k=0.5, recall_at_k=0.4, mrr=0.4),
        threshold(),
    )

    assert result.passed is False
    assert result.failed_metrics == ["hitAtK", "recallAtK", "mrr"]


def test_regression_checker_message_explains_failure_reason():
    result = RetrievalRegressionChecker().check(summary(hit_at_k=0.5), threshold())

    assert "failed" in result.message
    assert "hitAtK" in result.message
    assert "below threshold" in result.message


def test_thresholds_json_can_be_loaded():
    thresholds = load_thresholds(THRESHOLDS_PATH)

    assert thresholds["bm25"].top_k == 3
    assert thresholds["bm25"].min_hit_at_k == 0.6
    assert thresholds["bm25"].min_mrr == 0.35
    assert thresholds["hybrid"].min_mrr == 0.55


def test_check_rag_regression_script_can_be_imported_without_running():
    module = load_cli_module()

    assert hasattr(module, "main")


def test_check_rag_regression_returns_zero_when_passed(monkeypatch, tmp_path):
    module = load_cli_module()
    thresholds_path = tmp_path / "thresholds.json"
    thresholds_path.write_text(
        json.dumps({
            "bm25": {
                "topK": 3,
                "minHitAtK": 0.6,
                "minRecallAtK": 0.45,
                "minMRR": 0.45,
            }
        }),
        encoding="utf-8",
    )
    monkeypatch.setattr(
        module,
        "evaluate_retrievers",
        lambda retriever, top_k: summary().model_dump(by_alias=True),
    )

    exit_code = module.main(["--retriever", "bm25", "--thresholds", str(thresholds_path)])

    assert exit_code == 0


def test_check_rag_regression_returns_one_when_failed(monkeypatch, tmp_path):
    module = load_cli_module()
    thresholds_path = tmp_path / "thresholds.json"
    thresholds_path.write_text(
        json.dumps({
            "bm25": {
                "topK": 3,
                "minHitAtK": 0.6,
                "minRecallAtK": 0.45,
                "minMRR": 0.45,
            }
        }),
        encoding="utf-8",
    )
    monkeypatch.setattr(
        module,
        "evaluate_retrievers",
        lambda retriever, top_k: summary(hit_at_k=0.5).model_dump(by_alias=True),
    )

    exit_code = module.main(["--retriever", "bm25", "--thresholds", str(thresholds_path)])

    assert exit_code == 1


def test_bm25_regression_gate_does_not_instantiate_milvus(monkeypatch, tmp_path):
    module = load_cli_module()
    thresholds_path = tmp_path / "thresholds.json"
    thresholds_path.write_text(
        json.dumps({
            "bm25": {
                "topK": 3,
                "minHitAtK": 0.0,
                "minRecallAtK": 0.0,
                "minMRR": 0.0,
            }
        }),
        encoding="utf-8",
    )

    def fail_if_milvus_is_used(*args, **kwargs):
        raise AssertionError("Milvus should not be used by the bm25 regression gate")

    monkeypatch.setattr(
        "app.evaluation.retrieval_evaluator.MilvusRunbookRetriever",
        fail_if_milvus_is_used,
    )

    exit_code = module.main(["--retriever", "bm25", "--thresholds", str(thresholds_path)])

    assert exit_code == 0
