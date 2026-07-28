package com.faultlab.backend.rule;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.faultlab.backend.common.BusinessException;
import com.faultlab.backend.diagnosis.entity.DiagnosisReport;
import com.faultlab.backend.diagnosis.mapper.DiagnosisReportMapper;
import com.faultlab.backend.experiment.entity.FaultExperiment;
import com.faultlab.backend.experiment.mapper.FaultExperimentMapper;
import com.faultlab.backend.metric.entity.FaultMetric;
import com.faultlab.backend.metric.mapper.FaultMetricMapper;
import com.faultlab.backend.rule.diagnoser.MqBacklogRuleDiagnoser;
import com.faultlab.backend.rule.diagnoser.RuleDiagnoser;
import com.faultlab.backend.rule.diagnoser.ThreadPoolSaturationRuleDiagnoser;
import com.faultlab.backend.rule.dto.RuleDiagnosisResult;
import com.faultlab.backend.rule.service.RuleDiagnosisService;
import com.faultlab.backend.scenario.model.ScenarioCode;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RuleDiagnosisServiceTests {

    private final FaultExperimentMapper faultExperimentMapper = mock(FaultExperimentMapper.class);
    private final FaultMetricMapper faultMetricMapper = mock(FaultMetricMapper.class);
    private final DiagnosisReportMapper diagnosisReportMapper = mock(DiagnosisReportMapper.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void shouldRejectUnsupportedScenarioCode() {
        RuleDiagnosisService service = service(List.of(new MqBacklogRuleDiagnoser()), objectMapper);
        when(faultExperimentMapper.selectOne(any(Wrapper.class))).thenReturn(experiment("UNKNOWN"));

        assertThatThrownBy(() -> service.diagnose("exp-1"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("unsupported scenarioCode");
    }

    @Test
    void shouldCreateDiagnosisReportWhenNotExists() {
        RuleDiagnosisService service = service(List.of(new MqBacklogRuleDiagnoser()), objectMapper);
        when(faultExperimentMapper.selectOne(any(Wrapper.class))).thenReturn(experiment(ScenarioCode.MQ_BACKLOG));
        when(faultMetricMapper.selectList(any(Wrapper.class))).thenReturn(metrics());
        when(diagnosisReportMapper.selectOne(any(Wrapper.class))).thenReturn(null);

        RuleDiagnosisResult result = service.diagnose("exp-1");

        ArgumentCaptor<DiagnosisReport> captor = ArgumentCaptor.forClass(DiagnosisReport.class);
        verify(diagnosisReportMapper).insert(captor.capture());
        DiagnosisReport report = captor.getValue();
        assertThat(result.getMatched()).isTrue();
        assertThat(report.getExperimentId()).isEqualTo("exp-1");
        assertThat(report.getFaultType()).isEqualTo(ScenarioCode.MQ_BACKLOG);
        assertThat(report.getRuleResultJson()).contains("\"experimentId\":\"exp-1\"");
    }

    @Test
    void shouldDiagnoseThreadPoolSaturationScenario() {
        RuleDiagnosisService service = service(List.of(new MqBacklogRuleDiagnoser(), new ThreadPoolSaturationRuleDiagnoser()), objectMapper);
        when(faultExperimentMapper.selectOne(any(Wrapper.class))).thenReturn(experiment(ScenarioCode.THREAD_POOL_SATURATION));
        when(faultMetricMapper.selectList(any(Wrapper.class))).thenReturn(threadPoolMetrics());
        when(diagnosisReportMapper.selectOne(any(Wrapper.class))).thenReturn(null);

        RuleDiagnosisResult result = service.diagnose("exp-1");

        ArgumentCaptor<DiagnosisReport> captor = ArgumentCaptor.forClass(DiagnosisReport.class);
        verify(diagnosisReportMapper).insert(captor.capture());
        DiagnosisReport report = captor.getValue();
        assertThat(result.getMatched()).isTrue();
        assertThat(result.getFaultType()).isEqualTo(ScenarioCode.THREAD_POOL_SATURATION);
        assertThat(result.getConfidence()).isEqualTo(0.90);
        assertThat(report.getFaultType()).isEqualTo(ScenarioCode.THREAD_POOL_SATURATION);
        assertThat(report.getRuleResultJson()).contains("\"faultType\":\"THREAD_POOL_SATURATION\"");
    }

    @Test
    void shouldUpdateDiagnosisReportWhenExists() {
        RuleDiagnosisService service = service(List.of(new MqBacklogRuleDiagnoser()), objectMapper);
        DiagnosisReport existingReport = new DiagnosisReport();
        existingReport.setId(1L);
        existingReport.setExperimentId("exp-1");
        when(faultExperimentMapper.selectOne(any(Wrapper.class))).thenReturn(experiment(ScenarioCode.MQ_BACKLOG));
        when(faultMetricMapper.selectList(any(Wrapper.class))).thenReturn(metrics());
        when(diagnosisReportMapper.selectOne(any(Wrapper.class))).thenReturn(existingReport);

        service.diagnose("exp-1");

        ArgumentCaptor<DiagnosisReport> captor = ArgumentCaptor.forClass(DiagnosisReport.class);
        verify(diagnosisReportMapper).updateById(captor.capture());
        assertThat(captor.getValue().getId()).isEqualTo(1L);
        assertThat(captor.getValue().getRuleResultJson()).contains("\"faultType\":\"MQ_BACKLOG\"");
    }

    @Test
    void shouldThrowBusinessExceptionWhenJsonSerializationFailed() throws JsonProcessingException {
        ObjectMapper failingObjectMapper = mock(ObjectMapper.class);
        RuleDiagnosisService service = service(List.of(new MqBacklogRuleDiagnoser()), failingObjectMapper);
        when(faultExperimentMapper.selectOne(any(Wrapper.class))).thenReturn(experiment(ScenarioCode.MQ_BACKLOG));
        when(faultMetricMapper.selectList(any(Wrapper.class))).thenReturn(metrics());
        when(failingObjectMapper.writeValueAsString(any())).thenThrow(new JsonProcessingException("boom") {
        });

        assertThatThrownBy(() -> service.diagnose("exp-1"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("failed to serialize rule diagnosis result");
    }

    private RuleDiagnosisService service(List<RuleDiagnoser> diagnosers, ObjectMapper objectMapper) {
        return new RuleDiagnosisService(
                faultExperimentMapper,
                faultMetricMapper,
                diagnosisReportMapper,
                objectMapper,
                diagnosers
        );
    }

    private FaultExperiment experiment(String scenarioCode) {
        FaultExperiment experiment = new FaultExperiment();
        experiment.setExperimentId("exp-1");
        experiment.setScenarioCode(scenarioCode);
        return experiment;
    }

    private List<FaultMetric> metrics() {
        return List.of(
                metric("publishCount", 100),
                metric("consumeCount", 20),
                metric("consumerDelayMs", 1000),
                metric("avgConsumeMs", 1000)
        );
    }

    private List<FaultMetric> threadPoolMetrics() {
        return List.of(
                metric("taskCount", 30),
                metric("acceptedTaskCount", 12),
                metric("rejectedTaskCount", 18),
                metric("activeThreadCount", 2),
                metric("queueSize", 10),
                metric("avgTaskDurationMs", 3000)
        );
    }

    private FaultMetric metric(String metricName, long metricValue) {
        FaultMetric metric = new FaultMetric();
        metric.setExperimentId("exp-1");
        metric.setMetricName(metricName);
        metric.setMetricValue(BigDecimal.valueOf(metricValue));
        return metric;
    }
}
