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
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
@Slf4j
public class HlsParserService {

    private final OkHttpClient httpClient;

    @Data
    @Builder
    public static class HlsPlaylist {
        private PlaylistType type;
        private List<VideoVariant> videoVariants;
        private List<AudioTrack> audioTracks;
        private List<SubtitleTrack> subtitleTracks;
        private List<String> segments;
        private String baseUrl;
    }

    @Data
    @Builder
    public static class VideoVariant {
        private String url;
        private String resolution;
        private int bandwidth;
        private String codecs;
    }

    @Data
    @Builder
    public static class AudioTrack {
        private String url;
        private String language;
        private String name;
        private String groupId;
    }

    @Data
    @Builder
    public static class SubtitleTrack {
        private String url;
        private String language;
        private String name;
    }

    @Data
    @Builder
    public static class EncryptionInfo {
        private String method;  // e.g., "AES-128", "NONE"
        private String uri;     // Key URI
        private String iv;      // Initialization Vector (hex string)
    }

    @Data
    @Builder
    public static class MediaPlaylistInfo {
        private List<String> segments;
        private EncryptionInfo encryption;
        private String baseUrl;
    }

    public enum PlaylistType {
        MASTER,  // Contains references to media playlists
        MEDIA    // Contains segment URLs
    }

    /**
     * Parse HLS playlist from URL
     */
    public Optional<HlsPlaylist> parsePlaylist(String playlistUrl, String referer) {
        try {
            String content = fetchPlaylistContent(playlistUrl, referer);
            if (content == null) {
                return Optional.empty();
            }

            String baseUrl = extractBaseUrl(playlistUrl);

            // Detect playlist type
            if (content.contains("#EXT-X-STREAM-INF") || content.contains("#EXT-X-MEDIA")) {
                // Master playlist
                return Optional.of(parseMasterPlaylist(content, baseUrl));
            } else if (content.contains("#EXTINF")) {
                // Media playlist with segments
                return Optional.of(parseMediaPlaylist(content, baseUrl));
            }

            log.warn("Unknown playlist format");
            return Optional.empty();

        } catch (Exception e) {
            log.error("Failed to parse playlist: {}", e.getMessage(), e);
            return Optional.empty();
        }
    }

    /**
     * Parse segments from a media playlist URL
     */
    public Optional<List<String>> parseSegments(String mediaPlaylistUrl, String referer) {
        try {
            String content = fetchPlaylistContent(mediaPlaylistUrl, referer);
            if (content == null) {
                return Optional.empty();
            }

            String baseUrl = extractBaseUrl(mediaPlaylistUrl);
            HlsPlaylist playlist = parseMediaPlaylist(content, baseUrl);
            return Optional.of(playlist.getSegments());

        } catch (Exception e) {
            log.error("Failed to parse segments: {}", e.getMessage(), e);
            return Optional.empty();
        }
    }

    /**
     * Parse media playlist with encryption info
     */
    public Optional<MediaPlaylistInfo> parseMediaPlaylistInfo(String mediaPlaylistUrl, String referer) {
        try {
            String content = fetchPlaylistContent(mediaPlaylistUrl, referer);
            if (content == null) {
                return Optional.empty();
            }

            String baseUrl = extractBaseUrl(mediaPlaylistUrl);
            return Optional.of(parseMediaPlaylistWithEncryption(content, baseUrl));

        } catch (Exception e) {
            log.error("Failed to parse media playlist info: {}", e.getMessage(), e);
            return Optional.empty();
        }
    }

    private HlsPlaylist parseMasterPlaylist(String content, String baseUrl) {
        List<VideoVariant> videoVariants = new ArrayList<>();
        List<AudioTrack> audioTracks = new ArrayList<>();
        List<SubtitleTrack> subtitleTracks = new ArrayList<>();

        String[] lines = content.split("\n");

        // Parse audio tracks
        Pattern audioUriPattern = Pattern.compile("URI=\"([^\"]+)\"");
        Pattern audioLangPattern = Pattern.compile("LANGUAGE=\"([^\"]+)\"");
        for (String line : lines) {
            if (line.contains("#EXT-X-MEDIA:TYPE=AUDIO")) {
                Matcher uriMatcher = audioUriPattern.matcher(line);
                Matcher langMatcher = audioLangPattern.matcher(line);

                if (uriMatcher.find() && langMatcher.find()) {
                    String url = resolveUrl(baseUrl, uriMatcher.group(1));
                    String language = langMatcher.group(1);

                    audioTracks.add(AudioTrack.builder()
                            .url(url)
                            .language(language)
                            .name("Audio - " + language.toUpperCase())
                            .build());
                }
            }
        }

        // Parse video variants
        Pattern streamPattern = Pattern.compile("#EXT-X-STREAM-INF:.*?BANDWIDTH=(\\d+).*?RESOLUTION=([0-9x]+)");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            Matcher streamMatcher = streamPattern.matcher(line);

            if (streamMatcher.find()) {
                int bandwidth = Integer.parseInt(streamMatcher.group(1));
                String resolution = streamMatcher.group(2);

                // Next line contains the URL
                if (i + 1 < lines.length) {
                    String url = resolveUrl(baseUrl, lines[i + 1].trim());

                    videoVariants.add(VideoVariant.builder()
                            .url(url)
                            .resolution(resolution)
                            .bandwidth(bandwidth)
                            .build());
                }
            }
        }

        // Parse subtitle tracks
        Pattern subtitleUriPattern = Pattern.compile("URI=\"([^\"]+)\"");
        Pattern subtitleLangPattern = Pattern.compile("LANGUAGE=\"([^\"]+)\"");
        for (String line : lines) {
            if (line.contains("#EXT-X-MEDIA:TYPE=SUBTITLES")) {
                Matcher uriMatcher = subtitleUriPattern.matcher(line);
                Matcher langMatcher = subtitleLangPattern.matcher(line);

                if (uriMatcher.find() && langMatcher.find()) {
                    String url = resolveUrl(baseUrl, uriMatcher.group(1));
                    String language = langMatcher.group(1);

                    subtitleTracks.add(SubtitleTrack.builder()
                            .url(url)
                            .language(language)
                            .name("Subtitle - " + language.toUpperCase())
                            .build());
                }
            }
        }

        log.debug("Parsed master playlist: {} video variants, {} audio tracks, {} subtitle tracks",
                videoVariants.size(), audioTracks.size(), subtitleTracks.size());

        return HlsPlaylist.builder()
                .type(PlaylistType.MASTER)
                .videoVariants(videoVariants)
                .audioTracks(audioTracks)
                .subtitleTracks(subtitleTracks)
                .baseUrl(baseUrl)
                .build();
    }

    private HlsPlaylist parseMediaPlaylist(String content, String baseUrl) {
        List<String> segments = new ArrayList<>();
        String[] lines = content.split("\n");

        for (String line : lines) {
            line = line.trim();
            if (!line.isEmpty() && !line.startsWith("#")) {
                // This is a segment URL
                String segmentUrl = resolveUrl(baseUrl, line);
                segments.add(segmentUrl);
            }
        }

        log.debug("Parsed media playlist: {} segments", segments.size());

        return HlsPlaylist.builder()
                .type(PlaylistType.MEDIA)
                .segments(segments)
                .baseUrl(baseUrl)
                .build();
    }

    private MediaPlaylistInfo parseMediaPlaylistWithEncryption(String content, String baseUrl) {
        List<String> segments = new ArrayList<>();
        EncryptionInfo encryption = null;
        String[] lines = content.split("\n");

        // Patterns for parsing encryption info
        Pattern keyMethodPattern = Pattern.compile("METHOD=([^,]+)");
        Pattern keyUriPattern = Pattern.compile("URI=\"([^\"]+)\"");
        Pattern keyIvPattern = Pattern.compile("IV=0x([0-9A-Fa-f]+)");

        for (String line : lines) {
            line = line.trim();

            // Parse encryption key
            if (line.startsWith("#EXT-X-KEY:")) {
                Matcher methodMatcher = keyMethodPattern.matcher(line);
                Matcher uriMatcher = keyUriPattern.matcher(line);
                Matcher ivMatcher = keyIvPattern.matcher(line);

                String method = methodMatcher.find() ? methodMatcher.group(1) : null;
                String uri = uriMatcher.find() ? resolveUrl(baseUrl, uriMatcher.group(1)) : null;
                String iv = ivMatcher.find() ? ivMatcher.group(1) : null;

                if (method != null && !method.equals("NONE")) {
                    encryption = EncryptionInfo.builder()
                            .method(method)
                            .uri(uri)
                            .iv(iv)
                            .build();
                    log.debug("Found encryption: method={}, uri={}, iv={}", method, uri != null, iv != null);
                }
            }

            // Parse segment URLs
            if (!line.isEmpty() && !line.startsWith("#")) {
                String segmentUrl = resolveUrl(baseUrl, line);
                segments.add(segmentUrl);
            }
        }

        log.debug("Parsed media playlist: {} segments, encrypted={}", segments.size(), encryption != null);

        return MediaPlaylistInfo.builder()
                .segments(segments)
                .encryption(encryption)
                .baseUrl(baseUrl)
                .build();
    }

    private String fetchPlaylistContent(String url, String referer) {
        try {
            Request.Builder requestBuilder = new Request.Builder()
                    .url(url)
                    .addHeader("Accept", "*/*");

            if (referer != null) {
                requestBuilder.addHeader("Referer", referer);
            }

            try (Response response = httpClient.newCall(requestBuilder.build()).execute()) {
                if (!response.isSuccessful()) {
                    log.error("Failed to fetch playlist: HTTP {}", response.code());
                    return null;
                }
                return response.body().string();
            }

        } catch (IOException e) {
            log.error("Failed to fetch playlist content: {}", e.getMessage());
            return null;
        }
    }

    private String extractBaseUrl(String url) {
        try {
            URI uri = new URI(url);
            String path = uri.getPath();
            int lastSlash = path.lastIndexOf('/');
            String basePath = lastSlash >= 0 ? path.substring(0, lastSlash + 1) : path;

            return uri.getScheme() + "://" + uri.getHost() +
                   (uri.getPort() != -1 ? ":" + uri.getPort() : "") + basePath;
        } catch (URISyntaxException e) {
            log.warn("Failed to parse URL: {}", e.getMessage());
            return url.substring(0, url.lastIndexOf('/') + 1);
        }
    }

    private String resolveUrl(String baseUrl, String url) {
        if (url.startsWith("http://") || url.startsWith("https://")) {
            return url;
        }

        if (url.startsWith("/")) {
            try {
                URI baseUri = new URI(baseUrl);
                return baseUri.getScheme() + "://" + baseUri.getHost() +
                       (baseUri.getPort() != -1 ? ":" + baseUri.getPort() : "") + url;
            } catch (URISyntaxException e) {
                log.warn("Failed to resolve absolute URL: {}", e.getMessage());
            }
        }

        return baseUrl + url;
    }
}
