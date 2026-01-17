package com.github.stormino.util;

import lombok.Builder;
import lombok.Data;
import lombok.experimental.UtilityClass;

/**
 * Utility class for download progress calculations including speed, ETA, and percentage.
 */
@UtilityClass
public class ProgressCalculator {

    /**
     * Result of progress calculation containing all computed metrics.
     */
    @Data
    @Builder
    public static class ProgressMetrics {
        private final Double progressPercentage;
        private final String downloadSpeed;
        private final Long etaSeconds;
        private final Long downloadedBytes;
        private final Long totalBytes;
    }

    /**
     * Calculate download speed in bytes per second.
     *
     * @param downloadedBytes Total bytes downloaded so far
     * @param elapsedSeconds Time elapsed since download started
     * @return Speed in bytes per second, or null if calculation not possible
     */
    public static Double calculateSpeed(long downloadedBytes, long elapsedSeconds) {
        if (elapsedSeconds <= 0 || downloadedBytes <= 0) {
            return null;
        }
        return (double) downloadedBytes / elapsedSeconds;
    }

    /**
     * Calculate ETA (estimated time remaining) in seconds.
     *
     * @param downloadedBytes Total bytes downloaded so far
     * @param totalBytes Total bytes to download
     * @param elapsedSeconds Time elapsed since download started
     * @return ETA in seconds, or null if calculation not possible
     */
    public static Long calculateEta(long downloadedBytes, long totalBytes, long elapsedSeconds) {
        if (totalBytes <= 0 || downloadedBytes <= 0 || elapsedSeconds <= 0) {
            return null;
        }

        long remainingBytes = totalBytes - downloadedBytes;
        if (remainingBytes <= 0) {
            return 0L;
        }

        double bytesPerSecond = (double) downloadedBytes / elapsedSeconds;
        if (bytesPerSecond <= 0) {
            return null;
        }

        return (long) (remainingBytes / bytesPerSecond);
    }

    /**
     * Calculate progress percentage based on bytes.
     *
     * @param downloadedBytes Total bytes downloaded so far
     * @param totalBytes Total bytes to download
     * @return Progress percentage (0-100), or null if calculation not possible
     */
    public static Double calculateProgress(long downloadedBytes, long totalBytes) {
        if (totalBytes <= 0 || downloadedBytes < 0) {
            return null;
        }

        if (downloadedBytes >= totalBytes) {
            return 100.0;
        }

        return Math.min(100.0, (downloadedBytes * 100.0) / totalBytes);
    }

    /**
     * Calculate progress percentage based on time (for video encoding).
     *
     * @param currentTimeSeconds Current processing time in video
     * @param totalDurationSeconds Total video duration
     * @return Progress percentage (0-100), or null if calculation not possible
     */
    public static Double calculateProgressByTime(double currentTimeSeconds, double totalDurationSeconds) {
        if (totalDurationSeconds <= 0 || currentTimeSeconds < 0) {
            return null;
        }

        if (currentTimeSeconds >= totalDurationSeconds) {
            return 100.0;
        }

        return Math.min(100.0, (currentTimeSeconds / totalDurationSeconds) * 100.0);
    }

    /**
     * Calculate all progress metrics at once.
     *
     * @param downloadedBytes Total bytes downloaded
     * @param totalBytes Total bytes to download (null if unknown)
     * @param elapsedMillis Milliseconds elapsed since download started
     * @return Complete progress metrics
     */
    public static ProgressMetrics calculateMetrics(long downloadedBytes, Long totalBytes, long elapsedMillis) {
        long elapsedSeconds = Math.max(1, elapsedMillis / 1000);

        Double progressPercentage = null;
        if (totalBytes != null) {
            progressPercentage = calculateProgress(downloadedBytes, totalBytes);
        }

        Double speedBytesPerSecond = calculateSpeed(downloadedBytes, elapsedSeconds);
        String downloadSpeed = speedBytesPerSecond != null ?
            FormatUtils.formatSpeed(speedBytesPerSecond) : null;

        Long etaSeconds = null;
        if (totalBytes != null) {
            etaSeconds = calculateEta(downloadedBytes, totalBytes, elapsedSeconds);
        }

        return ProgressMetrics.builder()
                .progressPercentage(progressPercentage)
                .downloadSpeed(downloadSpeed)
                .etaSeconds(etaSeconds)
                .downloadedBytes(downloadedBytes)
                .totalBytes(totalBytes)
                .build();
    }

    /**
     * Calculate weighted average progress from multiple sub-tasks.
     *
     * @param subTaskDownloaded Array of downloaded bytes per sub-task
     * @param subTaskTotal Array of total bytes per sub-task
     * @return Weighted average progress percentage (0-100)
     */
    public static Double calculateWeightedProgress(long[] subTaskDownloaded, long[] subTaskTotal) {
        if (subTaskDownloaded == null || subTaskTotal == null ||
            subTaskDownloaded.length != subTaskTotal.length ||
            subTaskDownloaded.length == 0) {
            return null;
        }

        long totalDownloaded = 0;
        long totalSize = 0;

        for (int i = 0; i < subTaskDownloaded.length; i++) {
            totalDownloaded += subTaskDownloaded[i];
            totalSize += subTaskTotal[i];
        }

        return calculateProgress(totalDownloaded, totalSize);
    }
}
