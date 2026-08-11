from enum import StrEnum
from typing import Any

from pydantic import Field

from app.schemas import CamelModel


class RunbookIndexTaskStatus(StrEnum):
    PENDING = "PENDING"
    RUNNING = "RUNNING"
    SUCCESS = "SUCCESS"
    PARTIAL_SUCCESS = "PARTIAL_SUCCESS"
    FAILED = "FAILED"


class RunbookIndexTask(CamelModel):
    task_id: str = Field(alias="taskId")
    status: RunbookIndexTaskStatus = RunbookIndexTaskStatus.PENDING
    started_at: str | None = Field(default=None, alias="startedAt")
    finished_at: str | None = Field(default=None, alias="finishedAt")
    duration_ms: int | None = Field(default=None, alias="durationMs")
    force_rebuild: bool = Field(default=False, alias="forceRebuild")
    requested_doc_ids: list[str] = Field(default_factory=list, alias="requestedDocIds")
    collection_name: str = Field(default="", alias="collectionName")
    indexed_count: int = Field(default=0, alias="indexedCount")
    skipped_count: int = Field(default=0, alias="skippedCount")
    deleted_count: int = Field(default=0, alias="deletedCount")
    failed_count: int = Field(default=0, alias="failedCount")
    indexed_documents: list[str] = Field(default_factory=list, alias="indexedDocuments")
    skipped_documents: list[str] = Field(default_factory=list, alias="skippedDocuments")
    failed_documents: list[str] = Field(default_factory=list, alias="failedDocuments")
    error_message: str = Field(default="", alias="errorMessage")


class RunbookIndexTaskRequest(CamelModel):
    force_rebuild: bool = Field(default=False, alias="forceRebuild")
    doc_ids: list[str] = Field(default_factory=list, alias="docIds")


class RunbookIndexTaskRunResponse(CamelModel):
    task: RunbookIndexTask
    result: dict[str, Any] | None = None
