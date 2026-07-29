import json

from fastapi.testclient import TestClient

from app import workflow
from app.main import app


client = TestClient(app)


class FakeLlmClient:
    def __init__(self, outputs=None, exception=None):
        self.outputs = list(outputs or [])
        self.exception = exception
        self.calls = 0

    def generate(self, system_prompt, user_prompt):
        self.calls += 1
        assert "Evidence Package" in user_prompt
        assert "严格 JSON" in system_prompt
        if self.exception:
            raise self.exception
        if self.outputs:
            return self.outputs.pop(0)
        return ""


def build_request(rule_result=None, metrics=None, roots=None):
    payload = {
        "experiment": {
            "experimentId": "exp_xxx",
            "scenarioCode": "MQ_BACKLOG",
            "status": "RUNNING",
            "traceId": "trace_xxx",
        },
        "metrics": metrics if metrics is not None else [
            {
                "metricName": "publishCount",
                "metricValue": "10",
                "metricUnit": "count",
                "component": "RabbitMQ",
            }
        ],
        "traceTree": {
            "traceId": "trace_xxx",
            "roots": roots if roots is not None else [],
        },
    }
    if rule_result is not None:
        payload["ruleResult"] = rule_result
    return payload


def matched_rule_result():
    return {
        "experimentId": "exp_xxx",
        "faultType": "MQ_BACKLOG",
        "faultName": "MQ 消息堆积",
        "confidence": 0.85,
        "matched": True,
        "reason": "生产消息数大于消费消息数，且消费耗时较高。",
        "evidence": [
            "publishCount=10",
            "consumeCount=1",
        ],
        "suggestions": [
            "增加消费者并发",
        ],
    }


def llm_response(confidence=0.86):
    return {
        "experimentId": "exp_xxx",
        "faultType": "MQ_BACKLOG",
        "faultName": "MQ 消息堆积",
        "confidence": confidence,
        "summary": "LLM 基于证据判断存在 MQ 消息堆积风险。",
        "phenomenon": ["消费数量明显低于生产数量"],
        "evidence": ["publishCount=10", "consumeCount=1"],
        "rootCauses": ["消费者处理速度不足"],
        "suggestions": ["增加消费者并发"],
        "runbookReferences": [],
        "fallback": False,
    }


def enable_llm(monkeypatch, fake_client):
    monkeypatch.setattr(workflow.settings, "llm_enabled", True)
    monkeypatch.setattr(workflow.settings, "dashscope_api_key", "test-key")
    monkeypatch.setattr(workflow.settings, "llm_max_retries", 1)
    monkeypatch.setattr(workflow, "LlmClient", lambda: fake_client)


def disable_llm(monkeypatch):
    monkeypatch.setattr(workflow.settings, "llm_enabled", False)
    monkeypatch.setattr(workflow.settings, "dashscope_api_key", "test-key")


def clear_api_key(monkeypatch):
    monkeypatch.setattr(workflow.settings, "llm_enabled", True)
    monkeypatch.setattr(workflow.settings, "dashscope_api_key", "")


def test_health_returns_up():
    response = client.get("/ai/health")

    assert response.status_code == 200
    assert response.json() == {
        "service": "faultlab-ai-service",
        "status": "UP",
    }


def test_generate_diagnosis_returns_fallback_when_llm_disabled(monkeypatch):
    disable_llm(monkeypatch)

    response = client.post(
        "/ai/diagnosis/generate",
        json=build_request(rule_result=matched_rule_result()),
    )

    assert response.status_code == 200
    body = response.json()
    assert body["experimentId"] == "exp_xxx"
    assert body["faultType"] == "MQ_BACKLOG"
    assert body["fallback"] is True


def test_generate_diagnosis_does_not_call_llm_when_api_key_empty(monkeypatch):
    clear_api_key(monkeypatch)
    fake_client = FakeLlmClient(outputs=[json.dumps(llm_response(), ensure_ascii=False)])
    monkeypatch.setattr(workflow, "LlmClient", lambda: fake_client)

    response = client.post(
        "/ai/diagnosis/generate",
        json=build_request(rule_result=matched_rule_result()),
    )

    assert response.status_code == 200
    assert response.json()["fallback"] is True
    assert fake_client.calls == 0


def test_generate_diagnosis_with_valid_llm_json_returns_non_fallback(monkeypatch):
    fake_client = FakeLlmClient(outputs=[json.dumps(llm_response(), ensure_ascii=False)])
    enable_llm(monkeypatch, fake_client)

    response = client.post(
        "/ai/diagnosis/generate",
        json=build_request(rule_result=matched_rule_result()),
    )

    assert response.status_code == 200
    body = response.json()
    assert body["fallback"] is False
    assert body["summary"] == "LLM 基于证据判断存在 MQ 消息堆积风险。"
    assert body["rootCauses"] == ["消费者处理速度不足"]
    assert fake_client.calls == 1


def test_generate_diagnosis_parses_markdown_json_code_block(monkeypatch):
    raw = "```json\n" + json.dumps(llm_response(), ensure_ascii=False) + "\n```"
    fake_client = FakeLlmClient(outputs=[raw])
    enable_llm(monkeypatch, fake_client)

    response = client.post(
        "/ai/diagnosis/generate",
        json=build_request(rule_result=matched_rule_result()),
    )

    assert response.status_code == 200
    assert response.json()["fallback"] is False


def test_generate_diagnosis_fallbacks_when_llm_returns_invalid_json(monkeypatch):
    fake_client = FakeLlmClient(outputs=["not json", "still not json"])
    enable_llm(monkeypatch, fake_client)

    response = client.post(
        "/ai/diagnosis/generate",
        json=build_request(rule_result=matched_rule_result()),
    )

    assert response.status_code == 200
    body = response.json()
    assert body["fallback"] is True
    assert body["evidence"] == ["publishCount=10", "consumeCount=1"]
    assert fake_client.calls == 2


def test_generate_diagnosis_fallbacks_when_llm_raises(monkeypatch):
    fake_client = FakeLlmClient(exception=RuntimeError("timeout"))
    enable_llm(monkeypatch, fake_client)

    response = client.post(
        "/ai/diagnosis/generate",
        json=build_request(rule_result=matched_rule_result()),
    )

    assert response.status_code == 200
    assert response.json()["fallback"] is True


def test_generate_diagnosis_clamps_confidence(monkeypatch):
    fake_client = FakeLlmClient(outputs=[json.dumps(llm_response(confidence=1.8), ensure_ascii=False)])
    enable_llm(monkeypatch, fake_client)

    response = client.post(
        "/ai/diagnosis/generate",
        json=build_request(rule_result=matched_rule_result()),
    )

    assert response.status_code == 200
    body = response.json()
    assert body["fallback"] is False
    assert body["confidence"] == 1.0


def test_fallback_report_keeps_rule_evidence_and_suggestions(monkeypatch):
    disable_llm(monkeypatch)

    response = client.post(
        "/ai/diagnosis/generate",
        json=build_request(rule_result=matched_rule_result()),
    )

    assert response.status_code == 200
    body = response.json()
    assert body["fallback"] is True
    assert body["evidence"] == ["publishCount=10", "consumeCount=1"]
    assert body["suggestions"] == ["增加消费者并发"]


def test_generate_diagnosis_accepts_empty_metrics_and_trace_roots(monkeypatch):
    disable_llm(monkeypatch)

    response = client.post(
        "/ai/diagnosis/generate",
        json=build_request(rule_result=matched_rule_result(), metrics=[], roots=[]),
    )

    assert response.status_code == 200
    body = response.json()
    assert body["experimentId"] == "exp_xxx"
    assert body["fallback"] is True
