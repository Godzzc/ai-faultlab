import re
from typing import Any

from pydantic import Field

from app.schemas import CamelModel

DOC_ID_PATTERN = re.compile(r"^[a-z0-9]+(?:-[a-z0-9]+)*$")


class RunbookServiceError(Exception):
    status_code = 500


class RunbookValidationError(RunbookServiceError):
    status_code = 400


class RunbookNotFoundError(RunbookServiceError):
    status_code = 404


class RunbookConflictError(RunbookServiceError):
    status_code = 409


def validate_doc_id_value(doc_id: str) -> str:
    if not DOC_ID_PATTERN.fullmatch(doc_id or ""):
        raise ValueError("docId must contain lowercase letters, digits, and single hyphens only")
    return doc_id


class RunbookDocumentSummary(CamelModel):
    doc_id: str = Field(alias="docId")
    title: str = ""
    fault_type: str = Field(default="", alias="faultType")
    keywords: list[str] = Field(default_factory=list)
    section_count: int = Field(default=0, alias="sectionCount")
    file_name: str = Field(default="", alias="fileName")
    content_hash: str = Field(default="", alias="contentHash")
    indexed_at: str | None = Field(default=None, alias="indexedAt")
    chunk_count: int = Field(default=0, alias="chunkCount")
    exists_in_index_state: bool = Field(default=False, alias="existsInIndexState")
    status: str = "not_indexed"


class RunbookSection(CamelModel):
    section: str
    content: str = ""


class RunbookDocumentDetail(CamelModel):
    doc_id: str = Field(alias="docId")
    title: str = ""
    fault_type: str = Field(default="", alias="faultType")
    keywords: list[str] = Field(default_factory=list)
    file_name: str = Field(default="", alias="fileName")
    content_hash: str = Field(default="", alias="contentHash")
    sections: list[RunbookSection] = Field(default_factory=list)
    raw_markdown: str = Field(default="", alias="rawMarkdown")
    indexed_at: str | None = Field(default=None, alias="indexedAt")
    chunk_count: int = Field(default=0, alias="chunkCount")
    exists_in_index_state: bool = Field(default=False, alias="existsInIndexState")
    status: str = "not_indexed"


class RunbookSaveRequest(CamelModel):
    doc_id: str = Field(alias="docId")
    title: str = ""
    fault_type: str = Field(default="", alias="faultType")
    keywords: list[str] = Field(default_factory=list)
    raw_markdown: str = Field(default="", alias="rawMarkdown")


class RunbookSaveResponse(CamelModel):
    doc_id: str = Field(alias="docId")
    file_name: str = Field(alias="fileName")
    status: str
    message: str


class RunbookDeleteResponse(CamelModel):
    doc_id: str = Field(alias="docId")
    file_name: str = Field(alias="fileName")
    status: str
    message: str


class RunbookIndexTaskResponse(CamelModel):
    task: Any
    result: dict[str, Any] | None = None
