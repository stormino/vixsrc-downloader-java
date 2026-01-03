package com.github.stormino.controller;

import com.github.stormino.model.ContentMetadata;
import com.github.stormino.model.DownloadTask;
import com.github.stormino.service.DownloadQueueService;
import com.github.stormino.service.TmdbMetadataService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class DownloadController {
    
    private final DownloadQueueService downloadQueueService;
    private final TmdbMetadataService metadataService;
    
    /**
     * Search movies
     */
    @GetMapping("/search/movies")
    public ResponseEntity<List<ContentMetadata>> searchMovies(@RequestParam String query) {
        log.info("Searching movies: {}", query);
        List<ContentMetadata> results = metadataService.searchMovies(query);
        return ResponseEntity.ok(results);
    }
    
    /**
     * Search TV shows
     */
    @GetMapping("/search/tv")
    public ResponseEntity<List<ContentMetadata>> searchTv(@RequestParam String query) {
        log.info("Searching TV shows: {}", query);
        List<ContentMetadata> results = metadataService.searchTvShows(query);
        return ResponseEntity.ok(results);
    }
    
    /**
     * Add movie download
     */
    @PostMapping("/download/movie")
    public ResponseEntity<DownloadTask> downloadMovie(
            @RequestParam int tmdbId,
            @RequestParam(required = false) List<String> languages,
            @RequestParam(required = false) String quality) {
        
        log.info("Adding movie download: TMDB ID {}", tmdbId);
        
        DownloadTask task = downloadQueueService.addDownload(
                tmdbId,
                DownloadTask.ContentType.MOVIE,
                null,
                null,
                languages,
                quality
        );
        
        return ResponseEntity.ok(task);
    }
    
    /**
     * Add TV episode download
     */
    @PostMapping("/download/tv")
    public ResponseEntity<DownloadTask> downloadTv(
            @RequestParam int tmdbId,
            @RequestParam int season,
            @RequestParam int episode,
            @RequestParam(required = false) List<String> languages,
            @RequestParam(required = false) String quality) {
        
        log.info("Adding TV download: TMDB ID {}, S{}E{}", tmdbId, season, episode);
        
        DownloadTask task = downloadQueueService.addDownload(
                tmdbId,
                DownloadTask.ContentType.TV,
                season,
                episode,
                languages,
                quality
        );
        
        return ResponseEntity.ok(task);
    }
    
    /**
     * Get all downloads
     */
    @GetMapping("/downloads")
    public ResponseEntity<List<DownloadTask>> getAllDownloads() {
        return ResponseEntity.ok(downloadQueueService.getAllTasks());
    }
    
    /**
     * Get download by ID
     */
    @GetMapping("/downloads/{id}")
    public ResponseEntity<DownloadTask> getDownload(@PathVariable String id) {
        return downloadQueueService.getTask(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
    
    /**
     * Cancel download
     */
    @DeleteMapping("/downloads/{id}")
    public ResponseEntity<Void> cancelDownload(@PathVariable String id) {
        boolean cancelled = downloadQueueService.cancelTask(id);
        return cancelled ? ResponseEntity.ok().build() : ResponseEntity.notFound().build();
    }
}
