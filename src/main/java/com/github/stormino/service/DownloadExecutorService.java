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
    private static final Pattern YTDLP_PROGRESS_PATTERN = Pattern.compile("PROGRESS:(\\d+\\.?\\d*)%?");
    private static final Pattern FFMPEG_TIME_PATTERN = Pattern.compile("time=(\\d{2}):(\\d{2}):(\\d{2}\\.\\d+)");
    private static final Pattern FFMPEG_DURATION_PATTERN = Pattern.compile("Duration:\\s*(\\d{2}):(\\d{2}):(\\d{2}\\.\\d+)");
    private static final Pattern FFMPEG_BITRATE_PATTERN = Pattern.compile("bitrate=\\s*(\\d+\\.?\\d*)\\s*([kmgt]?bits/s)", Pattern.CASE_INSENSITIVE);
    
    /**
     * Cancel a running download
     */
    public void cancelDownload(String taskId) {
        Process process = runningProcesses.get(taskId);
        if (process != null && process.isAlive()) {
            log.info("Killing process and descendants for task: {}", taskId);
            // Kill all descendant processes first
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            // Then kill the main process
            process.destroyForcibly();
        }
    }

    /**
     * Download video using yt-dlp or ffmpeg
     */
    public boolean downloadVideo(DownloadTask task, Consumer<ProgressUpdate> progressCallback) {
        try {
            // Check if yt-dlp is available (preferred)
            if (isCommandAvailable("yt-dlp")) {
                return downloadWithYtDlp(task, progressCallback);
            }

            // Fallback to ffmpeg
            if (isCommandAvailable("ffmpeg")) {
                return downloadWithFfmpeg(task, progressCallback);
            }

            log.error("Neither yt-dlp nor ffmpeg found");
            progressCallback.accept(ProgressUpdate.error(task.getId(),
                    "Neither yt-dlp nor ffmpeg found. Please install one."));
            return false;

        } catch (Exception e) {
            log.error("Download failed for task {}: {}", task.getId(), e.getMessage(), e);
            progressCallback.accept(ProgressUpdate.error(task.getId(),
                    "Download failed: " + e.getMessage()));
            return false;
        }
    }
    
    /**
     * Download using yt-dlp (preferred method)
     */
    private boolean downloadWithYtDlp(DownloadTask task, Consumer<ProgressUpdate> progressCallback) {
        log.info("Downloading with yt-dlp: {}", task.getDisplayName());
        
        List<String> command = buildYtDlpCommand(task);
        
        return executeCommand(command, task, progressCallback, new YtDlpProgressParser());
    }
    
    /**
     * Download using ffmpeg (fallback)
     */
    private boolean downloadWithFfmpeg(DownloadTask task, Consumer<ProgressUpdate> progressCallback) {
        log.info("Downloading with ffmpeg: {}", task.getDisplayName());
        
        List<String> command = buildFfmpegCommand(task);
        
        return executeCommand(command, task, progressCallback, new FfmpegProgressParser());
    }
    
    private List<String> buildYtDlpCommand(DownloadTask task) {
        List<String> command = new ArrayList<>();
        command.add("yt-dlp");
        command.add("-N");
        command.add(String.valueOf(properties.getDownload().getYtdlpConcurrency()));
        
        // Format selection based on quality
        String quality = task.getQuality() != null ? task.getQuality() : properties.getDownload().getDefaultQuality();
        String formatSelector;
        
        if (quality.matches("\\d+")) {
            // Specific height (e.g., "720", "1080")
            formatSelector = String.format(
                    "bestvideo[height<=%s]+bestaudio/best[height<=%s]",
                    quality, quality
            );
        } else {
            // "best" or "worst"
            formatSelector = "bestvideo+bestaudio/best";
        }
        
        command.add("-f");
        command.add(formatSelector);
        
        command.add("--merge-output-format");
        command.add("mp4");
        
        command.add("--referer");
        command.add(properties.getExtractor().getBaseUrl());
        
        command.add("--add-header");
        command.add("Accept: */*");
        
        command.add("-o");
        command.add(task.getOutputPath());
        
        command.add("--newline");
        command.add("--no-warnings");

        command.add("--progress-template");
        command.add("download:PROGRESS:%(progress._percent_str)s");

        command.add(task.getPlaylistUrl());
        
        return command;
    }
    
    private List<String> buildFfmpegCommand(DownloadTask task) {
        List<String> command = new ArrayList<>();
        command.add("ffmpeg");
        command.add("-i");
        command.add(task.getPlaylistUrl());
        command.add("-c");
        command.add("copy");
        command.add("-bsf:a");
        command.add("aac_adtstoasc");
        command.add("-y"); // Overwrite
        command.add(task.getOutputPath());
        
        return command;
    }
    
    private boolean executeCommand(List<String> command, DownloadTask task,
                                   Consumer<ProgressUpdate> progressCallback,
                                   ProgressParser parser) {
        Process process = null;
        try {
            log.info("Executing command: {}", String.join(" ", command));

            ProcessBuilder processBuilder = new ProcessBuilder(command);
            processBuilder.redirectErrorStream(true);

            process = processBuilder.start();

            // Track the process
            runningProcesses.put(task.getId(), process);

            // Read output and parse progress
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {

                String line;
                while ((line = reader.readLine()) != null && process.isAlive()) {
                    // Check if task was cancelled
                    if (task.getStatus() == DownloadStatus.CANCELLED) {
                        log.info("Task {} cancelled, stopping process", task.getId());
                        process.descendants().forEach(ProcessHandle::destroyForcibly);
                        process.destroyForcibly();
                        progressCallback.accept(ProgressUpdate.builder()
                                .taskId(task.getId())
                                .status(DownloadStatus.CANCELLED)
                                .progress(task.getProgress())
                                .message("Download cancelled")
                                .build());
                        return false;
                    }

                    ProgressUpdate update = parser.parseLine(line, task.getId());
                    if (update != null) {
                        progressCallback.accept(update);
                    } else {
                        log.debug("Download output: {}", line);
                    }
                }
            }

            // Check if cancelled after loop exits
            if (task.getStatus() == DownloadStatus.CANCELLED || !process.isAlive()) {
                if (task.getStatus() == DownloadStatus.CANCELLED) {
                    progressCallback.accept(ProgressUpdate.builder()
                            .taskId(task.getId())
                            .status(DownloadStatus.CANCELLED)
                            .progress(task.getProgress())
                            .message("Download cancelled")
                            .build());
                }
                return false;
            }

            boolean completed = process.waitFor(2, TimeUnit.HOURS);

            if (!completed) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                return false;
            }

            int exitCode = process.exitValue();

            if (exitCode == 0) {
                // Verify file was created
                if (Files.exists(Paths.get(task.getOutputPath()))) {
                    progressCallback.accept(ProgressUpdate.builder()
                            .taskId(task.getId())
                            .status(DownloadStatus.COMPLETED)
                            .progress(100.0)
                            .message("Download completed")
                            .build());
                    return true;
                }
            }

            log.error("Download process exited with code: {} for task: {}", exitCode, task.getDisplayName());
            progressCallback.accept(ProgressUpdate.error(task.getId(),
                    "Download failed with exit code " + exitCode + ". Check logs for details."));
            return false;

        } catch (IOException | InterruptedException e) {
            log.error("Error executing download command: {}", e.getMessage());
            Thread.currentThread().interrupt();
            return false;
        } finally {
            // Remove from tracking
            if (process != null) {
                runningProcesses.remove(task.getId());
            }
        }
    }
    
    private boolean isCommandAvailable(String command) {
        try {
            Process process = new ProcessBuilder(command, "--version")
                    .redirectErrorStream(true)
                    .start();
            
            boolean completed = process.waitFor(5, TimeUnit.SECONDS);
            
            if (!completed) {
                process.destroyForcibly();
                return false;
            }
            
            return process.exitValue() == 0;
            
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }
    
    /**
     * Interface for parsing progress from command output
     */
    private interface ProgressParser {
        ProgressUpdate parseLine(String line, String taskId);
    }
    
    /**
     * Parse yt-dlp progress output
     * Note: yt-dlp uses ffmpeg internally for HLS, so we need to parse ffmpeg-style output
     */
    private static class YtDlpProgressParser implements ProgressParser {

        private Double totalDuration = null;

        @Override
        public ProgressUpdate parseLine(String line, String taskId) {
            if (line == null || line.isBlank()) {
                return null;
            }

            // Try progress template format first (for non-HLS downloads)
            Matcher progressMatcher = YTDLP_PROGRESS_PATTERN.matcher(line);
            if (progressMatcher.find()) {
                try {
                    double progress = Double.parseDouble(progressMatcher.group(1));

                    return ProgressUpdate.builder()
                            .taskId(taskId)
                            .status(DownloadStatus.DOWNLOADING)
                            .progress(progress)
                            .build();

                } catch (NumberFormatException e) {
                    log.debug("Failed to parse progress from line: {}", line, e);
                    return null;
                }
            }

            // Parse ffmpeg-style output (for HLS downloads)
            // Extract total duration
            if (totalDuration == null && line.contains("Duration:")) {
                Matcher durationMatcher = FFMPEG_DURATION_PATTERN.matcher(line);
                if (durationMatcher.find()) {
                    totalDuration = parseTime(
                            durationMatcher.group(1),
                            durationMatcher.group(2),
                            durationMatcher.group(3)
                    );
                    log.debug("Parsed duration: {} seconds", totalDuration);
                }
            }

            // Parse progress from ffmpeg output
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
