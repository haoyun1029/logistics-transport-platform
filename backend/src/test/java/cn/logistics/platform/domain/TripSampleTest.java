package cn.logistics.platform.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TripSampleTest {

    @Test
    void validTripNeedsCompletedTimesPositiveFeedbackAndRecoverableRoute() {
        TripSample valid = sample(true);
        assertTrue(valid.isValidForOptimization());

        TripSample unrecoverable = new TripSample("T-1", "A", "B", true,
                Instant.parse("2026-10-01T00:00:00Z"), Instant.parse("2026-10-01T01:00:00Z"),
                100, 20, false, true);
        assertFalse(unrecoverable.isValidForOptimization());
        assertEquals("轨迹匹配不足以可靠还原路线差异", unrecoverable.exclusionReason());
        assertTrue(valid.matchedTrajectoryIsSimulated());
    }

    private TripSample sample(boolean recoverable) {
        return new TripSample("T-1", "A", "B", true,
                Instant.parse("2026-10-01T00:00:00Z"), Instant.parse("2026-10-01T01:00:00Z"),
                100, 20, recoverable, true);
    }
}
