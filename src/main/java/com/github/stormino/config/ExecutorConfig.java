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
}
