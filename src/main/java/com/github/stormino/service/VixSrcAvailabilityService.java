package com.github.stormino.service;

import com.github.stormino.config.VixSrcProperties;
import com.github.stormino.model.AvailabilityResult;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class VixSrcAvailabilityService {

    private final OkHttpClient httpClient;
    private final VixSrcProperties properties;

    public VixSrcAvailabilityService(OkHttpClient httpClient, VixSrcProperties properties) {
        this.httpClient = httpClient;
        this.properties = properties;
    }

    /**
     * Check if a movie is available on vixsrc for any of the given languages
     */
    public AvailabilityResult checkMovieAvailability(int tmdbId, Set<String> languages) {
        Set<String> availableLanguages = new HashSet<>();

        for (String language : languages) {
            String url = buildMovieUrl(tmdbId, language);
            if (isUrlAvailable(url)) {
                availableLanguages.add(language);
                // Short-circuit: if we find it in one language, it's available
                break;
            }
        }

        return AvailabilityResult.builder()
                .available(!availableLanguages.isEmpty())
                .availableLanguages(availableLanguages)
                .build();
    }

    /**
     * Check if a TV show is available on vixsrc for any of the given languages
     * (checks S01E01)
     */
    public AvailabilityResult checkTvAvailability(int tmdbId, Set<String> languages) {
        Set<String> availableLanguages = new HashSet<>();

        for (String language : languages) {
            String url = buildTvUrl(tmdbId, 1, 1, language);
            if (isUrlAvailable(url)) {
                availableLanguages.add(language);
                // Short-circuit: if we find it in one language, it's available
                break;
            }
        }

        return AvailabilityResult.builder()
                .available(!availableLanguages.isEmpty())
                .availableLanguages(availableLanguages)
                .build();
    }

    /**
     * Check if a URL is available (returns 200-299)
     */
    private boolean isUrlAvailable(String url) {
        OkHttpClient clientWithTimeout = httpClient.newBuilder()
                .connectTimeout(3, TimeUnit.SECONDS)
                .readTimeout(3, TimeUnit.SECONDS)
                .build();

        Request request = new Request.Builder()
                .url(url)
                .head()
                .build();

        try (Response response = clientWithTimeout.newCall(request).execute()) {
            int code = response.code();

            if (code >= 200 && code < 300) {
                log.debug("Content available at: {}", url);
                return true;
            } else if (code == 503) {
                // Retry once for Cloudflare issues
                log.debug("Received 503, retrying: {}", url);
                Thread.sleep(1000);
                try (Response retryResponse = clientWithTimeout.newCall(request).execute()) {
                    boolean available = retryResponse.code() >= 200 && retryResponse.code() < 300;
                    log.debug("Retry result for {}: {}", url, available);
                    return available;
                }
            } else {
                log.debug("Content not available at {} (status: {})", url, code);
                return false;
            }
        } catch (Exception e) {
            log.debug("Failed to check availability for {}: {}", url, e.getMessage());
            return false;
        }
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
