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
    private final HlsSegmentDownloader segmentDownloader;
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

            // 3. Parse video media playlist for segments and encryption info
            Optional<HlsParserService.MediaPlaylistInfo> playlistInfoOpt =
                    hlsParser.parseMediaPlaylistInfo(videoPlaylistUrl, referer);
            if (playlistInfoOpt.isEmpty()) {
                log.error("Failed to parse video playlist info");
                return false;
            }

            HlsParserService.MediaPlaylistInfo playlistInfo = playlistInfoOpt.get();
            List<String> segments = playlistInfo.getSegments();
            HlsParserService.EncryptionInfo encryption = playlistInfo.getEncryption();

            log.info("Downloading {} video segments with concurrency={}, encrypted={}",
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
                log.error("Video segment download failed: {}", result.getErrorMessage());
                return false;
            }

            // 5. Notify that conversion is starting
            if (progressCallback != null) {
                ProgressUpdate convertingUpdate = ProgressUpdate.builder()
                        .taskId(parentTaskId)
                        .subTaskId(subTask.getId())
                        .status(DownloadStatus.DOWNLOADING)
                        .progress(100.0)
                        .message("Converting to MP4...")
                        .build();
                progressCallback.accept(convertingUpdate);
            }

            // 6. Convert TS to MP4 using ffmpeg (fast copy, no re-encoding)
            log.info("Converting video from TS to MP4: {}", tempVideoFile);
            List<String> command = new ArrayList<>();
            command.add("ffmpeg");
            command.add("-hide_banner");
            command.add("-loglevel");
            command.add("info");
            command.add("-stats");
            command.add("-i");
            command.add(tempVideoFile.toString());
            command.add("-c");
            command.add("copy");
            command.add("-bsf:a");
            command.add("aac_adtstoasc");
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
                java.nio.file.Files.deleteIfExists(tempVideoFile);
            } catch (Exception e) {
                log.warn("Failed to delete temp file: {}", tempVideoFile);
            }

            if (!conversionSuccess) {
                log.error("Video conversion to MP4 failed");
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
