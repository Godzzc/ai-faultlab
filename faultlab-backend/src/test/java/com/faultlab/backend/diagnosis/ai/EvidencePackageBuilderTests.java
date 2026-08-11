package com.faultlab.backend.diagnosis.ai;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.faultlab.backend.diagnosis.ai.dto.AiDiagnosisRequest;
import com.faultlab.backend.diagnosis.entity.DiagnosisReport;
import com.faultlab.backend.diagnosis.mapper.DiagnosisReportMapper;
import com.faultlab.backend.experiment.entity.FaultExperiment;
import com.faultlab.backend.experiment.mapper.FaultExperimentMapper;
import com.faultlab.backend.metric.entity.FaultMetric;
import com.faultlab.backend.metric.mapper.FaultMetricMapper;
import com.faultlab.backend.rule.dto.RuleDiagnosisResult;
import com.faultlab.backend.rule.service.RuleDiagnosisService;
import com.faultlab.backend.scenario.model.ExperimentStatus;
import com.faultlab.backend.scenario.model.ScenarioCode;
import com.faultlab.backend.trace.dto.TraceTreeResponse;
import com.faultlab.backend.trace.service.TraceQueryService;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EvidencePackageBuilderTests {

    private final FaultExperimentMapper faultExperimentMapper = mock(FaultExperimentMapper.class);
    private final FaultMetricMapper faultMetricMapper = mock(FaultMetricMapper.class);
    private final DiagnosisReportMapper diagnosisReportMapper = mock(DiagnosisReportMapper.class);
    private final TraceQueryService traceQueryService = mock(TraceQueryService.class);
    private final RuleDiagnosisService ruleDiagnosisService = mock(RuleDiagnosisService.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final EvidencePackageBuilder builder = new EvidencePackageBuilder(
            faultExperimentMapper,
            faultMetricMapper,
            diagnosisReportMapper,
            traceQueryService,
            ruleDiagnosisService,
            objectMapper
    );

    @Test
    void shouldBuildEvidencePackageWithExperimentMetricsTraceAndRuleResult() throws JsonProcessingException {
        RuleDiagnosisResult ruleResult = ruleResult();
        DiagnosisReport report = new DiagnosisReport();
        report.setRuleResultJson(objectMapper.writeValueAsString(ruleResult));
        when(faultExperimentMapper.selectOne(any(Wrapper.class))).thenReturn(experiment());
        when(faultMetricMapper.selectList(any(Wrapper.class))).thenReturn(List.of(metric("publishCount", 10)));
        when(traceQueryService.getTraceTree("trace-1")).thenReturn(new TraceTreeResponse("trace-1", List.of()));
        when(diagnosisReportMapper.selectOne(any(Wrapper.class))).thenReturn(report);

        AiDiagnosisRequest request = builder.build("exp-1");

        assertThat(request.experiment().experimentId()).isEqualTo("exp-1");
        assertThat(request.experiment().scenarioCode()).isEqualTo(ScenarioCode.MQ_BACKLOG);
        assertThat(request.metrics()).hasSize(1);
        assertThat(request.metrics().get(0).metricName()).isEqualTo("publishCount");
        assertThat(request.metrics().get(0).metricValue()).isEqualTo("10");
        assertThat(request.traceTree().getTraceId()).isEqualTo("trace-1");
        assertThat(request.ruleResult().getFaultType()).isEqualTo(ScenarioCode.MQ_BACKLOG);
    }

    @Test
    void shouldGenerateRuleDiagnosisWhenRuleResultMissing() {
        when(faultExperimentMapper.selectOne(any(Wrapper.class))).thenReturn(experiment());
        when(faultMetricMapper.selectList(any(Wrapper.class))).thenReturn(List.of());
        when(traceQueryService.getTraceTree("trace-1")).thenReturn(new TraceTreeResponse("trace-1", List.of()));
        when(diagnosisReportMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        when(ruleDiagnosisService.diagnose("exp-1")).thenReturn(ruleResult());

        AiDiagnosisRequest request = builder.build("exp-1");

        assertThat(request.ruleResult().getMatched()).isTrue();
        verify(ruleDiagnosisService).diagnose("exp-1");
    }

    @Test
    void shouldBuildEvidencePackageForCacheFaultType() throws JsonProcessingException {
        RuleDiagnosisResult ruleResult = ruleResult(ScenarioCode.CACHE_PENETRATION);
        DiagnosisReport report = new DiagnosisReport();
        report.setRuleResultJson(objectMapper.writeValueAsString(ruleResult));
        when(faultExperimentMapper.selectOne(any(Wrapper.class))).thenReturn(experiment(ScenarioCode.CACHE_PENETRATION));
        when(faultMetricMapper.selectList(any(Wrapper.class))).thenReturn(List.of(metric("cache.db.query.count", 80)));
        when(traceQueryService.getTraceTree("trace-1")).thenReturn(new TraceTreeResponse("trace-1", List.of()));
        when(diagnosisReportMapper.selectOne(any(Wrapper.class))).thenReturn(report);

        AiDiagnosisRequest request = builder.build("exp-1");

        assertThat(request.experiment().scenarioCode()).isEqualTo(ScenarioCode.CACHE_PENETRATION);
        assertThat(request.ruleResult().getFaultType()).isEqualTo(ScenarioCode.CACHE_PENETRATION);
        assertThat(request.metrics().get(0).metricName()).isEqualTo("cache.db.query.count");
    }

    private FaultExperiment experiment() {
        return experiment(ScenarioCode.MQ_BACKLOG);
    }

    private FaultExperiment experiment(String scenarioCode) {
        LocalDateTime now = LocalDateTime.now();
        FaultExperiment experiment = new FaultExperiment();
        experiment.setExperimentId("exp-1");
        experiment.setScenarioCode(scenarioCode);
        experiment.setScenarioName("MQ 消息堆积");
        experiment.setStatus(ExperimentStatus.RUNNING);
        experiment.setTraceId("trace-1");
        experiment.setStartTime(now.minusSeconds(10));
        experiment.setEndTime(now);
        return experiment;
    }

    private FaultMetric metric(String name, long value) {
        FaultMetric metric = new FaultMetric();
        metric.setExperimentId("exp-1");
        metric.setMetricName(name);
        metric.setMetricValue(BigDecimal.valueOf(value));
        metric.setMetricUnit("count");
        metric.setComponent("RabbitMQ");
        metric.setCreatedAt(LocalDateTime.now());
        return metric;
    }

    private RuleDiagnosisResult ruleResult() {
        return ruleResult(ScenarioCode.MQ_BACKLOG);
    }

    private RuleDiagnosisResult ruleResult(String faultType) {
        RuleDiagnosisResult result = new RuleDiagnosisResult();
        result.setExperimentId("exp-1");
        result.setFaultType(faultType);
        result.setFaultName("MQ 消息堆积");
        result.setConfidence(0.85);
        result.setMatched(true);
        result.setReason("生产消息数大于消费消息数。");
        result.setEvidence(List.of("publishCount=10"));
        result.setSuggestions(List.of("增加消费者并发"));
        return result;
    }
}
