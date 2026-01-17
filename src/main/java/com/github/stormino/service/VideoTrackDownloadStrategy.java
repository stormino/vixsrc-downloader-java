package com.github.stormino.service;

import com.github.stormino.model.DownloadResult;
import com.github.stormino.model.DownloadStatus;
import com.github.stormino.model.DownloadSubTask;
import com.github.stormino.model.ProgressUpdate;
import com.github.stormino.service.command.FfmpegCommandBuilder;
import com.github.stormino.service.progress.ProgressEventBuilder;
import com.github.stormino.service.strategy.TrackDownloadRequest;
import com.github.stormino.service.strategy.TrackDownloadStrategy;
import com.github.stormino.util.DownloadConstants;
import com.github.stormino.util.FormatUtils;
import lombok.NonNull;
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
public class VideoTrackDownloadStrategy implements TrackDownloadStrategy {

    private final HlsParserService hlsParser;
    private final HlsSegmentDownloader segmentDownloader;
    private final DownloadExecutorService executorService;
    private final FfmpegCommandBuilder commandBuilder;
    private final ProgressEventBuilder progressBuilder;

    @Override
    public DownloadResult downloadTrack(TrackDownloadRequest request) {
        return downloadVideoTrack(
                request.getPlaylistUrl(),
                request.getReferer(),
                request.getOutputFile(),
                request.getQuality(),
                request.getMaxConcurrency(),
                request.getSubTask(),
                request.getParentTaskId(),
                request.getProgressCallback()
        );
    }

    @Override
    public TrackType getTrackType() {
        return TrackType.VIDEO;
    }

    /**
     * Download video track from HLS playlist
     */
    public DownloadResult downloadVideoTrack(
            @NonNull String playlistUrl,
            @NonNull String referer,
            @NonNull Path outputFile,
            String quality,
            int maxConcurrent,
            @NonNull DownloadSubTask subTask,
            @NonNull String parentTaskId,
            Consumer<ProgressUpdate> progressCallback) {

        log.debug("Starting video track download from: {}", playlistUrl);

        try {
            // 1. Parse master playlist
            Optional<HlsParserService.HlsPlaylist> playlistOpt = hlsParser.parsePlaylist(playlistUrl, referer);
            if (playlistOpt.isEmpty()) {
                log.error("Failed to parse master playlist");
                return DownloadResult.failure("Failed to parse master playlist");
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
                    return DownloadResult.failure("No video variant found");
                }

                log.debug("Selected video variant: {} ({})", selectedVariant.getResolution(), selectedVariant.getBandwidth());
                videoPlaylistUrl = selectedVariant.getUrl();

                // Update sub-task resolution
                subTask.setResolution(selectedVariant.getResolution());

            } else {
                // Already a media playlist
                videoPlaylistUrl = playlistUrl;
            }

            // 3. Parse video media playlist for segments and encryption info
            Optional<HlsParserService.MediaPlaylistInfo> playlistInfoOpt =
                    hlsParser.parseMediaPlaylistInfo(videoPlaylistUrl, referer);
            if (playlistInfoOpt.isEmpty()) {
                log.error("Failed to parse video playlist info");
                return DownloadResult.failure("Failed to parse video playlist info");
            }

            HlsParserService.MediaPlaylistInfo playlistInfo = playlistInfoOpt.get();
            List<String> segments = playlistInfo.getSegments();
            HlsParserService.EncryptionInfo encryption = playlistInfo.getEncryption();

            log.debug("Downloading {} video segments with concurrency={}, encrypted={}",
                    segments.size(), maxConcurrent, encryption != null);

            // 4. Download segments concurrently with HlsSegmentDownloader
            Path tempVideoFile = outputFile.getParent().resolve(outputFile.getFileName() + ".temp.ts");
            long downloadStartTime = System.currentTimeMillis();

            HlsSegmentDownloader.SegmentDownloadResult result = segmentDownloader.downloadSegments(
                    segments,
                    tempVideoFile,
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
                            downloadSpeed = FormatUtils.formatSpeed(bytesPerSecond);

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
                            ProgressUpdate update = progressBuilder.buildSegmentProgress(
                                    parentTaskId,
                                    subTask.getId(),
                                    progress.getPercentage(),
                                    progress.getCurrentSegment(),
                                    progress.getDownloadedBytes(),
                                    progress.getTotalBytes(),
                                    downloadSpeed,
                                    etaSeconds
                            );
                            progressCallback.accept(update);
                        }
                    }
            );

            if (!result.isSuccess()) {
                log.error("Video segment download failed: {}", result.getErrorMessage());
                return DownloadResult.failure("Video segment download failed: " + result.getErrorMessage());
            }

            // 5. Notify that conversion is starting
            if (progressCallback != null) {
                ProgressUpdate convertingUpdate = progressBuilder.buildConversionStatus(
                        parentTaskId,
                        subTask.getId(),
                        "Converting to MP4..."
                );
                progressCallback.accept(convertingUpdate);
            }

            // 6. Convert TS to MP4 using ffmpeg (fast copy, no re-encoding)
            log.debug("Converting video from TS to MP4: {}", tempVideoFile);
            List<String> command = commandBuilder.buildVideoConversionCommand(tempVideoFile, outputFile);

            DownloadResult conversionResult = executorService.downloadTrack(
                    command,
                    parentTaskId,
                    subTask.getId(),
                    progressCallback
            );

            // Cleanup temp file
            try {
                java.nio.file.Files.deleteIfExists(tempVideoFile);
            } catch (Exception e) {
                log.warn("Failed to delete temp file: {}", tempVideoFile);
            }

            if (!conversionResult.isSuccess()) {
                log.error("Video conversion to MP4 failed: {}", conversionResult.getErrorMessage());
                return DownloadResult.failure("Video conversion to MP4 failed: " + conversionResult.getErrorMessage());
            }

            log.debug("Video track download completed: {}", outputFile);
            return DownloadResult.success();

        } catch (Exception e) {
            log.error("Failed to download video track: {}", e.getMessage(), e);
            return DownloadResult.failure("Failed to download video track: " + e.getMessage(), e);
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
                            log.debug("Could not parse resolution height from '{}': {}", resolution, e.getMessage());
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
