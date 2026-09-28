package com.faultlab.backend.experiment.remediation;

import com.faultlab.backend.experiment.dto.ExpectedMetricDirection;
import com.faultlab.backend.experiment.dto.ExpectedMetricEffect;
import com.faultlab.backend.experiment.dto.MetricComparison;
import com.faultlab.backend.experiment.dto.MetricComparisonStatus;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class RemediationMetricComparator {

    static final BigDecimal STABLE_TOLERANCE = new BigDecimal("0.000001");

    public MetricComparison compare(
            ExpectedMetricEffect expectedEffect,
            Map<String, BigDecimal> beforeMetrics,
            Map<String, BigDecimal> afterMetrics
    ) {
        BigDecimal beforeValue = beforeMetrics.get(expectedEffect.getMetricName());
        BigDecimal afterValue = afterMetrics.get(expectedEffect.getMetricName());

        MetricComparison comparison = new MetricComparison();
        comparison.setMetricName(expectedEffect.getMetricName());
        comparison.setExpectedDirection(expectedEffect.getDirection());
        comparison.setDescription(expectedEffect.getDescription());
        comparison.setBeforeValue(beforeValue);
        comparison.setAfterValue(afterValue);

        if (beforeValue == null || afterValue == null) {
            comparison.setStatus(MetricComparisonStatus.INCONCLUSIVE);
            comparison.setMatchedExpectation(null);
            comparison.setNote("required metric is missing");
            return comparison;
        }

        BigDecimal absoluteChange = afterValue.subtract(beforeValue);
        comparison.setAbsoluteChange(absoluteChange);
        comparison.setChangePercent(changePercent(beforeValue, absoluteChange, comparison));

        boolean matched = matches(expectedEffect.getDirection(), beforeValue, afterValue, absoluteChange);
        comparison.setMatchedExpectation(matched);
        comparison.setStatus(matched ? MetricComparisonStatus.MATCHED : MetricComparisonStatus.UNMATCHED);
        return comparison;
    }

    private BigDecimal changePercent(
            BigDecimal beforeValue,
            BigDecimal absoluteChange,
            MetricComparison comparison
    ) {
        if (beforeValue.compareTo(BigDecimal.ZERO) == 0) {
            comparison.setNote("baseline is zero");
            return null;
        }
        return absoluteChange
                .multiply(BigDecimal.valueOf(100))
                .divide(beforeValue.abs(), 4, RoundingMode.HALF_UP)
                .stripTrailingZeros();
    }

    private boolean matches(
            ExpectedMetricDirection direction,
            BigDecimal beforeValue,
            BigDecimal afterValue,
            BigDecimal absoluteChange
    ) {
        return switch (direction) {
            case INCREASE -> afterValue.compareTo(beforeValue) > 0;
            case DECREASE -> afterValue.compareTo(beforeValue) < 0;
            case STABLE -> absoluteChange.abs().compareTo(STABLE_TOLERANCE) <= 0;
        };
    }
}
