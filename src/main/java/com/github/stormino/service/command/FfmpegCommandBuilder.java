package com.github.stormino.service.command;

import com.github.stormino.model.DownloadSubTask;
import com.github.stormino.util.DownloadConstants;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Builder for constructing ffmpeg command-line arguments.
 * Centralizes all ffmpeg command construction logic for consistency and testability.
 */
@Component
@Slf4j
public class FfmpegCommandBuilder {

    /**
     * Build ffmpeg command for converting video TS to MP4.
     *
     * @param inputFile Input TS file
     * @param outputFile Output MP4 file
     * @return ffmpeg command arguments
     */
    public List<String> buildVideoConversionCommand(@NonNull Path inputFile, @NonNull Path outputFile) {
        List<String> command = new ArrayList<>();
        command.add("ffmpeg");
        command.add("-hide_banner");
        command.add("-loglevel");
        command.add(DownloadConstants.FFMPEG_LOG_LEVEL);
        command.add("-stats");
        command.add("-i");
        command.add(inputFile.toString());
        command.add("-c");
        command.add("copy");
        command.add("-bsf:a");
        command.add(DownloadConstants.FFMPEG_AAC_BSF);
        command.add("-y");
        command.add(outputFile.toString());

        log.debug("Built video conversion command: {}", String.join(" ", command));
        return command;
    }

    /**
     * Build ffmpeg command for converting audio TS to M4A.
     *
     * @param inputFile Input TS file
     * @param outputFile Output M4A file
     * @return ffmpeg command arguments
     */
    public List<String> buildAudioConversionCommand(@NonNull Path inputFile, @NonNull Path outputFile) {
        List<String> command = new ArrayList<>();
        command.add("ffmpeg");
        command.add("-hide_banner");
        command.add("-loglevel");
        command.add(DownloadConstants.FFMPEG_LOG_LEVEL);
        command.add("-stats");
        command.add("-i");
        command.add(inputFile.toString());
        command.add("-c");
        command.add("copy");
        command.add("-vn");  // No video
        command.add("-y");
        command.add(outputFile.toString());

        log.debug("Built audio conversion command: {}", String.join(" ", command));
        return command;
    }

    /**
     * Build ffmpeg command for merging video, audio, and subtitle tracks.
     *
     * @param videoFile Video track file
     * @param audioTracks List of audio track files with metadata
     * @param subtitleTracks List of subtitle track files with metadata
     * @param outputFile Final output file
     * @return ffmpeg command arguments
     */
    public List<String> buildMergeCommand(
            @NonNull Path videoFile,
            @NonNull List<AudioTrackInput> audioTracks,
            @NonNull List<SubtitleTrackInput> subtitleTracks,
            @NonNull Path outputFile) {

        List<String> command = new ArrayList<>();
        command.add("ffmpeg");
        command.add("-hide_banner");
        command.add("-loglevel");
        command.add(DownloadConstants.FFMPEG_LOG_LEVEL_MERGE);
        command.add("-stats");

        // Video input
        command.add("-i");
        command.add(videoFile.toString());

        // Audio inputs
        for (AudioTrackInput audioTrack : audioTracks) {
            command.add("-i");
            command.add(audioTrack.getFile().toString());
        }

        // Subtitle inputs
        for (SubtitleTrackInput subtitleTrack : subtitleTracks) {
            command.add("-i");
            command.add(subtitleTrack.getFile().toString());
        }

        // Map video
        command.add("-map");
        command.add("0:v:0");

        // Map audio tracks
        if (audioTracks.isEmpty()) {
            // No separate audio tracks - copy audio from video
            command.add("-map");
            command.add("0:a?");  // ? makes it optional
        } else {
            // Map all separate audio tracks
            for (int i = 1; i <= audioTracks.size(); i++) {
                command.add("-map");
                command.add(i + ":a:0");
            }
        }

        // Map subtitles
        int subtitleInputOffset = 1 + audioTracks.size();
        for (int i = 0; i < subtitleTracks.size(); i++) {
            command.add("-map");
            command.add((subtitleInputOffset + i) + ":s:0");
        }

        // Copy video and audio codecs (no re-encoding)
        command.add("-c:v");
        command.add("copy");
        command.add("-c:a");
        command.add("copy");

        // Subtitle codec
        if (!subtitleTracks.isEmpty()) {
            command.add("-c:s");
            command.add(DownloadConstants.FFMPEG_SUBTITLE_CODEC);
        }

        // Audio metadata (only if we have separate audio tracks)
        if (!audioTracks.isEmpty()) {
            for (int i = 0; i < audioTracks.size(); i++) {
                AudioTrackInput audioTrack = audioTracks.get(i);
                if (audioTrack.getLanguage() != null) {
                    command.add(String.format("-metadata:s:a:%d", i));
                    command.add(String.format("language=%s", audioTrack.getLanguage()));
                }
                if (audioTrack.getTitle() != null) {
                    command.add(String.format("-metadata:s:a:%d", i));
                    command.add(String.format("title=%s", audioTrack.getTitle()));
                }
            }
        }

        // Subtitle metadata
        for (int i = 0; i < subtitleTracks.size(); i++) {
            SubtitleTrackInput subtitleTrack = subtitleTracks.get(i);
            if (subtitleTrack.getLanguage() != null) {
                command.add(String.format("-metadata:s:s:%d", i));
                command.add(String.format("language=%s", subtitleTrack.getLanguage()));
            }
            if (subtitleTrack.getTitle() != null) {
                command.add(String.format("-metadata:s:s:%d", i));
                command.add(String.format("title=%s", subtitleTrack.getTitle()));
            }
        }

        // Mark first audio track as default (only if we have separate audio tracks)
        if (!audioTracks.isEmpty()) {
            command.add("-disposition:a:0");
            command.add("default");
        }

        // Mark first subtitle track as default
        if (!subtitleTracks.isEmpty()) {
            command.add("-disposition:s:0");
            command.add("default");
        }

        command.add("-y");
        command.add(outputFile.toString());

        log.debug("Built merge command: {}", String.join(" ", command));
        return command;
    }

    /**
     * Input for audio track in merge operation.
     */
    public static class AudioTrackInput {
        private final Path file;
        private final String language;
        private final String title;

        public AudioTrackInput(Path file, DownloadSubTask subTask) {
            this.file = file;
            this.language = subTask.getLanguage();
            this.title = subTask.getTitle();
        }

        public Path getFile() {
            return file;
        }

        public String getLanguage() {
            return language;
        }

        public String getTitle() {
            return title;
        }
    }

    /**
     * Input for subtitle track in merge operation.
     */
    public static class SubtitleTrackInput {
        private final Path file;
        private final String language;
        private final String title;

        public SubtitleTrackInput(Path file, DownloadSubTask subTask) {
            this.file = file;
            this.language = subTask.getLanguage();
            this.title = subTask.getTitle();
        }

        public Path getFile() {
            return file;
        }

        public String getLanguage() {
            return language;
        }

        public String getTitle() {
            return title;
        }
    }
}
