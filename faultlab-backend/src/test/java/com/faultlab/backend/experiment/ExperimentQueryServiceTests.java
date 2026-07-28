package com.faultlab.backend.experiment;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.faultlab.backend.common.BusinessException;
import com.faultlab.backend.diagnosis.dto.DiagnosisReportResponse;
import com.faultlab.backend.diagnosis.entity.DiagnosisReport;
import com.faultlab.backend.diagnosis.mapper.DiagnosisReportMapper;
import com.faultlab.backend.experiment.dto.ExperimentDetailResponse;
import com.faultlab.backend.experiment.entity.FaultExperiment;
import com.faultlab.backend.experiment.mapper.FaultExperimentMapper;
import com.faultlab.backend.experiment.service.ExperimentQueryService;
import com.faultlab.backend.metric.dto.MetricResponse;
import com.faultlab.backend.metric.entity.FaultMetric;
import com.faultlab.backend.metric.mapper.FaultMetricMapper;
import com.faultlab.backend.scenario.model.ExperimentStatus;
import com.faultlab.backend.scenario.model.ScenarioCode;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ExperimentQueryServiceTests {

    private final FaultExperimentMapper faultExperimentMapper = mock(FaultExperimentMapper.class);
    private final FaultMetricMapper faultMetricMapper = mock(FaultMetricMapper.class);
    private final DiagnosisReportMapper diagnosisReportMapper = mock(DiagnosisReportMapper.class);
    private final ExperimentQueryService queryService = new ExperimentQueryService(
            faultExperimentMapper,
            faultMetricMapper,
            diagnosisReportMapper
    );

    @Test
    void shouldGetExperimentDetail() {
        FaultExperiment experiment = experiment();
        when(faultExperimentMapper.selectOne(any(Wrapper.class))).thenReturn(experiment);

        ExperimentDetailResponse response = queryService.getExperimentDetail("exp-1");

        assertThat(response.getExperimentId()).isEqualTo("exp-1");
        assertThat(response.getScenarioCode()).isEqualTo(ScenarioCode.MQ_BACKLOG);
        assertThat(response.getStatus()).isEqualTo(ExperimentStatus.RUNNING);
        assertThat(response.getTraceId()).isEqualTo("trace-1");
        assertThat(response.getStartTime()).isEqualTo(experiment.getStartTime());
    }

    @Test
    void shouldThrowWhenExperimentNotFound() {
        when(faultExperimentMapper.selectOne(any(Wrapper.class))).thenReturn(null);

        assertThatThrownBy(() -> queryService.getExperimentDetail("exp-missing"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("experiment not found");
    }

    @Test
    void shouldReturnMetricsWhenExperimentExists() {
        when(faultExperimentMapper.selectOne(any(Wrapper.class))).thenReturn(experiment());
        when(faultMetricMapper.selectList(any(Wrapper.class))).thenReturn(List.of(metric("publishCount", 10)));

        List<MetricResponse> metrics = queryService.getExperimentMetrics("exp-1");

        assertThat(metrics).hasSize(1);
        assertThat(metrics.get(0).getExperimentId()).isEqualTo("exp-1");
        assertThat(metrics.get(0).getMetricName()).isEqualTo("publishCount");
        assertThat(metrics.get(0).getMetricValue()).isEqualTo("10");
        assertThat(metrics.get(0).getComponent()).isEqualTo("RabbitMQ");
    }

    @Test
    void shouldReturnEmptyMetricsWhenNoMetrics() {
        when(faultExperimentMapper.selectOne(any(Wrapper.class))).thenReturn(experiment());
        when(faultMetricMapper.selectList(any(Wrapper.class))).thenReturn(List.of());

        List<MetricResponse> metrics = queryService.getExperimentMetrics("exp-1");

        assertThat(metrics).isEmpty();
    }

    @Test
    void shouldReturnDiagnosisReportWhenExists() {
        when(faultExperimentMapper.selectOne(any(Wrapper.class))).thenReturn(experiment());
        when(diagnosisReportMapper.selectOne(any(Wrapper.class))).thenReturn(diagnosisReport());

        DiagnosisReportResponse response = queryService.getDiagnosisReport("exp-1");

        assertThat(response).isNotNull();
        assertThat(response.getExperimentId()).isEqualTo("exp-1");
        assertThat(response.getFaultType()).isEqualTo(ScenarioCode.MQ_BACKLOG);
        assertThat(response.getConfidence()).isEqualTo("0.85");
        assertThat(response.getRuleResultJson()).isEqualTo("{\"matched\":true}");
    }

    @Test
    void shouldReturnNullDiagnosisReportWhenNotExists() {
        when(faultExperimentMapper.selectOne(any(Wrapper.class))).thenReturn(experiment());
        when(diagnosisReportMapper.selectOne(any(Wrapper.class))).thenReturn(null);

        DiagnosisReportResponse response = queryService.getDiagnosisReport("exp-1");

        assertThat(response).isNull();
    }

    private FaultExperiment experiment() {
        LocalDateTime now = LocalDateTime.now();
        FaultExperiment experiment = new FaultExperiment();
        experiment.setExperimentId("exp-1");
        experiment.setScenarioCode(ScenarioCode.MQ_BACKLOG);
        experiment.setStatus(ExperimentStatus.RUNNING);
        experiment.setTraceId("trace-1");
        experiment.setStartTime(now.minusSeconds(10));
        experiment.setCreatedAt(now.minusSeconds(10));
        experiment.setUpdatedAt(now);
        return experiment;
    }

    private FaultMetric metric(String metricName, long metricValue) {
        FaultMetric metric = new FaultMetric();
        metric.setExperimentId("exp-1");
        metric.setMetricName(metricName);
        metric.setMetricValue(BigDecimal.valueOf(metricValue));
        metric.setMetricUnit("count");
        metric.setComponent("RabbitMQ");
        metric.setCreatedAt(LocalDateTime.now());
        return metric;
    }

    private DiagnosisReport diagnosisReport() {
        DiagnosisReport report = new DiagnosisReport();
        report.setExperimentId("exp-1");
        report.setFaultType(ScenarioCode.MQ_BACKLOG);
        report.setFaultName("MQ 消息堆积");
        report.setConfidence(BigDecimal.valueOf(0.85));
        report.setRuleResultJson("{\"matched\":true}");
        report.setAiReportJson(null);
        report.setCreatedAt(LocalDateTime.now());
        report.setUpdatedAt(LocalDateTime.now());
        return report;
    }
}
