package com.github.stormino.util;

import lombok.extern.slf4j.Slf4j;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;

/**
 * Manager for temporary files and directories with automatic cleanup.
 * Implements AutoCloseable for use with try-with-resources.
 * Thread-safe using CopyOnWriteArrayList.
 */
@Slf4j
public class TempFileManager implements Closeable {

    private final List<Path> tempFiles = new CopyOnWriteArrayList<>();
    private final List<Path> tempDirectories = new CopyOnWriteArrayList<>();
    private volatile boolean closed = false;

    /**
     * Register a temporary file for automatic cleanup.
     *
     * @param file Temporary file path
     */
    public void registerTempFile(Path file) {
        if (closed) {
            log.warn("TempFileManager is already closed, cannot register file: {}", file);
            return;
        }
        tempFiles.add(file);
        log.debug("Registered temp file: {}", file);
    }

    /**
     * Register a temporary directory for automatic cleanup.
     *
     * @param directory Temporary directory path
     */
    public void registerTempDirectory(Path directory) {
        if (closed) {
            log.warn("TempFileManager is already closed, cannot register directory: {}", directory);
            return;
        }
        tempDirectories.add(directory);
        log.debug("Registered temp directory: {}", directory);
    }

    /**
     * Delete a specific temporary file immediately.
     *
     * @param file File to delete
     * @return true if deleted successfully
     */
    public boolean deleteTempFile(Path file) {
        boolean removed = tempFiles.remove(file);
        if (removed) {
            return deleteFile(file);
        }
        return false;
    }

    /**
     * Delete a specific temporary directory immediately.
     *
     * @param directory Directory to delete
     * @return true if deleted successfully
     */
    public boolean deleteTempDirectory(Path directory) {
        boolean removed = tempDirectories.remove(directory);
        if (removed) {
            return deleteDirectory(directory);
        }
        return false;
    }

    /**
     * Clean up all registered temporary files and directories.
     * Called automatically when used with try-with-resources.
     */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;

        log.debug("Cleaning up {} temp files and {} temp directories",
                tempFiles.size(), tempDirectories.size());

        // Delete files first
        List<Path> filesToDelete = new ArrayList<>(tempFiles);
        for (Path file : filesToDelete) {
            deleteFile(file);
        }
        tempFiles.clear();

        // Then delete directories
        List<Path> dirsToDelete = new ArrayList<>(tempDirectories);
        for (Path directory : dirsToDelete) {
            deleteDirectory(directory);
        }
        tempDirectories.clear();

        log.debug("Temp file cleanup completed");
    }

    /**
     * Delete a single file.
     */
    private boolean deleteFile(Path file) {
        try {
            if (Files.exists(file)) {
                Files.delete(file);
                log.debug("Deleted temp file: {}", file);
                return true;
            }
        } catch (IOException e) {
            log.warn("Failed to delete temp file: {}", file, e);
        }
        return false;
    }

    /**
     * Recursively delete a directory and its contents.
     */
    private boolean deleteDirectory(Path directory) {
        try {
            if (Files.exists(directory)) {
                try (Stream<Path> paths = Files.walk(directory)) {
                    paths.sorted(Comparator.reverseOrder())
                            .forEach(path -> {
                                try {
                                    Files.delete(path);
                                } catch (IOException e) {
                                    log.warn("Failed to delete: {}", path, e);
                                }
                            });
                }
                log.debug("Deleted temp directory: {}", directory);
                return true;
            }
        } catch (IOException e) {
            log.warn("Failed to delete temp directory: {}", directory, e);
        }
        return false;
    }

    /**
     * Get count of registered temporary files.
     *
     * @return Number of temp files
     */
    public int getTempFileCount() {
        return tempFiles.size();
    }

    /**
     * Get count of registered temporary directories.
     *
     * @return Number of temp directories
     */
    public int getTempDirectoryCount() {
        return tempDirectories.size();
    }

    /**
     * Check if manager is closed.
     *
     * @return true if closed
     */
    public boolean isClosed() {
        return closed;
    }

    /**
     * Create a shutdown hook for emergency cleanup.
     * Call this method to register cleanup on JVM shutdown.
     *
     * @return The shutdown hook thread (for potential removal)
     */
    public Thread registerShutdownHook() {
        Thread hook = new Thread(this::close, "TempFileManager-ShutdownHook");
        Runtime.getRuntime().addShutdownHook(hook);
        log.debug("Registered shutdown hook for temp file cleanup");
        return hook;
    }
}
