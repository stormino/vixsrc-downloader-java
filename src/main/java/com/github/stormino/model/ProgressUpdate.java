package com.github.stormino.model;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class ProgressUpdate {
    
    private String taskId;
    private String subTaskId;  // Optional - null for parent task updates
    private DownloadStatus status;
    private Double progress;
    private String bitrate;
    private Long downloadedBytes;
    private Long totalBytes;
    private String downloadSpeed;  // Human readable: "5.2 MB/s"
    private Long etaSeconds;  // Estimated time remaining in seconds
    private String message;
    private String errorMessage;

    @Builder.Default
    private LocalDateTime timestamp = LocalDateTime.now();
    
    public static ProgressUpdate forTask(DownloadTask task) {
        return ProgressUpdate.builder()
                .taskId(task.getId())
                .status(task.getStatus())
                .progress(task.getAggregatedProgress())
                .bitrate(task.getBitrate())
                .downloadedBytes(task.getAggregatedDownloadedBytes())
                .totalBytes(task.getAggregatedTotalBytes())
                .downloadSpeed(task.getAggregatedDownloadSpeed())
                .etaSeconds(task.getAggregatedEtaSeconds())
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

    public static ProgressUpdate forSubTask(DownloadSubTask subTask, String parentTaskId) {
        return ProgressUpdate.builder()
                .taskId(parentTaskId)
                .subTaskId(subTask.getId())
                .status(subTask.getStatus())
                .progress(subTask.getProgress())
                .bitrate(subTask.getDownloadSpeed())
                .downloadedBytes(subTask.getDownloadedBytes())
                .totalBytes(subTask.getTotalBytes())
                .downloadSpeed(subTask.getDownloadSpeed())
                .etaSeconds(subTask.getEtaSeconds())
                .errorMessage(subTask.getErrorMessage())
                .build();
    }
}
