package com.faultlab.backend.scenario.mq;

import java.time.LocalDateTime;

public class MqBacklogMessage {

    private String experimentId;
    private int sequence;
    private long consumerDelayMs;
    private LocalDateTime createdAt;

    public MqBacklogMessage() {
    }

    public MqBacklogMessage(String experimentId, int sequence, long consumerDelayMs, LocalDateTime createdAt) {
        this.experimentId = experimentId;
        this.sequence = sequence;
        this.consumerDelayMs = consumerDelayMs;
        this.createdAt = createdAt;
    }

    public String getExperimentId() {
        return experimentId;
    }

    public void setExperimentId(String experimentId) {
        this.experimentId = experimentId;
    }

    public int getSequence() {
        return sequence;
    }

    public void setSequence(int sequence) {
        this.sequence = sequence;
    }

    public long getConsumerDelayMs() {
        return consumerDelayMs;
    }

    public void setConsumerDelayMs(long consumerDelayMs) {
        this.consumerDelayMs = consumerDelayMs;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
