package com.github.stormino.service;

import com.github.stormino.config.VixSrcProperties;
import com.github.stormino.model.DownloadResult;
import com.github.stormino.model.DownloadStatus;
import com.github.stormino.model.ProgressUpdate;
import com.github.stormino.service.parser.FfmpegProgressParser;
import com.github.stormino.service.progress.ProgressEventBuilder;
import com.github.stormino.util.DownloadConstants;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
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
    private final ProgressEventBuilder progressBuilder;

    // Track running processes per task
    private final java.util.concurrent.ConcurrentHashMap<String, Process> runningProcesses = new java.util.concurrent.ConcurrentHashMap<>();
    
    // Progress parsing patterns
    private static final Pattern FFMPEG_TIME_PATTERN = Pattern.compile("time=(\\d{2}):(\\d{2}):(\\d{2}\\.\\d+)");
    private static final Pattern FFMPEG_DURATION_PATTERN = Pattern.compile("Duration:\\s*(\\d{2}):(\\d{2}):(\\d{2}\\.\\d+)");
    private static final Pattern FFMPEG_BITRATE_PATTERN = Pattern.compile("bitrate=\\s*(\\d+\\.?\\d*)\\s*([kmgt]?bits/s)", Pattern.CASE_INSENSITIVE);
    private static final Pattern FFMPEG_SIZE_PATTERN = Pattern.compile("size=\\s*(\\d+)([kKmMgGtT]?[iI]?[bB])", Pattern.CASE_INSENSITIVE);
    
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
     * Download track using external command (for concurrent track downloads)
     */
    public DownloadResult downloadTrack(@NonNull List<String> command, @NonNull String taskId, @NonNull String subTaskId,
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
                return DownloadResult.failure("Download timeout exceeded");
            }

            int exitCode = process.exitValue();

            if (exitCode == 0) {
                progressCallback.accept(progressBuilder.buildTrackCompletion(
                        taskId,
                        subTaskId,
                        lastTotalBytes != null ? lastTotalBytes : lastDownloadedBytes,
                        lastTotalBytes
                ));
                return DownloadResult.success();
            }

            log.error("Track download process exited with code: {} for subTask: {}", exitCode, subTaskId);
            log.error("Command was: {}", String.join(" ", command));
            progressCallback.accept(progressBuilder.buildTrackFailure(
                    taskId,
                    subTaskId,
                    "Download failed with exit code " + exitCode
            ));
            return DownloadResult.failure("Download failed with exit code " + exitCode);

        } catch (IOException | InterruptedException e) {
            log.error("Error executing track download command for task {}: {}", taskId, e.getMessage(), e);
            Thread.currentThread().interrupt();
            return DownloadResult.failure("Error executing track download: " + e.getMessage(), e);
        } finally {
            if (process != null) {
                runningProcesses.remove(processKey);
            }
        }
    }

    /**
     * Merge tracks using ffmpeg
     */
    public DownloadResult mergeTracks(@NonNull List<String> command, @NonNull String taskId,
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
                return DownloadResult.failure("Merge timeout exceeded");
            }

            int exitCode = process.exitValue();

            if (exitCode == 0) {
                progressCallback.accept(progressBuilder.buildMergeCompletion(
                        taskId,
                        lastTotalBytes != null ? lastTotalBytes : lastDownloadedBytes,
                        lastTotalBytes
                ));
                return DownloadResult.success();
            }

            // Log full error output on failure
            log.error("Merge process exited with code: {}", exitCode);
            log.error("Full ffmpeg output:\n{}", errorOutput.toString());
            progressCallback.accept(progressBuilder.buildMergeFailure(
                    taskId,
                    "Merge failed with exit code " + exitCode
            ));
            return DownloadResult.failure("Merge failed with exit code " + exitCode);

        } catch (IOException | InterruptedException e) {
            log.error("Error executing merge command for task {}: {}", taskId, e.getMessage(), e);
            Thread.currentThread().interrupt();
            return DownloadResult.failure("Error executing merge: " + e.getMessage(), e);
        } finally {
            if (process != null) {
                runningProcesses.remove(processKey);
            }
        }
    }
}
