package com.github.stormino.service;

import com.github.stormino.config.VixSrcProperties;
import com.github.stormino.model.DownloadStatus;
import com.github.stormino.model.DownloadSubTask;
import com.github.stormino.model.DownloadTask;
import com.github.stormino.model.PlaylistInfo;
import com.github.stormino.model.ProgressUpdate;
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

    @Qualifier("trackExecutor")
    private final Executor trackExecutor;

    /**
     * Download video and audio tracks separately, then merge
     */
    public boolean downloadWithTracks(DownloadTask task) {
        log.info("Starting concurrent track download for: {}", task.getDisplayName());

        // 1. Create temp directory
        Path tempDir;
        try {
            tempDir = createTempDirectory(task);
        } catch (IOException e) {
            log.error("Failed to create temp directory: {}", e.getMessage());
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
                    .get(2, TimeUnit.HOURS);

            // Check critical failures (video or all audio tracks failed)
            boolean videoFailed = subTasks.stream()
                    .filter(st -> st.getType() == DownloadSubTask.SubTaskType.VIDEO)
                    .anyMatch(st -> st.getStatus() == DownloadStatus.FAILED);

            long successfulAudioTracks = subTasks.stream()
                    .filter(st -> st.getType() == DownloadSubTask.SubTaskType.AUDIO)
                    .filter(st -> st.getStatus() == DownloadStatus.COMPLETED)
                    .count();

            if (videoFailed) {
                log.error("Video track download failed");
                task.setErrorMessage("Video track failed to download");
                task.setStatus(DownloadStatus.FAILED);
                broadcastParentUpdate(task);
                return false;
            }

            if (successfulAudioTracks == 0) {
                log.error("All audio track downloads failed");
                task.setErrorMessage("No audio tracks downloaded successfully");
                task.setStatus(DownloadStatus.FAILED);
                broadcastParentUpdate(task);
                return false;
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

        // 7. Cleanup
        cleanupTempFiles(tempDir);

        return true;
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

        log.info("Initialized {} track sub-tasks: 1 video + {} audio + {} subtitle",
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

            log.info("Starting download for track: {}", subTask.getDisplayName());

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
            boolean success;
            int maxConcurrent = properties.getDownload().getSegmentConcurrency();

            if (isVideo) {
                success = videoStrategy.downloadVideoTrack(
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
                success = audioStrategy.downloadAudioTrack(
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
                success = subtitleStrategy.downloadSubtitleTrack(
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

            if (success) {
                subTask.setStatus(DownloadStatus.COMPLETED);
                subTask.setProgress(100.0);
                subTask.setCompletedAt(LocalDateTime.now());
                broadcastSubTaskUpdate(task, subTask);
                log.info("Completed download for: {}", subTask.getDisplayName());
                return true;
            } else {
                subTask.setStatus(DownloadStatus.FAILED);
                subTask.setErrorMessage("Download failed");
                broadcastSubTaskUpdate(task, subTask);
                log.error("Failed download for: {}", subTask.getDisplayName());
                return false;
            }

        }, trackExecutor);
    }

    private boolean mergeTracks(DownloadTask task, List<DownloadSubTask> subTasks, Path tempDir) {
        log.info("Merging {} tracks", subTasks.size());

        task.setStatus(DownloadStatus.MERGING);
        task.setProgress(0.0);
        broadcastParentUpdate(task);

        List<String> command = new ArrayList<>();
        command.add("ffmpeg");
        command.add("-hide_banner");
        command.add("-loglevel");
        command.add("warning");

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

        // Video input (ffmpeg already downloaded as proper MP4)
        command.add("-i");
        command.add(videoTask.getTempFilePath());

        // Audio inputs (ffmpeg already downloaded as proper M4A)
        List<DownloadSubTask> audioTasks = subTasks.stream()
                .filter(st -> st.getType() == DownloadSubTask.SubTaskType.AUDIO)
                .filter(st -> st.getStatus() == DownloadStatus.COMPLETED)
                .toList();

        for (DownloadSubTask audioTask : audioTasks) {
            command.add("-i");
            command.add(audioTask.getTempFilePath());
        }

        // Subtitle inputs (WebVTT format)
        List<DownloadSubTask> subtitleTasks = subTasks.stream()
                .filter(st -> st.getType() == DownloadSubTask.SubTaskType.SUBTITLE)
                .filter(st -> st.getStatus() == DownloadStatus.COMPLETED)
                .toList();

        for (DownloadSubTask subtitleTask : subtitleTasks) {
            command.add("-i");
            command.add(subtitleTask.getTempFilePath());
        }

        // Map video
        command.add("-map");
        command.add("0:v:0");

        // Map all audio
        for (int i = 1; i <= audioTasks.size(); i++) {
            command.add("-map");
            command.add(i + ":a:0");
        }

        // Map all subtitles
        int subtitleInputOffset = 1 + audioTasks.size();
        for (int i = 0; i < subtitleTasks.size(); i++) {
            command.add("-map");
            command.add((subtitleInputOffset + i) + ":s:0");
        }

        // Copy video and audio codecs (no re-encoding)
        command.add("-c:v");
        command.add("copy");
        command.add("-c:a");
        command.add("copy");

        // Subtitle codec
        if (!subtitleTasks.isEmpty()) {
            command.add("-c:s");
            command.add("mov_text");  // Convert WebVTT to MP4 compatible format
        }

        // Audio language metadata
        for (int i = 0; i < audioTasks.size(); i++) {
            DownloadSubTask audioTask = audioTasks.get(i);
            if (audioTask.getLanguage() != null) {
                command.add(String.format("-metadata:s:a:%d", i));
                command.add(String.format("language=%s", audioTask.getLanguage()));
            }
        }

        // Subtitle language metadata
        for (int i = 0; i < subtitleTasks.size(); i++) {
            DownloadSubTask subtitleTask = subtitleTasks.get(i);
            if (subtitleTask.getLanguage() != null) {
                command.add(String.format("-metadata:s:s:%d", i));
                command.add(String.format("language=%s", subtitleTask.getLanguage()));
            }
        }

        command.add("-y");
        command.add(task.getOutputPath());

        log.info("Merge command: {}", String.join(" ", command));

        boolean success = executorService.executeMergeCommand(
                command,
                task.getId(),
                update -> progressBroadcast.broadcastProgress(update)
        );

        if (!success) {
            task.setErrorMessage("Failed to merge tracks");
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
        log.info("Created temp directory: {}", tempDir);
        return tempDir;
    }

    private void cleanupTempFiles(Path tempDir) {
        try {
            if (Files.exists(tempDir)) {
                try (Stream<Path> paths = Files.walk(tempDir)) {
                    paths.sorted(Comparator.reverseOrder())
                            .forEach(path -> {
                                try {
                                    Files.delete(path);
                                } catch (IOException e) {
                                    log.warn("Failed to delete: {}", path);
                                }
                            });
                }
                log.info("Cleaned up temp directory: {}", tempDir);
            }
        } catch (IOException e) {
            log.warn("Failed to cleanup temp directory: {}", e.getMessage());
        }
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
                .message(subTask.getErrorMessage())
                .build();
        progressBroadcast.broadcastProgress(update);
    }
}
