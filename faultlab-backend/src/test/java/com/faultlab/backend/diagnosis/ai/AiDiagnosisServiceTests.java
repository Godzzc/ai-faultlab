package com.faultlab.backend.diagnosis.ai;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.faultlab.backend.common.BusinessException;
import com.faultlab.backend.common.ErrorCode;
import com.faultlab.backend.diagnosis.ai.dto.AiDiagnosisRequest;
import com.faultlab.backend.diagnosis.ai.dto.AiDiagnosisResponse;
import com.faultlab.backend.diagnosis.ai.dto.ExperimentInfo;
import com.faultlab.backend.diagnosis.entity.DiagnosisReport;
import com.faultlab.backend.diagnosis.mapper.DiagnosisReportMapper;
import com.faultlab.backend.rule.dto.RuleDiagnosisResult;
import com.faultlab.backend.scenario.model.ExperimentStatus;
import com.faultlab.backend.scenario.model.ScenarioCode;
import com.faultlab.backend.trace.dto.TraceTreeResponse;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiDiagnosisServiceTests {

    private final EvidencePackageBuilder evidencePackageBuilder = mock(EvidencePackageBuilder.class);
    private final AiDiagnosisClient aiDiagnosisClient = mock(AiDiagnosisClient.class);
    private final DiagnosisReportMapper diagnosisReportMapper = mock(DiagnosisReportMapper.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AiDiagnosisService service = new AiDiagnosisService(
            evidencePackageBuilder,
            aiDiagnosisClient,
            diagnosisReportMapper,
            objectMapper
    );

    @Test
    void shouldGenerateAiDiagnosisWhenAiServiceAvailable() {
        AiDiagnosisRequest request = evidencePackage();
        AiDiagnosisResponse aiResponse = aiResponse(false);
        when(evidencePackageBuilder.build("exp-1")).thenReturn(request);
        when(aiDiagnosisClient.generate(request)).thenReturn(aiResponse);
        when(diagnosisReportMapper.selectOne(any(Wrapper.class))).thenReturn(existingReport());

        AiDiagnosisResponse response = service.generateAiDiagnosis("exp-1");

        assertThat(response.fallback()).isFalse();
        assertThat(response.summary()).isEqualTo("本次实验检测到 MQ 消息堆积风险。");
        verify(diagnosisReportMapper).updateById(any(DiagnosisReport.class));
    }

    @Test
    void shouldSaveAiResultJsonWhenDiagnosisReportExists() {
        when(evidencePackageBuilder.build("exp-1")).thenReturn(evidencePackage());
        when(aiDiagnosisClient.generate(any())).thenReturn(aiResponse(false));
        when(diagnosisReportMapper.selectOne(any(Wrapper.class))).thenReturn(existingReport());

        service.generateAiDiagnosis("exp-1");

        ArgumentCaptor<DiagnosisReport> captor = ArgumentCaptor.forClass(DiagnosisReport.class);
        verify(diagnosisReportMapper).updateById(captor.capture());
        assertThat(captor.getValue().getId()).isEqualTo(1L);
        assertThat(captor.getValue().getAiReportJson()).contains("\"fallback\":false");
    }

    @Test
    void shouldCreateDiagnosisReportWhenNotExists() {
        when(evidencePackageBuilder.build("exp-1")).thenReturn(evidencePackage());
        when(aiDiagnosisClient.generate(any())).thenReturn(aiResponse(false));
        when(diagnosisReportMapper.selectOne(any(Wrapper.class))).thenReturn(null);

        service.generateAiDiagnosis("exp-1");

        ArgumentCaptor<DiagnosisReport> captor = ArgumentCaptor.forClass(DiagnosisReport.class);
        verify(diagnosisReportMapper).insert(captor.capture());
        assertThat(captor.getValue().getExperimentId()).isEqualTo("exp-1");
        assertThat(captor.getValue().getAiReportJson()).contains("\"faultType\":\"MQ_BACKLOG\"");
        assertThat(captor.getValue().getRuleResultJson()).contains("\"matched\":true");
    }

    @Test
    void shouldFallbackWhenAiServiceTimeout() {
        when(evidencePackageBuilder.build("exp-1")).thenReturn(evidencePackage());
        when(aiDiagnosisClient.generate(any())).thenThrow(new BusinessException(ErrorCode.AI_SERVICE_ERROR, "timeout"));
        when(diagnosisReportMapper.selectOne(any(Wrapper.class))).thenReturn(existingReport());

        AiDiagnosisResponse response = service.generateAiDiagnosis("exp-1");

        assertThat(response.fallback()).isTrue();
        assertThat(response.summary()).isEqualTo("AI 诊断服务暂时不可用，当前返回基于规则诊断的降级报告。");
        assertThat(response.evidence()).containsExactly("publishCount=10");
    }

    @Test
    void shouldFallbackWhenAiServiceUnavailable() {
        when(evidencePackageBuilder.build("exp-1")).thenReturn(evidencePackage());
        when(aiDiagnosisClient.generate(any())).thenThrow(new BusinessException(ErrorCode.AI_SERVICE_ERROR, "connection refused"));
        when(diagnosisReportMapper.selectOne(any(Wrapper.class))).thenReturn(existingReport());

        AiDiagnosisResponse response = service.generateAiDiagnosis("exp-1");

        assertThat(response.fallback()).isTrue();
        assertThat(response.faultType()).isEqualTo(ScenarioCode.MQ_BACKLOG);
        verify(diagnosisReportMapper).updateById(any(DiagnosisReport.class));
    }

    @Test
    void shouldNotOverwriteRuleResultJson() {
        DiagnosisReport existing = existingReport();
        existing.setRuleResultJson("{\"matched\":true,\"source\":\"existing\"}");
        when(evidencePackageBuilder.build("exp-1")).thenReturn(evidencePackage());
        when(aiDiagnosisClient.generate(any())).thenReturn(aiResponse(false));
        when(diagnosisReportMapper.selectOne(any(Wrapper.class))).thenReturn(existing);

        service.generateAiDiagnosis("exp-1");

        ArgumentCaptor<DiagnosisReport> captor = ArgumentCaptor.forClass(DiagnosisReport.class);
        verify(diagnosisReportMapper).updateById(captor.capture());
        assertThat(captor.getValue().getRuleResultJson()).isEqualTo("{\"matched\":true,\"source\":\"existing\"}");
    }

    @Test
    void shouldThrowWhenExperimentNotFound() {
        when(evidencePackageBuilder.build("missing")).thenThrow(new BusinessException(ErrorCode.NOT_FOUND, "experiment not found: missing"));

        assertThatThrownBy(() -> service.generateAiDiagnosis("missing"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("experiment not found");
        verify(aiDiagnosisClient, never()).generate(any());
    }

    private AiDiagnosisRequest evidencePackage() {
        return new AiDiagnosisRequest(
                new ExperimentInfo(
                        "exp-1",
                        ScenarioCode.MQ_BACKLOG,
                        "MQ 消息堆积",
                        ExperimentStatus.RUNNING,
                        "trace-1",
                        LocalDateTime.now().minusSeconds(10),
                        null
                ),
                List.of(),
                new TraceTreeResponse("trace-1", List.of()),
                ruleResult()
        );
    }

    private AiDiagnosisResponse aiResponse(boolean fallback) {
        return new AiDiagnosisResponse(
                "exp-1",
                ScenarioCode.MQ_BACKLOG,
                "MQ 消息堆积",
                0.85,
                "本次实验检测到 MQ 消息堆积风险。",
                List.of(),
                List.of("publishCount=10"),
                List.of(),
                List.of("增加消费者并发"),
                List.of(),
                fallback
        );
    }

    private RuleDiagnosisResult ruleResult() {
        RuleDiagnosisResult result = new RuleDiagnosisResult();
        result.setExperimentId("exp-1");
        result.setFaultType(ScenarioCode.MQ_BACKLOG);
        result.setFaultName("MQ 消息堆积");
        result.setConfidence(0.85);
        result.setMatched(true);
        result.setEvidence(List.of("publishCount=10"));
        result.setSuggestions(List.of("增加消费者并发"));
        return result;
    }

    private DiagnosisReport existingReport() {
        DiagnosisReport report = new DiagnosisReport();
        report.setId(1L);
        report.setExperimentId("exp-1");
        report.setFaultType(ScenarioCode.MQ_BACKLOG);
        report.setRuleResultJson("{\"matched\":true}");
        return report;
    }
}
