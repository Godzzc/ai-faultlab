import json
from typing import Any

from app.retrieval.models import RunbookChunk, runbook_chunks_to_prompt_context
from app.schemas import DiagnosisRequest


class PromptBuilder:
    def build(
        self,
        request: DiagnosisRequest,
        trace_summary: dict[str, Any],
        runbook_chunks: list[RunbookChunk] | None = None,
    ) -> tuple[str, str]:
        runbook_context = runbook_chunks_to_prompt_context(runbook_chunks or [])

        system_prompt = (
            "You are the AI FaultLab backend incident diagnosis assistant. "
            "Generate a diagnosis report only from the provided Evidence Package and Runbook Context. "
            "Do not invent metrics, trace nodes, runbook references, or incident facts. "
            "You may use Runbook Context as operational guidance, but must not fabricate content outside it. "
            "runbookReferences may only cite docId and section pairs present in Runbook Context. "
            "Do not output Markdown or explanatory text. Output a strict JSON object only."
        )

        evidence_package = request.model_dump(by_alias=True)
        evidence_package["traceSummary"] = trace_summary

        user_prompt = (
            "Generate a DiagnosisResponse JSON from the Evidence Package.\n"
            "Requirements:\n"
            "1. Include fields: experimentId, faultType, faultName, confidence, summary, "
            "phenomenon, evidence, rootCauses, suggestions, runbookReferences, fallback.\n"
            "2. confidence must be between 0 and 1.\n"
            "3. phenomenon, evidence, rootCauses, suggestions, and runbookReferences must be arrays.\n"
            "4. If ruleResult.matched=true, the conclusion must not conflict with ruleResult.\n"
            "5. If evidence is insufficient, summary must state that evidence is insufficient.\n"
            "6. You can reference Runbook Context, but cannot invent runbook content.\n"
            "7. runbookReferences must contain only docId, title, and section values from Runbook Context.\n"
            "8. If Runbook Context is empty, generate only from Evidence Package and return empty runbookReferences.\n"
            "9. When LLM generation succeeds, fallback must be false.\n"
            "\nEvidence Package:\n"
            f"{json.dumps(evidence_package, ensure_ascii=False)}"
            "\nRunbook Context:\n"
            f"{json.dumps(runbook_context, ensure_ascii=False)}"
        )
        return system_prompt, user_prompt
