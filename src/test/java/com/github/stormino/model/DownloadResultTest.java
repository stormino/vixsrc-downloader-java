package com.github.stormino.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("DownloadResult")
class DownloadResultTest {

    @Nested
    @DisplayName("factory methods")
    class FactoryMethodTests {

        @Test
        @DisplayName("success() should create successful result")
        void successShouldCreateSuccessfulResult() {
            DownloadResult result = DownloadResult.success();

            assertTrue(result.isSuccess());
            assertEquals(DownloadResult.ResultStatus.SUCCESS, result.getStatus());
            assertNull(result.getErrorMessage());
            assertNull(result.getCause());
        }

        @Test
        @DisplayName("success(metadata) should create successful result with metadata")
        void successWithMetadataShouldCreateSuccessfulResultWithMetadata() {
            Map<String, Object> metadata = Map.of("file", "test.mp4", "size", 1024L);
            DownloadResult result = DownloadResult.success(metadata);

            assertTrue(result.isSuccess());
            assertEquals(DownloadResult.ResultStatus.SUCCESS, result.getStatus());
            assertEquals("test.mp4", result.getMetadata("file"));
            assertEquals(1024L, result.<Long>getMetadata("size"));
        }

        @Test
        @DisplayName("failure(message) should create failed result")
        void failureShouldCreateFailedResult() {
            DownloadResult result = DownloadResult.failure("Connection timeout");

            assertFalse(result.isSuccess());
            assertEquals(DownloadResult.ResultStatus.FAILED, result.getStatus());
            assertEquals("Connection timeout", result.getErrorMessage());
            assertNull(result.getCause());
        }

        @Test
        @DisplayName("failure(message, cause) should create failed result with cause")
        void failureWithCauseShouldCreateFailedResultWithCause() {
            Exception cause = new RuntimeException("Network error");
            DownloadResult result = DownloadResult.failure("Download failed", cause);

            assertFalse(result.isSuccess());
            assertEquals(DownloadResult.ResultStatus.FAILED, result.getStatus());
            assertEquals("Download failed", result.getErrorMessage());
            assertEquals(cause, result.getCause());
        }

        @Test
        @DisplayName("notFound() should create not found result")
        void notFoundShouldCreateNotFoundResult() {
            DownloadResult result = DownloadResult.notFound("Track not available");

            assertFalse(result.isSuccess());
            assertEquals(DownloadResult.ResultStatus.NOT_FOUND, result.getStatus());
            assertEquals("Track not available", result.getErrorMessage());
        }

        @Test
        @DisplayName("cancelled() should create cancelled result")
        void cancelledShouldCreateCancelledResult() {
            DownloadResult result = DownloadResult.cancelled("User cancelled");

            assertFalse(result.isSuccess());
            assertEquals(DownloadResult.ResultStatus.CANCELLED, result.getStatus());
            assertEquals("User cancelled", result.getErrorMessage());
        }

        @Test
        @DisplayName("partial() should create partial success result")
        void partialShouldCreatePartialSuccessResult() {
            DownloadResult result = DownloadResult.partial("3 of 5 segments downloaded");

            assertTrue(result.isSuccess());
            assertEquals(DownloadResult.ResultStatus.PARTIAL, result.getStatus());
            assertEquals("3 of 5 segments downloaded", result.getErrorMessage());
        }
    }

    @Nested
    @DisplayName("metadata operations")
    class MetadataOperationsTests {

        @Test
        @DisplayName("addMetadata should add key-value pair")
        void addMetadataShouldAddKeyValuePair() {
            DownloadResult result = DownloadResult.success();

            result.addMetadata("outputPath", "/downloads/movie.mp4");

            assertTrue(result.hasMetadata("outputPath"));
            assertEquals("/downloads/movie.mp4", result.getMetadata("outputPath"));
        }

        @Test
        @DisplayName("hasMetadata should return false for non-existent key")
        void hasMetadataShouldReturnFalseForNonExistentKey() {
            DownloadResult result = DownloadResult.success();

            assertFalse(result.hasMetadata("nonExistent"));
        }

        @Test
        @DisplayName("getMetadata should return null for non-existent key")
        void getMetadataShouldReturnNullForNonExistentKey() {
            DownloadResult result = DownloadResult.success();

            assertNull(result.getMetadata("nonExistent"));
        }

        @Test
        @DisplayName("should support different value types")
        void shouldSupportDifferentValueTypes() {
            DownloadResult result = DownloadResult.success();

            result.addMetadata("string", "value");
            result.addMetadata("number", 42);
            result.addMetadata("boolean", true);
            result.addMetadata("long", 1000000000L);

            assertEquals("value", result.<String>getMetadata("string"));
            assertEquals(42, result.<Integer>getMetadata("number"));
            assertEquals(true, result.<Boolean>getMetadata("boolean"));
            assertEquals(1000000000L, result.<Long>getMetadata("long"));
        }
    }

    @Nested
    @DisplayName("ResultStatus enum")
    class ResultStatusTests {

        @Test
        @DisplayName("should have all expected statuses")
        void shouldHaveAllExpectedStatuses() {
            DownloadResult.ResultStatus[] statuses = DownloadResult.ResultStatus.values();

            assertEquals(5, statuses.length);
            assertNotNull(DownloadResult.ResultStatus.valueOf("SUCCESS"));
            assertNotNull(DownloadResult.ResultStatus.valueOf("FAILED"));
            assertNotNull(DownloadResult.ResultStatus.valueOf("NOT_FOUND"));
            assertNotNull(DownloadResult.ResultStatus.valueOf("CANCELLED"));
            assertNotNull(DownloadResult.ResultStatus.valueOf("PARTIAL"));
        }
    }

    @Nested
    @DisplayName("builder")
    class BuilderTests {

        @Test
        @DisplayName("should create result with all fields")
        void shouldCreateResultWithAllFields() {
            Exception cause = new RuntimeException("Test");
            DownloadResult result = DownloadResult.builder()
                    .success(false)
                    .status(DownloadResult.ResultStatus.FAILED)
                    .errorMessage("Test error")
                    .cause(cause)
                    .build();

            assertFalse(result.isSuccess());
            assertEquals(DownloadResult.ResultStatus.FAILED, result.getStatus());
            assertEquals("Test error", result.getErrorMessage());
            assertEquals(cause, result.getCause());
        }

        @Test
        @DisplayName("should use default status when not specified")
        void shouldUseDefaultStatusWhenNotSpecified() {
            DownloadResult result = DownloadResult.builder()
                    .success(true)
                    .build();

            assertEquals(DownloadResult.ResultStatus.SUCCESS, result.getStatus());
        }
    }
}
