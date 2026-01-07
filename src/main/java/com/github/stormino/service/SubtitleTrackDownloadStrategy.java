package com.github.stormino.service;

import com.github.stormino.model.DownloadStatus;
import com.github.stormino.model.DownloadSubTask;
import com.github.stormino.model.ProgressUpdate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

@Service
@RequiredArgsConstructor
@Slf4j
public class SubtitleTrackDownloadStrategy {

    private final HlsParserService hlsParser;
    private final HlsSegmentDownloader segmentDownloader;

    /**
     * Download subtitle track for specific language from HLS playlist
     */
    public boolean downloadSubtitleTrack(
            String playlistUrl,
            String referer,
            Path outputFile,
            String language,
            int maxConcurrent,
            DownloadSubTask subTask,
            String parentTaskId,
            Consumer<ProgressUpdate> progressCallback) {

        log.info("Starting subtitle track download for language: {}", language);

        try {
            // 1. Parse master playlist
            Optional<HlsParserService.HlsPlaylist> playlistOpt = hlsParser.parsePlaylist(playlistUrl, referer);
            if (playlistOpt.isEmpty()) {
                log.error("Failed to parse master playlist");
                return false;
            }

            HlsParserService.HlsPlaylist playlist = playlistOpt.get();

            // 2. Select subtitle track for language
            String subtitlePlaylistUrl;

            if (playlist.getType() == HlsParserService.PlaylistType.MASTER) {
                HlsParserService.SubtitleTrack selectedTrack = selectSubtitleTrack(
                        playlist.getSubtitleTracks(), language);

                if (selectedTrack == null) {
                    log.info("No subtitle track available for language: {} (skipping)", language);
                    subTask.setStatus(DownloadStatus.NOT_FOUND);
                    subTask.setErrorMessage("Track not available for this language");
                    return false;
                }

                log.info("Selected subtitle track: {}", selectedTrack.getName());
                subtitlePlaylistUrl = selectedTrack.getUrl();

                // Set track title for metadata
                if (selectedTrack.getName() != null && subTask != null) {
                    subTask.setTitle(selectedTrack.getName());
                }

            } else {
                // Already a media playlist - assume it's the right language
                subtitlePlaylistUrl = playlistUrl;
            }

            // 3. Parse subtitle media playlist for segments and encryption info
            Optional<HlsParserService.MediaPlaylistInfo> playlistInfoOpt =
                    hlsParser.parseMediaPlaylistInfo(subtitlePlaylistUrl, referer);
            if (playlistInfoOpt.isEmpty()) {
                log.error("Failed to parse subtitle playlist info");
                return false;
            }

            HlsParserService.MediaPlaylistInfo playlistInfo = playlistInfoOpt.get();
            List<String> segments = playlistInfo.getSegments();
            HlsParserService.EncryptionInfo encryption = playlistInfo.getEncryption();

            log.info("Found {} subtitle segments for {}, encrypted={}", segments.size(), language, encryption != null);

            // 4. Download segments with progress tracking
            Path tempSubtitleFile = outputFile.getParent().resolve(outputFile.getFileName() + ".temp");

            HlsSegmentDownloader.SegmentDownloadResult result = segmentDownloader.downloadSegments(
                    segments,
                    tempSubtitleFile,
                    referer,
                    maxConcurrent,
                    encryption,
                    progress -> {
                        // Update sub-task progress
                        subTask.setProgress(progress.getPercentage());

                        // Broadcast progress
                        if (progressCallback != null) {
                            ProgressUpdate update = ProgressUpdate.builder()
                                    .taskId(parentTaskId)
                                    .subTaskId(subTask.getId())
                                    .status(DownloadStatus.DOWNLOADING)
                                    .progress(progress.getPercentage())
                                    .message(progress.getCurrentSegment())
                                    .build();
                            progressCallback.accept(update);
                        }
                    }
            );

            if (!result.isSuccess()) {
                log.error("Subtitle track download failed: {}", result.getErrorMessage());
                return false;
            }

            // 5. Convert to proper WebVTT/SRT if needed
            convertSubtitleFormat(tempSubtitleFile, outputFile);
            Files.deleteIfExists(tempSubtitleFile);

            log.info("Subtitle track download completed: {}", outputFile);
            return true;

        } catch (Exception e) {
            log.error("Failed to download subtitle track: {}", e.getMessage(), e);
            return false;
        }
    }

    private HlsParserService.SubtitleTrack selectSubtitleTrack(
            List<HlsParserService.SubtitleTrack> subtitleTracks, String language) {

        if (subtitleTracks == null || subtitleTracks.isEmpty()) {
            return null;
        }

        // Try exact match first
        for (HlsParserService.SubtitleTrack track : subtitleTracks) {
            if (track.getLanguage() != null &&
                track.getLanguage().equalsIgnoreCase(language)) {
                return track;
            }
        }

        // Try partial match (ISO 639-1 vs ISO 639-2: "it" vs "ita", "en" vs "eng")
        for (HlsParserService.SubtitleTrack track : subtitleTracks) {
            if (track.getLanguage() != null) {
                String trackLang = track.getLanguage().toLowerCase();
                String requestLang = language.toLowerCase();

                // Match if either starts with the other (handle 2-char vs 3-char codes)
                if (trackLang.startsWith(requestLang) || requestLang.startsWith(trackLang)) {
                    log.info("Matched subtitle track {} to requested language {}", trackLang, requestLang);
                    return track;
                }
            }
        }

        // No match found
        log.warn("No subtitle track found for language: {}", language);
        return null;
    }

    /**
     * Convert concatenated WebVTT segments to proper WebVTT format
     * by removing duplicate headers and ensuring proper formatting
     */
    private void convertSubtitleFormat(Path inputFile, Path outputFile) throws IOException {
        // Read all lines from concatenated file
        List<String> lines = Files.readAllLines(inputFile);

        try (BufferedWriter writer = Files.newBufferedWriter(outputFile)) {
            boolean headerWritten = false;
            boolean skipNextBlank = false;

            for (String line : lines) {
                // Write WEBVTT header only once at the beginning
                if (line.startsWith("WEBVTT")) {
                    if (!headerWritten) {
                        writer.write(line);
                        writer.newLine();
                        headerWritten = true;
                        skipNextBlank = true;
                    }
                    continue;
                }

                // Skip blank line immediately after header duplicates
                if (skipNextBlank && line.trim().isEmpty()) {
                    skipNextBlank = false;
                    continue;
                }

                skipNextBlank = false;

                // Write all other lines (cues, timestamps, text)
                writer.write(line);
                writer.newLine();
            }
        }

        log.info("Converted subtitle format from {} to {}", inputFile, outputFile);
    }
}
