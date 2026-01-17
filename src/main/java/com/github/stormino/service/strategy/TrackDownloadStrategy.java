package com.github.stormino.service.strategy;

import com.github.stormino.model.DownloadResult;

/**
 * Strategy interface for downloading different types of tracks (video, audio, subtitle).
 * Implementations handle track-specific download logic while maintaining a consistent interface.
 */
public interface TrackDownloadStrategy {

    /**
     * Download a track from an HLS playlist.
     *
     * @param request Request object containing all download parameters
     * @return Download result with status and error information
     */
    DownloadResult downloadTrack(TrackDownloadRequest request);

    /**
     * Get the type of track this strategy handles.
     *
     * @return Track type identifier
     */
    TrackType getTrackType();

    /**
     * Enum representing the type of track.
     */
    enum TrackType {
        VIDEO,
        AUDIO,
        SUBTITLE
    }
}
