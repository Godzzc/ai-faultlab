package com.faultlab.backend.experiment.remediation;

import com.faultlab.backend.experiment.dto.ExpectedMetricDirection;
import com.faultlab.backend.experiment.dto.ExpectedMetricEffect;
import com.faultlab.backend.experiment.dto.MetricComparison;
import com.faultlab.backend.experiment.dto.MetricComparisonStatus;
import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RemediationMetricComparatorTests {

    private final RemediationMetricComparator comparator = new RemediationMetricComparator();

    @Test
    void decreaseShouldMatchWhenAfterIsLower() {
        MetricComparison comparison = compare("metric", ExpectedMetricDirection.DECREASE, "100", "50");

        assertThat(comparison.getMatchedExpectation()).isTrue();
        assertThat(comparison.getStatus()).isEqualTo(MetricComparisonStatus.MATCHED);
        assertThat(comparison.getAbsoluteChange()).isEqualByComparingTo("-50");
        assertThat(comparison.getChangePercent()).isEqualByComparingTo("-50");
    }

    @Test
    void decreaseShouldFailWhenAfterIsHigher() {
        MetricComparison comparison = compare("metric", ExpectedMetricDirection.DECREASE, "100", "120");

        assertThat(comparison.getMatchedExpectation()).isFalse();
        assertThat(comparison.getStatus()).isEqualTo(MetricComparisonStatus.UNMATCHED);
    }

    @Test
    void increaseShouldMatchWithZeroBaselineWithoutInfinity() {
        MetricComparison comparison = compare("metric", ExpectedMetricDirection.INCREASE, "0", "10");

        assertThat(comparison.getMatchedExpectation()).isTrue();
        assertThat(comparison.getStatus()).isEqualTo(MetricComparisonStatus.MATCHED);
        assertThat(comparison.getChangePercent()).isNull();
        assertThat(comparison.getNote()).isEqualTo("baseline is zero");
    }

    @Test
    void stableShouldMatchWithinTolerance() {
        MetricComparison comparison = compare("metric", ExpectedMetricDirection.STABLE, "1.0", "1.0000001");

        assertThat(comparison.getMatchedExpectation()).isTrue();
        assertThat(comparison.getStatus()).isEqualTo(MetricComparisonStatus.MATCHED);
    }

    @Test
    void stableShouldFailOutsideTolerance() {
        MetricComparison comparison = compare("metric", ExpectedMetricDirection.STABLE, "1.0", "1.1");

        assertThat(comparison.getMatchedExpectation()).isFalse();
        assertThat(comparison.getStatus()).isEqualTo(MetricComparisonStatus.UNMATCHED);
    }

    @Test
    void missingMetricShouldBeInconclusive() {
        ExpectedMetricEffect effect = new ExpectedMetricEffect(
                "missing.metric",
                ExpectedMetricDirection.DECREASE,
                "missing",
                true
        );

        MetricComparison comparison = comparator.compare(
                effect,
                Map.of("other", BigDecimal.ONE),
                Map.of("other", BigDecimal.ZERO)
        );

        assertThat(comparison.getStatus()).isEqualTo(MetricComparisonStatus.INCONCLUSIVE);
        assertThat(comparison.getMatchedExpectation()).isNull();
    }

    private MetricComparison compare(
            String metricName,
            ExpectedMetricDirection direction,
            String before,
            String after
    ) {
        ExpectedMetricEffect effect = new ExpectedMetricEffect(metricName, direction, "", true);
        return comparator.compare(
                effect,
                Map.of(metricName, new BigDecimal(before)),
                Map.of(metricName, new BigDecimal(after))
        );
    }
}
