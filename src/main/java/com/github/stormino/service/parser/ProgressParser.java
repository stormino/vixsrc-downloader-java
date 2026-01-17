package com.github.stormino.service.parser;

import com.github.stormino.model.ProgressUpdate;

/**
 * Interface for parsing progress information from command output.
 * Allows for different implementations for various download tools (ffmpeg, yt-dlp, aria2c, etc.).
 */
public interface ProgressParser {

    /**
     * Parse a single line of output and extract progress information.
     *
     * @param line Output line to parse
     * @param taskId Task ID for the progress update
     * @return ProgressUpdate if progress information was found, null otherwise
     */
    ProgressUpdate parseLine(String line, String taskId);

    /**
     * Reset the parser state (e.g., for new download).
     */
    void reset();

    /**
     * Get the total duration if known (for time-based progress).
     *
     * @return Total duration in seconds, or null if unknown
     */
    Double getTotalDuration();

    /**
     * Get the estimated total size if known (for size-based progress).
     *
     * @return Total size in bytes, or null if unknown
     */
    Long getTotalSize();
}
