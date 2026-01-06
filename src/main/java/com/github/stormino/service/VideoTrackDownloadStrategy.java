package com.github.stormino.service;

import com.github.stormino.model.DownloadStatus;
import com.github.stormino.model.DownloadSubTask;
import com.github.stormino.model.ProgressUpdate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

@Service
@RequiredArgsConstructor
@Slf4j
public class VideoTrackDownloadStrategy {

    private final HlsParserService hlsParser;
    private final DownloadExecutorService executorService;

    /**
     * Download video track from HLS playlist
     */
    public boolean downloadVideoTrack(
            String playlistUrl,
            String referer,
            Path outputFile,
            String quality,
            int maxConcurrent,
            DownloadSubTask subTask,
            String parentTaskId,
            Consumer<ProgressUpdate> progressCallback) {

        log.info("Starting video track download from: {}", playlistUrl);

        try {
            // 1. Parse master playlist
            Optional<HlsParserService.HlsPlaylist> playlistOpt = hlsParser.parsePlaylist(playlistUrl, referer);
            if (playlistOpt.isEmpty()) {
                log.error("Failed to parse master playlist");
                return false;
            }

            HlsParserService.HlsPlaylist playlist = playlistOpt.get();

            // 2. Select video variant
            String videoPlaylistUrl;

            if (playlist.getType() == HlsParserService.PlaylistType.MASTER) {
                // Select best video variant
                HlsParserService.VideoVariant selectedVariant = selectVideoVariant(
                        playlist.getVideoVariants(), quality);

                if (selectedVariant == null) {
                    log.error("No video variant found");
                    return false;
                }

                log.info("Selected video variant: {} ({})", selectedVariant.getResolution(), selectedVariant.getBandwidth());
                videoPlaylistUrl = selectedVariant.getUrl();

                // Update sub-task resolution
                subTask.setResolution(selectedVariant.getResolution());

            } else {
                // Already a media playlist
                videoPlaylistUrl = playlistUrl;
            }

            // 3. Parse video media playlist for segments
            Optional<List<String>> segmentsOpt = hlsParser.parseSegments(videoPlaylistUrl, referer);
            if (segmentsOpt.isEmpty()) {
                log.error("Failed to parse video segments");
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
            command.add(videoPlaylistUrl);
            command.add("-c");
            command.add("copy");
            command.add("-bsf:a");
            command.add("aac_adtstoasc");
            command.add("-y");
            command.add(outputFile.toString());

            log.info("Downloading video track with ffmpeg: {}", videoPlaylistUrl);

            boolean success = executorService.executeTrackDownload(
                    command,
                    parentTaskId,
                    subTask.getId(),
                    progressCallback
            );

            if (!success) {
                log.error("Video track download failed");
                return false;
            }

            log.info("Video track download completed: {}", outputFile);
            return true;

        } catch (Exception e) {
            log.error("Failed to download video track: {}", e.getMessage(), e);
            return false;
        }
    }

    private HlsParserService.VideoVariant selectVideoVariant(
            List<HlsParserService.VideoVariant> variants, String quality) {

        if (variants == null || variants.isEmpty()) {
            return null;
        }

        // If only one variant, return it
        if (variants.size() == 1) {
            return variants.get(0);
        }

        // Parse quality preference (e.g., "1080p", "720p")
        Integer preferredHeight = parseQualityHeight(quality);

        if (preferredHeight != null) {
            // Try to find exact match
            for (HlsParserService.VideoVariant variant : variants) {
                String resolution = variant.getResolution();
                if (resolution != null && resolution.contains("x")) {
                    String[] parts = resolution.split("x");
                    if (parts.length == 2) {
                        try {
                            int height = Integer.parseInt(parts[1]);
                            if (height == preferredHeight) {
                                return variant;
                            }
                        } catch (NumberFormatException e) {
                            // Ignore
                        }
                    }
                }
            }
        }

        // Otherwise, select highest bandwidth (best quality)
        return variants.stream()
                .max(Comparator.comparingInt(HlsParserService.VideoVariant::getBandwidth))
                .orElse(variants.get(0));
    }

    private Integer parseQualityHeight(String quality) {
        if (quality == null) {
            return null;
        }

        // Extract height from quality string like "1080p", "720p"
        String normalized = quality.toLowerCase().replace("p", "");
        try {
            return Integer.parseInt(normalized);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
