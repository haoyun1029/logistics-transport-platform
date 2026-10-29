package cn.logistics.platform.domain;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FeedbackPlausibilityTest {
    @Test void impossibleTripAndInvalidNumbersAreExcluded() {
        assertNotNull(FeedbackPlausibility.exclusionReason(.25, 100.0, 25.0));
        assertNotNull(FeedbackPlausibility.exclusionReason(60.0, Double.NaN, 25.0));
        assertNotNull(FeedbackPlausibility.exclusionReason(60.0, 100.0, Double.POSITIVE_INFINITY));
        assertNotNull(FeedbackPlausibility.exclusionReason(0.0, 100.0, 25.0));
        assertNotNull(FeedbackPlausibility.exclusionReason(60.0, 1.0, 10.0));
    }
    @Test void includesBoundaryAndDoesNotTreatSpeedLimitAsQualityCeiling() {
        assertNull(FeedbackPlausibility.exclusionReason(60.0, 150.0, 25.0));
        assertNotNull(FeedbackPlausibility.exclusionReason(60.0, 150.01, 25.0));
        assertNull(FeedbackPlausibility.exclusionReason(60.0, 100.0, 25.0));
    }
}
