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
                        log.info("Killing process and descendants for: {}", e.getKey());
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
            log.info("Executing track download: {}", String.join(" ", command));

            ProcessBuilder processBuilder = new ProcessBuilder(command);
            processBuilder.redirectErrorStream(true);

            process = processBuilder.start();

            // Track the process with composite key
            runningProcesses.put(processKey, process);

            // Read output and parse progress (use ffmpeg parser for track downloads)
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {

                String line;
                FfmpegProgressParser parser = new FfmpegProgressParser();

                while ((line = reader.readLine()) != null && process.isAlive()) {
                    ProgressUpdate update = parser.parseLine(line, taskId);
                    if (update != null) {
                        // Add subTaskId to update
                        update.setSubTaskId(subTaskId);
                        progressCallback.accept(update);
                    } else {
                        log.debug("Track download output: {}", line);
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
            log.info("Executing merge command: {}", String.join(" ", command));

            ProcessBuilder processBuilder = new ProcessBuilder(command);
            processBuilder.redirectErrorStream(true);

            process = processBuilder.start();

            // Track the process
            runningProcesses.put(processKey, process);

            // Read output and parse progress
            StringBuilder errorOutput = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {

                String line;
                FfmpegProgressParser parser = new FfmpegProgressParser();

                while ((line = reader.readLine()) != null && process.isAlive()) {
                    // Capture all output for error reporting
                    errorOutput.append(line).append("\n");

                    ProgressUpdate update = parser.parseLine(line, taskId);
                    if (update != null) {
                        // Override status to MERGING
                        update.setStatus(DownloadStatus.MERGING);
                        progressCallback.accept(update);
                    } else {
                        log.debug("Merge output: {}", line);
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
        
        @Override
        public ProgressUpdate parseLine(String line, String taskId) {
            if (line == null || line.isBlank()) {
                return null;
            }
            
            // Extract total duration
            if (totalDuration == null && line.contains("Duration:")) {
                Matcher durationMatcher = FFMPEG_DURATION_PATTERN.matcher(line);
                if (durationMatcher.find()) {
                    totalDuration = parseTime(
                            durationMatcher.group(1),
                            durationMatcher.group(2),
                            durationMatcher.group(3)
                    );
                }
            }
            
            // Parse progress
            if (line.contains("frame=") && line.contains("time=")) {
                Matcher timeMatcher = FFMPEG_TIME_PATTERN.matcher(line);
                Matcher bitrateMatcher = FFMPEG_BITRATE_PATTERN.matcher(line);
                
                if (timeMatcher.find() && totalDuration != null) {
                    double currentTime = parseTime(
                            timeMatcher.group(1),
                            timeMatcher.group(2),
                            timeMatcher.group(3)
                    );
                    
                    double progress = (currentTime / totalDuration) * 100.0;
                    
                    if (progress <= 100.0) {
                        String bitrate = null;
                        if (bitrateMatcher.find()) {
                            bitrate = bitrateMatcher.group(1) + bitrateMatcher.group(2);
                        }
                        
                        return ProgressUpdate.builder()
                                .taskId(taskId)
                                .status(DownloadStatus.DOWNLOADING)
                                .progress(progress)
                                .bitrate(bitrate)
                                .build();
                    }
                }
            }
            
            return null;
        }
        
        private double parseTime(String hours, String minutes, String seconds) {
            return Integer.parseInt(hours) * 3600 +
                   Integer.parseInt(minutes) * 60 +
                   Double.parseDouble(seconds);
        }
    }
}
