package com.github.stormino.service.progress;

import com.github.stormino.model.DownloadStatus;
import com.github.stormino.model.DownloadSubTask;
import com.github.stormino.model.ProgressUpdate;
import lombok.NonNull;
import org.springframework.stereotype.Component;

/**
 * Builder for constructing ProgressUpdate events.
 * Centralizes all progress update construction logic for consistency.
 */
@Component
public class ProgressEventBuilder {

    /**
     * Create a progress update for segment download progress.
     *
     * @param taskId Parent task ID
     * @param subTaskId Sub-task ID
     * @param percentage Progress percentage (0-100)
     * @param currentSegment Current segment being downloaded
     * @param downloadedBytes Bytes downloaded so far
     * @param totalBytes Total bytes to download
     * @param downloadSpeed Download speed (formatted string, e.g., "1.5 MB/s")
     * @param etaSeconds Estimated time to completion in seconds
     * @return ProgressUpdate event
     */
    public ProgressUpdate buildSegmentProgress(
            @NonNull String taskId,
            @NonNull String subTaskId,
            double percentage,
            String currentSegment,
            Long downloadedBytes,
            Long totalBytes,
            String downloadSpeed,
            Long etaSeconds) {

        return ProgressUpdate.builder()
                .taskId(taskId)
                .subTaskId(subTaskId)
                .status(DownloadStatus.DOWNLOADING)
                .progress(percentage)
                .message(currentSegment)
                .downloadedBytes(downloadedBytes)
                .totalBytes(totalBytes)
                .downloadSpeed(downloadSpeed)
                .etaSeconds(etaSeconds)
                .build();
    }

    /**
     * Create a progress update for conversion status.
     *
     * @param taskId Parent task ID
     * @param subTaskId Sub-task ID
     * @param message Conversion message (e.g., "Converting to MP4...")
     * @return ProgressUpdate event
     */
    public ProgressUpdate buildConversionStatus(
            @NonNull String taskId,
            @NonNull String subTaskId,
            @NonNull String message) {

        return ProgressUpdate.builder()
                .taskId(taskId)
                .subTaskId(subTaskId)
                .status(DownloadStatus.DOWNLOADING)
                .progress(100.0)
                .message(message)
                .build();
    }

    /**
     * Create a progress update for track completion.
     *
     * @param taskId Parent task ID
     * @param subTaskId Sub-task ID
     * @param downloadedBytes Final downloaded bytes
     * @param totalBytes Total bytes
     * @return ProgressUpdate event
     */
    public ProgressUpdate buildTrackCompletion(
            @NonNull String taskId,
            @NonNull String subTaskId,
            Long downloadedBytes,
            Long totalBytes) {

        return ProgressUpdate.builder()
                .taskId(taskId)
                .subTaskId(subTaskId)
                .status(DownloadStatus.COMPLETED)
                .progress(100.0)
                .downloadedBytes(downloadedBytes)
                .totalBytes(totalBytes)
                .downloadSpeed(null)
                .etaSeconds(null)
                .build();
    }

    /**
     * Create a progress update for track failure.
     *
     * @param taskId Parent task ID
     * @param subTaskId Sub-task ID
     * @param errorMessage Error message
     * @return ProgressUpdate event
     */
    public ProgressUpdate buildTrackFailure(
            @NonNull String taskId,
            @NonNull String subTaskId,
            @NonNull String errorMessage) {

        return ProgressUpdate.builder()
                .taskId(taskId)
                .subTaskId(subTaskId)
                .status(DownloadStatus.FAILED)
                .errorMessage(errorMessage)
                .build();
    }

    /**
     * Create a progress update for merge progress.
     *
     * @param taskId Parent task ID
     * @param percentage Progress percentage (0-100)
     * @param downloadedBytes Bytes processed
     * @param totalBytes Total bytes
     * @param downloadSpeed Processing speed
     * @param etaSeconds Estimated time to completion
     * @return ProgressUpdate event
     */
    public ProgressUpdate buildMergeProgress(
            @NonNull String taskId,
            double percentage,
            Long downloadedBytes,
            Long totalBytes,
            String downloadSpeed,
            Long etaSeconds) {

        return ProgressUpdate.builder()
                .taskId(taskId)
                .status(DownloadStatus.MERGING)
                .progress(percentage)
                .downloadedBytes(downloadedBytes)
                .totalBytes(totalBytes)
                .downloadSpeed(downloadSpeed)
                .etaSeconds(etaSeconds)
                .build();
    }

    /**
     * Create a progress update for merge completion.
     *
     * @param taskId Parent task ID
     * @param downloadedBytes Final bytes
     * @param totalBytes Total bytes
     * @return ProgressUpdate event
     */
    public ProgressUpdate buildMergeCompletion(
            @NonNull String taskId,
            Long downloadedBytes,
            Long totalBytes) {

        return ProgressUpdate.builder()
                .taskId(taskId)
                .status(DownloadStatus.COMPLETED)
                .progress(100.0)
                .downloadedBytes(downloadedBytes)
                .totalBytes(totalBytes)
                .downloadSpeed(null)
                .etaSeconds(null)
                .message("Merge completed")
                .build();
    }

    /**
     * Create a progress update for merge failure.
     *
     * @param taskId Parent task ID
     * @param errorMessage Error message
     * @return ProgressUpdate event
     */
    public ProgressUpdate buildMergeFailure(
            @NonNull String taskId,
            @NonNull String errorMessage) {

        return ProgressUpdate.builder()
                .taskId(taskId)
                .status(DownloadStatus.FAILED)
                .errorMessage(errorMessage)
                .build();
    }

    /**
     * Create a progress update from sub-task state.
     * Useful for broadcasting current sub-task state.
     *
     * @param taskId Parent task ID
     * @param subTask Sub-task with current state
     * @return ProgressUpdate event
     */
    public ProgressUpdate buildFromSubTask(@NonNull String taskId, @NonNull DownloadSubTask subTask) {
        return ProgressUpdate.builder()
                .taskId(taskId)
                .subTaskId(subTask.getId())
                .status(subTask.getStatus())
                .progress(subTask.getProgress())
                .downloadedBytes(subTask.getDownloadedBytes())
                .totalBytes(subTask.getTotalBytes())
                .downloadSpeed(subTask.getDownloadSpeed())
                .etaSeconds(subTask.getEtaSeconds())
                .message(subTask.getErrorMessage())
                .build();
    }
}
