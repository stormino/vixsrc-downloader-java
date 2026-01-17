package com.github.stormino.exception;

/**
 * Exception thrown when track download fails.
 */
public class TrackDownloadException extends DownloadException {

    private final String trackType;
    private final String language;
    private final String playlistUrl;

    public TrackDownloadException(String message, String trackType) {
        super(message);
        this.trackType = trackType;
        this.language = null;
        this.playlistUrl = null;
    }

    public TrackDownloadException(String message, String trackType, String language) {
        super(message);
        this.trackType = trackType;
        this.language = language;
        this.playlistUrl = null;
    }

    public TrackDownloadException(String message, Throwable cause, String trackType, String playlistUrl) {
        super(message, cause);
        this.trackType = trackType;
        this.language = null;
        this.playlistUrl = playlistUrl;
    }

    public String getTrackType() {
        return trackType;
    }

    public String getLanguage() {
        return language;
    }

    public String getPlaylistUrl() {
        return playlistUrl;
    }
}
