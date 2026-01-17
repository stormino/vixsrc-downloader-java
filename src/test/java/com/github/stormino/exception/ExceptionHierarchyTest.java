package com.github.stormino.exception;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Exception Hierarchy")
class ExceptionHierarchyTest {

    @Nested
    @DisplayName("DownloadException")
    class DownloadExceptionTests {

        @Test
        @DisplayName("should extend RuntimeException")
        void shouldExtendRuntimeException() {
            DownloadException ex = new DownloadException("Test error");
            assertInstanceOf(RuntimeException.class, ex);
        }

        @Test
        @DisplayName("should create with message")
        void shouldCreateWithMessage() {
            DownloadException ex = new DownloadException("Download failed");
            assertEquals("Download failed", ex.getMessage());
            assertNull(ex.getCause());
        }

        @Test
        @DisplayName("should create with message and cause")
        void shouldCreateWithMessageAndCause() {
            Exception cause = new RuntimeException("Root cause");
            DownloadException ex = new DownloadException("Download failed", cause);

            assertEquals("Download failed", ex.getMessage());
            assertEquals(cause, ex.getCause());
        }

        @Test
        @DisplayName("should create with cause only")
        void shouldCreateWithCauseOnly() {
            Exception cause = new RuntimeException("Root cause");
            DownloadException ex = new DownloadException(cause);

            assertEquals(cause, ex.getCause());
        }
    }

    @Nested
    @DisplayName("TrackDownloadException")
    class TrackDownloadExceptionTests {

        @Test
        @DisplayName("should extend DownloadException")
        void shouldExtendDownloadException() {
            TrackDownloadException ex = new TrackDownloadException("Test", "VIDEO");
            assertInstanceOf(DownloadException.class, ex);
        }

        @Test
        @DisplayName("should create with message and track type")
        void shouldCreateWithMessageAndTrackType() {
            TrackDownloadException ex = new TrackDownloadException("Video track failed", "VIDEO");

            assertEquals("Video track failed", ex.getMessage());
            assertEquals("VIDEO", ex.getTrackType());
            assertNull(ex.getLanguage());
            assertNull(ex.getPlaylistUrl());
        }

        @Test
        @DisplayName("should create with message, track type, and language")
        void shouldCreateWithMessageTrackTypeAndLanguage() {
            TrackDownloadException ex = new TrackDownloadException("Audio track failed", "AUDIO", "en");

            assertEquals("Audio track failed", ex.getMessage());
            assertEquals("AUDIO", ex.getTrackType());
            assertEquals("en", ex.getLanguage());
            assertNull(ex.getPlaylistUrl());
        }

        @Test
        @DisplayName("should create with message, cause, track type, and playlist URL")
        void shouldCreateWithAllFields() {
            Exception cause = new RuntimeException("Network error");
            TrackDownloadException ex = new TrackDownloadException(
                    "Download failed", cause, "VIDEO", "https://example.com/playlist.m3u8");

            assertEquals("Download failed", ex.getMessage());
            assertEquals(cause, ex.getCause());
            assertEquals("VIDEO", ex.getTrackType());
            assertEquals("https://example.com/playlist.m3u8", ex.getPlaylistUrl());
        }
    }

    @Nested
    @DisplayName("PlaylistExtractionException")
    class PlaylistExtractionExceptionTests {

        @Test
        @DisplayName("should extend DownloadException")
        void shouldExtendDownloadException() {
            PlaylistExtractionException ex = new PlaylistExtractionException("Test", "https://example.com");
            assertInstanceOf(DownloadException.class, ex);
        }

        @Test
        @DisplayName("should capture embed URL")
        void shouldCaptureEmbedUrl() {
            PlaylistExtractionException ex = new PlaylistExtractionException(
                    "Failed to extract playlist", "https://vixsrc.to/embed/12345");

            assertEquals("Failed to extract playlist", ex.getMessage());
            assertEquals("https://vixsrc.to/embed/12345", ex.getEmbedUrl());
        }

        @Test
        @DisplayName("should capture TMDB ID when provided")
        void shouldCaptureTmdbIdWhenProvided() {
            PlaylistExtractionException ex = new PlaylistExtractionException(
                    "Failed to extract", "https://example.com", 550);

            assertEquals(Integer.valueOf(550), ex.getTmdbId());
        }
    }

    @Nested
    @DisplayName("MergeException")
    class MergeExceptionTests {

        @Test
        @DisplayName("should extend DownloadException")
        void shouldExtendDownloadException() {
            MergeException ex = new MergeException("Test", null, "output.mp4");
            assertInstanceOf(DownloadException.class, ex);
        }

        @Test
        @DisplayName("should capture input files and output file")
        void shouldCaptureInputFilesAndOutputFile() {
            java.util.List<String> inputs = java.util.List.of("video.mp4", "audio.m4a");
            MergeException ex = new MergeException("Merge failed", inputs, "output.mp4");

            assertEquals("Merge failed", ex.getMessage());
            assertEquals(inputs, ex.getInputFiles());
            assertEquals("output.mp4", ex.getOutputFile());
        }

        @Test
        @DisplayName("should capture exit code when provided")
        void shouldCaptureExitCodeWhenProvided() {
            MergeException ex = new MergeException("Merge failed", null, "output.mp4", 1);

            assertEquals(Integer.valueOf(1), ex.getExitCode());
        }
    }

    @Nested
    @DisplayName("ConfigurationException")
    class ConfigurationExceptionTests {

        @Test
        @DisplayName("should extend DownloadException")
        void shouldExtendDownloadException() {
            ConfigurationException ex = new ConfigurationException("Test", "key");
            assertInstanceOf(DownloadException.class, ex);
        }

        @Test
        @DisplayName("should capture config key")
        void shouldCaptureConfigKey() {
            ConfigurationException ex = new ConfigurationException(
                    "Invalid configuration", "vixsrc.download.path");

            assertEquals("Invalid configuration", ex.getMessage());
            assertEquals("vixsrc.download.path", ex.getConfigKey());
        }

        @Test
        @DisplayName("should capture config value when provided")
        void shouldCaptureConfigValueWhenProvided() {
            ConfigurationException ex = new ConfigurationException(
                    "Invalid value", "vixsrc.quality", "invalid-quality");

            assertEquals("vixsrc.quality", ex.getConfigKey());
            assertEquals("invalid-quality", ex.getConfigValue());
        }
    }

    @Nested
    @DisplayName("exception hierarchy relationships")
    class HierarchyRelationshipsTests {

        @Test
        @DisplayName("all custom exceptions should be catchable as DownloadException")
        void allCustomExceptionsShouldBeCatchableAsDownloadException() {
            assertDoesNotThrow(() -> {
                try {
                    throw new TrackDownloadException("Test", "VIDEO");
                } catch (DownloadException e) {
                    // Should be caught
                }
            });

            assertDoesNotThrow(() -> {
                try {
                    throw new PlaylistExtractionException("Test", "url");
                } catch (DownloadException e) {
                    // Should be caught
                }
            });

            assertDoesNotThrow(() -> {
                try {
                    throw new MergeException("Test", null, "output");
                } catch (DownloadException e) {
                    // Should be caught
                }
            });

            assertDoesNotThrow(() -> {
                try {
                    throw new ConfigurationException("Test", "key");
                } catch (DownloadException e) {
                    // Should be caught
                }
            });
        }

        @Test
        @DisplayName("all custom exceptions should be catchable as RuntimeException")
        void allCustomExceptionsShouldBeCatchableAsRuntimeException() {
            assertDoesNotThrow(() -> {
                try {
                    throw new DownloadException("Test");
                } catch (RuntimeException e) {
                    // Should be caught
                }
            });
        }
    }
}
