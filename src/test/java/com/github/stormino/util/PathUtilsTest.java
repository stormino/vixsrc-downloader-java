package com.github.stormino.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("PathUtils")
class PathUtilsTest {

    @Nested
    @DisplayName("sanitizeFilename")
    class SanitizeFilenameTests {

        @Test
        @DisplayName("should remove invalid characters")
        void shouldRemoveInvalidCharacters() {
            assertEquals("filename", PathUtils.sanitizeFilename("file<>:name"));
            assertEquals("filename", PathUtils.sanitizeFilename("file\"/\\name"));
            assertEquals("filename", PathUtils.sanitizeFilename("file|?*name"));
        }

        @Test
        @DisplayName("should replace spaces with dots")
        void shouldReplaceSpacesWithDots() {
            assertEquals("my.movie.name", PathUtils.sanitizeFilename("my movie name"));
        }

        @Test
        @DisplayName("should replace multiple spaces with single dot")
        void shouldReplaceMultipleSpacesWithSingleDot() {
            assertEquals("my.movie", PathUtils.sanitizeFilename("my   movie"));
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"   ", "\t", "\n"})
        @DisplayName("should return unnamed for null, empty, or blank input")
        void shouldReturnUnnamedForInvalidInput(String input) {
            assertEquals("unnamed", PathUtils.sanitizeFilename(input));
        }

        @Test
        @DisplayName("should handle complex filename")
        void shouldHandleComplexFilename() {
            String input = "Movie: The \"Best\" One? (2024)";
            String expected = "Movie.The.Best.One.(2024)";
            assertEquals(expected, PathUtils.sanitizeFilename(input));
        }
    }

    @Nested
    @DisplayName("createDirectoryStructure")
    class CreateDirectoryStructureTests {

        @Test
        @DisplayName("should create directory that does not exist")
        void shouldCreateDirectoryThatDoesNotExist(@TempDir Path tempDir) {
            Path newDir = tempDir.resolve("new/nested/directory");
            assertFalse(Files.exists(newDir));

            assertTrue(PathUtils.createDirectoryStructure(newDir));
            assertTrue(Files.exists(newDir));
            assertTrue(Files.isDirectory(newDir));
        }

        @Test
        @DisplayName("should return true for existing directory")
        void shouldReturnTrueForExistingDirectory(@TempDir Path tempDir) {
            assertTrue(Files.exists(tempDir));
            assertTrue(PathUtils.createDirectoryStructure(tempDir));
        }

        @Test
        @DisplayName("should work with string path")
        void shouldWorkWithStringPath(@TempDir Path tempDir) {
            String newDirPath = tempDir.resolve("string/path/test").toString();

            assertTrue(PathUtils.createDirectoryStructure(newDirPath));
            assertTrue(Files.exists(Path.of(newDirPath)));
        }
    }

    @Nested
    @DisplayName("buildPath")
    class BuildPathTests {

        @Test
        @DisplayName("should build path from segments")
        void shouldBuildPathFromSegments() {
            String result = PathUtils.buildPath("/base", "sub", "file.txt");
            assertTrue(result.contains("base"));
            assertTrue(result.contains("sub"));
            assertTrue(result.contains("file.txt"));
        }

        @Test
        @DisplayName("should skip null segments")
        void shouldSkipNullSegments() {
            String result = PathUtils.buildPath("/base", null, "file.txt");
            assertTrue(result.contains("base"));
            assertTrue(result.contains("file.txt"));
        }

        @Test
        @DisplayName("should skip blank segments")
        void shouldSkipBlankSegments() {
            String result = PathUtils.buildPath("/base", "   ", "file.txt");
            assertTrue(result.contains("base"));
            assertTrue(result.contains("file.txt"));
        }

        @Test
        @DisplayName("should handle single segment")
        void shouldHandleSingleSegment() {
            String result = PathUtils.buildPath("/base");
            assertTrue(result.contains("base"));
        }
    }

    @Nested
    @DisplayName("ensureExtension")
    class EnsureExtensionTests {

        @ParameterizedTest
        @CsvSource({
            "video, .mp4, video.mp4",
            "video.mp4, .mp4, video.mp4",
            "video.MP4, .mp4, video.MP4",
            "video, mp4, video.mp4"
        })
        @DisplayName("should ensure correct extension")
        void shouldEnsureCorrectExtension(String filename, String extension, String expected) {
            assertEquals(expected, PathUtils.ensureExtension(filename, extension));
        }

        @ParameterizedTest
        @NullAndEmptySource
        @DisplayName("should handle null or empty filename")
        void shouldHandleNullOrEmptyFilename(String filename) {
            assertEquals("unnamed.mp4", PathUtils.ensureExtension(filename, ".mp4"));
        }

        @Test
        @DisplayName("should handle null extension")
        void shouldHandleNullExtension() {
            assertEquals("video", PathUtils.ensureExtension("video", null));
        }

        @Test
        @DisplayName("should handle blank extension")
        void shouldHandleBlankExtension() {
            assertEquals("video", PathUtils.ensureExtension("video", "   "));
        }
    }

    @Nested
    @DisplayName("getExtension")
    class GetExtensionTests {

        @ParameterizedTest
        @CsvSource({
            "video.mp4, .mp4",
            "archive.tar.gz, .gz",
            "file.name.with.dots.txt, .txt"
        })
        @DisplayName("should extract extension correctly")
        void shouldExtractExtensionCorrectly(String filename, String expected) {
            assertEquals(expected, PathUtils.getExtension(filename));
        }

        @ParameterizedTest
        @ValueSource(strings = {"noextension", ".hidden", "file."})
        @DisplayName("should return empty for files without extension")
        void shouldReturnEmptyForFilesWithoutExtension(String filename) {
            assertEquals("", PathUtils.getExtension(filename));
        }

        @ParameterizedTest
        @NullAndEmptySource
        @DisplayName("should return empty for null or empty input")
        void shouldReturnEmptyForNullOrEmptyInput(String filename) {
            assertEquals("", PathUtils.getExtension(filename));
        }
    }

    @Nested
    @DisplayName("getFilenameWithoutExtension")
    class GetFilenameWithoutExtensionTests {

        @ParameterizedTest
        @CsvSource({
            "video.mp4, video",
            "archive.tar.gz, archive.tar",
            "file.name.with.dots.txt, file.name.with.dots"
        })
        @DisplayName("should remove extension correctly")
        void shouldRemoveExtensionCorrectly(String filename, String expected) {
            assertEquals(expected, PathUtils.getFilenameWithoutExtension(filename));
        }

        @Test
        @DisplayName("should return filename if no extension")
        void shouldReturnFilenameIfNoExtension() {
            assertEquals("noextension", PathUtils.getFilenameWithoutExtension("noextension"));
        }

        @ParameterizedTest
        @NullAndEmptySource
        @DisplayName("should return empty for null or empty input")
        void shouldReturnEmptyForNullOrEmptyInput(String filename) {
            assertEquals("", PathUtils.getFilenameWithoutExtension(filename));
        }

        @Test
        @DisplayName("should handle hidden files")
        void shouldHandleHiddenFiles() {
            // Hidden files starting with . are returned as-is (no extension to remove)
            assertEquals(".hidden", PathUtils.getFilenameWithoutExtension(".hidden"));
        }
    }
}
