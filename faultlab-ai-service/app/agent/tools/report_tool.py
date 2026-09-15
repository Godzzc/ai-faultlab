from typing import Any

from app.agent.models import DiagnosisAgentContext
from app.agent.tools.base import AgentTool
from app.agent.tools.models import AgentToolResult
from app.fallback import build_fallback_report


class DiagnosisReportGeneratorTool(AgentTool):
    def __init__(
        self,
        llm_client: Any | None = None,
        prompt_builder: Any | None = None,
        model_router: Any | None = None,
    ) -> None:
        self.llm_client = llm_client
        self.prompt_builder = prompt_builder
        self.model_router = model_router

    @property
    def name(self) -> str:
        return "diagnosis_report_generator"

    @property
    def description(self) -> str:
        return "Generate a diagnosis report through the existing LLM or rule-based fallback flow."

    def execute(self, context: DiagnosisAgentContext) -> AgentToolResult:
        from app import workflow

        request = context.evidence_package
        if context.fallback_reason == "runbook retrieval unavailable":
            report = build_fallback_report(request)
            return AgentToolResult.ok(
                tool_name=self.name,
                data={"report": report},
                metadata={"fallback": True, "fallbackReason": context.fallback_reason},
            )

        if not workflow.should_call_llm():
            report = workflow.fallback_if_needed(request, build_fallback_report(request))
            return AgentToolResult.ok(
                tool_name=self.name,
                data={"report": report},
                metadata={"fallback": True, "fallbackReason": "llm_disabled_or_api_key_missing"},
            )

        try:
            system_prompt, user_prompt = workflow.build_prompt(
                request,
                context.trace_summary or {},
                context.retrieved_chunks,
                self.prompt_builder,
            )
            route_result = workflow.select_model(request, context.trace_summary or {}, self.model_router)
            response = workflow.call_llm_and_parse(
                request,
                system_prompt,
                user_prompt,
                route_result.model,
                self.llm_client,
            )
            workflow.validate_runbook_references(response, context.retrieved_chunks)
            report = workflow.fallback_if_needed(request, response)
            return AgentToolResult.ok(
                tool_name=self.name,
                data={"report": report},
                metadata={
                    "fallback": report.fallback,
                    "model": route_result.model,
                    "provider": "dashscope_openai_compatible",
                },
            )
        except Exception as exc:
            return AgentToolResult.fail(
                tool_name=self.name,
                error_code="MODEL_PROVIDER_FAILED",
                error_message=str(exc),
            )
