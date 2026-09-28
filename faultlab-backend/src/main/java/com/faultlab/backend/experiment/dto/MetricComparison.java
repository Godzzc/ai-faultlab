package com.faultlab.backend.experiment.dto;

import java.math.BigDecimal;

public class MetricComparison {

    private String metricName;
    private BigDecimal beforeValue;
    private BigDecimal afterValue;
    private BigDecimal absoluteChange;
    private BigDecimal changePercent;
    private ExpectedMetricDirection expectedDirection;
    private Boolean matchedExpectation;
    private MetricComparisonStatus status;
    private String description;
    private String note;

    public String getMetricName() {
        return metricName;
    }

    public void setMetricName(String metricName) {
        this.metricName = metricName;
    }

    public BigDecimal getBeforeValue() {
        return beforeValue;
    }

    public void setBeforeValue(BigDecimal beforeValue) {
        this.beforeValue = beforeValue;
    }

    public BigDecimal getAfterValue() {
        return afterValue;
    }

    public void setAfterValue(BigDecimal afterValue) {
        this.afterValue = afterValue;
    }

    public BigDecimal getAbsoluteChange() {
        return absoluteChange;
    }

    public void setAbsoluteChange(BigDecimal absoluteChange) {
        this.absoluteChange = absoluteChange;
    }

    public BigDecimal getChangePercent() {
        return changePercent;
    }

    public void setChangePercent(BigDecimal changePercent) {
        this.changePercent = changePercent;
    }

    public ExpectedMetricDirection getExpectedDirection() {
        return expectedDirection;
    }

    public void setExpectedDirection(ExpectedMetricDirection expectedDirection) {
        this.expectedDirection = expectedDirection;
    }

    public Boolean getMatchedExpectation() {
        return matchedExpectation;
    }

    public void setMatchedExpectation(Boolean matchedExpectation) {
        this.matchedExpectation = matchedExpectation;
    }

    public MetricComparisonStatus getStatus() {
        return status;
    }

    public void setStatus(MetricComparisonStatus status) {
        this.status = status;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }
}
