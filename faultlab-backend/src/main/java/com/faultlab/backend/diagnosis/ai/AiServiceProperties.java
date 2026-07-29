package com.faultlab.backend.diagnosis.ai;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "faultlab.ai-service")
public class AiServiceProperties {

    private String baseUrl = "http://localhost:8000";
    private String diagnosisPath = "/ai/diagnosis/generate";
    private int connectTimeoutMs = 3000;
    private int readTimeoutMs = 10000;

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getDiagnosisPath() {
        return diagnosisPath;
    }

    public void setDiagnosisPath(String diagnosisPath) {
        this.diagnosisPath = diagnosisPath;
    }

    public int getConnectTimeoutMs() {
        return connectTimeoutMs;
    }

    public void setConnectTimeoutMs(int connectTimeoutMs) {
        this.connectTimeoutMs = connectTimeoutMs;
    }

    public int getReadTimeoutMs() {
        return readTimeoutMs;
    }

    public void setReadTimeoutMs(int readTimeoutMs) {
        this.readTimeoutMs = readTimeoutMs;
    }
}
