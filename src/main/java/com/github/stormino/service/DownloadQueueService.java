package com.github.stormino.service;

import com.github.stormino.config.VixSrcProperties;
import com.github.stormino.model.ContentMetadata;
import com.github.stormino.model.DownloadStatus;
import com.github.stormino.model.DownloadTask;
import com.github.stormino.model.PlaylistInfo;
import com.github.stormino.model.ProgressUpdate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
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
public class DownloadQueueService {

    private final VixSrcExtractorService extractorService;
    private final TmdbMetadataService metadataService;
    private final DownloadExecutorService executorService;
    private final TrackDownloadOrchestrator trackOrchestrator;
    private final ProgressBroadcastService progressBroadcast;
    private final VixSrcProperties properties;

    @Lazy
    @Autowired
    private DownloadQueueService self;

    private final ConcurrentHashMap<String, DownloadTask> tasks = new ConcurrentHashMap<>();
    private final Queue<DownloadTask> queue = new LinkedList<>();

    public DownloadQueueService(VixSrcExtractorService extractorService,
                                TmdbMetadataService metadataService,
                                DownloadExecutorService executorService,
                                TrackDownloadOrchestrator trackOrchestrator,
                                ProgressBroadcastService progressBroadcast,
                                VixSrcProperties properties) {
        this.extractorService = extractorService;
        this.metadataService = metadataService;
        this.executorService = executorService;
        this.trackOrchestrator = trackOrchestrator;
        this.progressBroadcast = progressBroadcast;
        this.properties = properties;
    }
    
    /**
     * Add a new download task
     */
    public DownloadTask addDownload(int tmdbId, DownloadTask.ContentType contentType,
                                   Integer season, Integer episode,
                                   List<String> languages, String quality) {

        // Handle batch downloads for TV shows
        if (contentType == DownloadTask.ContentType.TV) {
            if (season == null && episode == null) {
                // Download entire show
                return addEntireShowDownload(tmdbId, languages, quality);
            } else if (season != null && episode == null) {
                // Download entire season
                return addEntireSeasonDownload(tmdbId, season, languages, quality);
            }
        }

        // Single episode or movie download
        return addSingleDownload(tmdbId, contentType, season, episode, languages, quality);
    }

    /**
     * Add a single download task (movie or specific TV episode)
     */
    private DownloadTask addSingleDownload(int tmdbId, DownloadTask.ContentType contentType,
                                          Integer season, Integer episode,
                                          List<String> languages, String quality) {
        return addSingleDownload(tmdbId, contentType, season, episode, languages, quality, true);
    }

    /**
     * Add a single download task with option to defer processing
     */
    private DownloadTask addSingleDownload(int tmdbId, DownloadTask.ContentType contentType,
                                          Integer season, Integer episode,
                                          List<String> languages, String quality,
                                          boolean startProcessing) {

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

        // Start processing only if requested
        if (startProcessing) {
            processQueue();
        }

        return task;
    }

    /**
     * Add download tasks for entire show (all seasons and episodes)
     */
    private DownloadTask addEntireShowDownload(int tmdbId, List<String> languages, String quality) {
        log.info("Queueing entire show download for TMDB ID: {}", tmdbId);

        // Fetch show metadata once
        var showMetadata = metadataService.getTvShowMetadata(tmdbId).orElse(null);
        String showTitle = showMetadata != null ? showMetadata.getTitle() : null;
        Integer year = showMetadata != null ? showMetadata.getYear() : null;

        var seasons = metadataService.getSeasons(tmdbId);
        int totalEpisodes = 0;
        DownloadTask firstTask = null;

        // Queue all episodes without fetching individual metadata
        for (var season : seasons) {
            var episodes = metadataService.getEpisodes(tmdbId, season.season_number);
            for (var episode : episodes) {
                DownloadTask task = createTaskWithoutMetadataFetch(
                    tmdbId, season.season_number, episode.episode_number,
                    showTitle, episode.name, year, languages, quality);
                if (firstTask == null) {
                    firstTask = task;
                }
                totalEpisodes++;

                // Broadcast queued status every 10 episodes for UI responsiveness
                if (totalEpisodes % 10 == 0) {
                    broadcastQueuedStatus(task);
                }
            }
        }

        // Broadcast final queued tasks
        tasks.values().stream()
            .filter(t -> t.getStatus() == DownloadStatus.QUEUED)
            .forEach(this::broadcastQueuedStatus);

        log.info("Queued {} episodes from {} seasons", totalEpisodes, seasons.size());

        // Start processing once for all queued episodes
        processQueue();

        return firstTask;
    }

    /**
     * Add download tasks for entire season
     */
    private DownloadTask addEntireSeasonDownload(int tmdbId, int season, List<String> languages, String quality) {
        log.info("Queueing entire season download for TMDB ID: {}, Season: {}", tmdbId, season);

        // Fetch show metadata once
        var showMetadata = metadataService.getTvShowMetadata(tmdbId).orElse(null);
        String showTitle = showMetadata != null ? showMetadata.getTitle() : null;
        Integer year = showMetadata != null ? showMetadata.getYear() : null;

        var episodes = metadataService.getEpisodes(tmdbId, season);
        DownloadTask firstTask = null;

        // Queue all episodes without fetching individual metadata
        int episodeCount = 0;
        for (var episode : episodes) {
            DownloadTask task = createTaskWithoutMetadataFetch(
                tmdbId, season, episode.episode_number,
                showTitle, episode.name, year, languages, quality);
            if (firstTask == null) {
                firstTask = task;
            }
            episodeCount++;

            // Broadcast queued status every 5 episodes for UI responsiveness
            if (episodeCount % 5 == 0) {
                broadcastQueuedStatus(task);
            }
        }

        // Broadcast final queued tasks
        tasks.values().stream()
            .filter(t -> t.getStatus() == DownloadStatus.QUEUED)
            .forEach(this::broadcastQueuedStatus);

        log.info("Queued {} episodes for season {}", episodes.size(), season);

        // Start processing once for all queued episodes
        processQueue();

        return firstTask;
    }

    /**
     * Broadcast queued status to update UI
     */
    private void broadcastQueuedStatus(DownloadTask task) {
        progressBroadcast.broadcastProgress(ProgressUpdate.builder()
                .taskId(task.getId())
                .status(DownloadStatus.QUEUED)
                .progress(0.0)
                .message("Queued")
                .build());
    }

    /**
     * Create task directly without individual metadata fetch
     */
    private DownloadTask createTaskWithoutMetadataFetch(int tmdbId, int season, int episode,
                                                        String title, String episodeName, Integer year,
                                                        List<String> languages, String quality) {
        // Build task
        DownloadTask task = DownloadTask.builder()
                .contentType(DownloadTask.ContentType.TV)
                .tmdbId(tmdbId)
                .season(season)
                .episode(episode)
                .title(title)
                .episodeName(episodeName)
                .year(year)
                .languages(languages != null ? languages : properties.getDownload().getDefaultLanguageList())
                .quality(quality != null ? quality : properties.getDownload().getDefaultQuality())
                .build();

        // Generate output path
        String outputPath = generateOutputPathFast(task);
        task.setOutputPath(outputPath);

        // Store and queue
        tasks.put(task.getId(), task);
        synchronized (queue) {
            queue.offer(task);
        }

        return task;
    }

    /**
     * Fast path generation without metadata object
     */
    private String generateOutputPathFast(DownloadTask task) {
        String basePath = properties.getDownload().getBasePath();
        String filename;

        if (task.getContentType() == DownloadTask.ContentType.TV && task.getTitle() != null) {
            String episodePart = task.getEpisodeName() != null ?
                " - " + task.getEpisodeName() : "";
            filename = String.format("%s - S%02dE%02d%s.mp4",
                task.getTitle(), task.getSeason(), task.getEpisode(), episodePart);
            filename = sanitizeFilename(filename);

            String showDir = sanitizeFilename(task.getTitle());
            String seasonDir = String.format("Season %02d", task.getSeason());

            try {
                java.nio.file.Files.createDirectories(
                    java.nio.file.Paths.get(basePath, showDir, seasonDir));
            } catch (IOException e) {
                log.error("Failed to create directories: {}", e.getMessage());
            }

            return java.nio.file.Paths.get(basePath, showDir, seasonDir, filename).toString();
        } else {
            filename = String.format("tv_%d_s%02de%02d.mp4",
                task.getTmdbId(), task.getSeason(), task.getEpisode());
            return java.nio.file.Paths.get(basePath, filename).toString();
        }
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
     * Process download queue - triggers async task processing
     */
    public synchronized void processQueue() {
        int maxParallel = properties.getDownload().getParallelDownloads();
        long activeCount = tasks.values().stream()
                .filter(t -> t.getStatus() == DownloadStatus.DOWNLOADING ||
                            t.getStatus() == DownloadStatus.EXTRACTING)
                .count();

        // Start new tasks if slots available
        int toStart = (int) (maxParallel - activeCount);
        for (int i = 0; i < toStart; i++) {
            DownloadTask task = queue.poll();
            if (task == null) {
                break;
            }
            self.processTaskAsync(task);
        }
    }

    @Async("downloadExecutor")
    public void processTaskAsync(DownloadTask task) {
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
            // Trigger queue processing for next task
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
