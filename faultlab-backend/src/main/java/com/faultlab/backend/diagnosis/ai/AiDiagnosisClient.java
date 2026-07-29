package com.faultlab.backend.diagnosis.ai;

import com.faultlab.backend.common.BusinessException;
import com.faultlab.backend.common.ErrorCode;
import com.faultlab.backend.diagnosis.ai.dto.AiDiagnosisRequest;
import com.faultlab.backend.diagnosis.ai.dto.AiDiagnosisResponse;
import com.faultlab.backend.trace.annotation.TraceSpan;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
public class AiDiagnosisClient {

    private static final Logger log = LoggerFactory.getLogger(AiDiagnosisClient.class);

    private final AiServiceProperties properties;
    private final RestClient restClient;

    public AiDiagnosisClient(AiServiceProperties properties) {
        this.properties = properties;
        this.restClient = RestClient.builder()
                .baseUrl(properties.getBaseUrl())
                .requestFactory(requestFactory(properties))
                .build();
    }

    @TraceSpan(operationName = "ai.client.call", component = "AI")
    public AiDiagnosisResponse generate(AiDiagnosisRequest request) {
        try {
            AiDiagnosisResponse response = restClient.post()
                    .uri(properties.getDiagnosisPath())
                    .body(request)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (httpRequest, httpResponse) -> {
                        log.warn("AI diagnosis service returned status {}", httpResponse.getStatusCode());
                        throw new BusinessException(
                                ErrorCode.AI_SERVICE_ERROR,
                                "AI diagnosis service returned status " + httpResponse.getStatusCode()
                        );
                    })
                    .body(AiDiagnosisResponse.class);
            if (response == null) {
                log.warn("AI diagnosis service returned empty body");
                throw new BusinessException(ErrorCode.AI_SERVICE_ERROR, "AI diagnosis service returned empty body");
            }
            return response;
        } catch (BusinessException exception) {
            throw exception;
        } catch (RestClientException exception) {
            log.warn("AI diagnosis service request failed: {}", exception.getMessage());
            throw new BusinessException(ErrorCode.AI_SERVICE_ERROR, "AI diagnosis service request failed");
        }
    }

    private SimpleClientHttpRequestFactory requestFactory(AiServiceProperties properties) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofMillis(properties.getConnectTimeoutMs()));
        requestFactory.setReadTimeout(Duration.ofMillis(properties.getReadTimeoutMs()));
        return requestFactory;
    }
}
