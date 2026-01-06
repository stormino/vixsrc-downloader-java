package com.github.stormino.service;

import com.github.stormino.model.DownloadStatus;
import com.github.stormino.model.DownloadSubTask;
import com.github.stormino.model.ProgressUpdate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

@Service
@RequiredArgsConstructor
@Slf4j
public class AudioTrackDownloadStrategy {

    private final HlsParserService hlsParser;
    private final DownloadExecutorService executorService;

    /**
     * Download audio track for specific language from HLS playlist
     */
    public boolean downloadAudioTrack(
            String playlistUrl,
            String referer,
            Path outputFile,
            String language,
            int maxConcurrent,
            DownloadSubTask subTask,
            String parentTaskId,
            Consumer<ProgressUpdate> progressCallback) {

        log.info("Starting audio track download for language: {}", language);

        try {
            // 1. Parse master playlist
            Optional<HlsParserService.HlsPlaylist> playlistOpt = hlsParser.parsePlaylist(playlistUrl, referer);
            if (playlistOpt.isEmpty()) {
                log.error("Failed to parse master playlist");
                return false;
            }

            HlsParserService.HlsPlaylist playlist = playlistOpt.get();

            // 2. Select audio track for language
            String audioPlaylistUrl;

            if (playlist.getType() == HlsParserService.PlaylistType.MASTER) {
                HlsParserService.AudioTrack selectedTrack = selectAudioTrack(
                        playlist.getAudioTracks(), language);

                if (selectedTrack == null) {
                    log.error("No audio track found for language: {}", language);
                    return false;
                }

                log.info("Selected audio track: {}", selectedTrack.getName());
                audioPlaylistUrl = selectedTrack.getUrl();

            } else {
                // Already a media playlist - assume it's the right language
                audioPlaylistUrl = playlistUrl;
            }

            // 3. Parse audio media playlist for segments
            Optional<List<String>> segmentsOpt = hlsParser.parseSegments(audioPlaylistUrl, referer);
            if (segmentsOpt.isEmpty()) {
                log.error("Failed to parse audio segments");
                return false;
            }

            // 3. Use ffmpeg to download HLS (handles encryption automatically)
            List<String> command = new ArrayList<>();
            command.add("ffmpeg");
            command.add("-hide_banner");
            command.add("-loglevel");
            command.add("info");  // Changed from warning to info to see progress
            command.add("-stats");  // Enable statistics output
            command.add("-headers");
            command.add("Referer: " + referer);
            command.add("-i");
            command.add(audioPlaylistUrl);
            command.add("-c");
            command.add("copy");
            command.add("-vn");  // No video
            command.add("-y");
            command.add(outputFile.toString());

            log.info("Downloading audio track with ffmpeg: {}", audioPlaylistUrl);

            boolean success = executorService.executeTrackDownload(
                    command,
                    parentTaskId,
                    subTask.getId(),
                    progressCallback
            );

            if (!success) {
                log.error("Audio track download failed");
                return false;
            }

            log.info("Audio track download completed: {}", outputFile);
            return true;

        } catch (Exception e) {
            log.error("Failed to download audio track: {}", e.getMessage(), e);
            return false;
        }
    }

    private HlsParserService.AudioTrack selectAudioTrack(
            List<HlsParserService.AudioTrack> audioTracks, String language) {

        if (audioTracks == null || audioTracks.isEmpty()) {
            return null;
        }

        // Try exact match first
        for (HlsParserService.AudioTrack track : audioTracks) {
            if (track.getLanguage() != null &&
                track.getLanguage().equalsIgnoreCase(language)) {
                return track;
            }
        }

        // Try partial match (ISO 639-1 vs ISO 639-2: "it" vs "ita", "en" vs "eng")
        for (HlsParserService.AudioTrack track : audioTracks) {
            if (track.getLanguage() != null) {
                String trackLang = track.getLanguage().toLowerCase();
                String requestLang = language.toLowerCase();

                // Match if either starts with the other (handle 2-char vs 3-char codes)
                if (trackLang.startsWith(requestLang) || requestLang.startsWith(trackLang)) {
                    log.info("Matched audio track {} to requested language {}", trackLang, requestLang);
                    return track;
                }
            }
        }

        // No match found
        log.warn("No audio track found for language: {}", language);
        return null;
    }
}
