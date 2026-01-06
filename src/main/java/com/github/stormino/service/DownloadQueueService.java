package com.github.stormino.service;

import com.github.stormino.config.VixSrcProperties;
import com.github.stormino.model.ContentMetadata;
import com.github.stormino.model.DownloadStatus;
import com.github.stormino.model.DownloadTask;
import com.github.stormino.model.PlaylistInfo;
import com.github.stormino.model.ProgressUpdate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class DownloadQueueService {
    
    private final VixSrcExtractorService extractorService;
    private final TmdbMetadataService metadataService;
    private final DownloadExecutorService executorService;
    private final TrackDownloadOrchestrator trackOrchestrator;
    private final ProgressBroadcastService progressBroadcast;
    private final VixSrcProperties properties;
    
    private final ConcurrentHashMap<String, DownloadTask> tasks = new ConcurrentHashMap<>();
    private final Queue<DownloadTask> queue = new LinkedList<>();
    
    /**
     * Add a new download task
     */
    public DownloadTask addDownload(int tmdbId, DownloadTask.ContentType contentType,
                                   Integer season, Integer episode,
                                   List<String> languages, String quality) {
        
        // Fetch metadata
        ContentMetadata metadata = null;
        if (contentType == DownloadTask.ContentType.TV && season != null && episode != null) {
            metadata = metadataService.getTvMetadata(tmdbId, season, episode).orElse(null);
        } else if (contentType == DownloadTask.ContentType.MOVIE) {
            metadata = metadataService.getMovieMetadata(tmdbId).orElse(null);
        }
        
        // Build task
        DownloadTask.DownloadTaskBuilder taskBuilder = DownloadTask.builder()
                .contentType(contentType)
                .tmdbId(tmdbId)
                .season(season)
                .episode(episode)
                .languages(languages != null ? languages : properties.getDownload().getDefaultLanguageList())
                .quality(quality != null ? quality : properties.getDownload().getDefaultQuality());
        
        if (metadata != null) {
            taskBuilder
                    .title(metadata.getTitle())
                    .episodeName(metadata.getEpisodeName())
                    .year(metadata.getYear());
        }
        
        DownloadTask task = taskBuilder.build();
        
        // Generate output path
        String outputPath = generateOutputPath(task, metadata);
        task.setOutputPath(outputPath);
        
        // Store and queue
        tasks.put(task.getId(), task);
        synchronized (queue) {
            queue.offer(task);
        }
        
        log.info("Added download task: {} [{}]", task.getDisplayName(), task.getId());
        
        // Start processing
        processQueue();
        
        return task;
    }
    
    /**
     * Get all tasks
     */
    public List<DownloadTask> getAllTasks() {
        return new ArrayList<>(tasks.values());
    }
    
    /**
     * Get task by ID
     */
    public Optional<DownloadTask> getTask(String taskId) {
        return Optional.ofNullable(tasks.get(taskId));
    }
    
    /**
     * Cancel a task
     */
    public boolean cancelTask(String taskId) {
        DownloadTask task = tasks.get(taskId);
        if (task != null && !task.isCompleted()) {
            task.setStatus(DownloadStatus.CANCELLED);
            synchronized (queue) {
                queue.remove(task);
            }

            // Kill the running process if it exists
            executorService.cancelDownload(taskId);

            // Broadcast cancellation
            progressBroadcast.broadcastProgress(ProgressUpdate.builder()
                    .taskId(taskId)
                    .status(DownloadStatus.CANCELLED)
                    .progress(task.getProgress())
                    .downloadedBytes(task.getAggregatedDownloadedBytes())
                    .totalBytes(task.getAggregatedTotalBytes())
                    .downloadSpeed(null)
                    .etaSeconds(null)
                    .message("Download cancelled by user")
                    .build());

            log.info("Cancelled task: {}", task.getDisplayName());
            return true;
        }
        return false;
    }
    
    /**
     * Process download queue asynchronously
     */
    @Async("downloadExecutor")
    public void processQueue() {
        DownloadTask task;
        
        synchronized (queue) {
            task = queue.poll();
        }
        
        if (task == null) {
            return;
        }
        
        processTask(task);
    }
    
    private void processTask(DownloadTask task) {
        log.info("Processing task: {} [{}]", task.getDisplayName(), task.getId());
        
        try {
            // Update status
            updateTaskStatus(task, DownloadStatus.EXTRACTING, 0.0, "Extracting playlist URL");
            
            // Extract playlist URL for primary language
            String primaryLang = task.getLanguages().get(0);
            Optional<PlaylistInfo> playlistInfo;
            
            if (task.getContentType() == DownloadTask.ContentType.TV) {
                playlistInfo = extractorService.getTvPlaylist(
                        task.getTmdbId(), task.getSeason(), task.getEpisode(), primaryLang);
            } else {
                playlistInfo = extractorService.getMoviePlaylist(task.getTmdbId(), primaryLang);
            }
            
            if (playlistInfo.isEmpty()) {
                updateTaskStatus(task, DownloadStatus.FAILED, 0.0, "Failed to extract playlist URL");
                return;
            }
            
            task.setPlaylistUrl(playlistInfo.get().getUrl());

            // Always use track orchestrator for concurrent downloads
            downloadWithTracks(task);
            
        } catch (Exception e) {
            log.error("Error processing task {}: {}", task.getId(), e.getMessage(), e);
            updateTaskStatus(task, DownloadStatus.FAILED, 0.0, "Error: " + e.getMessage());
        } finally {
            // Process next task
            processQueue();
        }
    }
    
    private void downloadWithTracks(DownloadTask task) {
        updateTaskStatus(task, DownloadStatus.DOWNLOADING, 0.0, "Starting download");

        task.setStartedAt(LocalDateTime.now());

        // Use track orchestrator for concurrent video/audio downloads
        boolean success = trackOrchestrator.downloadWithTracks(task);

        if (success) {
            task.setCompletedAt(LocalDateTime.now());
            task.setDownloadSpeed(null);
            task.setEtaSeconds(null);
            updateTaskStatus(task, DownloadStatus.COMPLETED, 100.0, "Download completed");
        } else {
            task.setDownloadSpeed(null);
            task.setEtaSeconds(null);
            updateTaskStatus(task, DownloadStatus.FAILED, task.getProgress(),
                    task.getErrorMessage() != null ? task.getErrorMessage() : "Download failed");
        }
    }
    
    private void updateTaskStatus(DownloadTask task, DownloadStatus status, Double progress, String message) {
        task.setStatus(status);
        task.setProgress(progress);

        ProgressUpdate update = ProgressUpdate.builder()
                .taskId(task.getId())
                .status(status)
                .progress(progress)
                .downloadedBytes(task.getAggregatedDownloadedBytes())
                .totalBytes(task.getAggregatedTotalBytes())
                .downloadSpeed(task.getAggregatedDownloadSpeed())
                .etaSeconds(task.getAggregatedEtaSeconds())
                .message(message)
                .build();

        progressBroadcast.broadcastProgress(update);
    }
    
    private String generateOutputPath(DownloadTask task, ContentMetadata metadata) {
        String basePath = properties.getDownload().getBasePath();
        String filename;
        
        if (metadata != null) {
            filename = metadata.generateFilename(
                    task.getLanguages().get(0), 
                    "mp4"
            );
        } else {
            // Fallback filename
            if (task.getContentType() == DownloadTask.ContentType.TV) {
                filename = String.format("tv_%d_s%02de%02d.mp4",
                        task.getTmdbId(), task.getSeason(), task.getEpisode());
            } else {
                filename = String.format("movie_%d.mp4", task.getTmdbId());
            }
        }
        
        // Create directory structure for TV shows
        String outputPath;
        if (task.getContentType() == DownloadTask.ContentType.TV && metadata != null) {
            String showDir = sanitizeFilename(metadata.getTitle());
            String seasonDir = String.format("Season %02d", task.getSeason());
            outputPath = Paths.get(basePath, showDir, seasonDir, filename).toString();
            
            // Create directories
            try {
                Files.createDirectories(Paths.get(basePath, showDir, seasonDir));
            } catch (IOException e) {
                log.error("Failed to create directories: {}", e.getMessage());
            }
        } else {
            outputPath = Paths.get(basePath, filename).toString();
        }
        
        return outputPath;
    }
    
    private String sanitizeFilename(String filename) {
        return filename
                .replaceAll("[<>:\"/\\\\|?*]", "")
                .replaceAll("\\s+", ".")
                .trim();
    }
}
