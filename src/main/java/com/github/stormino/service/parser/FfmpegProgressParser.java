package com.github.stormino.service.parser;

import com.github.stormino.model.DownloadStatus;
import com.github.stormino.model.ProgressUpdate;
import com.github.stormino.util.FormatUtils;
import lombok.extern.slf4j.Slf4j;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parser for FFmpeg progress output.
 * Extracts progress information from FFmpeg stdout during download/conversion operations.
 */
@Slf4j
public class FfmpegProgressParser implements ProgressParser {

    // Regex patterns for FFmpeg output
    private static final Pattern FFMPEG_TIME_PATTERN = Pattern.compile("time=(\\d{2}):(\\d{2}):(\\d{2}\\.\\d+)");
    private static final Pattern FFMPEG_DURATION_PATTERN = Pattern.compile("Duration:\\s*(\\d{2}):(\\d{2}):(\\d{2}\\.\\d+)");
    private static final Pattern FFMPEG_BITRATE_PATTERN = Pattern.compile("bitrate=\\s*(\\d+\\.?\\d*)\\s*([kmgt]?bits/s)", Pattern.CASE_INSENSITIVE);
    private static final Pattern FFMPEG_SIZE_PATTERN = Pattern.compile("size=\\s*(\\d+)([kKmMgGtT]?[iI]?[bB])", Pattern.CASE_INSENSITIVE);

    private Double totalDuration = null;
    private Long totalSize = null;
    private final long startTimeMillis;

    public FfmpegProgressParser() {
        this.startTimeMillis = System.currentTimeMillis();
    }

    @Override
    public ProgressUpdate parseLine(String line, String taskId) {
        if (line == null || line.isBlank()) {
            return null;
        }

        // Extract total duration
        if (totalDuration == null && line.contains("Duration:")) {
            extractDuration(line);
        }

        // Parse progress from frames/time/size
        if (line.contains("frame=") || line.contains("size=")) {
            return parseProgressLine(line, taskId);
        }

        return null;
    }

    @Override
    public void reset() {
        totalDuration = null;
        totalSize = null;
    }

    @Override
    public Double getTotalDuration() {
        return totalDuration;
    }

    @Override
    public Long getTotalSize() {
        return totalSize;
    }

    /**
     * Extract total duration from FFmpeg output.
     */
    private void extractDuration(String line) {
        Matcher durationMatcher = FFMPEG_DURATION_PATTERN.matcher(line);
        if (durationMatcher.find()) {
            String durationStr = durationMatcher.group(0);
            // Check if duration is N/A (common for HLS)
            if (!durationStr.contains("N/A")) {
                totalDuration = parseTime(
                        durationMatcher.group(1),
                        durationMatcher.group(2),
                        durationMatcher.group(3)
                );
                log.debug("Extracted total duration: {} seconds", totalDuration);
            }
        }
    }

    /**
     * Parse a progress line and create a ProgressUpdate.
     */
    private ProgressUpdate parseProgressLine(String line, String taskId) {
        Matcher timeMatcher = FFMPEG_TIME_PATTERN.matcher(line);
        Matcher bitrateMatcher = FFMPEG_BITRATE_PATTERN.matcher(line);
        Matcher sizeMatcher = FFMPEG_SIZE_PATTERN.matcher(line);

        long currentSizeBytes = 0;
        if (sizeMatcher.find()) {
            currentSizeBytes = parseSizeToBytes(sizeMatcher.group(1), sizeMatcher.group(2));
        }

        // Parse current time
        Double currentTime = null;
        if (timeMatcher.find()) {
            currentTime = parseTime(
                    timeMatcher.group(1),
                    timeMatcher.group(2),
                    timeMatcher.group(3)
            );
        }

        // Calculate elapsed time and speed
        long elapsedMillis = System.currentTimeMillis() - startTimeMillis;
        long elapsedSeconds = Math.max(1, elapsedMillis / 1000);

        String downloadSpeed = null;
        Long etaSeconds = null;

        if (elapsedSeconds > 0 && currentSizeBytes > 0) {
            double bytesPerSecond = (double) currentSizeBytes / elapsedSeconds;
            downloadSpeed = FormatUtils.formatSpeed(bytesPerSecond);

            // If we have duration and current time, estimate total size and calculate ETA
            if (totalDuration != null && currentTime != null && currentTime > 0) {
                // Estimate total size only once (when not yet set)
                if (totalSize == null) {
                    totalSize = (long) ((currentSizeBytes / currentTime) * totalDuration);
                }
                long remainingBytes = totalSize - currentSizeBytes;

                if (remainingBytes > 0 && bytesPerSecond > 0) {
                    etaSeconds = (long) (remainingBytes / bytesPerSecond);
                }
            }
        }

        // Calculate progress
        Double progress = null;
        if (currentTime != null && totalDuration != null && totalDuration > 0) {
            // Time-based progress (more reliable)
            progress = (currentTime / totalDuration) * 100.0;
        } else if (totalSize != null && totalSize > 0) {
            // Size-based progress (fallback)
            progress = (currentSizeBytes * 100.0) / totalSize;
        }

        // Only return update if we have progress or size info
        if (progress != null || currentSizeBytes > 0) {
            String bitrate = null;
            if (bitrateMatcher.find()) {
                bitrate = bitrateMatcher.group(1) + bitrateMatcher.group(2);
            }

            return ProgressUpdate.builder()
                    .taskId(taskId)
                    .status(DownloadStatus.DOWNLOADING)
                    .progress(progress != null && progress <= 100.0 ? progress : null)
                    .bitrate(bitrate)
                    .downloadedBytes(currentSizeBytes > 0 ? currentSizeBytes : null)
                    .totalBytes(totalSize)
                    .downloadSpeed(downloadSpeed)
                    .etaSeconds(etaSeconds)
                    .build();
        }

        return null;
    }

    /**
     * Parse time string to seconds.
     */
    private double parseTime(String hours, String minutes, String seconds) {
        return Integer.parseInt(hours) * 3600 +
               Integer.parseInt(minutes) * 60 +
               Double.parseDouble(seconds);
    }

    /**
     * Parse size string to bytes.
     */
    private long parseSizeToBytes(String value, String unit) {
        try {
            long baseValue = Long.parseLong(value);
            String unitLower = unit.toLowerCase();

            if (unitLower.contains("k")) {
                return baseValue * 1024;
            } else if (unitLower.contains("m")) {
                return baseValue * 1024 * 1024;
            } else if (unitLower.contains("g")) {
                return baseValue * 1024 * 1024 * 1024;
            } else if (unitLower.contains("t")) {
                return baseValue * 1024L * 1024 * 1024 * 1024;
            } else {
                return baseValue;  // Already in bytes
            }
        } catch (NumberFormatException e) {
            log.warn("Failed to parse size: {} {}", value, unit);
            return 0;
        }
    }
}
