package com.github.stormino.config;

import com.uwetrottmann.tmdb2.Tmdb;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Slf4j
@Configuration
@RequiredArgsConstructor
public class TmdbConfig {
    
    private final VixSrcProperties properties;
    
    @Bean
    public Tmdb tmdb() {
        if (!properties.getTmdb().isConfigured()) {
            log.warn("TMDB API key not configured. Metadata features will be disabled.");
            log.info("Set TMDB_API_KEY environment variable to enable metadata features.");
            return null;
        }
        
        Tmdb tmdb = new Tmdb(properties.getTmdb().getApiKey());
        log.info("TMDB client initialized successfully");
        return tmdb;
    }
}
