package com.github.stormino.util;

/**
 * Constants used throughout the download system.
 */
public final class DownloadConstants {

    private DownloadConstants() {
        // Utility class, no instantiation
    }

    // ========== Timeouts ==========

    /**
     * Maximum timeout for download operations in hours.
     */
    public static final long MAX_DOWNLOAD_TIMEOUT_HOURS = 2;

    /**
     * Maximum timeout for download operations in milliseconds.
     */
    public static final long MAX_DOWNLOAD_TIMEOUT_MS = MAX_DOWNLOAD_TIMEOUT_HOURS * 60 * 60 * 1000;

    // ========== File Extensions ==========

    /**
     * Video file extension for final output.
     */
    public static final String VIDEO_EXTENSION = ".mp4";

    /**
     * Audio file extension for track downloads.
     */
    public static final String AUDIO_EXTENSION = ".m4a";

    /**
     * Subtitle file extension.
     */
    public static final String SUBTITLE_EXTENSION = ".vtt";

    /**
     * Transport stream extension for temporary files.
     */
    public static final String TS_EXTENSION = ".ts";

    /**
     * Temporary file suffix.
     */
    public static final String TEMP_FILE_SUFFIX = ".temp";

    // ========== FFmpeg Configuration ==========

    /**
     * FFmpeg log level for normal operations.
     */
    public static final String FFMPEG_LOG_LEVEL = "info";

    /**
     * FFmpeg log level for merge operations.
     */
    public static final String FFMPEG_LOG_LEVEL_MERGE = "info";

    /**
     * FFmpeg AAC bitstream filter for MP4 compatibility.
     */
    public static final String FFMPEG_AAC_BSF = "aac_adtstoasc";

    /**
     * FFmpeg subtitle codec for MP4.
     */
    public static final String FFMPEG_SUBTITLE_CODEC = "mov_text";

    // ========== Progress Tracking ==========

    /**
     * Minimum interval between progress broadcasts in milliseconds.
     */
    public static final long PROGRESS_BROADCAST_INTERVAL_MS = 500;

    /**
     * Percentage threshold for progress updates (avoid flooding with tiny changes).
     */
    public static final double PROGRESS_UPDATE_THRESHOLD = 0.1;

    // ========== Batch Operations ==========

    /**
     * Number of episodes to process before broadcasting queue status.
     */
    public static final int BATCH_QUEUE_BROADCAST_INTERVAL = 10;

    /**
     * Number of season episodes to process before broadcasting status.
     */
    public static final int SEASON_QUEUE_BROADCAST_INTERVAL = 5;

    // ========== File Naming ==========

    /**
     * Default filename for unnamed content.
     */
    public static final String DEFAULT_FILENAME = "unnamed";

    /**
     * Season directory format string.
     */
    public static final String SEASON_DIR_FORMAT = "Season %02d";

    /**
     * TV episode filename format (without extension).
     */
    public static final String TV_EPISODE_FORMAT = "%s - S%02dE%02d";

    /**
     * TV episode with name format (without extension).
     */
    public static final String TV_EPISODE_WITH_NAME_FORMAT = "%s - S%02dE%02d - %s";

    // ========== Size Units ==========

    /**
     * Bytes in one kilobyte (using decimal, not binary).
     */
    public static final long BYTES_PER_KB = 1_000L;

    /**
     * Bytes in one megabyte.
     */
    public static final long BYTES_PER_MB = 1_000_000L;

    /**
     * Bytes in one gigabyte.
     */
    public static final long BYTES_PER_GB = 1_000_000_000L;

    /**
     * Bytes in one terabyte.
     */
    public static final long BYTES_PER_TB = 1_000_000_000_000L;

    // ========== Binary Size Units ==========

    /**
     * Bytes in one kibibyte (1024 bytes).
     */
    public static final long BYTES_PER_KIB = 1024L;

    /**
     * Bytes in one mebibyte.
     */
    public static final long BYTES_PER_MIB = 1024L * 1024;

    /**
     * Bytes in one gibibyte.
     */
    public static final long BYTES_PER_GIB = 1024L * 1024 * 1024;

    /**
     * Bytes in one tebibyte.
     */
    public static final long BYTES_PER_TIB = 1024L * 1024 * 1024 * 1024;

    // ========== HTTP ==========

    /**
     * HTTP status code for server errors that should trigger retry.
     */
    public static final int[] RETRYABLE_HTTP_STATUS_CODES = {500, 502, 503, 504, 429};

    /**
     * HTTP status code for Cloudflare challenges.
     */
    public static final int HTTP_STATUS_CLOUDFLARE_CHALLENGE = 403;

    /**
     * HTTP status code for service unavailable.
     */
    public static final int HTTP_STATUS_SERVICE_UNAVAILABLE = 503;

    // ========== Process Management ==========

    /**
     * Key separator for composite process keys (taskId:subTaskId).
     */
    public static final String PROCESS_KEY_SEPARATOR = ":";

    /**
     * Process key suffix for merge operations.
     */
    public static final String MERGE_PROCESS_SUFFIX = "merge";
}
