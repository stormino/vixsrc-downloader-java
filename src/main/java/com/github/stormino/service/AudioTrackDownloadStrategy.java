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
    private final HlsSegmentDownloader segmentDownloader;
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

        log.debug("Starting audio track download for language: {}", language);

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
                    log.debug("No audio track found for language: {} (may be embedded in video)", language);
                    subTask.setStatus(DownloadStatus.NOT_FOUND);
                    subTask.setErrorMessage("Track not available for this language");
                    return false;
                }

                log.debug("Selected audio track: {}", selectedTrack.getName());
                audioPlaylistUrl = selectedTrack.getUrl();

                // Set track title for metadata
                if (selectedTrack.getName() != null && subTask != null) {
                    subTask.setTitle(selectedTrack.getName());
                }

            } else {
                // Already a media playlist - assume it's the right language
                audioPlaylistUrl = playlistUrl;
            }

            // 3. Parse audio media playlist for segments and encryption info
            Optional<HlsParserService.MediaPlaylistInfo> playlistInfoOpt =
                    hlsParser.parseMediaPlaylistInfo(audioPlaylistUrl, referer);
            if (playlistInfoOpt.isEmpty()) {
                log.error("Failed to parse audio playlist info");
                return false;
            }

            HlsParserService.MediaPlaylistInfo playlistInfo = playlistInfoOpt.get();
            List<String> segments = playlistInfo.getSegments();
            HlsParserService.EncryptionInfo encryption = playlistInfo.getEncryption();

            log.debug("Downloading {} audio segments with concurrency={}, encrypted={}",
                    segments.size(), maxConcurrent, encryption != null);

            // 4. Download segments concurrently with HlsSegmentDownloader
            Path tempAudioFile = outputFile.getParent().resolve(outputFile.getFileName() + ".temp.ts");
            long downloadStartTime = System.currentTimeMillis();

            HlsSegmentDownloader.SegmentDownloadResult result = segmentDownloader.downloadSegments(
                    segments,
                    tempAudioFile,
                    referer,
                    maxConcurrent,
                    encryption,
                    progress -> {
                        // Update sub-task progress
                        subTask.setProgress(progress.getPercentage());
                        subTask.setDownloadedBytes(progress.getDownloadedBytes());
                        subTask.setTotalBytes(progress.getTotalBytes());

                        // Calculate speed and ETA
                        String downloadSpeed = null;
                        Long etaSeconds = null;

                        long elapsedMillis = System.currentTimeMillis() - downloadStartTime;
                        long elapsedSeconds = Math.max(1, elapsedMillis / 1000);

                        if (progress.getDownloadedBytes() != null && progress.getDownloadedBytes() > 0) {
                            double bytesPerSecond = (double) progress.getDownloadedBytes() / elapsedSeconds;
                            downloadSpeed = formatSpeed(bytesPerSecond);

                            if (progress.getTotalBytes() != null && progress.getTotalBytes() > progress.getDownloadedBytes()) {
                                long remainingBytes = progress.getTotalBytes() - progress.getDownloadedBytes();
                                if (bytesPerSecond > 0) {
                                    etaSeconds = (long) (remainingBytes / bytesPerSecond);
                                }
                            }
                        }

                        subTask.setDownloadSpeed(downloadSpeed);
                        subTask.setEtaSeconds(etaSeconds);

                        // Broadcast progress
                        if (progressCallback != null) {
                            ProgressUpdate update = ProgressUpdate.builder()
                                    .taskId(parentTaskId)
                                    .subTaskId(subTask.getId())
                                    .status(DownloadStatus.DOWNLOADING)
                                    .progress(progress.getPercentage())
                                    .message(progress.getCurrentSegment())
                                    .downloadedBytes(progress.getDownloadedBytes())
                                    .totalBytes(progress.getTotalBytes())
                                    .downloadSpeed(downloadSpeed)
                                    .etaSeconds(etaSeconds)
                                    .build();
                            progressCallback.accept(update);
                        }
                    }
            );

            if (!result.isSuccess()) {
                log.error("Audio segment download failed: {}", result.getErrorMessage());
                return false;
            }

            // 5. Notify that conversion is starting
            if (progressCallback != null) {
                ProgressUpdate convertingUpdate = ProgressUpdate.builder()
                        .taskId(parentTaskId)
                        .subTaskId(subTask.getId())
                        .status(DownloadStatus.DOWNLOADING)
                        .progress(100.0)
                        .message("Converting to M4A...")
                        .build();
                progressCallback.accept(convertingUpdate);
            }

            // 6. Convert TS to M4A using ffmpeg (fast copy, no re-encoding)
            log.debug("Converting audio from TS to M4A: {}", tempAudioFile);
            List<String> command = new ArrayList<>();
            command.add("ffmpeg");
            command.add("-hide_banner");
            command.add("-loglevel");
            command.add("info");
            command.add("-stats");
            command.add("-i");
            command.add(tempAudioFile.toString());
            command.add("-c");
            command.add("copy");
            command.add("-vn");  // No video
            command.add("-y");
            command.add(outputFile.toString());

            boolean conversionSuccess = executorService.executeTrackDownload(
                    command,
                    parentTaskId,
                    subTask.getId(),
                    progressCallback
            );

            // Cleanup temp file
            try {
                java.nio.file.Files.deleteIfExists(tempAudioFile);
            } catch (Exception e) {
                log.warn("Failed to delete temp file: {}", tempAudioFile);
            }

            if (!conversionSuccess) {
                log.error("Audio conversion to M4A failed");
                return false;
            }

            log.debug("Audio track download completed: {}", outputFile);
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
                    log.debug("Matched audio track {} to requested language {}", trackLang, requestLang);
                    return track;
                }
            }
        }

        // No match found
        log.warn("No audio track found for language: {}", language);
        return null;
    }

    private String formatSpeed(double bytesPerSecond) {
        if (bytesPerSecond >= 1_000_000_000) {
            return String.format("%.2f GB/s", bytesPerSecond / 1_000_000_000);
        } else if (bytesPerSecond >= 1_000_000) {
            return String.format("%.2f MB/s", bytesPerSecond / 1_000_000);
        } else if (bytesPerSecond >= 1_000) {
            return String.format("%.2f KB/s", bytesPerSecond / 1_000);
        } else {
            return String.format("%.0f B/s", bytesPerSecond);
        }
    }
}
