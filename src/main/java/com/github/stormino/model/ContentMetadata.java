package com.github.stormino.model;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ContentMetadata {
    
    private Integer tmdbId;
    private String title;
    private String originalTitle;
    private Integer year;
    private String overview;
    private Double voteAverage;
    
    // TV-specific
    private Integer season;
    private Integer episode;
    private String episodeName;
    private Integer numberOfSeasons;
    private Integer totalEpisodes;
    
    public String generateFilename(String language, String extension) {
        StringBuilder filename = new StringBuilder();
        
        String cleanTitle = sanitizeFilename(title != null ? title : "Unknown");
        filename.append(cleanTitle.replace(" ", "."));
        
        if (season != null && episode != null) {
            // TV format: Show.S01E01.EpisodeName.mp4
            filename.append(String.format(".S%02dE%02d", season, episode));
            if (episodeName != null && !episodeName.isBlank()) {
                String cleanEpisode = sanitizeFilename(episodeName);
                filename.append(".").append(cleanEpisode.replace(" ", "."));
            }
        } else if (year != null) {
            // Movie format: Title.2023.mp4
            filename.append(".").append(year);
        }
        
        filename.append(".").append(extension);
        return filename.toString();
    }
    
    private String sanitizeFilename(String filename) {
        return filename
                .replaceAll("[<>:\"/\\\\|?*]", "")
                .replaceAll("\\s+", " ")
                .trim();
    }
}
