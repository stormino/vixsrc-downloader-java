package com.github.stormino.util;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("TempFileManager")
class TempFileManagerTest {

    private TempFileManager manager;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        manager = new TempFileManager();
    }

    @AfterEach
    void tearDown() {
        if (!manager.isClosed()) {
            manager.close();
        }
    }

    @Nested
    @DisplayName("registerTempFile")
    class RegisterTempFileTests {

        @Test
        @DisplayName("should register file")
        void shouldRegisterFile() throws IOException {
            Path tempFile = tempDir.resolve("test.tmp");
            Files.createFile(tempFile);

            manager.registerTempFile(tempFile);

            assertEquals(1, manager.getTempFileCount());
        }

        @Test
        @DisplayName("should not register file after close")
        void shouldNotRegisterFileAfterClose() throws IOException {
            Path tempFile = tempDir.resolve("test.tmp");
            Files.createFile(tempFile);

            manager.close();
            manager.registerTempFile(tempFile);

            assertEquals(0, manager.getTempFileCount());
        }

        @Test
        @DisplayName("should register multiple files")
        void shouldRegisterMultipleFiles() throws IOException {
            for (int i = 0; i < 5; i++) {
                Path tempFile = tempDir.resolve("test" + i + ".tmp");
                Files.createFile(tempFile);
                manager.registerTempFile(tempFile);
            }

            assertEquals(5, manager.getTempFileCount());
        }
    }

    @Nested
    @DisplayName("registerTempDirectory")
    class RegisterTempDirectoryTests {

        @Test
        @DisplayName("should register directory")
        void shouldRegisterDirectory() throws IOException {
            Path subDir = tempDir.resolve("subdir");
            Files.createDirectory(subDir);

            manager.registerTempDirectory(subDir);

            assertEquals(1, manager.getTempDirectoryCount());
        }

        @Test
        @DisplayName("should not register directory after close")
        void shouldNotRegisterDirectoryAfterClose() throws IOException {
            Path subDir = tempDir.resolve("subdir");
            Files.createDirectory(subDir);

            manager.close();
            manager.registerTempDirectory(subDir);

            assertEquals(0, manager.getTempDirectoryCount());
        }
    }

    @Nested
    @DisplayName("deleteTempFile")
    class DeleteTempFileTests {

        @Test
        @DisplayName("should delete registered file")
        void shouldDeleteRegisteredFile() throws IOException {
            Path tempFile = tempDir.resolve("test.tmp");
            Files.createFile(tempFile);
            manager.registerTempFile(tempFile);

            assertTrue(Files.exists(tempFile));
            assertTrue(manager.deleteTempFile(tempFile));
            assertFalse(Files.exists(tempFile));
            assertEquals(0, manager.getTempFileCount());
        }

        @Test
        @DisplayName("should return false for unregistered file")
        void shouldReturnFalseForUnregisteredFile() throws IOException {
            Path tempFile = tempDir.resolve("unregistered.tmp");
            Files.createFile(tempFile);

            assertFalse(manager.deleteTempFile(tempFile));
            assertTrue(Files.exists(tempFile)); // File should still exist
        }
    }

    @Nested
    @DisplayName("deleteTempDirectory")
    class DeleteTempDirectoryTests {

        @Test
        @DisplayName("should delete registered directory")
        void shouldDeleteRegisteredDirectory() throws IOException {
            Path subDir = tempDir.resolve("subdir");
            Files.createDirectory(subDir);
            manager.registerTempDirectory(subDir);

            assertTrue(Files.exists(subDir));
            assertTrue(manager.deleteTempDirectory(subDir));
            assertFalse(Files.exists(subDir));
            assertEquals(0, manager.getTempDirectoryCount());
        }

        @Test
        @DisplayName("should delete directory with contents")
        void shouldDeleteDirectoryWithContents() throws IOException {
            Path subDir = tempDir.resolve("subdir");
            Files.createDirectory(subDir);
            Files.createFile(subDir.resolve("file1.txt"));
            Files.createFile(subDir.resolve("file2.txt"));
            Path nestedDir = subDir.resolve("nested");
            Files.createDirectory(nestedDir);
            Files.createFile(nestedDir.resolve("nested-file.txt"));

            manager.registerTempDirectory(subDir);

            assertTrue(manager.deleteTempDirectory(subDir));
            assertFalse(Files.exists(subDir));
        }
    }

    @Nested
    @DisplayName("close")
    class CloseTests {

        @Test
        @DisplayName("should delete all registered files on close")
        void shouldDeleteAllRegisteredFilesOnClose() throws IOException {
            Path file1 = tempDir.resolve("file1.tmp");
            Path file2 = tempDir.resolve("file2.tmp");
            Files.createFile(file1);
            Files.createFile(file2);

            manager.registerTempFile(file1);
            manager.registerTempFile(file2);

            manager.close();

            assertFalse(Files.exists(file1));
            assertFalse(Files.exists(file2));
            assertTrue(manager.isClosed());
        }

        @Test
        @DisplayName("should delete all registered directories on close")
        void shouldDeleteAllRegisteredDirectoriesOnClose() throws IOException {
            Path dir1 = tempDir.resolve("dir1");
            Path dir2 = tempDir.resolve("dir2");
            Files.createDirectory(dir1);
            Files.createDirectory(dir2);

            manager.registerTempDirectory(dir1);
            manager.registerTempDirectory(dir2);

            manager.close();

            assertFalse(Files.exists(dir1));
            assertFalse(Files.exists(dir2));
        }

        @Test
        @DisplayName("close should be idempotent")
        void closeShouldBeIdempotent() throws IOException {
            Path tempFile = tempDir.resolve("test.tmp");
            Files.createFile(tempFile);
            manager.registerTempFile(tempFile);

            manager.close();
            manager.close(); // Should not throw

            assertTrue(manager.isClosed());
        }

        @Test
        @DisplayName("should handle already deleted files gracefully")
        void shouldHandleAlreadyDeletedFilesGracefully() throws IOException {
            Path tempFile = tempDir.resolve("test.tmp");
            Files.createFile(tempFile);
            manager.registerTempFile(tempFile);

            // Delete file before close
            Files.delete(tempFile);

            // Should not throw
            assertDoesNotThrow(() -> manager.close());
        }
    }

    @Nested
    @DisplayName("try-with-resources")
    class TryWithResourcesTests {

        @Test
        @DisplayName("should cleanup when used with try-with-resources")
        void shouldCleanupWhenUsedWithTryWithResources() throws IOException {
            Path tempFile = tempDir.resolve("auto-cleanup.tmp");
            Files.createFile(tempFile);

            try (TempFileManager autoManager = new TempFileManager()) {
                autoManager.registerTempFile(tempFile);
                assertTrue(Files.exists(tempFile));
            }

            assertFalse(Files.exists(tempFile));
        }
    }

    @Nested
    @DisplayName("isClosed")
    class IsClosedTests {

        @Test
        @DisplayName("should return false when not closed")
        void shouldReturnFalseWhenNotClosed() {
            assertFalse(manager.isClosed());
        }

        @Test
        @DisplayName("should return true after close")
        void shouldReturnTrueAfterClose() {
            manager.close();
            assertTrue(manager.isClosed());
        }
    }

    @Nested
    @DisplayName("counts")
    class CountTests {

        @Test
        @DisplayName("should track file count correctly")
        void shouldTrackFileCountCorrectly() throws IOException {
            assertEquals(0, manager.getTempFileCount());

            Path file1 = tempDir.resolve("file1.tmp");
            Path file2 = tempDir.resolve("file2.tmp");
            Files.createFile(file1);
            Files.createFile(file2);

            manager.registerTempFile(file1);
            assertEquals(1, manager.getTempFileCount());

            manager.registerTempFile(file2);
            assertEquals(2, manager.getTempFileCount());

            manager.deleteTempFile(file1);
            assertEquals(1, manager.getTempFileCount());
        }

        @Test
        @DisplayName("should track directory count correctly")
        void shouldTrackDirectoryCountCorrectly() throws IOException {
            assertEquals(0, manager.getTempDirectoryCount());

            Path dir1 = tempDir.resolve("dir1");
            Path dir2 = tempDir.resolve("dir2");
            Files.createDirectory(dir1);
            Files.createDirectory(dir2);

            manager.registerTempDirectory(dir1);
            assertEquals(1, manager.getTempDirectoryCount());

            manager.registerTempDirectory(dir2);
            assertEquals(2, manager.getTempDirectoryCount());

            manager.deleteTempDirectory(dir1);
            assertEquals(1, manager.getTempDirectoryCount());
        }
    }
}
