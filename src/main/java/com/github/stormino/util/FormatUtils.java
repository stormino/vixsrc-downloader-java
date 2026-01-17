package com.github.stormino.util;

import lombok.experimental.UtilityClass;

/**
 * Utility class for formatting data sizes, speeds, durations, and other display values.
 */
@UtilityClass
public class FormatUtils {

    /**
     * Format bytes per second to human-readable speed string.
     *
     * @param bytesPerSecond Speed in bytes per second
     * @return Formatted string like "5.23 MB/s", "128.45 KB/s", or "512 B/s"
     */
    public static String formatSpeed(double bytesPerSecond) {
        if (bytesPerSecond >= 1_000_000_000) {
            return String.format("%.2f GB/s", bytesPerSecond / 1_000_000_000);
        } else if (bytesPerSecond >= 1_000_000) {
            return String.format("%.2f MB/s", bytesPerSecond / 1_000_000);
        } else if (bytesPerSecond >= 1_000) {
            return String.format("%.2f KB/s", bytesPerSecond / 1_000);
        } else {
            return String.format("%.0f B/s", bytesPerSecond);
        }
    }

    /**
     * Format bytes to human-readable size string.
     *
     * @param bytes Size in bytes
     * @return Formatted string like "1.23 GB", "456.78 MB", or "789 KB"
     */
    public static String formatSize(long bytes) {
        if (bytes >= 1_000_000_000) {
            return String.format("%.2f GB", bytes / 1_000_000_000.0);
        } else if (bytes >= 1_000_000) {
            return String.format("%.2f MB", bytes / 1_000_000.0);
        } else if (bytes >= 1_000) {
            return String.format("%.2f KB", bytes / 1_000.0);
        } else {
            return String.format("%d B", bytes);
        }
    }

    /**
     * Format duration in seconds to human-readable time string.
     *
     * @param seconds Duration in seconds
     * @return Formatted string like "2h 15m 30s", "45m 12s", or "23s"
     */
    public static String formatDuration(long seconds) {
        if (seconds < 0) {
            return "0s";
        }

        long hours = seconds / 3600;
        long minutes = (seconds % 3600) / 60;
        long secs = seconds % 60;

        if (hours > 0) {
            return String.format("%dh %dm %ds", hours, minutes, secs);
        } else if (minutes > 0) {
            return String.format("%dm %ds", minutes, secs);
        } else {
            return String.format("%ds", secs);
        }
    }

    /**
     * Format duration in seconds to compact time string.
     *
     * @param seconds Duration in seconds
     * @return Formatted string like "02:15:30" or "45:12"
     */
    public static String formatDurationCompact(long seconds) {
        if (seconds < 0) {
            return "00:00";
        }

        long hours = seconds / 3600;
        long minutes = (seconds % 3600) / 60;
        long secs = seconds % 60;

        if (hours > 0) {
            return String.format("%02d:%02d:%02d", hours, minutes, secs);
        } else {
            return String.format("%02d:%02d", minutes, secs);
        }
    }

    /**
     * Format percentage with specified decimal places.
     *
     * @param percentage Percentage value (0-100)
     * @param decimalPlaces Number of decimal places (0-2)
     * @return Formatted percentage string like "45.67%"
     */
    public static String formatPercentage(double percentage, int decimalPlaces) {
        if (decimalPlaces < 0 || decimalPlaces > 2) {
            decimalPlaces = 1;
        }
        String format = String.format("%%.%df%%%%", decimalPlaces);
        return String.format(format, percentage);
    }

    /**
     * Format percentage with 1 decimal place.
     *
     * @param percentage Percentage value (0-100)
     * @return Formatted percentage string like "45.6%"
     */
    public static String formatPercentage(double percentage) {
        return formatPercentage(percentage, 1);
    }
}
