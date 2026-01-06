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
    private String downloadSpeed;  // Human readable: "5.2 MB/s"
    private Long etaSeconds;  // Estimated time remaining in seconds

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

        // If all subtasks completed, return 100%
        if (allSubTasksCompleted()) {
            return 100.0;
        }

        // Only use subtasks with size information for accurate progress
        List<DownloadSubTask> trackedSubTasks = subTasks.stream()
                .filter(st -> st.getTotalBytes() != null && st.getTotalBytes() > 0)
                .toList();

        if (trackedSubTasks.isEmpty()) {
            // No size info yet - only use progress from actively downloading subtasks
            List<DownloadSubTask> activeSubTasks = subTasks.stream()
                    .filter(st -> st.getStatus() == DownloadStatus.DOWNLOADING)
                    .toList();

            if (activeSubTasks.isEmpty()) {
                return 0.0;  // Nothing downloading yet
            }

            // Average only active subtasks (ignore completed without size info)
            double totalProgress = activeSubTasks.stream()
                    .mapToDouble(st -> st.getProgress() != null ? st.getProgress() : 0.0)
                    .sum();
            // Cap at 100% to handle any edge cases
            return Math.min(100.0, totalProgress / activeSubTasks.size());
        }

        // Calculate size-based weighted progress
        long totalSize = trackedSubTasks.stream()
                .mapToLong(DownloadSubTask::getTotalBytes)
                .sum();

        long totalDownloaded = trackedSubTasks.stream()
                .mapToLong(st -> st.getDownloadedBytes() != null ? st.getDownloadedBytes() : 0L)
                .sum();

        // Cap at 100% (estimated total size may be inaccurate)
        return Math.min(100.0, (totalDownloaded * 100.0) / totalSize);
    }

    public boolean allSubTasksCompleted() {
        return !subTasks.isEmpty() &&
                subTasks.stream().allMatch(st -> st.getStatus() == DownloadStatus.COMPLETED);
    }

    public boolean anySubTaskFailed() {
        return subTasks.stream().anyMatch(st -> st.getStatus() == DownloadStatus.FAILED);
    }

    public Long getAggregatedTotalBytes() {
        if (subTasks.isEmpty()) {
            return totalBytes;
        }

        long total = subTasks.stream()
                .mapToLong(st -> st.getTotalBytes() != null ? st.getTotalBytes() : 0L)
                .sum();

        return total > 0 ? total : null;
    }

    public Long getAggregatedDownloadedBytes() {
        if (subTasks.isEmpty()) {
            return downloadedBytes;
        }

        long downloaded = subTasks.stream()
                .mapToLong(st -> st.getDownloadedBytes() != null ? st.getDownloadedBytes() : 0L)
                .sum();

        return downloaded > 0 ? downloaded : null;
    }

    public Long getAggregatedEtaSeconds() {
        if (subTasks.isEmpty()) {
            return etaSeconds;
        }

        // Calculate ETA based on total remaining bytes and average speed
        Long totalBytesNullable = getAggregatedTotalBytes();
        Long downloadedBytesNullable = getAggregatedDownloadedBytes();

        if (totalBytesNullable == null || downloadedBytesNullable == null || startedAt == null) {
            return null;
        }

        long totalBytes = totalBytesNullable;
        long downloadedBytes = downloadedBytesNullable;
        long remainingBytes = totalBytes - downloadedBytes;

        if (remainingBytes <= 0) {
            return null;
        }

        long elapsedSeconds = java.time.Duration.between(startedAt, LocalDateTime.now()).getSeconds();
        if (elapsedSeconds <= 0) {
            return null;
        }

        double bytesPerSecond = (double) downloadedBytes / elapsedSeconds;
        if (bytesPerSecond <= 0) {
            return null;
        }

        return (long) (remainingBytes / bytesPerSecond);
    }

    public String getAggregatedDownloadSpeed() {
        if (subTasks.isEmpty()) {
            return downloadSpeed;
        }

        if (startedAt == null) {
            return null;
        }

        Long downloadedBytesNullable = getAggregatedDownloadedBytes();
        if (downloadedBytesNullable == null) {
            return null;
        }

        long elapsedSeconds = java.time.Duration.between(startedAt, LocalDateTime.now()).getSeconds();
        if (elapsedSeconds <= 0) {
            return null;
        }

        double bytesPerSecond = (double) downloadedBytesNullable / elapsedSeconds;
        return formatSpeed(bytesPerSecond);
    }

    private static String formatSpeed(double bytesPerSecond) {
        if (bytesPerSecond >= 1_000_000) {
            return String.format("%.2f MB/s", bytesPerSecond / 1_000_000);
        } else if (bytesPerSecond >= 1_000) {
            return String.format("%.2f KB/s", bytesPerSecond / 1_000);
        } else {
            return String.format("%.0f B/s", bytesPerSecond);
        }
    }
}
