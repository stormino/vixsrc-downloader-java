package com.github.stormino.model;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Builder
public class DownloadSubTask {

    @Builder.Default
    private String id = UUID.randomUUID().toString();

    private String parentTaskId;
    private SubTaskType type;
    private String language;  // null for video tracks
    private String title;  // Human-readable track name (e.g., "English", "Italiano")

    // Track metadata
    private String codec;
    private String resolution;  // For video: "1920x1080"
    private Long bitrate;

    private String tempFilePath;  // Where track is downloaded
    private String playlistUrl;  // Playlist URL for this language

    @Builder.Default
    private volatile DownloadStatus status = DownloadStatus.QUEUED;

    @Builder.Default
    private volatile Double progress = 0.0;

    private String downloadSpeed;  // Human readable: "5.2 MB/s"
    private Long downloadedBytes;
    private Long totalBytes;
    private Long etaSeconds;  // Estimated time remaining in seconds

    private String errorMessage;

    private LocalDateTime startedAt;
    private LocalDateTime completedAt;

    public enum SubTaskType {
        VIDEO("Video"),
        AUDIO("Audio"),
        SUBTITLE("Subtitle");

        private final String displayName;

        SubTaskType(String displayName) {
            this.displayName = displayName;
        }

        public String getDisplayName() {
            return displayName;
        }
    }

    public String getDisplayName() {
        if (type == SubTaskType.VIDEO) {
            return resolution != null ? String.format("Video (%s)", resolution) : "Video";
        } else if (type == SubTaskType.AUDIO) {
            // Audio track with language
            String langDisplay = language != null ? language.toUpperCase() : "unknown";
            return String.format("Audio - %s", langDisplay);
        } else {
            // Subtitle track with language
            String langDisplay = language != null ? language.toUpperCase() : "unknown";
            return String.format("Subtitle - %s", langDisplay);
        }
    }

    public boolean isCompleted() {
        return status == DownloadStatus.COMPLETED;
    }

    public boolean isFailed() {
        return status == DownloadStatus.FAILED;
    }

    public boolean isNotFound() {
        return status == DownloadStatus.NOT_FOUND;
    }

    public boolean isActive() {
        return status == DownloadStatus.DOWNLOADING || status == DownloadStatus.EXTRACTING;
    }
}
