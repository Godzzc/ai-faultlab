package com.faultlab.backend.metric.service;

import com.faultlab.backend.metric.entity.FaultMetric;
import com.faultlab.backend.metric.mapper.FaultMetricMapper;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.springframework.stereotype.Service;

@Service
public class MetricService {

    private final FaultMetricMapper faultMetricMapper;

    public MetricService(FaultMetricMapper faultMetricMapper) {
        this.faultMetricMapper = faultMetricMapper;
    }

    public void recordMetric(String experimentId, String metricName, Number metricValue, String metricUnit, String component) {
        FaultMetric metric = new FaultMetric();
        metric.setExperimentId(experimentId);
        metric.setMetricName(metricName);
        metric.setMetricValue(toBigDecimal(metricValue));
        metric.setMetricUnit(metricUnit);
        metric.setComponent(component);
        metric.setCreatedAt(LocalDateTime.now());
        faultMetricMapper.insert(metric);
    }

    private BigDecimal toBigDecimal(Number metricValue) {
        if (metricValue == null) {
            return BigDecimal.ZERO;
        }
        return BigDecimal.valueOf(metricValue.doubleValue());
    }
}
