import json
from pathlib import Path

from pydantic import Field

from app.evaluation.models import RetrievalEvalSummary
from app.schemas import CamelModel


class RetrievalRegressionThreshold(CamelModel):
    top_k: int = Field(alias="topK")
    min_hit_at_k: float = Field(alias="minHitAtK")
    min_recall_at_k: float = Field(alias="minRecallAtK")
    min_mrr: float = Field(alias="minMRR")


class RetrievalRegressionResult(CamelModel):
    retriever_name: str = Field(alias="retrieverName")
    passed: bool
    top_k: int = Field(alias="topK")
    hit_at_k: float = Field(alias="hitAtK")
    min_hit_at_k: float = Field(alias="minHitAtK")
    recall_at_k: float = Field(alias="recallAtK")
    min_recall_at_k: float = Field(alias="minRecallAtK")
    mrr: float
    min_mrr: float = Field(alias="minMRR")
    failed_metrics: list[str] = Field(default_factory=list, alias="failedMetrics")
    message: str


class RetrievalRegressionChecker:
    def check(
        self,
        summary: RetrievalEvalSummary,
        threshold: RetrievalRegressionThreshold,
    ) -> RetrievalRegressionResult:
        failed_metrics: list[str] = []
        if summary.hit_at_k < threshold.min_hit_at_k:
            failed_metrics.append("hitAtK")
        if summary.recall_at_k < threshold.min_recall_at_k:
            failed_metrics.append("recallAtK")
        if summary.mrr < threshold.min_mrr:
            failed_metrics.append("mrr")

        passed = not failed_metrics
        message = (
            f"{summary.retriever_name} retrieval regression check passed."
            if passed
            else (
                f"{summary.retriever_name} retrieval regression check failed: "
                f"{', '.join(failed_metrics)} below threshold."
            )
        )

        return RetrievalRegressionResult(
            retrieverName=summary.retriever_name,
            passed=passed,
            topK=threshold.top_k,
            hitAtK=summary.hit_at_k,
            minHitAtK=threshold.min_hit_at_k,
            recallAtK=summary.recall_at_k,
            minRecallAtK=threshold.min_recall_at_k,
            mrr=summary.mrr,
            minMRR=threshold.min_mrr,
            failedMetrics=failed_metrics,
            message=message,
        )


def default_thresholds_path() -> Path:
    return Path(__file__).resolve().parents[2] / "evaluation" / "rag_eval_thresholds.json"


def load_thresholds(path: Path | str | None = None) -> dict[str, RetrievalRegressionThreshold]:
    thresholds_path = Path(path) if path else default_thresholds_path()
    raw_thresholds = json.loads(thresholds_path.read_text(encoding="utf-8"))
    return {
        retriever_name: RetrievalRegressionThreshold.model_validate(threshold)
        for retriever_name, threshold in raw_thresholds.items()
    }
