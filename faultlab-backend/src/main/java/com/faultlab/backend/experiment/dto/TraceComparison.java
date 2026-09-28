package com.faultlab.backend.experiment.dto;

import java.util.LinkedHashMap;
import java.util.Map;

public class TraceComparison {

    private int beforeSpanCount;
    private int afterSpanCount;
    private int beforeErrorSpanCount;
    private int afterErrorSpanCount;
    private long beforeTotalDurationMs;
    private long afterTotalDurationMs;
    private Map<String, Long> beforeOperationCounts = new LinkedHashMap<>();
    private Map<String, Long> afterOperationCounts = new LinkedHashMap<>();

    public int getBeforeSpanCount() {
        return beforeSpanCount;
    }

    public void setBeforeSpanCount(int beforeSpanCount) {
        this.beforeSpanCount = beforeSpanCount;
    }

    public int getAfterSpanCount() {
        return afterSpanCount;
    }

    public void setAfterSpanCount(int afterSpanCount) {
        this.afterSpanCount = afterSpanCount;
    }

    public int getBeforeErrorSpanCount() {
        return beforeErrorSpanCount;
    }

    public void setBeforeErrorSpanCount(int beforeErrorSpanCount) {
        this.beforeErrorSpanCount = beforeErrorSpanCount;
    }

    public int getAfterErrorSpanCount() {
        return afterErrorSpanCount;
    }

    public void setAfterErrorSpanCount(int afterErrorSpanCount) {
        this.afterErrorSpanCount = afterErrorSpanCount;
    }

    public long getBeforeTotalDurationMs() {
        return beforeTotalDurationMs;
    }

    public void setBeforeTotalDurationMs(long beforeTotalDurationMs) {
        this.beforeTotalDurationMs = beforeTotalDurationMs;
    }

    public long getAfterTotalDurationMs() {
        return afterTotalDurationMs;
    }

    public void setAfterTotalDurationMs(long afterTotalDurationMs) {
        this.afterTotalDurationMs = afterTotalDurationMs;
    }

    public Map<String, Long> getBeforeOperationCounts() {
        return beforeOperationCounts;
    }

    public void setBeforeOperationCounts(Map<String, Long> beforeOperationCounts) {
        this.beforeOperationCounts = beforeOperationCounts;
    }

    public Map<String, Long> getAfterOperationCounts() {
        return afterOperationCounts;
    }

    public void setAfterOperationCounts(Map<String, Long> afterOperationCounts) {
        this.afterOperationCounts = afterOperationCounts;
    }
}
