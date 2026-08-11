from app.config import load_settings, settings
from app.llm_client import LlmClient
from app.model_router import ModelRouter
from app.schemas import DiagnosisRequest


def build_request(rule_result=None, metrics=None, roots=None):
    return DiagnosisRequest.model_validate({
        "experiment": {
            "experimentId": "exp_xxx",
            "scenarioCode": "MQ_BACKLOG",
            "status": "RUNNING",
            "traceId": "trace_xxx",
        },
        "metrics": metrics or [],
        "traceTree": {
            "traceId": "trace_xxx",
            "roots": roots or [],
        },
        "ruleResult": rule_result,
    })


def matched_rule_result(evidence=None):
    return {
        "experimentId": "exp_xxx",
        "faultType": "MQ_BACKLOG",
        "faultName": "MQ 消息堆积",
        "confidence": 0.85,
        "matched": True,
        "reason": "生产消息数大于消费消息数。",
        "evidence": evidence or ["publishCount=10"],
        "suggestions": ["增加消费者并发"],
    }


def test_config_only_reads_dashscope_api_key_from_environment(monkeypatch):
    monkeypatch.setenv("DASHSCOPE_API_KEY", "env-key")
    monkeypatch.setenv("DASHSCOPE_BASE_URL", "https://ignored.example.com")
    monkeypatch.setenv("LLM_TIMEOUT_SECONDS", "1")
    monkeypatch.setenv("LLM_ENABLED", "false")

    loaded = load_settings()

    assert loaded.dashscope_api_key == "env-key"
    assert loaded.dashscope_base_url == "https://dashscope.aliyuncs.com/compatible-mode/v1"
    assert loaded.llm_timeout_seconds == 90
    assert loaded.llm_enabled is True
    assert loaded.embedding_dimension == 1024
    assert loaded.milvus_host == "localhost"
    assert loaded.milvus_port == 19530


def test_config_uses_default_base_url_and_timeout(monkeypatch):
    monkeypatch.delenv("DASHSCOPE_API_KEY", raising=False)

    loaded = load_settings()

    assert loaded.dashscope_base_url == "https://dashscope.aliyuncs.com/compatible-mode/v1"
    assert loaded.llm_timeout_seconds == 90


def test_model_router_selects_fast_model_when_rule_result_missing():
    result = ModelRouter().select_model(build_request(rule_result=None), {"nodeCount": 0})

    assert result.model == settings.llm_fast_model
    assert result.reason == "rule_result_missing_or_not_matched"


def test_model_router_selects_fast_model_when_rule_result_not_matched():
    rule_result = matched_rule_result()
    rule_result["matched"] = False

    result = ModelRouter().select_model(build_request(rule_result=rule_result), {"nodeCount": 0})

    assert result.model == settings.llm_fast_model
    assert result.reason == "rule_result_missing_or_not_matched"


def test_model_router_selects_reasoning_model_for_large_trace_tree():
    result = ModelRouter().select_model(
        build_request(rule_result=matched_rule_result()),
        {"nodeCount": 30},
    )

    assert result.model == settings.llm_reasoning_model
    assert result.reason == "large_trace_tree"


def test_model_router_selects_reasoning_model_for_rich_rule_evidence():
    result = ModelRouter().select_model(
        build_request(rule_result=matched_rule_result(evidence=[f"evidence_{index}" for index in range(10)])),
        {"nodeCount": 1},
    )

    assert result.model == settings.llm_reasoning_model
    assert result.reason == "rich_rule_evidence"


def test_model_router_selects_long_context_model_for_many_metrics():
    metrics = [
        {
            "metricName": f"metric_{index}",
            "metricValue": str(index),
            "metricUnit": "count",
            "component": "Test",
        }
        for index in range(20)
    ]

    result = ModelRouter().select_model(
        build_request(rule_result=matched_rule_result(), metrics=metrics),
        {"nodeCount": 1},
    )

    assert result.model == settings.llm_long_context_model
    assert result.reason == "many_metrics"


def test_model_router_selects_default_model():
    result = ModelRouter().select_model(
        build_request(rule_result=matched_rule_result()),
        {"nodeCount": 1},
    )

    assert result.model == settings.llm_default_model
    assert result.reason == "default_diagnosis"


def test_llm_client_generate_uses_passed_model(monkeypatch):
    calls = []

    class FakeMessage:
        content = "{}"

    class FakeChoice:
        message = FakeMessage()

    class FakeCompletions:
        def create(self, **kwargs):
            calls.append(kwargs)
            return type("Response", (), {"choices": [FakeChoice()]})()

    class FakeChat:
        completions = FakeCompletions()

    class FakeOpenAI:
        def __init__(self, **kwargs):
            self.chat = FakeChat()

    monkeypatch.setattr("app.llm_client.OpenAI", FakeOpenAI)

    client = LlmClient()
    content = client.generate("system", "user", model="qwen-custom")

    assert content == "{}"
    assert calls[0]["model"] == "qwen-custom"
