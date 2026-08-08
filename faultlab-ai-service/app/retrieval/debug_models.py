from typing import Any

from pydantic import Field

from app.schemas import CamelModel, DiagnosisRequest


class RetrievalDebugRequest(DiagnosisRequest):
    top_k: int = Field(default=3, alias="topK")
    include_content: bool = Field(default=True, alias="includeContent")


class RetrievalDebugChunk(CamelModel):
    doc_id: str = Field(alias="docId")
    title: str = ""
    fault_type: str = Field(default="", alias="faultType")
    section: str = ""
    score: float = 0.0
    content: str = ""
    keywords: list[str] = Field(default_factory=list)
    metadata: dict[str, Any] = Field(default_factory=dict)


class RetrievalDebugResponse(CamelModel):
    query_text: str = Field(alias="queryText")
    fault_type: str = Field(default="", alias="faultType")
    vector_results: list[RetrievalDebugChunk] = Field(default_factory=list, alias="vectorResults")
    bm25_results: list[RetrievalDebugChunk] = Field(default_factory=list, alias="bm25Results")
    fusion_results: list[RetrievalDebugChunk] = Field(default_factory=list, alias="fusionResults")
    rerank_results: list[RetrievalDebugChunk] = Field(default_factory=list, alias="rerankResults")
    final_results: list[RetrievalDebugChunk] = Field(default_factory=list, alias="finalResults")
    fallback_reason: str | None = Field(default=None, alias="fallbackReason")
    warnings: list[str] = Field(default_factory=list)
