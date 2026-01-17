package com.github.stormino.service;

import com.github.stormino.config.VixSrcProperties;
import com.github.stormino.model.DownloadResult;
import com.github.stormino.model.DownloadStatus;
import com.github.stormino.model.DownloadSubTask;
import com.github.stormino.model.DownloadTask;
import com.github.stormino.model.PlaylistInfo;
import com.github.stormino.model.ProgressUpdate;
import com.github.stormino.service.command.FfmpegCommandBuilder;
import com.github.stormino.util.DownloadConstants;
import com.github.stormino.util.TempFileManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

@Service
@RequiredArgsConstructor
@Slf4j
public class TrackDownloadOrchestrator {

    private final VixSrcExtractorService extractorService;
    private final ProgressBroadcastService progressBroadcast;
    private final VixSrcProperties properties;
    private final VideoTrackDownloadStrategy videoStrategy;
    private final AudioTrackDownloadStrategy audioStrategy;
    private final SubtitleTrackDownloadStrategy subtitleStrategy;
    private final DownloadExecutorService executorService;
    private final FfmpegCommandBuilder commandBuilder;

    @Qualifier("trackExecutor")
    private final Executor trackExecutor;

    /**
     * Download video and audio tracks separately, then merge
     */
    public boolean downloadWithTracks(DownloadTask task) {
        log.debug("Starting concurrent track download for: {}", task.getDisplayName());

        // Use TempFileManager for automatic cleanup on success or failure
        try (TempFileManager tempFileManager = new TempFileManager()) {
            // 1. Create temp directory
            Path tempDir;
            try {
                tempDir = createTempDirectory(task);
                tempFileManager.registerTempDirectory(tempDir);
            } catch (IOException e) {
                log.error("Failed to create temp directory for task {}: {}", task.getId(), e.getMessage(), e);
                task.setErrorMessage("Failed to create temp directory: " + e.getMessage());
                return false;
            }

            // 2. Get languages
            List<String> languages = task.getLanguages() != null && !task.getLanguages().isEmpty()
                    ? task.getLanguages()
                    : properties.getDownload().getDefaultLanguageList();

            // 3. Initialize sub-tasks: 1 video + N audio tracks + N subtitle tracks
            List<DownloadSubTask> subTasks = initializeTrackSubTasks(task, languages);
            task.setSubTasks(subTasks);

            // Broadcast initial tree structure
            broadcastTaskStructure(task);

            // 4. Download tracks concurrently (1 video + N audio + N subtitle)
            List<CompletableFuture<Boolean>> downloadFutures = subTasks.stream()
                    .map(subTask -> downloadTrackAsync(task, subTask, tempDir, languages.get(0)))
                    .toList();

            // 5. Wait for all downloads
            try {
                CompletableFuture.allOf(downloadFutures.toArray(new CompletableFuture[0]))
                        .get(DownloadConstants.MAX_DOWNLOAD_TIMEOUT_HOURS, TimeUnit.HOURS);

                // Check critical failures (video or all audio tracks failed)
                boolean videoFailed = subTasks.stream()
                        .filter(st -> st.getType() == DownloadSubTask.SubTaskType.VIDEO)
                        .anyMatch(st -> st.getStatus() == DownloadStatus.FAILED);

                long successfulAudioTracks = subTasks.stream()
                        .filter(st -> st.getType() == DownloadSubTask.SubTaskType.AUDIO)
                        .filter(st -> st.getStatus() == DownloadStatus.COMPLETED)
                        .count();

                long failedAudioTracks = subTasks.stream()
                        .filter(st -> st.getType() == DownloadSubTask.SubTaskType.AUDIO)
                        .filter(st -> st.getStatus() == DownloadStatus.FAILED)
                        .count();

                if (videoFailed) {
                    log.error("Video track download failed");
                    task.setErrorMessage("Video track failed to download");
                    task.setStatus(DownloadStatus.FAILED);
                    broadcastParentUpdate(task);
                    return false;
                }

                // Only fail if audio tracks actually failed (not just not found)
                // If all audio tracks are NOT_FOUND, assume audio is embedded in video
                if (successfulAudioTracks == 0 && failedAudioTracks > 0) {
                    log.error("All audio track downloads failed");
                    task.setErrorMessage("No audio tracks downloaded successfully");
                    task.setStatus(DownloadStatus.FAILED);
                    broadcastParentUpdate(task);
                    return false;
                }

                if (successfulAudioTracks == 0) {
                    log.debug("No separate audio tracks available (audio may be embedded in video)");
                }

                // Log subtitle failures (non-critical)
                long failedSubtitles = subTasks.stream()
                        .filter(st -> st.getType() == DownloadSubTask.SubTaskType.SUBTITLE)
                        .filter(st -> st.getStatus() == DownloadStatus.FAILED)
                        .count();

                if (failedSubtitles > 0) {
                    log.warn("{} subtitle track(s) failed to download (continuing anyway)", failedSubtitles);
                }

            } catch (Exception e) {
                log.error("Download error: {}", e.getMessage(), e);
                task.setErrorMessage("Download error: " + e.getMessage());
                task.setStatus(DownloadStatus.FAILED);
                broadcastParentUpdate(task);
                return false;
            }

            // 6. Merge tracks
            boolean mergeSuccess = mergeTracks(task, subTasks, tempDir);
            if (!mergeSuccess) {
                return false;
            }

            // 7. Mark as completed
            task.setStatus(DownloadStatus.COMPLETED);
            task.setProgress(100.0);
            task.setCompletedAt(LocalDateTime.now());
            task.setDownloadSpeed(null);
            task.setEtaSeconds(null);
            task.setBitrate(null);
            broadcastParentUpdate(task);
            log.info("Download completed successfully: {}", task.getDisplayName());

            // 8. Cleanup happens automatically via try-with-resources
            return true;
        }
    }

    private List<DownloadSubTask> initializeTrackSubTasks(DownloadTask task, List<String> languages) {
        List<DownloadSubTask> subTasks = new ArrayList<>();

        // Create ONE video track sub-task
        DownloadSubTask videoTask = DownloadSubTask.builder()
                .parentTaskId(task.getId())
                .type(DownloadSubTask.SubTaskType.VIDEO)
                .language(null)
                .resolution(task.getQuality())
                .build();
        subTasks.add(videoTask);

        // Create audio track sub-tasks (one per language)
        for (String language : languages) {
            DownloadSubTask audioTask = DownloadSubTask.builder()
                    .parentTaskId(task.getId())
                    .type(DownloadSubTask.SubTaskType.AUDIO)
                    .language(language)
                    .build();
            subTasks.add(audioTask);
        }

        // Create subtitle track sub-tasks (one per language)
        for (String language : languages) {
            DownloadSubTask subtitleTask = DownloadSubTask.builder()
                    .parentTaskId(task.getId())
                    .type(DownloadSubTask.SubTaskType.SUBTITLE)
                    .language(language)
                    .build();
            subTasks.add(subtitleTask);
        }

        log.debug("Initialized {} track sub-tasks: 1 video + {} audio + {} subtitle",
                subTasks.size(), languages.size(), languages.size());
        return subTasks;
    }

    private CompletableFuture<Boolean> downloadTrackAsync(DownloadTask task, DownloadSubTask subTask,
                                                          Path tempDir, String primaryLanguage) {
        return CompletableFuture.supplyAsync(() -> {
            subTask.setStartedAt(LocalDateTime.now());
            subTask.setStatus(DownloadStatus.DOWNLOADING);
            broadcastSubTaskUpdate(task, subTask);

            boolean isVideo = subTask.getType() == DownloadSubTask.SubTaskType.VIDEO;
            boolean isAudio = subTask.getType() == DownloadSubTask.SubTaskType.AUDIO;
            String language = isVideo ? primaryLanguage : subTask.getLanguage();

            log.debug("Starting download for track: {}", subTask.getDisplayName());

            // Build embed URL (referer)
            String embedUrl;
            if (task.getContentType() == DownloadTask.ContentType.TV) {
                embedUrl = String.format("%s/tv/%d/%d/%d?lang=%s",
                        properties.getExtractor().getBaseUrl(),
                        task.getTmdbId(), task.getSeason(), task.getEpisode(), language);
            } else {
                embedUrl = String.format("%s/movie/%d?lang=%s",
                        properties.getExtractor().getBaseUrl(),
                        task.getTmdbId(), language);
            }

            // Get playlist URL
            Optional<PlaylistInfo> playlistInfo;
            if (task.getContentType() == DownloadTask.ContentType.TV) {
                playlistInfo = extractorService.getTvPlaylist(
                        task.getTmdbId(), task.getSeason(), task.getEpisode(), language);
            } else {
                playlistInfo = extractorService.getMoviePlaylist(task.getTmdbId(), language);
            }

            if (playlistInfo.isEmpty()) {
                log.error("Failed to get playlist for {}", subTask.getDisplayName());
                subTask.setStatus(DownloadStatus.FAILED);
                subTask.setErrorMessage("Failed to get playlist URL");
                broadcastSubTaskUpdate(task, subTask);
                return false;
            }

            String playlistUrl = playlistInfo.get().getUrl();

            // Set output path
            Path outputPath;
            if (isVideo) {
                outputPath = tempDir.resolve("video.mp4");
            } else if (isAudio) {
                outputPath = tempDir.resolve("audio_" + language + ".m4a");
            } else {
                outputPath = tempDir.resolve("subtitle_" + language + ".vtt");
            }
            subTask.setTempFilePath(outputPath.toString());

            // Download using appropriate strategy
            DownloadResult result;
            int maxConcurrent = properties.getDownload().getSegmentConcurrency();

            if (isVideo) {
                result = videoStrategy.downloadVideoTrack(
                        playlistUrl,
                        embedUrl,
                        outputPath,
                        task.getQuality(),
                        maxConcurrent,
                        subTask,
                        task.getId(),
                        update -> progressBroadcast.broadcastProgress(update)
                );
            } else if (isAudio) {
                result = audioStrategy.downloadAudioTrack(
                        playlistUrl,
                        embedUrl,
                        outputPath,
                        language,
                        maxConcurrent,
                        subTask,
                        task.getId(),
                        update -> progressBroadcast.broadcastProgress(update)
                );
            } else {
                // Subtitle track
                result = subtitleStrategy.downloadSubtitleTrack(
                        playlistUrl,
                        embedUrl,
                        outputPath,
                        language,
                        maxConcurrent,
                        subTask,
                        task.getId(),
                        update -> progressBroadcast.broadcastProgress(update)
                );
            }

            if (result.isSuccess()) {
                subTask.setStatus(DownloadStatus.COMPLETED);
                subTask.setProgress(100.0);
                subTask.setCompletedAt(LocalDateTime.now());
                subTask.setDownloadSpeed(null);
                subTask.setEtaSeconds(null);
                broadcastSubTaskUpdate(task, subTask);
                log.debug("Completed download for: {}", subTask.getDisplayName());
                return true;
            } else {
                // Handle different failure statuses
                if (result.getStatus() == DownloadResult.ResultStatus.NOT_FOUND) {
                    // Status already set by strategy
                } else if (subTask.getStatus() != DownloadStatus.NOT_FOUND) {
                    subTask.setStatus(DownloadStatus.FAILED);
                    subTask.setErrorMessage(result.getErrorMessage() != null ? result.getErrorMessage() : "Download failed");
                }
                broadcastSubTaskUpdate(task, subTask);
                log.debug("Download not successful for: {} (status: {}, error: {})",
                        subTask.getDisplayName(), subTask.getStatus(), result.getErrorMessage());
                return false;
            }

        }, trackExecutor);
    }

    private boolean mergeTracks(DownloadTask task, List<DownloadSubTask> subTasks, Path tempDir) {
        log.debug("Merging {} tracks", subTasks.size());

        task.setStatus(DownloadStatus.MERGING);
        task.setProgress(0.0);
        broadcastParentUpdate(task);

        // Find video track
        DownloadSubTask videoTask = subTasks.stream()
                .filter(st -> st.getType() == DownloadSubTask.SubTaskType.VIDEO)
                .findFirst()
                .orElse(null);

        if (videoTask == null || videoTask.getTempFilePath() == null) {
            task.setErrorMessage("No video track found for merging");
            task.setStatus(DownloadStatus.FAILED);
            broadcastParentUpdate(task);
            return false;
        }

        // Get completed audio and subtitle tracks
        List<DownloadSubTask> audioTasks = subTasks.stream()
                .filter(st -> st.getType() == DownloadSubTask.SubTaskType.AUDIO)
                .filter(st -> st.getStatus() == DownloadStatus.COMPLETED)
                .toList();

        List<DownloadSubTask> subtitleTasks = subTasks.stream()
                .filter(st -> st.getType() == DownloadSubTask.SubTaskType.SUBTITLE)
                .filter(st -> st.getStatus() == DownloadStatus.COMPLETED)
                .toList();

        log.debug("Found {} completed audio tracks and {} subtitle tracks for merging",
                audioTasks.size(), subtitleTasks.size());

        // If no audio tracks and no subtitles, just copy the video file
        if (audioTasks.isEmpty() && subtitleTasks.isEmpty()) {
            log.debug("No separate audio or subtitle tracks - copying video file directly");
            try {
                java.nio.file.Files.copy(
                        java.nio.file.Paths.get(videoTask.getTempFilePath()),
                        java.nio.file.Paths.get(task.getOutputPath()),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING
                );
                return true;
            } catch (IOException e) {
                log.error("Failed to copy video file for task {}: {}", task.getId(), e.getMessage(), e);
                task.setErrorMessage("Failed to copy video file: " + e.getMessage());
                task.setStatus(DownloadStatus.FAILED);
                broadcastParentUpdate(task);
                return false;
            }
        }

        // Build merge command using FfmpegCommandBuilder
        List<FfmpegCommandBuilder.AudioTrackInput> audioInputs = audioTasks.stream()
                .map(task1 -> new FfmpegCommandBuilder.AudioTrackInput(
                        Paths.get(task1.getTempFilePath()), task1))
                .toList();

        List<FfmpegCommandBuilder.SubtitleTrackInput> subtitleInputs = subtitleTasks.stream()
                .map(task1 -> new FfmpegCommandBuilder.SubtitleTrackInput(
                        Paths.get(task1.getTempFilePath()), task1))
                .toList();

        List<String> command = commandBuilder.buildMergeCommand(
                Paths.get(videoTask.getTempFilePath()),
                audioInputs,
                subtitleInputs,
                Paths.get(task.getOutputPath())
        );

        DownloadResult result = executorService.mergeTracks(
                command,
                task.getId(),
                update -> progressBroadcast.broadcastProgress(update)
        );

        if (!result.isSuccess()) {
            task.setErrorMessage(result.getErrorMessage() != null ? result.getErrorMessage() : "Failed to merge tracks");
            task.setStatus(DownloadStatus.FAILED);
            broadcastParentUpdate(task);
            return false;
        }

        return true;
    }

    private Path createTempDirectory(DownloadTask task) throws IOException {
        Path baseTempPath = Paths.get(properties.getDownload().getTempPath());
        Files.createDirectories(baseTempPath);
        Path tempDir = baseTempPath.resolve(task.getId());
        Files.createDirectories(tempDir);
        log.debug("Created temp directory: {}", tempDir);
        return tempDir;
    }

    private void broadcastTaskStructure(DownloadTask task) {
        ProgressUpdate update = ProgressUpdate.builder()
                .taskId(task.getId())
                .status(task.getStatus())
                .progress(task.getProgress())
                .build();
        progressBroadcast.broadcastProgress(update);
    }

    private void broadcastParentUpdate(DownloadTask task) {
        ProgressUpdate update = ProgressUpdate.builder()
                .taskId(task.getId())
                .status(task.getStatus())
                .progress(task.getProgress())
                .downloadedBytes(task.getAggregatedDownloadedBytes())
                .totalBytes(task.getAggregatedTotalBytes())
                .downloadSpeed(task.getAggregatedDownloadSpeed())
                .etaSeconds(task.getAggregatedEtaSeconds())
                .message(task.getErrorMessage())
                .build();
        progressBroadcast.broadcastProgress(update);
    }

    private void broadcastSubTaskUpdate(DownloadTask task, DownloadSubTask subTask) {
        ProgressUpdate update = ProgressUpdate.builder()
                .taskId(task.getId())
                .subTaskId(subTask.getId())
                .status(subTask.getStatus())
                .progress(subTask.getProgress())
                .downloadedBytes(subTask.getDownloadedBytes())
                .totalBytes(subTask.getTotalBytes())
                .downloadSpeed(subTask.getDownloadSpeed())
                .etaSeconds(subTask.getEtaSeconds())
                .message(subTask.getErrorMessage())
                .build();
        progressBroadcast.broadcastProgress(update);
    }
}
