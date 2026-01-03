package com.github.stormino.model;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class ProgressUpdate {
    
    private String taskId;
    private DownloadStatus status;
    private Double progress;
    private String bitrate;
    private Long downloadedBytes;
    private Long totalBytes;
    private String message;
    private String errorMessage;
    
    @Builder.Default
    private LocalDateTime timestamp = LocalDateTime.now();
    
    public static ProgressUpdate forTask(DownloadTask task) {
        return ProgressUpdate.builder()
                .taskId(task.getId())
                .status(task.getStatus())
                .progress(task.getProgress())
                .bitrate(task.getBitrate())
                .downloadedBytes(task.getDownloadedBytes())
                .totalBytes(task.getTotalBytes())
                .errorMessage(task.getErrorMessage())
                .build();
    }
    
    public static ProgressUpdate error(String taskId, String errorMessage) {
        return ProgressUpdate.builder()
                .taskId(taskId)
                .status(DownloadStatus.FAILED)
                .errorMessage(errorMessage)
                .build();
    }
}
