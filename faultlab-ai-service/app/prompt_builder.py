import json
from typing import Any

from app.schemas import DiagnosisRequest


class PromptBuilder:
    def build(
        self,
        request: DiagnosisRequest,
        trace_summary: dict[str, Any],
    ) -> tuple[str, str]:
        system_prompt = (
            "你是 AI FaultLab 的后端故障诊断助手。"
            "你只能基于用户提供的 Evidence Package 生成诊断报告。"
            "不要编造不存在的指标、Trace 节点、Runbook 引用或故障事实。"
            "不要输出 Markdown，不要输出解释性前后缀。"
            "必须只输出严格 JSON object。"
        )
        evidence_package = request.model_dump(by_alias=True)
        evidence_package["traceSummary"] = trace_summary
        user_prompt = (
            "请基于以下 Evidence Package 生成 DiagnosisResponse JSON。"
            "要求："
            "1. 字段必须包含 experimentId, faultType, faultName, confidence, summary, "
            "phenomenon, evidence, rootCauses, suggestions, runbookReferences, fallback。"
            "2. confidence 必须在 0 到 1 之间。"
            "3. phenomenon/evidence/rootCauses/suggestions/runbookReferences 必须是数组。"
            "4. 如果 ruleResult.matched=true，结论不得和 ruleResult 明显冲突。"
            "5. 如果证据不足，summary 必须说明证据不足。"
            "6. 当前没有 Runbook RAG，runbookReferences 必须返回空数组。"
            "7. LLM 成功生成时 fallback 必须为 false。"
            "\nEvidence Package:\n"
            f"{json.dumps(evidence_package, ensure_ascii=False)}"
        )
        return system_prompt, user_prompt
