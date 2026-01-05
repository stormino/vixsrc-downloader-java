package com.github.stormino.service;

import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

@Service
@RequiredArgsConstructor
@Slf4j
public class HlsSegmentDownloader {

    private final OkHttpClient httpClient;

    @Data
    @Builder
    public static class SegmentDownloadResult {
        private boolean success;
        private int totalSegments;
        private int downloadedSegments;
        private String errorMessage;
        private Path outputFile;
    }

    @Data
    @Builder
    public static class DownloadProgress {
        private int totalSegments;
        private int downloadedSegments;
        private double percentage;
        private String currentSegment;
        private long downloadedBytes;
        private long totalBytes;
    }

    /**
     * Download all segments and concatenate to output file
     */
    public SegmentDownloadResult downloadSegments(
            List<String> segmentUrls,
            Path outputFile,
            String referer,
            int maxConcurrent,
            Consumer<DownloadProgress> progressCallback) {

        log.info("Starting download of {} segments to {}", segmentUrls.size(), outputFile);

        Path tempDir = null;
        try {
            // Create temp directory for segments
            tempDir = Files.createTempDirectory("hls_segments_");
            log.info("Created temp segment directory: {}", tempDir);

            // Download segments concurrently
            List<Path> segmentFiles = downloadSegmentsConcurrently(
                    segmentUrls, tempDir, referer, maxConcurrent, progressCallback);

            if (segmentFiles.size() != segmentUrls.size()) {
                return SegmentDownloadResult.builder()
                        .success(false)
                        .totalSegments(segmentUrls.size())
                        .downloadedSegments(segmentFiles.size())
                        .errorMessage("Failed to download all segments")
                        .build();
            }

            // Concatenate segments
            concatenateSegments(segmentFiles, outputFile);

            // Cleanup temp files
            cleanupTempFiles(segmentFiles, tempDir);

            log.info("Successfully downloaded and concatenated {} segments", segmentUrls.size());

            return SegmentDownloadResult.builder()
                    .success(true)
                    .totalSegments(segmentUrls.size())
                    .downloadedSegments(segmentUrls.size())
                    .outputFile(outputFile)
                    .build();

        } catch (Exception e) {
            log.error("Failed to download segments: {}", e.getMessage(), e);
            return SegmentDownloadResult.builder()
                    .success(false)
                    .errorMessage(e.getMessage())
                    .build();
        } finally {
            // Cleanup temp directory
            if (tempDir != null) {
                try {
                    Files.deleteIfExists(tempDir);
                } catch (IOException e) {
                    log.warn("Failed to delete temp directory: {}", e.getMessage());
                }
            }
        }
    }

    private List<Path> downloadSegmentsConcurrently(
            List<String> segmentUrls,
            Path tempDir,
            String referer,
            int maxConcurrent,
            Consumer<DownloadProgress> progressCallback) throws InterruptedException, ExecutionException {

        ExecutorService executor = Executors.newFixedThreadPool(maxConcurrent);
        AtomicInteger downloadedCount = new AtomicInteger(0);
        AtomicInteger failedCount = new AtomicInteger(0);

        List<CompletableFuture<Path>> futures = new ArrayList<>();

        for (int i = 0; i < segmentUrls.size(); i++) {
            final int segmentIndex = i;
            final String segmentUrl = segmentUrls.get(i);
            final Path segmentFile = tempDir.resolve(String.format("segment_%05d.ts", segmentIndex));

            CompletableFuture<Path> future = CompletableFuture.supplyAsync(() -> {
                try {
                    downloadSegment(segmentUrl, segmentFile, referer);

                    int downloaded = downloadedCount.incrementAndGet();

                    // Report progress
                    if (progressCallback != null) {
                        DownloadProgress progress = DownloadProgress.builder()
                                .totalSegments(segmentUrls.size())
                                .downloadedSegments(downloaded)
                                .percentage((downloaded * 100.0) / segmentUrls.size())
                                .currentSegment(String.format("Segment %d/%d", downloaded, segmentUrls.size()))
                                .build();
                        progressCallback.accept(progress);
                    }

                    return segmentFile;

                } catch (Exception e) {
                    failedCount.incrementAndGet();
                    log.error("Failed to download segment {}: {}", segmentIndex, e.getMessage());
                    return null;
                }
            }, executor);

            futures.add(future);
        }

        // Wait for all downloads
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get();
        executor.shutdown();

        // Filter out failed downloads
        List<Path> segmentFiles = new ArrayList<>();
        for (CompletableFuture<Path> future : futures) {
            Path file = future.get();
            if (file != null && Files.exists(file)) {
                segmentFiles.add(file);
            }
        }

        if (failedCount.get() > 0) {
            log.warn("Failed to download {} segments out of {}", failedCount.get(), segmentUrls.size());
        }

        return segmentFiles;
    }

    private void downloadSegment(String url, Path outputFile, String referer) throws IOException {
        Request.Builder requestBuilder = new Request.Builder()
                .url(url)
                .addHeader("Accept", "*/*");

        if (referer != null) {
            requestBuilder.addHeader("Referer", referer);
        }

        try (Response response = httpClient.newCall(requestBuilder.build()).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("HTTP " + response.code());
            }

            try (OutputStream out = Files.newOutputStream(outputFile)) {
                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = response.body().byteStream().read(buffer)) != -1) {
                    out.write(buffer, 0, bytesRead);
                }
            }
        }
    }

    private void concatenateSegments(List<Path> segmentFiles, Path outputFile) throws IOException {
        log.info("Concatenating {} segments to {}", segmentFiles.size(), outputFile);

        Files.createDirectories(outputFile.getParent());

        try (OutputStream out = Files.newOutputStream(outputFile)) {
            for (Path segmentFile : segmentFiles) {
                Files.copy(segmentFile, out);
            }
        }
    }

    private void cleanupTempFiles(List<Path> segmentFiles, Path tempDir) {
        for (Path file : segmentFiles) {
            try {
                Files.deleteIfExists(file);
            } catch (IOException e) {
                log.warn("Failed to delete segment file: {}", file);
            }
        }

        try {
            Files.deleteIfExists(tempDir);
        } catch (IOException e) {
            log.warn("Failed to delete temp directory: {}", tempDir);
        }
    }
}
