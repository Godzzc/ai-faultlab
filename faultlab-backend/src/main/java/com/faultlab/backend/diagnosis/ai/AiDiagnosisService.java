package com.faultlab.backend.diagnosis.ai;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.faultlab.backend.common.BusinessException;
import com.faultlab.backend.common.ErrorCode;
import com.faultlab.backend.diagnosis.ai.dto.AiDiagnosisRequest;
import com.faultlab.backend.diagnosis.ai.dto.AiDiagnosisResponse;
import com.faultlab.backend.diagnosis.entity.DiagnosisReport;
import com.faultlab.backend.diagnosis.mapper.DiagnosisReportMapper;
import com.faultlab.backend.rule.dto.RuleDiagnosisResult;
import com.faultlab.backend.trace.annotation.TraceSpan;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class AiDiagnosisService {

    private static final Logger log = LoggerFactory.getLogger(AiDiagnosisService.class);
    private static final String FALLBACK_SUMMARY = "AI 诊断服务暂时不可用，当前返回基于规则诊断的降级报告。";

    private final EvidencePackageBuilder evidencePackageBuilder;
    private final AiDiagnosisClient aiDiagnosisClient;
    private final DiagnosisReportMapper diagnosisReportMapper;
    private final ObjectMapper objectMapper;

    public AiDiagnosisService(
            EvidencePackageBuilder evidencePackageBuilder,
            AiDiagnosisClient aiDiagnosisClient,
            DiagnosisReportMapper diagnosisReportMapper,
            ObjectMapper objectMapper
    ) {
        this.evidencePackageBuilder = evidencePackageBuilder;
        this.aiDiagnosisClient = aiDiagnosisClient;
        this.diagnosisReportMapper = diagnosisReportMapper;
        this.objectMapper = objectMapper;
    }

    @TraceSpan(operationName = "ai.generate.report", component = "AI")
    public AiDiagnosisResponse generateAiDiagnosis(String experimentId) {
        AiDiagnosisRequest evidencePackage = evidencePackageBuilder.build(experimentId);
        AiDiagnosisResponse response;
        try {
            response = aiDiagnosisClient.generate(evidencePackage);
        } catch (RuntimeException exception) {
            log.warn("AI diagnosis generation failed for experiment {}: {}", experimentId, exception.getMessage());
            response = buildFallbackReport(evidencePackage);
        }
        saveAiDiagnosisResult(evidencePackage, response);
        return response;
    }

    @TraceSpan(operationName = "ai.fallback.build", component = "AI")
    public AiDiagnosisResponse buildFallbackReport(AiDiagnosisRequest request) {
        RuleDiagnosisResult ruleResult = request.ruleResult();
        String scenarioCode = request.experiment() == null ? null : request.experiment().scenarioCode();
        String scenarioName = request.experiment() == null ? null : request.experiment().scenarioName();

        if (ruleResult != null) {
            return new AiDiagnosisResponse(
                    request.experiment().experimentId(),
                    firstText(ruleResult.getFaultType(), scenarioCode, "UNKNOWN"),
                    firstText(ruleResult.getFaultName(), scenarioName, ""),
                    ruleResult.getConfidence() == null ? 0.2 : ruleResult.getConfidence(),
                    FALLBACK_SUMMARY,
                    List.of(),
                    ruleResult.getEvidence() == null ? List.of() : ruleResult.getEvidence(),
                    List.of(),
                    ruleResult.getSuggestions() == null ? List.of() : ruleResult.getSuggestions(),
                    List.of(),
                    true
            );
        }

        return new AiDiagnosisResponse(
                request.experiment().experimentId(),
                firstText(scenarioCode, "UNKNOWN"),
                firstText(scenarioName, ""),
                0.2,
                FALLBACK_SUMMARY,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                true
        );
    }

    private void saveAiDiagnosisResult(AiDiagnosisRequest request, AiDiagnosisResponse response) {
        String aiReportJson = serialize(response);
        DiagnosisReport report = diagnosisReportMapper.selectOne(new LambdaQueryWrapper<DiagnosisReport>()
                .eq(DiagnosisReport::getExperimentId, response.experimentId())
                .last("LIMIT 1"));

        LocalDateTime now = LocalDateTime.now();
        if (report == null) {
            report = new DiagnosisReport();
            report.setExperimentId(response.experimentId());
            report.setCreatedAt(now);
            RuleDiagnosisResult ruleResult = request.ruleResult();
            if (ruleResult != null) {
                report.setRuleResultJson(serialize(ruleResult));
            }
            diagnosisReportMapper.insert(fillAiReport(report, response, aiReportJson, now));
            return;
        }

        diagnosisReportMapper.updateById(fillAiReport(report, response, aiReportJson, now));
    }

    private DiagnosisReport fillAiReport(DiagnosisReport report, AiDiagnosisResponse response, String aiReportJson, LocalDateTime now) {
        report.setFaultType(firstText(response.faultType(), "UNKNOWN"));
        report.setFaultName(firstText(response.faultName(), ""));
        report.setConfidence(BigDecimal.valueOf(response.confidence() == null ? 0.2 : response.confidence()));
        report.setAiReportJson(aiReportJson);
        report.setUpdatedAt(now);
        return report;
    }

    private String serialize(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "failed to serialize AI diagnosis result");
        }
    }

    private String firstText(String... values) {
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value;
            }
        }
        return "";
    }
}
