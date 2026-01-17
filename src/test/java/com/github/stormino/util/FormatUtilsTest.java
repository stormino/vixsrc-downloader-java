package com.github.stormino.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("FormatUtils")
class FormatUtilsTest {

    @Nested
    @DisplayName("formatSpeed")
    class FormatSpeedTests {

        @Test
        @DisplayName("should format bytes per second")
        void shouldFormatBytesPerSecond() {
            assertEquals("500 B/s", FormatUtils.formatSpeed(500));
        }

        @Test
        @DisplayName("should format kilobytes per second")
        void shouldFormatKilobytesPerSecond() {
            assertEquals("5.00 KB/s", FormatUtils.formatSpeed(5_000));
        }

        @Test
        @DisplayName("should format megabytes per second")
        void shouldFormatMegabytesPerSecond() {
            assertEquals("5.23 MB/s", FormatUtils.formatSpeed(5_230_000));
        }

        @Test
        @DisplayName("should format gigabytes per second")
        void shouldFormatGigabytesPerSecond() {
            assertEquals("1.50 GB/s", FormatUtils.formatSpeed(1_500_000_000));
        }

        @Test
        @DisplayName("should handle zero speed")
        void shouldHandleZeroSpeed() {
            assertEquals("0 B/s", FormatUtils.formatSpeed(0));
        }

        @ParameterizedTest
        @CsvSource({
            "999, 999 B/s",
            "1000, 1.00 KB/s",
            "999999, 1000.00 KB/s",
            "1000000, 1.00 MB/s"
        })
        @DisplayName("should handle boundary values")
        void shouldHandleBoundaryValues(double input, String expected) {
            assertEquals(expected, FormatUtils.formatSpeed(input));
        }
    }

    @Nested
    @DisplayName("formatSize")
    class FormatSizeTests {

        @Test
        @DisplayName("should format bytes")
        void shouldFormatBytes() {
            assertEquals("500 B", FormatUtils.formatSize(500));
        }

        @Test
        @DisplayName("should format kilobytes")
        void shouldFormatKilobytes() {
            assertEquals("5.00 KB", FormatUtils.formatSize(5_000));
        }

        @Test
        @DisplayName("should format megabytes")
        void shouldFormatMegabytes() {
            assertEquals("150.50 MB", FormatUtils.formatSize(150_500_000));
        }

        @Test
        @DisplayName("should format gigabytes")
        void shouldFormatGigabytes() {
            assertEquals("2.50 GB", FormatUtils.formatSize(2_500_000_000L));
        }

        @Test
        @DisplayName("should handle zero size")
        void shouldHandleZeroSize() {
            assertEquals("0 B", FormatUtils.formatSize(0));
        }
    }

    @Nested
    @DisplayName("formatDuration")
    class FormatDurationTests {

        @Test
        @DisplayName("should format seconds only")
        void shouldFormatSecondsOnly() {
            assertEquals("45s", FormatUtils.formatDuration(45));
        }

        @Test
        @DisplayName("should format minutes and seconds")
        void shouldFormatMinutesAndSeconds() {
            assertEquals("5m 30s", FormatUtils.formatDuration(330));
        }

        @Test
        @DisplayName("should format hours minutes and seconds")
        void shouldFormatHoursMinutesAndSeconds() {
            assertEquals("2h 15m 30s", FormatUtils.formatDuration(8130));
        }

        @Test
        @DisplayName("should handle zero duration")
        void shouldHandleZeroDuration() {
            assertEquals("0s", FormatUtils.formatDuration(0));
        }

        @Test
        @DisplayName("should handle negative duration")
        void shouldHandleNegativeDuration() {
            assertEquals("0s", FormatUtils.formatDuration(-10));
        }

        @ParameterizedTest
        @CsvSource({
            "59, 59s",
            "60, 1m 0s",
            "3599, 59m 59s",
            "3600, 1h 0m 0s"
        })
        @DisplayName("should handle boundary values")
        void shouldHandleBoundaryValues(long input, String expected) {
            assertEquals(expected, FormatUtils.formatDuration(input));
        }
    }

    @Nested
    @DisplayName("formatDurationCompact")
    class FormatDurationCompactTests {

        @Test
        @DisplayName("should format without hours")
        void shouldFormatWithoutHours() {
            assertEquals("05:30", FormatUtils.formatDurationCompact(330));
        }

        @Test
        @DisplayName("should format with hours")
        void shouldFormatWithHours() {
            assertEquals("02:15:30", FormatUtils.formatDurationCompact(8130));
        }

        @Test
        @DisplayName("should handle zero")
        void shouldHandleZero() {
            assertEquals("00:00", FormatUtils.formatDurationCompact(0));
        }

        @Test
        @DisplayName("should handle negative")
        void shouldHandleNegative() {
            assertEquals("00:00", FormatUtils.formatDurationCompact(-5));
        }
    }

    @Nested
    @DisplayName("formatPercentage")
    class FormatPercentageTests {

        @Test
        @DisplayName("should format with default decimal places")
        void shouldFormatWithDefaultDecimalPlaces() {
            assertEquals("45.7%", FormatUtils.formatPercentage(45.67));
        }

        @Test
        @DisplayName("should format with zero decimal places")
        void shouldFormatWithZeroDecimalPlaces() {
            assertEquals("46%", FormatUtils.formatPercentage(45.67, 0));
        }

        @Test
        @DisplayName("should format with two decimal places")
        void shouldFormatWithTwoDecimalPlaces() {
            assertEquals("45.67%", FormatUtils.formatPercentage(45.67, 2));
        }

        @Test
        @DisplayName("should handle invalid decimal places")
        void shouldHandleInvalidDecimalPlaces() {
            assertEquals("45.7%", FormatUtils.formatPercentage(45.67, -1));
            assertEquals("45.7%", FormatUtils.formatPercentage(45.67, 5));
        }

        @Test
        @DisplayName("should handle zero percentage")
        void shouldHandleZeroPercentage() {
            assertEquals("0.0%", FormatUtils.formatPercentage(0));
        }

        @Test
        @DisplayName("should handle 100 percentage")
        void shouldHandle100Percentage() {
            assertEquals("100.0%", FormatUtils.formatPercentage(100));
        }
    }
}
