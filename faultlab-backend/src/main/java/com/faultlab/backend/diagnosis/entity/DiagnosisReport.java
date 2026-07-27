package com.faultlab.backend.diagnosis.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@TableName("diagnosis_report")
public class DiagnosisReport {

    @TableId("id")
    private Long id;

    @TableField("experiment_id")
    private String experimentId;

    @TableField("fault_type")
    private String faultType;

    @TableField("fault_name")
    private String faultName;

    @TableField("confidence")
    private BigDecimal confidence;

    @TableField("rule_result_json")
    private String ruleResultJson;

    @TableField("rag_docs_json")
    private String ragDocsJson;

    @TableField("ai_report_json")
    private String aiReportJson;

    @TableField("fallback_report")
    private String fallbackReport;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("updated_at")
    private LocalDateTime updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getExperimentId() {
        return experimentId;
    }

    public void setExperimentId(String experimentId) {
        this.experimentId = experimentId;
    }

    public String getFaultType() {
        return faultType;
    }

    public void setFaultType(String faultType) {
        this.faultType = faultType;
    }

    public String getFaultName() {
        return faultName;
    }

    public void setFaultName(String faultName) {
        this.faultName = faultName;
    }

    public BigDecimal getConfidence() {
        return confidence;
    }

    public void setConfidence(BigDecimal confidence) {
        this.confidence = confidence;
    }

    public String getRuleResultJson() {
        return ruleResultJson;
    }

    public void setRuleResultJson(String ruleResultJson) {
        this.ruleResultJson = ruleResultJson;
    }

    public String getRagDocsJson() {
        return ragDocsJson;
    }

    public void setRagDocsJson(String ragDocsJson) {
        this.ragDocsJson = ragDocsJson;
    }

    public String getAiReportJson() {
        return aiReportJson;
    }

    public void setAiReportJson(String aiReportJson) {
        this.aiReportJson = aiReportJson;
    }

    public String getFallbackReport() {
        return fallbackReport;
    }

    public void setFallbackReport(String fallbackReport) {
        this.fallbackReport = fallbackReport;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
