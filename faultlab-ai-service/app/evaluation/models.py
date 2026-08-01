from typing import Any

from pydantic import Field

from app.schemas import CamelModel


class ExpectedRunbookRef(CamelModel):
    doc_id: str = Field(alias="docId")
    section: str = ""


class RetrievedRunbookRef(CamelModel):
    doc_id: str = Field(alias="docId")
    section: str = ""
    score: float = 0.0


class RetrievalEvalCase(CamelModel):
    case_id: str = Field(alias="caseId")
    scenario_code: str = Field(alias="scenarioCode")
    query: dict[str, Any] = Field(default_factory=dict)
    expected: list[ExpectedRunbookRef] = Field(default_factory=list)


class RetrievalEvalResult(CamelModel):
    case_id: str = Field(alias="caseId")
    retriever_name: str = Field(alias="retrieverName")
    top_k: int = Field(alias="topK")
    hit: bool = False
    reciprocal_rank: float = Field(default=0.0, alias="reciprocalRank")
    recall: float = 0.0
    expected: list[ExpectedRunbookRef] = Field(default_factory=list)
    retrieved: list[RetrievedRunbookRef] = Field(default_factory=list)


class RetrievalEvalSummary(CamelModel):
    retriever_name: str = Field(alias="retrieverName")
    case_count: int = Field(alias="caseCount")
    hit_at_k: float = Field(alias="hitAtK")
    recall_at_k: float = Field(alias="recallAtK")
    mrr: float = 0.0
    results: list[RetrievalEvalResult] = Field(default_factory=list)
