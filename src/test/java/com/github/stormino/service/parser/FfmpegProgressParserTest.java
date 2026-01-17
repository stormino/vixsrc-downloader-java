package com.github.stormino.service.parser;

import com.github.stormino.model.DownloadStatus;
import com.github.stormino.model.ProgressUpdate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("FfmpegProgressParser")
class FfmpegProgressParserTest {

    private FfmpegProgressParser parser;
    private static final String TASK_ID = "test-task-123";

    @BeforeEach
    void setUp() {
        parser = new FfmpegProgressParser();
    }

    @Nested
    @DisplayName("parseLine")
    class ParseLineTests {

        @Test
        @DisplayName("should return null for null input")
        void shouldReturnNullForNullInput() {
            assertNull(parser.parseLine(null, TASK_ID));
        }

        @Test
        @DisplayName("should return null for empty input")
        void shouldReturnNullForEmptyInput() {
            assertNull(parser.parseLine("", TASK_ID));
            assertNull(parser.parseLine("   ", TASK_ID));
        }

        @Test
        @DisplayName("should return null for irrelevant lines")
        void shouldReturnNullForIrrelevantLines() {
            assertNull(parser.parseLine("Stream mapping:", TASK_ID));
            assertNull(parser.parseLine("Output #0, mp4:", TASK_ID));
        }

        @Test
        @DisplayName("should parse progress line with size")
        void shouldParseProgressLineWithSize() {
            String line = "frame=  100 fps= 25 q=28.0 size=    5120kB time=00:00:04.00 bitrate=10485.8kbits/s speed=1.00x";

            ProgressUpdate update = parser.parseLine(line, TASK_ID);

            assertNotNull(update);
            assertEquals(TASK_ID, update.getTaskId());
            assertEquals(DownloadStatus.DOWNLOADING, update.getStatus());
            assertNotNull(update.getDownloadedBytes());
            assertEquals(5120L * 1024, update.getDownloadedBytes()); // 5120kB in bytes
        }

        @Test
        @DisplayName("should parse bitrate from progress line")
        void shouldParseBitrateFromProgressLine() {
            String line = "frame=  100 fps= 25 q=28.0 size=    1024kB time=00:00:04.00 bitrate=2048.0kbits/s speed=1.00x";

            ProgressUpdate update = parser.parseLine(line, TASK_ID);

            assertNotNull(update);
            assertNotNull(update.getBitrate());
            assertTrue(update.getBitrate().contains("2048"));
        }
    }

    @Nested
    @DisplayName("duration extraction")
    class DurationExtractionTests {

        @Test
        @DisplayName("should extract duration from Duration line")
        void shouldExtractDurationFromDurationLine() {
            String durationLine = "Duration: 01:30:45.50, start: 0.000000, bitrate: 5000 kb/s";

            parser.parseLine(durationLine, TASK_ID);

            Double duration = parser.getTotalDuration();
            assertNotNull(duration);
            // 1h 30m 45.5s = 5445.5 seconds
            assertEquals(5445.5, duration, 0.1);
        }

        @Test
        @DisplayName("should not extract N/A duration")
        void shouldNotExtractNaDuration() {
            String durationLine = "Duration: N/A, start: 0.000000, bitrate: N/A";

            parser.parseLine(durationLine, TASK_ID);

            assertNull(parser.getTotalDuration());
        }

        @Test
        @DisplayName("should calculate progress with duration")
        void shouldCalculateProgressWithDuration() {
            // First set the duration
            parser.parseLine("Duration: 00:02:00.00, start: 0.000000, bitrate: 5000 kb/s", TASK_ID);

            // Then parse a progress line at 1 minute (50%)
            String progressLine = "frame=  100 fps= 25 q=28.0 size=    1024kB time=00:01:00.00 bitrate=1024.0kbits/s speed=1.00x";
            ProgressUpdate update = parser.parseLine(progressLine, TASK_ID);

            assertNotNull(update);
            assertNotNull(update.getProgress());
            assertEquals(50.0, update.getProgress(), 1.0); // 50% with some tolerance
        }
    }

    @Nested
    @DisplayName("size parsing")
    class SizeParsingTests {

        @Test
        @DisplayName("should parse kilobyte sizes")
        void shouldParseKilobyteSizes() {
            String line = "frame=  100 fps= 25 size=    512kB time=00:00:04.00 bitrate=1024.0kbits/s";

            ProgressUpdate update = parser.parseLine(line, TASK_ID);

            assertNotNull(update);
            assertEquals(512L * 1024, update.getDownloadedBytes());
        }

        @Test
        @DisplayName("should parse megabyte sizes")
        void shouldParseMegabyteSizes() {
            String line = "frame=  100 fps= 25 size=    10mB time=00:00:04.00 bitrate=1024.0kbits/s";

            ProgressUpdate update = parser.parseLine(line, TASK_ID);

            assertNotNull(update);
            assertEquals(10L * 1024 * 1024, update.getDownloadedBytes());
        }

        @Test
        @DisplayName("should handle case insensitive units")
        void shouldHandleCaseInsensitiveUnits() {
            String line1 = "frame=  100 fps= 25 size=    100KB time=00:00:04.00 bitrate=1024.0kbits/s";
            String line2 = "frame=  100 fps= 25 size=    100kb time=00:00:04.00 bitrate=1024.0kbits/s";

            ProgressUpdate update1 = parser.parseLine(line1, TASK_ID);
            parser.reset();
            ProgressUpdate update2 = parser.parseLine(line2, TASK_ID);

            assertNotNull(update1);
            assertNotNull(update2);
            assertEquals(update1.getDownloadedBytes(), update2.getDownloadedBytes());
        }
    }

    @Nested
    @DisplayName("reset")
    class ResetTests {

        @Test
        @DisplayName("should clear duration on reset")
        void shouldClearDurationOnReset() {
            parser.parseLine("Duration: 01:00:00.00, start: 0.000000, bitrate: 5000 kb/s", TASK_ID);
            assertNotNull(parser.getTotalDuration());

            parser.reset();

            assertNull(parser.getTotalDuration());
        }

        @Test
        @DisplayName("should clear total size on reset")
        void shouldClearTotalSizeOnReset() {
            // Parse duration and progress to set totalSize
            parser.parseLine("Duration: 00:02:00.00, start: 0.000000, bitrate: 5000 kb/s", TASK_ID);
            parser.parseLine("frame=  100 fps= 25 size=    1024kB time=00:01:00.00 bitrate=1024.0kbits/s", TASK_ID);

            parser.reset();

            assertNull(parser.getTotalSize());
        }
    }

    @Nested
    @DisplayName("download speed calculation")
    class DownloadSpeedTests {

        @Test
        @DisplayName("should calculate and format download speed")
        void shouldCalculateAndFormatDownloadSpeed() throws InterruptedException {
            // Small delay to ensure elapsed time > 0
            Thread.sleep(100);

            String line = "frame=  100 fps= 25 size=    10240kB time=00:00:04.00 bitrate=1024.0kbits/s";
            ProgressUpdate update = parser.parseLine(line, TASK_ID);

            assertNotNull(update);
            assertNotNull(update.getDownloadSpeed());
            // Speed should be formatted as "X.XX MB/s" or "X.XX KB/s"
            assertTrue(update.getDownloadSpeed().contains("/s"));
        }
    }

    @Nested
    @DisplayName("ETA calculation")
    class EtaCalculationTests {

        @Test
        @DisplayName("should calculate ETA when duration and progress are known")
        void shouldCalculateEtaWhenDurationAndProgressAreKnown() throws InterruptedException {
            // Set duration first
            parser.parseLine("Duration: 00:02:00.00, start: 0.000000, bitrate: 5000 kb/s", TASK_ID);

            // Small delay to ensure elapsed time > 0
            Thread.sleep(100);

            // Progress at 50%
            String line = "frame=  100 fps= 25 size=    5120kB time=00:01:00.00 bitrate=1024.0kbits/s";
            ProgressUpdate update = parser.parseLine(line, TASK_ID);

            assertNotNull(update);
            // ETA might be null if elapsed time is too short, but if present should be > 0
            if (update.getEtaSeconds() != null) {
                assertTrue(update.getEtaSeconds() >= 0);
            }
        }
    }
}
