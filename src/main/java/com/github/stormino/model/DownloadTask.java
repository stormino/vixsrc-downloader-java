package com.github.stormino.model;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

@Data
@Builder
public class DownloadTask {
    
    @Builder.Default
    private String id = UUID.randomUUID().toString();
    
    private ContentType contentType;
    private Integer tmdbId;
    private Integer season;
    private Integer episode;
    
    private String title;
    private String episodeName;
    private Integer year;
    
    private List<String> languages;
    private String quality;
    
    private String outputPath;
    private String playlistUrl;

    @Builder.Default
    private List<DownloadSubTask> subTasks = new CopyOnWriteArrayList<>();

    @Builder.Default
    private volatile DownloadStatus status = DownloadStatus.QUEUED;

    @Builder.Default
    private volatile Double progress = 0.0;
    
    private String bitrate;
    private Long downloadedBytes;
    private Long totalBytes;
    
    private String errorMessage;
    
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();
    
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
    
    public enum ContentType {
        MOVIE, TV
    }
    
    public String getDisplayName() {
        if (contentType == ContentType.TV && season != null && episode != null) {
            String base = title != null ? title : "TV " + tmdbId;
            String seasonEp = String.format("S%02dE%02d", season, episode);
            if (episodeName != null) {
                return String.format("%s %s - %s", base, seasonEp, episodeName);
            }
            return String.format("%s %s", base, seasonEp);
        } else {
            String base = title != null ? title : "Movie " + tmdbId;
            if (year != null) {
                return String.format("%s (%d)", base, year);
            }
            return base;
        }
    }
    
    public boolean isCompleted() {
        return status == DownloadStatus.COMPLETED;
    }
    
    public boolean isFailed() {
        return status == DownloadStatus.FAILED;
    }
    
    public boolean isActive() {
        return status == DownloadStatus.DOWNLOADING || status == DownloadStatus.EXTRACTING;
    }

    public Double getAggregatedProgress() {
        if (subTasks.isEmpty()) {
            return progress;  // Fallback to parent progress
        }

        double totalProgress = subTasks.stream()
                .mapToDouble(st -> st.getProgress() != null ? st.getProgress() : 0.0)
                .sum();

        return totalProgress / subTasks.size();
    }

    public boolean allSubTasksCompleted() {
        return !subTasks.isEmpty() &&
                subTasks.stream().allMatch(st -> st.getStatus() == DownloadStatus.COMPLETED);
    }

    public boolean anySubTaskFailed() {
        return subTasks.stream().anyMatch(st -> st.getStatus() == DownloadStatus.FAILED);
    }
}
