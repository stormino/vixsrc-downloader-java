package com.github.stormino.service;

import com.github.stormino.config.VixSrcProperties;
import com.github.stormino.model.DownloadStatus;
import com.github.stormino.model.DownloadTask;
import com.github.stormino.model.ProgressUpdate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class DownloadExecutorService {

    private final VixSrcProperties properties;

    // Track running processes per task
    private final java.util.concurrent.ConcurrentHashMap<String, Process> runningProcesses = new java.util.concurrent.ConcurrentHashMap<>();
    
    // Progress parsing patterns
    private static final Pattern FFMPEG_TIME_PATTERN = Pattern.compile("time=(\\d{2}):(\\d{2}):(\\d{2}\\.\\d+)");
    private static final Pattern FFMPEG_DURATION_PATTERN = Pattern.compile("Duration:\\s*(\\d{2}):(\\d{2}):(\\d{2}\\.\\d+)");
    private static final Pattern FFMPEG_BITRATE_PATTERN = Pattern.compile("bitrate=\\s*(\\d+\\.?\\d*)\\s*([kmgt]?bits/s)", Pattern.CASE_INSENSITIVE);
    private static final Pattern FFMPEG_SIZE_PATTERN = Pattern.compile("size=\\s*(\\d+)([kKmMgGtT]?[iI]?[bB])", Pattern.CASE_INSENSITIVE);
    private static final Pattern FFMPEG_SPEED_PATTERN = Pattern.compile("speed=\\s*(\\d+\\.?\\d*)x");
    
    /**
     * Cancel a running download
     */
    public void cancelDownload(String taskId) {
        // Find all processes for this task (parent + sub-tasks)
        runningProcesses.entrySet().stream()
                .filter(e -> e.getKey().startsWith(taskId))
                .forEach(e -> {
                    Process process = e.getValue();
                    if (process.isAlive()) {
                        log.debug("Killing process and descendants for: {}", e.getKey());
                        process.descendants().forEach(ProcessHandle::destroyForcibly);
                        process.destroyForcibly();
                    }
                });
    }

    /**
     * Execute track-specific download (for concurrent track downloads)
     */
    public boolean executeTrackDownload(List<String> command, String taskId, String subTaskId,
                                       Consumer<ProgressUpdate> progressCallback) {
        String processKey = taskId + ":" + subTaskId;
        Process process = null;
        try {
            log.debug("Executing track download: {}", String.join(" ", command));

            ProcessBuilder processBuilder = new ProcessBuilder(command);
            processBuilder.redirectErrorStream(true);

            process = processBuilder.start();

            // Track the process with composite key
            runningProcesses.put(processKey, process);

            // Read output and parse progress (use ffmpeg parser for track downloads)
            Long lastDownloadedBytes = null;
            Long lastTotalBytes = null;

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {

                String line;
                FfmpegProgressParser parser = new FfmpegProgressParser();

                while ((line = reader.readLine()) != null && process.isAlive()) {
                    log.debug("FFmpeg output: {}", line);
                    ProgressUpdate update = parser.parseLine(line, taskId);
                    if (update != null) {
                        // Add subTaskId to update
                        update.setSubTaskId(subTaskId);
                        progressCallback.accept(update);

                        // Track last known sizes
                        if (update.getDownloadedBytes() != null) {
                            lastDownloadedBytes = update.getDownloadedBytes();
                        }
                        if (update.getTotalBytes() != null) {
                            lastTotalBytes = update.getTotalBytes();
                        }

                        log.debug("Progress update: {}% downloaded={} total={} speed={} eta={}s",
                                update.getProgress(), update.getDownloadedBytes(), update.getTotalBytes(),
                                update.getDownloadSpeed(), update.getEtaSeconds());
                    }
                }
            }

            boolean completed = process.waitFor(2, TimeUnit.HOURS);

            if (!completed) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                return false;
            }

            int exitCode = process.exitValue();

            if (exitCode == 0) {
                progressCallback.accept(ProgressUpdate.builder()
                        .taskId(taskId)
                        .subTaskId(subTaskId)
                        .status(DownloadStatus.COMPLETED)
                        .progress(100.0)
                        .downloadedBytes(lastTotalBytes != null ? lastTotalBytes : lastDownloadedBytes)
                        .totalBytes(lastTotalBytes)
                        .downloadSpeed(null)
                        .etaSeconds(null)
                        .build());
                return true;
            }

            log.error("Track download process exited with code: {} for subTask: {}", exitCode, subTaskId);
            log.error("Command was: {}", String.join(" ", command));
            progressCallback.accept(ProgressUpdate.builder()
                    .taskId(taskId)
                    .subTaskId(subTaskId)
                    .status(DownloadStatus.FAILED)
                    .errorMessage("Download failed with exit code " + exitCode)
                    .build());
            return false;

        } catch (IOException | InterruptedException e) {
            log.error("Error executing track download command: {}", e.getMessage());
            Thread.currentThread().interrupt();
            return false;
        } finally {
            if (process != null) {
                runningProcesses.remove(processKey);
            }
        }
    }

    /**
     * Execute ffmpeg merge command
     */
    public boolean executeMergeCommand(List<String> command, String taskId,
                                      Consumer<ProgressUpdate> progressCallback) {
        String processKey = taskId + ":merge";
        Process process = null;
        try {
            log.debug("Executing merge command: {}", String.join(" ", command));

            ProcessBuilder processBuilder = new ProcessBuilder(command);
            processBuilder.redirectErrorStream(true);

            process = processBuilder.start();

            // Track the process
            runningProcesses.put(processKey, process);

            // Read output and parse progress
            StringBuilder errorOutput = new StringBuilder();
            Long lastDownloadedBytes = null;
            Long lastTotalBytes = null;

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {

                String line;
                FfmpegProgressParser parser = new FfmpegProgressParser();

                while ((line = reader.readLine()) != null && process.isAlive()) {
                    // Capture all output for error reporting
                    errorOutput.append(line).append("\n");
                    log.debug("FFmpeg merge output: {}", line);

                    ProgressUpdate update = parser.parseLine(line, taskId);
                    if (update != null) {
                        // Override status to MERGING
                        update.setStatus(DownloadStatus.MERGING);
                        progressCallback.accept(update);

                        // Track last known sizes
                        if (update.getDownloadedBytes() != null) {
                            lastDownloadedBytes = update.getDownloadedBytes();
                        }
                        if (update.getTotalBytes() != null) {
                            lastTotalBytes = update.getTotalBytes();
                        }

                        log.debug("Merge progress update: {}% downloaded={} total={} speed={} eta={}s",
                                update.getProgress(), update.getDownloadedBytes(), update.getTotalBytes(),
                                update.getDownloadSpeed(), update.getEtaSeconds());
                    }
                }
            }

            boolean completed = process.waitFor(2, TimeUnit.HOURS);

            if (!completed) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                return false;
            }

            int exitCode = process.exitValue();

            if (exitCode == 0) {
                progressCallback.accept(ProgressUpdate.builder()
                        .taskId(taskId)
                        .status(DownloadStatus.COMPLETED)
                        .progress(100.0)
                        .downloadedBytes(lastTotalBytes != null ? lastTotalBytes : lastDownloadedBytes)
                        .totalBytes(lastTotalBytes)
                        .downloadSpeed(null)
                        .etaSeconds(null)
                        .message("Merge completed")
                        .build());
                return true;
            }

            // Log full error output on failure
            log.error("Merge process exited with code: {}", exitCode);
            log.error("Full ffmpeg output:\n{}", errorOutput.toString());
            progressCallback.accept(ProgressUpdate.builder()
                    .taskId(taskId)
                    .status(DownloadStatus.FAILED)
                    .errorMessage("Merge failed with exit code " + exitCode)
                    .build());
            return false;

        } catch (IOException | InterruptedException e) {
            log.error("Error executing merge command: {}", e.getMessage());
            Thread.currentThread().interrupt();
            return false;
        } finally {
            if (process != null) {
                runningProcesses.remove(processKey);
            }
        }
    }

    /**
     * Interface for parsing progress from command output
     */
    private interface ProgressParser {
        ProgressUpdate parseLine(String line, String taskId);
    }

    /**
     * Parse ffmpeg progress output
     */
    private static class FfmpegProgressParser implements ProgressParser {

        private Double totalDuration = null;
        private Long totalSize = null;
        private final long startTimeMillis = System.currentTimeMillis();

        @Override
        public ProgressUpdate parseLine(String line, String taskId) {
            if (line == null || line.isBlank()) {
                return null;
            }

            // Extract total duration
            if (totalDuration == null && line.contains("Duration:")) {
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
                    }
                }
            }

            // Parse progress from frames/time/size
            if (line.contains("frame=") || line.contains("size=")) {
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
                long elapsedSeconds = elapsedMillis / 1000;

                String downloadSpeed = null;
                Long etaSeconds = null;

                if (elapsedSeconds > 0 && currentSizeBytes > 0) {
                    double bytesPerSecond = (double) currentSizeBytes / elapsedSeconds;
                    downloadSpeed = formatSpeed(bytesPerSecond);

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
            }

            return null;
        }

        private double parseTime(String hours, String minutes, String seconds) {
            return Integer.parseInt(hours) * 3600 +
                   Integer.parseInt(minutes) * 60 +
                   Double.parseDouble(seconds);
        }

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
                return 0;
            }
        }

        private String formatSpeed(double bytesPerSecond) {
            if (bytesPerSecond >= 1_000_000) {
                return String.format("%.2f MB/s", bytesPerSecond / 1_000_000);
            } else if (bytesPerSecond >= 1_000) {
                return String.format("%.2f KB/s", bytesPerSecond / 1_000);
            } else {
                return String.format("%.0f B/s", bytesPerSecond);
            }
        }
    }
}
