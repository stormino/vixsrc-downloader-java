package com.github.stormino.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ProgressCalculator")
class ProgressCalculatorTest {

    @Nested
    @DisplayName("calculateSpeed")
    class CalculateSpeedTests {

        @Test
        @DisplayName("should calculate speed correctly")
        void shouldCalculateSpeedCorrectly() {
            Double speed = ProgressCalculator.calculateSpeed(1_000_000, 10);
            assertNotNull(speed);
            assertEquals(100_000.0, speed, 0.01);
        }

        @Test
        @DisplayName("should return null for zero elapsed time")
        void shouldReturnNullForZeroElapsedTime() {
            assertNull(ProgressCalculator.calculateSpeed(1000, 0));
        }

        @Test
        @DisplayName("should return null for negative elapsed time")
        void shouldReturnNullForNegativeElapsedTime() {
            assertNull(ProgressCalculator.calculateSpeed(1000, -5));
        }

        @Test
        @DisplayName("should return null for zero downloaded bytes")
        void shouldReturnNullForZeroDownloadedBytes() {
            assertNull(ProgressCalculator.calculateSpeed(0, 10));
        }
    }

    @Nested
    @DisplayName("calculateEta")
    class CalculateEtaTests {

        @Test
        @DisplayName("should calculate ETA correctly")
        void shouldCalculateEtaCorrectly() {
            // 500KB downloaded of 1MB total in 10 seconds = 50KB/s
            // Remaining 500KB at 50KB/s = 10 seconds ETA
            Long eta = ProgressCalculator.calculateEta(500_000, 1_000_000, 10);
            assertNotNull(eta);
            assertEquals(10L, eta);
        }

        @Test
        @DisplayName("should return 0 when download is complete")
        void shouldReturnZeroWhenDownloadComplete() {
            Long eta = ProgressCalculator.calculateEta(1_000_000, 1_000_000, 10);
            assertNotNull(eta);
            assertEquals(0L, eta);
        }

        @Test
        @DisplayName("should return null for zero total bytes")
        void shouldReturnNullForZeroTotalBytes() {
            assertNull(ProgressCalculator.calculateEta(500, 0, 10));
        }

        @Test
        @DisplayName("should return null for zero elapsed time")
        void shouldReturnNullForZeroElapsedTime() {
            assertNull(ProgressCalculator.calculateEta(500, 1000, 0));
        }

        @Test
        @DisplayName("should return null for zero downloaded bytes")
        void shouldReturnNullForZeroDownloadedBytes() {
            assertNull(ProgressCalculator.calculateEta(0, 1000, 10));
        }
    }

    @Nested
    @DisplayName("calculateProgress")
    class CalculateProgressTests {

        @Test
        @DisplayName("should calculate progress correctly")
        void shouldCalculateProgressCorrectly() {
            Double progress = ProgressCalculator.calculateProgress(500_000, 1_000_000);
            assertNotNull(progress);
            assertEquals(50.0, progress, 0.01);
        }

        @Test
        @DisplayName("should return 100 when download exceeds total")
        void shouldReturn100WhenDownloadExceedsTotal() {
            Double progress = ProgressCalculator.calculateProgress(1_500_000, 1_000_000);
            assertNotNull(progress);
            assertEquals(100.0, progress, 0.01);
        }

        @Test
        @DisplayName("should return null for zero total bytes")
        void shouldReturnNullForZeroTotalBytes() {
            assertNull(ProgressCalculator.calculateProgress(500, 0));
        }

        @Test
        @DisplayName("should return null for negative downloaded bytes")
        void shouldReturnNullForNegativeDownloadedBytes() {
            assertNull(ProgressCalculator.calculateProgress(-500, 1000));
        }

        @Test
        @DisplayName("should handle zero downloaded bytes")
        void shouldHandleZeroDownloadedBytes() {
            Double progress = ProgressCalculator.calculateProgress(0, 1000);
            assertNotNull(progress);
            assertEquals(0.0, progress, 0.01);
        }
    }

    @Nested
    @DisplayName("calculateProgressByTime")
    class CalculateProgressByTimeTests {

        @Test
        @DisplayName("should calculate time-based progress correctly")
        void shouldCalculateTimeBasedProgressCorrectly() {
            Double progress = ProgressCalculator.calculateProgressByTime(60.0, 120.0);
            assertNotNull(progress);
            assertEquals(50.0, progress, 0.01);
        }

        @Test
        @DisplayName("should return 100 when current exceeds total")
        void shouldReturn100WhenCurrentExceedsTotal() {
            Double progress = ProgressCalculator.calculateProgressByTime(150.0, 120.0);
            assertNotNull(progress);
            assertEquals(100.0, progress, 0.01);
        }

        @Test
        @DisplayName("should return null for zero duration")
        void shouldReturnNullForZeroDuration() {
            assertNull(ProgressCalculator.calculateProgressByTime(50.0, 0.0));
        }

        @Test
        @DisplayName("should return null for negative current time")
        void shouldReturnNullForNegativeCurrentTime() {
            assertNull(ProgressCalculator.calculateProgressByTime(-10.0, 100.0));
        }
    }

    @Nested
    @DisplayName("calculateMetrics")
    class CalculateMetricsTests {

        @Test
        @DisplayName("should calculate all metrics correctly")
        void shouldCalculateAllMetricsCorrectly() {
            ProgressCalculator.ProgressMetrics metrics =
                ProgressCalculator.calculateMetrics(500_000, 1_000_000L, 10_000);

            assertNotNull(metrics);
            assertEquals(50.0, metrics.getProgressPercentage(), 0.01);
            assertNotNull(metrics.getDownloadSpeed());
            assertNotNull(metrics.getEtaSeconds());
            assertEquals(500_000L, metrics.getDownloadedBytes());
            assertEquals(1_000_000L, metrics.getTotalBytes());
        }

        @Test
        @DisplayName("should handle null total bytes")
        void shouldHandleNullTotalBytes() {
            ProgressCalculator.ProgressMetrics metrics =
                ProgressCalculator.calculateMetrics(500_000, null, 10_000);

            assertNotNull(metrics);
            assertNull(metrics.getProgressPercentage());
            assertNull(metrics.getEtaSeconds());
            assertNotNull(metrics.getDownloadSpeed());
        }

        @Test
        @DisplayName("should handle very short elapsed time")
        void shouldHandleVeryShortElapsedTime() {
            ProgressCalculator.ProgressMetrics metrics =
                ProgressCalculator.calculateMetrics(500, 1000L, 100);

            assertNotNull(metrics);
            // Should use minimum of 1 second for calculation
            assertNotNull(metrics.getDownloadSpeed());
        }
    }

    @Nested
    @DisplayName("calculateWeightedProgress")
    class CalculateWeightedProgressTests {

        @Test
        @DisplayName("should calculate weighted progress correctly")
        void shouldCalculateWeightedProgressCorrectly() {
            long[] downloaded = {500_000, 250_000};  // 750K downloaded
            long[] totals = {1_000_000, 500_000};    // 1.5M total = 50%

            Double progress = ProgressCalculator.calculateWeightedProgress(downloaded, totals);
            assertNotNull(progress);
            assertEquals(50.0, progress, 0.01);
        }

        @Test
        @DisplayName("should return null for null arrays")
        void shouldReturnNullForNullArrays() {
            assertNull(ProgressCalculator.calculateWeightedProgress(null, new long[]{1000}));
            assertNull(ProgressCalculator.calculateWeightedProgress(new long[]{500}, null));
        }

        @Test
        @DisplayName("should return null for mismatched array lengths")
        void shouldReturnNullForMismatchedArrayLengths() {
            long[] downloaded = {500, 250};
            long[] totals = {1000};

            assertNull(ProgressCalculator.calculateWeightedProgress(downloaded, totals));
        }

        @Test
        @DisplayName("should return null for empty arrays")
        void shouldReturnNullForEmptyArrays() {
            assertNull(ProgressCalculator.calculateWeightedProgress(new long[]{}, new long[]{}));
        }

        @Test
        @DisplayName("should handle single subtask")
        void shouldHandleSingleSubtask() {
            long[] downloaded = {750_000};
            long[] totals = {1_000_000};

            Double progress = ProgressCalculator.calculateWeightedProgress(downloaded, totals);
            assertNotNull(progress);
            assertEquals(75.0, progress, 0.01);
        }
    }
}
