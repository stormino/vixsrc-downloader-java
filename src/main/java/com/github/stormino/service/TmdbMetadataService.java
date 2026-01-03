package com.github.stormino.service;

import com.uwetrottmann.tmdb2.Tmdb;
import com.uwetrottmann.tmdb2.entities.*;
import com.uwetrottmann.tmdb2.enumerations.AppendToResponseItem;
import com.github.stormino.model.ContentMetadata;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import retrofit2.Response;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class TmdbMetadataService {
    
    private final Tmdb tmdb;
    
    public boolean isAvailable() {
        return tmdb != null;
    }
    
    /**
     * Get movie metadata
     */
    public Optional<ContentMetadata> getMovieMetadata(int tmdbId) {
        if (!isAvailable()) {
            return Optional.empty();
        }
        
        try {
            Response<Movie> response = tmdb.moviesService()
                    .summary(tmdbId, null, null)
                    .execute();
            
            if (!response.isSuccessful() || response.body() == null) {
                log.warn("Failed to fetch movie metadata for ID {}: {}", tmdbId, response.code());
                return Optional.empty();
            }
            
            Movie movie = response.body();
            
            Integer year = null;
            if (movie.release_date != null) {
                SimpleDateFormat sdf = new SimpleDateFormat("yyyy");
                year = Integer.parseInt(sdf.format(movie.release_date));
            }
            
            return Optional.of(ContentMetadata.builder()
                    .tmdbId(tmdbId)
                    .title(movie.title)
                    .originalTitle(movie.original_title)
                    .year(year)
                    .overview(movie.overview)
                    .voteAverage(movie.vote_average)
                    .build());
            
        } catch (IOException e) {
            log.error("Error fetching movie metadata for ID {}: {}", tmdbId, e.getMessage());
            return Optional.empty();
        }
    }
    
    /**
     * Get TV show episode metadata
     */
    public Optional<ContentMetadata> getTvMetadata(int tmdbId, int season, int episode) {
        if (!isAvailable()) {
            return Optional.empty();
        }
        
        try {
            // Get show info
            Response<TvShow> showResponse = tmdb.tvService()
                    .tv(tmdbId, null, null)
                    .execute();
            
            if (!showResponse.isSuccessful() || showResponse.body() == null) {
                log.warn("Failed to fetch TV show metadata for ID {}: {}", tmdbId, showResponse.code());
                return Optional.empty();
            }
            
            TvShow show = showResponse.body();
            
            // Get episode info
            Response<TvEpisode> episodeResponse = tmdb.tvEpisodesService()
                    .episode(tmdbId, season, episode, null, null)
                    .execute();
            
            String episodeName = null;
            if (episodeResponse.isSuccessful() && episodeResponse.body() != null) {
                episodeName = episodeResponse.body().name;
            }
            
            Integer year = null;
            if (show.first_air_date != null) {
                SimpleDateFormat sdf = new SimpleDateFormat("yyyy");
                year = Integer.parseInt(sdf.format(show.first_air_date));
            }
            
            return Optional.of(ContentMetadata.builder()
                    .tmdbId(tmdbId)
                    .title(show.name)
                    .originalTitle(show.original_name)
                    .year(year)
                    .season(season)
                    .episode(episode)
                    .episodeName(episodeName)
                    .numberOfSeasons(show.number_of_seasons)
                    .overview(show.overview)
                    .voteAverage(show.vote_average)
                    .build());
            
        } catch (IOException e) {
            log.error("Error fetching TV metadata for ID {}, S{}E{}: {}", 
                    tmdbId, season, episode, e.getMessage());
            return Optional.empty();
        }
    }
    
    /**
     * Search for movies
     */
    public List<ContentMetadata> searchMovies(String query) {
        if (!isAvailable()) {
            return List.of();
        }
        
        try {
            Response<MovieResultsPage> response = tmdb.searchService()
                    .movie(query, null, null, null, null, null, null)
                    .execute();
            
            if (!response.isSuccessful() || response.body() == null) {
                log.warn("Movie search failed: {}", response.code());
                return List.of();
            }
            
            List<ContentMetadata> results = new ArrayList<>();

            for (BaseMovie movie : response.body().results) {
                Integer year = null;
                if (movie.release_date != null) {
                    SimpleDateFormat sdf = new SimpleDateFormat("yyyy");
                    year = Integer.parseInt(sdf.format(movie.release_date));
                }

                results.add(ContentMetadata.builder()
                        .tmdbId(movie.id)
                        .title(movie.title)
                        .originalTitle(movie.original_title)
                        .year(year)
                        .overview(movie.overview)
                        .voteAverage(movie.vote_average)
                        .build());
            }
            
            return results;
            
        } catch (IOException e) {
            log.error("Error searching movies: {}", e.getMessage());
            return List.of();
        }
    }
    
    /**
     * Search for TV shows
     */
    public List<ContentMetadata> searchTvShows(String query) {
        if (!isAvailable()) {
            return List.of();
        }
        
        try {
            Response<TvShowResultsPage> response = tmdb.searchService()
                    .tv(query, null, null, null, null)
                    .execute();
            
            if (!response.isSuccessful() || response.body() == null) {
                log.warn("TV search failed: {}", response.code());
                return List.of();
            }
            
            List<ContentMetadata> results = new ArrayList<>();
            
            for (BaseTvShow show : response.body().results) {
                // Fetch detailed info for episode count
                try {
                    Response<TvShow> detailResponse = tmdb.tvService()
                            .tv(show.id, null, null)
                            .execute();
                    
                    Integer year = null;
                    Integer totalEpisodes = null;
                    Integer numberOfSeasons = null;
                    
                    if (detailResponse.isSuccessful() && detailResponse.body() != null) {
                        TvShow detail = detailResponse.body();

                        if (detail.first_air_date != null) {
                            SimpleDateFormat sdf = new SimpleDateFormat("yyyy");
                            year = Integer.parseInt(sdf.format(detail.first_air_date));
                        }

                        numberOfSeasons = detail.number_of_seasons;
                        totalEpisodes = detail.number_of_episodes;
                    }
                    
                    results.add(ContentMetadata.builder()
                            .tmdbId(show.id)
                            .title(show.name)
                            .originalTitle(show.original_name)
                            .year(year)
                            .numberOfSeasons(numberOfSeasons)
                            .totalEpisodes(totalEpisodes)
                            .overview(show.overview)
                            .voteAverage(show.vote_average)
                            .build());
                    
                } catch (Exception e) {
                    log.debug("Failed to fetch details for show {}: {}", show.id, e.getMessage());
                }
            }
            
            return results;
            
        } catch (IOException e) {
            log.error("Error searching TV shows: {}", e.getMessage());
            return List.of();
        }
    }
    
    /**
     * Get all seasons for a TV show
     */
    public List<TvSeason> getSeasons(int tmdbId) {
        if (!isAvailable()) {
            return List.of();
        }
        
        try {
            Response<TvShow> response = tmdb.tvService()
                    .tv(tmdbId, null, new AppendToResponse(AppendToResponseItem.IMAGES))
                    .execute();
            
            if (!response.isSuccessful() || response.body() == null) {
                return List.of();
            }
            
            // Filter out specials (season 0)
            return response.body().seasons.stream()
                    .filter(season -> season.season_number > 0)
                    .toList();
            
        } catch (IOException e) {
            log.error("Error fetching seasons for show {}: {}", tmdbId, e.getMessage());
            return List.of();
        }
    }
    
    /**
     * Get all episodes for a season
     */
    public List<TvEpisode> getEpisodes(int tmdbId, int season) {
        if (!isAvailable()) {
            return List.of();
        }
        
        try {
            Response<TvSeason> response = tmdb.tvSeasonsService()
                    .season(tmdbId, season, null, null)
                    .execute();
            
            if (!response.isSuccessful() || response.body() == null) {
                return List.of();
            }
            
            return response.body().episodes != null ? response.body().episodes : List.of();
            
        } catch (IOException e) {
            log.error("Error fetching episodes for show {}, season {}: {}", tmdbId, season, e.getMessage());
            return List.of();
        }
    }
}
