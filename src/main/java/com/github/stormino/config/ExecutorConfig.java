package com.github.stormino.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

@Configuration
@RequiredArgsConstructor
public class ExecutorConfig {
    
    private final VixSrcProperties properties;
    
    @Bean(name = "downloadExecutor")
    public Executor downloadExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(properties.getDownload().getParallelDownloads());
        executor.setMaxPoolSize(properties.getDownload().getParallelDownloads());
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("download-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);
        executor.initialize();
        return executor;
    }

    @Bean(name = "trackExecutor")
    public Executor trackExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();

        // Calculate based on parallelDownloads × average tracks per download
        // Example: 3 downloads × 4 tracks = 12 concurrent track downloads
        int maxTracks = properties.getDownload().getParallelDownloads() * 4;

        executor.setCorePoolSize(maxTracks);
        executor.setMaxPoolSize(maxTracks);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("track-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);
        executor.initialize();
        return executor;
    }
}
