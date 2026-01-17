package com.github.stormino.service;

import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
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
        private Long downloadedBytes;
        private Long totalBytes;
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
        return downloadSegments(segmentUrls, outputFile, referer, maxConcurrent, null, progressCallback);
    }

    /**
     * Download all segments with optional decryption and concatenate to output file
     */
    public SegmentDownloadResult downloadSegments(
            List<String> segmentUrls,
            Path outputFile,
            String referer,
            int maxConcurrent,
            HlsParserService.EncryptionInfo encryptionInfo,
            Consumer<DownloadProgress> progressCallback) {

        log.debug("Starting download of {} segments to {}", segmentUrls.size(), outputFile);

        Path tempDir = null;
        byte[] decryptionKey = null;
        try {
            // Create temp directory for segments
            tempDir = Files.createTempDirectory("hls_segments_");
            log.debug("Created temp segment directory: {}", tempDir);

            // Download encryption key if needed
            if (encryptionInfo != null && encryptionInfo.getUri() != null) {
                log.debug("Downloading encryption key from: {}", encryptionInfo.getUri());
                decryptionKey = downloadEncryptionKey(encryptionInfo.getUri(), referer);
                if (decryptionKey == null) {
                    return SegmentDownloadResult.builder()
                            .success(false)
                            .errorMessage("Failed to download encryption key")
                            .build();
                }
                log.debug("Successfully downloaded encryption key ({} bytes)", decryptionKey.length);
            }

            // Download segments concurrently
            List<Path> segmentFiles = downloadSegmentsConcurrently(
                    segmentUrls, tempDir, referer, maxConcurrent, encryptionInfo, decryptionKey, progressCallback);

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

            log.debug("Successfully downloaded and concatenated {} segments", segmentUrls.size());

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
            HlsParserService.EncryptionInfo encryptionInfo,
            byte[] decryptionKey,
            Consumer<DownloadProgress> progressCallback) throws InterruptedException, ExecutionException {

        ExecutorService executor = Executors.newFixedThreadPool(maxConcurrent);
        AtomicInteger downloadedCount = new AtomicInteger(0);
        AtomicInteger failedCount = new AtomicInteger(0);
        java.util.concurrent.atomic.AtomicLong totalDownloadedBytes = new java.util.concurrent.atomic.AtomicLong(0);
        long startTime = System.currentTimeMillis();

        List<CompletableFuture<Path>> futures = new ArrayList<>();

        for (int i = 0; i < segmentUrls.size(); i++) {
            final int segmentIndex = i;
            final String segmentUrl = segmentUrls.get(i);
            final Path segmentFile = tempDir.resolve(String.format("segment_%05d.ts", segmentIndex));

            CompletableFuture<Path> future = CompletableFuture.supplyAsync(() -> {
                try {
                    // Download segment
                    byte[] encryptedData = downloadSegmentData(segmentUrl, referer);

                    // Decrypt if needed
                    byte[] data;
                    if (decryptionKey != null && encryptionInfo != null) {
                        data = decryptSegment(encryptedData, decryptionKey, encryptionInfo, segmentIndex);
                    } else {
                        data = encryptedData;
                    }

                    // Write to file
                    Files.write(segmentFile, data);

                    int downloaded = downloadedCount.incrementAndGet();
                    long bytesDownloaded = totalDownloadedBytes.addAndGet(data.length);

                    // Calculate speed and ETA
                    long elapsedMillis = System.currentTimeMillis() - startTime;
                    long elapsedSeconds = Math.max(1, elapsedMillis / 1000); // Avoid division by zero

                    Long estimatedTotalBytes = null;
                    Long etaSeconds = null;
                    String downloadSpeed = null;

                    if (downloaded > 0) {
                        // Estimate total size based on average segment size
                        long averageSegmentSize = bytesDownloaded / downloaded;
                        estimatedTotalBytes = averageSegmentSize * segmentUrls.size();

                        // Calculate speed
                        double bytesPerSecond = (double) bytesDownloaded / elapsedSeconds;
                        downloadSpeed = formatSpeed(bytesPerSecond);

                        // Calculate ETA
                        long remainingBytes = estimatedTotalBytes - bytesDownloaded;
                        if (bytesPerSecond > 0 && remainingBytes > 0) {
                            etaSeconds = (long) (remainingBytes / bytesPerSecond);
                        }
                    }

                    // Report progress
                    if (progressCallback != null) {
                        // Cap percentage at 100% to handle any threading edge cases
                        double percentage = Math.min(100.0, (downloaded * 100.0) / segmentUrls.size());

                        DownloadProgress progress = DownloadProgress.builder()
                                .totalSegments(segmentUrls.size())
                                .downloadedSegments(downloaded)
                                .percentage(percentage)
                                .currentSegment(String.format("Segment %d/%d", downloaded, segmentUrls.size()))
                                .downloadedBytes(bytesDownloaded)
                                .totalBytes(estimatedTotalBytes)
                                .build();

                        progressCallback.accept(progress);
                    }

                    return segmentFile;

                } catch (Exception e) {
                    failedCount.incrementAndGet();
                    log.error("Failed to download segment {} from {}: {}", segmentIndex, segmentUrl, e.getMessage(), e);
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

    private byte[] downloadSegmentData(String url, String referer) throws IOException {
        int maxRetries = Integer.MAX_VALUE;
        int retryDelayMs = 500;

        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                Request.Builder requestBuilder = new Request.Builder()
                        .url(url)
                        .addHeader("Accept", "*/*");

                if (referer != null) {
                    requestBuilder.addHeader("Referer", referer);
                }

                try (Response response = httpClient.newCall(requestBuilder.build()).execute()) {
                    if (response.isSuccessful()) {
                        return response.body().bytes();
                    }

                    int code = response.code();

                    // Retry on server errors (5xx) and rate limiting (429)
                    if (attempt < maxRetries && (code == 429 || code == 503 || code >= 500)) {
                        long delay = retryDelayMs * (long) Math.pow(2, attempt); // Exponential backoff
                        log.debug("HTTP {} for segment, retrying in {}ms (attempt {}/{})", code, delay, attempt + 1, maxRetries);
                        try {
                            Thread.sleep(delay);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new IOException("Download interrupted");
                        }
                        continue;
                    }

                    throw new IOException("HTTP " + code);
                }
            } catch (IOException e) {
                if (attempt < maxRetries && (e.getMessage().contains("timeout") || e.getMessage().contains("reset"))) {
                    long delay = retryDelayMs * (long) Math.pow(2, attempt);
                    log.debug("Network error for segment: {}, retrying in {}ms (attempt {}/{})",
                            e.getMessage(), delay, attempt + 1, maxRetries);
                    try {
                        Thread.sleep(delay);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new IOException("Download interrupted");
                    }
                    continue;
                }
                throw e;
            }
        }

        throw new IOException("Failed after " + maxRetries + " retries");
    }

    private byte[] downloadEncryptionKey(String keyUrl, String referer) {
        try {
            return downloadSegmentData(keyUrl, referer);
        } catch (IOException e) {
            log.error("Failed to download encryption key from {}: {}", keyUrl, e.getMessage(), e);
            return null;
        }
    }

    private byte[] decryptSegment(byte[] encryptedData, byte[] key, HlsParserService.EncryptionInfo encryptionInfo, int segmentIndex) {
        try {
            if (!"AES-128".equals(encryptionInfo.getMethod())) {
                log.warn("Unsupported encryption method: {}", encryptionInfo.getMethod());
                return encryptedData;
            }

            // Prepare IV
            byte[] iv = new byte[16];
            if (encryptionInfo.getIv() != null) {
                // Use explicit IV from playlist
                String ivHex = encryptionInfo.getIv();
                for (int i = 0; i < 16 && i < ivHex.length() / 2; i++) {
                    iv[i] = (byte) Integer.parseInt(ivHex.substring(i * 2, i * 2 + 2), 16);
                }
            } else {
                // Use segment index as IV (big-endian)
                for (int i = 0; i < 4; i++) {
                    iv[15 - i] = (byte) (segmentIndex >> (i * 8));
                }
            }

            // Decrypt using AES-128-CBC
            SecretKeySpec secretKey = new SecretKeySpec(key, "AES");
            IvParameterSpec ivSpec = new IvParameterSpec(iv);
            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(Cipher.DECRYPT_MODE, secretKey, ivSpec);

            return cipher.doFinal(encryptedData);

        } catch (Exception e) {
            log.error("Failed to decrypt segment {} (method={}): {}", segmentIndex, encryptionInfo.getMethod(), e.getMessage(), e);
            return encryptedData;  // Return encrypted data as fallback
        }
    }

    private void concatenateSegments(List<Path> segmentFiles, Path outputFile) throws IOException {
        log.debug("Concatenating {} segments to {}", segmentFiles.size(), outputFile);

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
