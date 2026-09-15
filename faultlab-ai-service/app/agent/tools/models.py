from typing import Any

from pydantic import Field

from app.schemas import CamelModel


class AgentToolResult(CamelModel):
    tool_name: str
    success: bool
    data: dict[str, Any] = Field(default_factory=dict)
    warnings: list[str] = Field(default_factory=list)
    error_code: str | None = None
    error_message: str | None = None
    metadata: dict[str, Any] = Field(default_factory=dict)

    @classmethod
    def ok(
        cls,
        tool_name: str,
        data: dict[str, Any] | None = None,
        warnings: list[str] | None = None,
        metadata: dict[str, Any] | None = None,
    ) -> "AgentToolResult":
        return cls(
            tool_name=tool_name,
            success=True,
            data=data or {},
            warnings=warnings or [],
            metadata=metadata or {},
        )

    @classmethod
    def fail(
        cls,
        tool_name: str,
        error_code: str,
        error_message: str,
        data: dict[str, Any] | None = None,
        warnings: list[str] | None = None,
        metadata: dict[str, Any] | None = None,
    ) -> "AgentToolResult":
        return cls(
            tool_name=tool_name,
            success=False,
            data=data or {},
            warnings=warnings or [],
            error_code=error_code,
            error_message=error_message,
            metadata=metadata or {},
        )
