package com.github.stormino.service.strategy;

import com.github.stormino.model.DownloadSubTask;
import com.github.stormino.model.ProgressUpdate;
import lombok.Builder;
import lombok.Data;

import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * Request object encapsulating all parameters needed for track download.
 * Reduces parameter count from 8+ to a single object.
 */
@Data
@Builder
public class TrackDownloadRequest {

    /**
     * URL of the HLS playlist to download from.
     */
    private final String playlistUrl;

    /**
     * Referer URL for HTTP headers (embed page URL).
     */
    private final String referer;

    /**
     * Output file path for the downloaded track.
     */
    private final Path outputFile;

    /**
     * Language code for the track (e.g., "en", "it", "es").
     * May be null for video tracks.
     */
    private final String language;

    /**
     * Quality preference (e.g., "best", "1080p", "720p").
     * Primarily used for video tracks.
     */
    private final String quality;

    /**
     * Maximum concurrent segment downloads.
     */
    private final int maxConcurrency;

    /**
     * Sub-task associated with this download.
     */
    private final DownloadSubTask subTask;

    /**
     * Parent task ID for progress tracking.
     */
    private final String parentTaskId;

    /**
     * Callback for progress updates.
     */
    private final Consumer<ProgressUpdate> progressCallback;
}
