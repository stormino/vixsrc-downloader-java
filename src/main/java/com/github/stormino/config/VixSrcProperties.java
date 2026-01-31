package com.github.stormino.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;

import java.util.List;

@Data
@Validated
@Configuration
@ConfigurationProperties(prefix = "vixsrc")
public class VixSrcProperties {
    
    private Download download = new Download();
    private Tmdb tmdb = new Tmdb();
    private Extractor extractor = new Extractor();
    
    @Data
    public static class Download {
        @NotBlank
        private String moviesPath = "/downloads/movies";

        @NotBlank
        private String tvShowsPath = "/downloads/tvshows";

        @NotBlank
        private String tempPath = "/downloads/temp";

        @Min(1)
        private int parallelDownloads = 3;

        @Min(1)
        private int segmentConcurrency = 5;

        @NotBlank
        private String defaultQuality = "best";

        @NotBlank
        private String defaultLanguage = "en";

        public List<String> getDefaultLanguageList() {
            return List.of(defaultLanguage);
        }
    }
    
    @Data
    public static class Tmdb {
        private String apiKey;
        
        public boolean isConfigured() {
            return apiKey != null && !apiKey.isBlank();
        }
    }
    
    @Data
    public static class Extractor {
        @NotBlank
        private String baseUrl = "https://vixsrc.to";

        @Min(1)
        private int timeoutSeconds = 30;

        @NotBlank
        private String userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36";

        @Min(100)
        private long retryDelayMs = 2000;

        @Min(1)
        private int maxRetries = Integer.MAX_VALUE;

        @Min(1000)
        private long maxRetryDelayMs = 30000;

        @Min(1)
        private int retryBackoffMultiplier = 2;
    }
}
