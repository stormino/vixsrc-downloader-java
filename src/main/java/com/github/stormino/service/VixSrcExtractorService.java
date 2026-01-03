package com.github.stormino.service;

import com.github.stormino.config.VixSrcProperties;
import com.github.stormino.model.PlaylistInfo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class VixSrcExtractorService {
    
    private final OkHttpClient httpClient;
    private final VixSrcProperties properties;
    
    // Regex patterns (ported from Python)
    private static final Pattern MASTER_PLAYLIST_PATTERN = 
            Pattern.compile("window\\.masterPlaylist\\s*=\\s*\\{[^}]*\\{[^}]*\\}[^}]*\\}");
    
    private static final Pattern PLAYLIST_DIRECT_PATTERN = 
            Pattern.compile("https://vixsrc\\.to/playlist/(\\d+)\\?[^\"']*");
    
    private static final Pattern API_ENDPOINTS_PATTERN = 
            Pattern.compile("[\"']([/]api/[^\"']+)[\"']");
    
    private static final Pattern VIDEO_ID_PATTERN = 
            Pattern.compile("video[_-]?id[\"']?\\s*[:=]\\s*[\"']?(\\d+)", Pattern.CASE_INSENSITIVE);
    
    /**
     * Get playlist URL for a movie
     */
    public Optional<PlaylistInfo> getMoviePlaylist(int tmdbId, String language) {
        String embedUrl = buildMovieUrl(tmdbId, language);
        return extractPlaylistUrl(embedUrl, language);
    }
    
    /**
     * Get playlist URL for a TV episode
     */
    public Optional<PlaylistInfo> getTvPlaylist(int tmdbId, int season, int episode, String language) {
        String embedUrl = buildTvUrl(tmdbId, season, episode, language);
        return extractPlaylistUrl(embedUrl, language);
    }
    
    /**
     * Extract playlist URL from embed page
     */
    public Optional<PlaylistInfo> extractPlaylistUrl(String embedUrl, String language) {
        log.info("Extracting playlist URL from: {}", embedUrl);

        try {
            String htmlContent = fetchEmbedPage(embedUrl);

            // Try multiple extraction strategies
            Optional<String> playlistUrl = extractFromMasterPlaylist(htmlContent, language)
                    .or(() -> extractFromDirectPattern(htmlContent))
                    .or(() -> extractFromApiEndpoints(htmlContent, embedUrl))
                    .or(() -> extractFromVideoId(htmlContent));
            
            if (playlistUrl.isPresent()) {
                String url = playlistUrl.get();
                boolean verified = verifyPlaylist(url, embedUrl);
                
                return Optional.of(PlaylistInfo.builder()
                        .url(url)
                        .language(language)
                        .verified(verified)
                        .build());
            }
            
            log.warn("Could not extract playlist URL from embed page");
            return Optional.empty();
            
        } catch (IOException e) {
            log.error("Error extracting playlist URL: {}", e.getMessage());
            return Optional.empty();
        }
    }
    
    private String fetchEmbedPage(String embedUrl) throws IOException {
        Request request = new Request.Builder()
                .url(embedUrl)
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Unexpected response code: " + response.code());
            }

            if (response.body() == null) {
                throw new IOException("Empty response body");
            }

            return response.body().string();
        }
    }
    
    /**
     * Strategy 1: Extract from window.masterPlaylist object
     */
    private Optional<String> extractFromMasterPlaylist(String html, String language) {
        Matcher matcher = MASTER_PLAYLIST_PATTERN.matcher(html);
        
        if (!matcher.find()) {
            return Optional.empty();
        }
        
        String section = matcher.group(0);
        
        // Extract components
        String url = extractValue(section, "url:\\s*['\"]([^'\"]+)['\"]");
        String token = extractValue(section, "['\"]token['\"]\\s*:\\s*['\"]([^'\"]+)['\"]");
        String expires = extractValue(section, "['\"]expires['\"]\\s*:\\s*['\"]([^'\"]+)['\"]");
        
        if (url == null || token == null || expires == null) {
            return Optional.empty();
        }
        
        // Build complete URL
        String playlistUrl = buildPlaylistUrl(url, token, expires, language, section);
        log.info("Extracted from masterPlaylist: {}", playlistUrl);
        
        return Optional.of(playlistUrl);
    }
    
    /**
     * Strategy 2: Direct regex pattern match
     */
    private Optional<String> extractFromDirectPattern(String html) {
        Matcher matcher = PLAYLIST_DIRECT_PATTERN.matcher(html);
        
        if (matcher.find()) {
            String url = matcher.group(0).replace("&amp;", "&");
            log.info("Extracted from direct pattern: {}", url);
            return Optional.of(url);
        }
        
        return Optional.empty();
    }
    
    /**
     * Strategy 3: Try API endpoints found in JavaScript
     */
    private Optional<String> extractFromApiEndpoints(String html, String embedUrl) {
        Matcher matcher = API_ENDPOINTS_PATTERN.matcher(html);
        
        while (matcher.find()) {
            String apiPath = matcher.group(1);
            String apiUrl = properties.getExtractor().getBaseUrl() + apiPath;
            
            try {
                Request request = new Request.Builder().url(apiUrl).build();
                try (Response response = httpClient.newCall(request).execute()) {
                    if (response.isSuccessful() && response.body() != null) {
                        String body = response.body().string();
                        if (body.contains("m3u8") || body.contains("playlist")) {
                            log.info("Extracted from API endpoint: {}", apiUrl);
                            return Optional.of(apiUrl);
                        }
                    }
                }
            } catch (IOException e) {
                log.debug("API endpoint failed: {}", apiUrl);
            }
        }
        
        return Optional.empty();
    }
    
    /**
     * Strategy 4: Extract video ID and try common patterns
     */
    private Optional<String> extractFromVideoId(String html) {
        Matcher matcher = VIDEO_ID_PATTERN.matcher(html);
        
        if (!matcher.find()) {
            return Optional.empty();
        }
        
        String videoId = matcher.group(1);
        String baseUrl = properties.getExtractor().getBaseUrl();
        
        String[] patterns = {
            baseUrl + "/playlist/" + videoId,
            baseUrl + "/api/playlist/" + videoId
        };
        
        for (String testUrl : patterns) {
            try {
                Request request = new Request.Builder().url(testUrl).build();
                try (Response response = httpClient.newCall(request).execute()) {
                    if (response.isSuccessful() && response.body() != null) {
                        String body = response.body().string();
                        if (body.contains("m3u8") || response.request().url().toString().contains("playlist")) {
                            String finalUrl = response.request().url().toString();
                            log.info("Extracted from video ID: {}", finalUrl);
                            return Optional.of(finalUrl);
                        }
                    }
                }
            } catch (IOException e) {
                log.debug("Video ID pattern failed: {}", testUrl);
            }
        }
        
        return Optional.empty();
    }
    
    private String buildPlaylistUrl(String base, String token, String expires, String language, String section) {
        // Extract ASN if present
        String asn = extractValue(section, "['\"]asn['\"]\\s*:\\s*['\"]([^'\"]*)['\"]");
        
        HttpUrl.Builder urlBuilder = HttpUrl.parse(base).newBuilder()
                .addQueryParameter("token", token)
                .addQueryParameter("expires", expires);
        
        if (asn != null && !asn.isEmpty()) {
            urlBuilder.addQueryParameter("asn", asn);
        }
        
        urlBuilder.addQueryParameter("h", "1")
                .addQueryParameter("lang", language);
        
        return urlBuilder.build().toString().replace("&amp;", "&");
    }
    
    private boolean verifyPlaylist(String playlistUrl, String referer) {
        try {
            Request request = new Request.Builder()
                    .url(playlistUrl)
                    .header("Referer", referer)
                    .header("Accept", "*/*")
                    .build();
            
            try (Response response = httpClient.newCall(request).execute()) {
                if (response.isSuccessful() && response.body() != null) {
                    String body = response.body().string();
                    if (body.startsWith("#EXTM3U")) {
                        log.info("Playlist URL verified successfully");
                        return true;
                    }
                }
            }
        } catch (IOException e) {
            log.warn("Playlist verification failed: {}", e.getMessage());
        }
        
        return false;
    }
    
    private String extractValue(String text, String pattern) {
        Matcher matcher = Pattern.compile(pattern).matcher(text);
        return matcher.find() ? matcher.group(1) : null;
    }
    
    private String buildMovieUrl(int tmdbId, String language) {
        return String.format("%s/movie/%d?lang=%s", 
                properties.getExtractor().getBaseUrl(), tmdbId, language);
    }
    
    private String buildTvUrl(int tmdbId, int season, int episode, String language) {
        return String.format("%s/tv/%d/%d/%d?lang=%s",
                properties.getExtractor().getBaseUrl(), tmdbId, season, episode, language);
    }
}
