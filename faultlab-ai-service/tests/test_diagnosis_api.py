from fastapi.testclient import TestClient

from app.main import app


client = TestClient(app)


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


def test_health_returns_up():
    response = client.get("/ai/health")

    assert response.status_code == 200
    assert response.json() == {
        "service": "faultlab-ai-service",
        "status": "UP",
    }


def test_generate_diagnosis_with_matched_rule_result():
    response = client.post(
        "/ai/diagnosis/generate",
        json=build_request(rule_result=matched_rule_result()),
    )

    assert response.status_code == 200
    body = response.json()
    assert body["experimentId"] == "exp_xxx"
    assert body["faultType"] == "MQ_BACKLOG"
    assert body["faultName"] == "MQ 消息堆积"
    assert body["confidence"] == 0.85
    assert body["summary"] == "本次实验检测到 MQ 消息堆积风险。"
    assert body["evidence"] == ["publishCount=10", "consumeCount=1"]
    assert body["suggestions"] == ["增加消费者并发"]
    assert body["runbookReferences"] == []
    assert body["fallback"] is False


def test_generate_diagnosis_without_rule_result_returns_fallback():
    response = client.post("/ai/diagnosis/generate", json=build_request())

    assert response.status_code == 200
    body = response.json()
    assert body["experimentId"] == "exp_xxx"
    assert body["faultType"] == "MQ_BACKLOG"
    assert body["confidence"] == 0.2
    assert body["summary"] == "当前证据不足，暂未生成明确故障结论。"
    assert body["fallback"] is True


def test_generate_diagnosis_accepts_empty_metrics():
    response = client.post(
        "/ai/diagnosis/generate",
        json=build_request(rule_result=matched_rule_result(), metrics=[]),
    )

    assert response.status_code == 200
    assert response.json()["fallback"] is False


def test_generate_diagnosis_accepts_empty_trace_roots():
    response = client.post(
        "/ai/diagnosis/generate",
        json=build_request(rule_result=matched_rule_result(), roots=[]),
    )

    assert response.status_code == 200
    assert response.json()["faultType"] == "MQ_BACKLOG"


def test_generate_diagnosis_response_contains_required_fields():
    response = client.post(
        "/ai/diagnosis/generate",
        json=build_request(rule_result=matched_rule_result()),
    )

    assert response.status_code == 200
    body = response.json()
    for field in [
        "experimentId",
        "faultType",
        "confidence",
        "summary",
        "evidence",
        "suggestions",
        "fallback",
    ]:
        assert field in body
