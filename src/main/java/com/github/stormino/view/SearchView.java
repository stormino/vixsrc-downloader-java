package com.github.stormino.view;

import com.vaadin.flow.component.Key;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.notification.NotificationVariant;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.radiobutton.RadioButtonGroup;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.theme.lumo.LumoUtility;
import com.github.stormino.config.VixSrcProperties;
import com.github.stormino.model.ContentMetadata;
import com.github.stormino.model.DownloadTask;
import com.github.stormino.service.DownloadQueueService;
import com.github.stormino.service.TmdbMetadataService;
import com.github.stormino.service.VixSrcAvailabilityService;
import com.github.stormino.view.component.SearchResultCard;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

@Route(value = "", layout = MainLayout.class)
@PageTitle("Search | VixSrc Downloader")
public class SearchView extends VerticalLayout {
    
    private final TmdbMetadataService tmdbService;
    private final DownloadQueueService downloadQueueService;
    private final VixSrcAvailabilityService availabilityService;
    private final VixSrcProperties properties;

    private final TextField searchField;
    private final RadioButtonGroup<String> contentTypeGroup;
    private final Button searchButton;
    private final Div resultsContainer;

    public SearchView(TmdbMetadataService tmdbService, DownloadQueueService downloadQueueService,
                     VixSrcAvailabilityService availabilityService, VixSrcProperties properties) {
        this.tmdbService = tmdbService;
        this.downloadQueueService = downloadQueueService;
        this.availabilityService = availabilityService;
        this.properties = properties;
        
        setSizeFull();
        setPadding(false);
        setSpacing(false);
        getStyle().set("padding", "1rem");

        // Header
        H2 title = new H2("Search Movies & TV Shows");
        title.addClassNames(LumoUtility.Margin.Top.NONE, LumoUtility.Margin.Bottom.SMALL);
        
        // Search controls
        searchField = new TextField();
        searchField.setPlaceholder("Search movies and TV shows...");
        searchField.setWidthFull();
        searchField.addKeyPressListener(Key.ENTER, e -> performSearch());
        searchField.getStyle().set("min-width", "200px");

        contentTypeGroup = new RadioButtonGroup<>();
        contentTypeGroup.setLabel("Type");
        contentTypeGroup.setItems("Movies", "TV Shows", "Both");
        contentTypeGroup.setValue("Both");

        searchButton = new Button("Search");
        searchButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        searchButton.addClickListener(e -> performSearch());
        searchButton.getStyle().set("margin-top", "auto");

        HorizontalLayout searchLayout = new HorizontalLayout(searchField, contentTypeGroup, searchButton);
        searchLayout.setWidthFull();
        searchLayout.setDefaultVerticalComponentAlignment(Alignment.END);
        searchLayout.setSpacing(true);
        searchLayout.addClassNames(LumoUtility.Gap.SMALL);
        searchLayout.getStyle().set("flex-wrap", "wrap");
        searchLayout.expand(searchField);

        // Results container
        resultsContainer = new Div();
        resultsContainer.addClassNames(
                LumoUtility.Display.GRID,
                LumoUtility.Gap.SMALL,
                LumoUtility.Padding.Vertical.SMALL
        );
        resultsContainer.getStyle()
                .set("grid-template-columns", "repeat(auto-fill, minmax(min(100%, 320px), 1fr))");
        
        // Check if TMDB is available
        if (!tmdbService.isAvailable()) {
            Paragraph warning = new Paragraph(
                    "⚠️ TMDB API key not configured. Search functionality is disabled. " +
                    "Set TMDB_API_KEY environment variable to enable search."
            );
            warning.addClassNames(
                    LumoUtility.Background.ERROR_10,
                    LumoUtility.Padding.MEDIUM,
                    LumoUtility.BorderRadius.MEDIUM
            );
            add(title, warning);
            searchButton.setEnabled(false);
        } else {
            add(title, searchLayout, resultsContainer);
        }
    }
    
    private void performSearch() {
        String query = searchField.getValue();

        if (query == null || query.isBlank()) {
            Notification.show("Please enter a search term", 3000, Notification.Position.MIDDLE)
                    .addThemeVariants(NotificationVariant.LUMO_ERROR);
            return;
        }

        resultsContainer.removeAll();
        searchButton.setEnabled(false);
        searchButton.setText("Searching...");

        Set<String> selectedLanguages = Set.of("en");
        String contentType = contentTypeGroup.getValue();

        CompletableFuture.runAsync(() -> {
            try {
                List<ContentMetadata> availableMovies = List.of();
                List<ContentMetadata> availableTvShows = List.of();

                if ("Movies".equals(contentType) || "Both".equals(contentType)) {
                    List<ContentMetadata> movies = tmdbService.searchMovies(query);
                    availableMovies = movies.parallelStream()
                            .filter(m -> availabilityService.checkMovieAvailability(
                                    m.getTmdbId(),
                                    selectedLanguages
                            ).isAvailable())
                            .collect(Collectors.toList());
                }

                if ("TV Shows".equals(contentType) || "Both".equals(contentType)) {
                    List<ContentMetadata> tvShows = tmdbService.searchTvShows(query);
                    availableTvShows = tvShows.parallelStream()
                            .filter(tv -> availabilityService.checkTvAvailability(
                                    tv.getTmdbId(),
                                    selectedLanguages
                            ).isAvailable())
                            .collect(Collectors.toList());
                }

                List<ContentMetadata> finalAvailableMovies = availableMovies;
                List<ContentMetadata> finalAvailableTvShows = availableTvShows;

                getUI().ifPresent(ui -> ui.access(() -> {
                    finalAvailableMovies.forEach(movie -> addResultCard(movie, DownloadTask.ContentType.MOVIE));
                    finalAvailableTvShows.forEach(show -> addResultCard(show, DownloadTask.ContentType.TV));

                    if (resultsContainer.getChildren().count() == 0) {
                        Paragraph noResults = new Paragraph("No results found for: " + query);
                        noResults.addClassNames(LumoUtility.TextColor.SECONDARY);
                        resultsContainer.add(noResults);
                    }

                    searchButton.setEnabled(true);
                    searchButton.setText("Search");
                }));

            } catch (Exception e) {
                getUI().ifPresent(ui -> ui.access(() -> {
                    Notification.show("Search failed: " + e.getMessage(), 5000, Notification.Position.MIDDLE)
                            .addThemeVariants(NotificationVariant.LUMO_ERROR);
                    searchButton.setEnabled(true);
                    searchButton.setText("Search");
                }));
            }
        });
    }
    
    private void addResultCard(ContentMetadata content, DownloadTask.ContentType type) {
        SearchResultCard card = new SearchResultCard(
                content,
                type,
                () -> Set.of(properties.getDownload().getDefaultLanguage()),
                () -> properties.getDownload().getDefaultQuality(),
                this::handleDownload
        );
        resultsContainer.add(card);
    }
    
    private void handleDownload(ContentMetadata content, DownloadTask.ContentType type,
                                Integer season, Integer episode,
                                Set<String> languages, String quality) {
        // Show immediate feedback
        String feedbackMessage = buildQueueMessage(content, type, season, episode);
        Notification.show(feedbackMessage, 2000, Notification.Position.BOTTOM_END);

        // Run on separate thread to avoid blocking UI
        int taskCountBefore = downloadQueueService.getAllTasks().size();

        CompletableFuture.supplyAsync(() -> {
            return downloadQueueService.addDownload(
                    content.getTmdbId(),
                    type,
                    season,
                    episode,
                    List.copyOf(languages),
                    quality
            );
        }).thenAccept(task -> {
            getUI().ifPresent(ui -> ui.access(() -> {
                int taskCountAfter = downloadQueueService.getAllTasks().size();
                int addedTasks = taskCountAfter - taskCountBefore;

                String message;
                if (addedTasks > 1) {
                    message = String.format("✓ Added %d episodes to queue", addedTasks);
                } else if (task != null) {
                    message = "✓ Added to queue: " + task.getDisplayName();
                } else {
                    message = "✓ Added to queue";
                }

                Notification notification = Notification.show(message, 5000, Notification.Position.BOTTOM_END);
                notification.addThemeVariants(NotificationVariant.LUMO_SUCCESS);
            }));
        }).exceptionally(e -> {
            getUI().ifPresent(ui -> ui.access(() -> {
                Notification.show("Failed to add download: " + e.getMessage(), 5000, Notification.Position.MIDDLE)
                        .addThemeVariants(NotificationVariant.LUMO_ERROR);
            }));
            return null;
        });
    }

    private String buildQueueMessage(ContentMetadata content, DownloadTask.ContentType type,
                                     Integer season, Integer episode) {
        if (type == DownloadTask.ContentType.TV) {
            if (season == null && episode == null) {
                return "Adding entire show to queue...";
            } else if (season != null && episode == null) {
                return "Adding season " + season + " to queue...";
            }
        }
        return "Adding to queue...";
    }
}
