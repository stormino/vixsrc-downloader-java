package com.github.stormino.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Cookie;
import okhttp3.CookieJar;
import okhttp3.HttpUrl;
import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.jetbrains.annotations.NotNull;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

@Slf4j
@Configuration
@RequiredArgsConstructor
public class HttpClientConfig {
    
    private final VixSrcProperties properties;
    
    @Bean
    public OkHttpClient okHttpClient() {
        return new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(properties.getExtractor().getTimeoutSeconds()))
                .readTimeout(Duration.ofSeconds(properties.getExtractor().getTimeoutSeconds()))
                .writeTimeout(Duration.ofSeconds(properties.getExtractor().getTimeoutSeconds()))
                .addInterceptor(new CloudflareInterceptor())
                .addInterceptor(new RetryInterceptor(Integer.MAX_VALUE))
                .cookieJar(new InMemoryCookieJar())
                .followRedirects(true)
                .followSslRedirects(true)
                .build();
    }
    
    /**
     * Interceptor to handle Cloudflare protection
     */
    private class CloudflareInterceptor implements Interceptor {
        
        @NotNull
        @Override
        public Response intercept(@NotNull Chain chain) throws IOException {
            Request original = chain.request();
            
            // Build request with proper headers to mimic browser behavior
            // NOTE: Don't set Accept-Encoding manually - OkHttp handles compression automatically
            Request request = original.newBuilder()
                    .header("User-Agent", properties.getExtractor().getUserAgent())
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .header("Accept-Language", "en-US,en;q=0.5")
                    .header("Connection", "keep-alive")
                    .header("Upgrade-Insecure-Requests", "1")
                    .header("Sec-Fetch-Dest", "document")
                    .header("Sec-Fetch-Mode", "navigate")
                    .header("Sec-Fetch-Site", "none")
                    .header("Cache-Control", "max-age=0")
                    .build();
            
            Response response = chain.proceed(request);
            
            // Check for Cloudflare challenge
            if (response.code() == 403 || response.code() == 503) {
                String body = response.peekBody(1024).string();
                if (body.contains("cloudflare") || body.contains("cf-browser-verification")) {
                    log.warn("Cloudflare challenge detected on {}", original.url());
                    // For now, just log - in production, might need browser automation
                    // or more sophisticated challenge solving
                }
            }
            
            return response;
        }
    }
    
    /**
     * Retry interceptor with exponential backoff
     */
    private class RetryInterceptor implements Interceptor {
        private final int maxRetries;
        
        public RetryInterceptor(int maxRetries) {
            this.maxRetries = maxRetries;
        }
        
        @NotNull
        @Override
        public Response intercept(@NotNull Chain chain) throws IOException {
            Request request = chain.request();
            Response response = null;
            IOException lastException = null;
            
            for (int attempt = 0; attempt < maxRetries; attempt++) {
                try {
                    if (response != null) {
                        response.close();
                    }
                    
                    response = chain.proceed(request);
                    
                    if (response.isSuccessful()) {
                        return response;
                    }
                    
                    // Retry on 5xx errors
                    if (response.code() >= 500) {
                        log.debug("Server error {} on attempt {}/{} for {}",
                                response.code(), attempt + 1, maxRetries, request.url());
                        
                        if (attempt < maxRetries - 1) {
                            response.close();
                            sleep(attempt);
                            continue;
                        }
                    }
                    
                    return response;
                    
                } catch (IOException e) {
                    lastException = e;
                    log.warn("Network error on attempt {}/{} for {}: {}",
                            attempt + 1, maxRetries, request.url(), e.getMessage());
                    
                    if (attempt < maxRetries - 1) {
                        sleep(attempt);
                    }
                }
            }
            
            if (response != null) {
                return response;
            }
            
            throw lastException != null ? lastException : new IOException("Max retries exceeded");
        }
        
        private void sleep(int attempt) {
            try {
                long delay = properties.getExtractor().getRetryDelayMs() * (long) Math.pow(2, attempt);
                TimeUnit.MILLISECONDS.sleep(delay);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
    
    /**
     * Simple in-memory cookie jar
     */
    private static class InMemoryCookieJar implements CookieJar {
        private final java.util.Map<String, java.util.List<Cookie>> cookieStore = new java.util.HashMap<>();
        
        @Override
        public void saveFromResponse(@NotNull HttpUrl url, @NotNull java.util.List<Cookie> cookies) {
            cookieStore.put(url.host(), cookies);
        }
        
        @NotNull
        @Override
        public java.util.List<Cookie> loadForRequest(@NotNull HttpUrl url) {
            java.util.List<Cookie> cookies = cookieStore.get(url.host());
            return cookies != null ? cookies : new java.util.ArrayList<>();
        }
    }
}
