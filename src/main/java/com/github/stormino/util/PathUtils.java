package com.github.stormino.util;

import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Utility class for file path operations and filename sanitization.
 */
@Slf4j
@UtilityClass
public class PathUtils {

    /**
     * Sanitize filename by removing invalid characters and replacing spaces.
     *
     * @param filename Original filename
     * @return Sanitized filename safe for filesystem use
     */
    public static String sanitizeFilename(String filename) {
        if (filename == null || filename.isBlank()) {
            return "unnamed";
        }

        return filename
                .replaceAll("[<>:\"/\\\\|?*]", "")  // Remove invalid filesystem characters
                .replaceAll("\\s+", ".")             // Replace whitespace with dots
                .trim();
    }

    /**
     * Create directory structure if it doesn't exist.
     *
     * @param path Directory path to create
     * @return true if directory exists or was created successfully, false otherwise
     */
    public static boolean createDirectoryStructure(Path path) {
        try {
            if (!Files.exists(path)) {
                Files.createDirectories(path);
                log.debug("Created directory structure: {}", path);
            }
            return true;
        } catch (IOException e) {
            log.error("Failed to create directory structure: {}", path, e);
            return false;
        }
    }

    /**
     * Create directory structure from string path.
     *
     * @param path Directory path to create
     * @return true if directory exists or was created successfully, false otherwise
     */
    public static boolean createDirectoryStructure(String path) {
        return createDirectoryStructure(Paths.get(path));
    }

    /**
     * Build file path with proper separators.
     *
     * @param basePath Base directory path
     * @param segments Path segments to append
     * @return Complete file path as string
     */
    public static String buildPath(String basePath, String... segments) {
        Path path = Paths.get(basePath);
        for (String segment : segments) {
            if (segment != null && !segment.isBlank()) {
                path = path.resolve(segment);
            }
        }
        return path.toString();
    }

    /**
     * Ensure path ends with specific extension.
     *
     * @param filename Original filename
     * @param extension Desired extension (with or without leading dot)
     * @return Filename with correct extension
     */
    public static String ensureExtension(String filename, String extension) {
        if (filename == null || filename.isBlank()) {
            return "unnamed" + normalizeExtension(extension);
        }

        String normalizedExt = normalizeExtension(extension);

        if (filename.toLowerCase().endsWith(normalizedExt.toLowerCase())) {
            return filename;
        }

        return filename + normalizedExt;
    }

    /**
     * Normalize extension to always have leading dot.
     *
     * @param extension Extension string
     * @return Extension with leading dot
     */
    private static String normalizeExtension(String extension) {
        if (extension == null || extension.isBlank()) {
            return "";
        }
        return extension.startsWith(".") ? extension : "." + extension;
    }

    /**
     * Get file extension from filename.
     *
     * @param filename Filename to extract extension from
     * @return Extension with leading dot, or empty string if no extension
     */
    public static String getExtension(String filename) {
        if (filename == null || filename.isBlank()) {
            return "";
        }

        int lastDot = filename.lastIndexOf('.');
        if (lastDot > 0 && lastDot < filename.length() - 1) {
            return filename.substring(lastDot);
        }

        return "";
    }

    /**
     * Get filename without extension.
     *
     * @param filename Full filename
     * @return Filename without extension
     */
    public static String getFilenameWithoutExtension(String filename) {
        if (filename == null || filename.isBlank()) {
            return "";
        }

        int lastDot = filename.lastIndexOf('.');
        if (lastDot > 0) {
            return filename.substring(0, lastDot);
        }

        return filename;
    }
}
