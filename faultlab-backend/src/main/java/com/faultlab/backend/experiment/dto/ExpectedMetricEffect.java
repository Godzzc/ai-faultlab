package com.faultlab.backend.experiment.dto;

public class ExpectedMetricEffect {

    private String metricName;
    private ExpectedMetricDirection direction;
    private String description;
    private boolean required = true;

    public ExpectedMetricEffect() {
    }

    public ExpectedMetricEffect(
            String metricName,
            ExpectedMetricDirection direction,
            String description,
            boolean required
    ) {
        this.metricName = metricName;
        this.direction = direction;
        this.description = description;
        this.required = required;
    }

    public String getMetricName() {
        return metricName;
    }

    public void setMetricName(String metricName) {
        this.metricName = metricName;
    }

    public ExpectedMetricDirection getDirection() {
        return direction;
    }

    public void setDirection(ExpectedMetricDirection direction) {
        this.direction = direction;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public boolean isRequired() {
        return required;
    }

    public void setRequired(boolean required) {
        this.required = required;
    }
}
