package com.github.stormino.model;

import lombok.Builder;
import lombok.Data;

import java.util.HashMap;
import java.util.Map;

/**
 * Result object for download operations, providing detailed status and error information.
 * Replaces simple boolean returns with structured result data.
 */
@Data
@Builder
public class DownloadResult {

    /**
     * Whether the download was successful.
     */
    private final boolean success;

    /**
     * Status of the download operation.
     */
    @Builder.Default
    private final ResultStatus status = ResultStatus.SUCCESS;

    /**
     * Error message if download failed.
     */
    private final String errorMessage;

    /**
     * Optional exception that caused the failure.
     */
    private final Throwable cause;

    /**
     * Additional metadata about the download.
     */
    @Builder.Default
    private final Map<String, Object> metadata = new HashMap<>();

    /**
     * Download result status.
     */
    public enum ResultStatus {
        /**
         * Download completed successfully.
         */
        SUCCESS,

        /**
         * Download failed due to an error.
         */
        FAILED,

        /**
         * Content not found (e.g., track not available for language).
         */
        NOT_FOUND,

        /**
         * Download was cancelled by user.
         */
        CANCELLED,

        /**
         * Partial success (some segments downloaded, some failed).
         */
        PARTIAL
    }

    /**
     * Create a successful result.
     *
     * @return Successful download result
     */
    public static DownloadResult success() {
        return DownloadResult.builder()
                .success(true)
                .status(ResultStatus.SUCCESS)
                .build();
    }

    /**
     * Create a successful result with metadata.
     *
     * @param metadata Additional metadata
     * @return Successful download result with metadata
     */
    public static DownloadResult success(Map<String, Object> metadata) {
        return DownloadResult.builder()
                .success(true)
                .status(ResultStatus.SUCCESS)
                .metadata(metadata)
                .build();
    }

    /**
     * Create a failed result.
     *
     * @param errorMessage Error message
     * @return Failed download result
     */
    public static DownloadResult failure(String errorMessage) {
        return DownloadResult.builder()
                .success(false)
                .status(ResultStatus.FAILED)
                .errorMessage(errorMessage)
                .build();
    }

    /**
     * Create a failed result with cause.
     *
     * @param errorMessage Error message
     * @param cause Exception that caused the failure
     * @return Failed download result
     */
    public static DownloadResult failure(String errorMessage, Throwable cause) {
        return DownloadResult.builder()
                .success(false)
                .status(ResultStatus.FAILED)
                .errorMessage(errorMessage)
                .cause(cause)
                .build();
    }

    /**
     * Create a not found result.
     *
     * @param message Description of what was not found
     * @return Not found download result
     */
    public static DownloadResult notFound(String message) {
        return DownloadResult.builder()
                .success(false)
                .status(ResultStatus.NOT_FOUND)
                .errorMessage(message)
                .build();
    }

    /**
     * Create a cancelled result.
     *
     * @param message Cancellation message
     * @return Cancelled download result
     */
    public static DownloadResult cancelled(String message) {
        return DownloadResult.builder()
                .success(false)
                .status(ResultStatus.CANCELLED)
                .errorMessage(message)
                .build();
    }

    /**
     * Create a partial success result.
     *
     * @param message Description of partial result
     * @return Partial success download result
     */
    public static DownloadResult partial(String message) {
        return DownloadResult.builder()
                .success(true)
                .status(ResultStatus.PARTIAL)
                .errorMessage(message)
                .build();
    }

    /**
     * Add metadata to the result.
     *
     * @param key Metadata key
     * @param value Metadata value
     */
    public void addMetadata(String key, Object value) {
        metadata.put(key, value);
    }

    /**
     * Get metadata value.
     *
     * @param key Metadata key
     * @param <T> Type of metadata value
     * @return Metadata value or null if not found
     */
    @SuppressWarnings("unchecked")
    public <T> T getMetadata(String key) {
        return (T) metadata.get(key);
    }

    /**
     * Check if result has metadata.
     *
     * @param key Metadata key
     * @return true if metadata exists
     */
    public boolean hasMetadata(String key) {
        return metadata.containsKey(key);
    }
}
