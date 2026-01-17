package com.github.stormino.exception;

/**
 * Exception thrown when playlist URL extraction fails.
 */
public class PlaylistExtractionException extends DownloadException {

    private final String embedUrl;
    private final Integer tmdbId;

    public PlaylistExtractionException(String message, String embedUrl) {
        super(message);
        this.embedUrl = embedUrl;
        this.tmdbId = null;
    }

    public PlaylistExtractionException(String message, String embedUrl, Integer tmdbId) {
        super(message);
        this.embedUrl = embedUrl;
        this.tmdbId = tmdbId;
    }

    public PlaylistExtractionException(String message, Throwable cause, String embedUrl) {
        super(message, cause);
        this.embedUrl = embedUrl;
        this.tmdbId = null;
    }

    public String getEmbedUrl() {
        return embedUrl;
    }

    public Integer getTmdbId() {
        return tmdbId;
    }
}
