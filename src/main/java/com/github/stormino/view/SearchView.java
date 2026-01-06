package com.github.stormino.view;

import com.vaadin.flow.component.Key;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.combobox.MultiSelectComboBox;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.notification.NotificationVariant;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.radiobutton.RadioButtonGroup;
import com.vaadin.flow.component.select.Select;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.theme.lumo.LumoUtility;
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

    private final TextField searchField;
    private final RadioButtonGroup<String> contentTypeGroup;
    private final Button searchButton;
    private final Div resultsContainer;

    private final MultiSelectComboBox<String> languageSelector;
    private final Select<String> qualitySelector;

    public SearchView(TmdbMetadataService tmdbService, DownloadQueueService downloadQueueService,
                     VixSrcAvailabilityService availabilityService) {
        this.tmdbService = tmdbService;
        this.downloadQueueService = downloadQueueService;
        this.availabilityService = availabilityService;
        
        setSizeFull();
        setPadding(true);
        setSpacing(true);
        
        // Header
        H2 title = new H2("Search Movies & TV Shows");
        title.addClassNames(LumoUtility.Margin.Bottom.MEDIUM);
        
        // Search controls
        searchField = new TextField();
        searchField.setPlaceholder("Enter movie or TV show name...");
        searchField.setWidthFull();
        searchField.addKeyPressListener(Key.ENTER, e -> performSearch());
        
        contentTypeGroup = new RadioButtonGroup<>();
        contentTypeGroup.setLabel("Content Type");
        contentTypeGroup.setItems("Movies", "TV Shows", "Both");
        contentTypeGroup.setValue("Both");
        
        searchButton = new Button("Search");
        searchButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        searchButton.addClickListener(e -> performSearch());
        
        HorizontalLayout searchLayout = new HorizontalLayout(searchField, contentTypeGroup, searchButton);
        searchLayout.setWidthFull();
        searchLayout.setDefaultVerticalComponentAlignment(Alignment.END);
        searchLayout.expand(searchField);
        
        // Global settings
        languageSelector = new MultiSelectComboBox<>("Default Languages");
        languageSelector.setItems("en", "it", "es", "fr", "de", "pt", "ja", "ko");
        languageSelector.setValue(Set.of("en"));
        languageSelector.setWidth("200px");
        
        qualitySelector = new Select<>();
        qualitySelector.setLabel("Default Quality");
        qualitySelector.setItems("best", "1080", "720", "worst");
        qualitySelector.setValue("best");
        qualitySelector.setWidth("150px");
        
        HorizontalLayout settingsLayout = new HorizontalLayout(languageSelector, qualitySelector);
        settingsLayout.addClassNames(LumoUtility.Gap.MEDIUM);
        
        // Results container
        resultsContainer = new Div();
        resultsContainer.addClassNames(
                LumoUtility.Display.GRID,
                LumoUtility.Gap.MEDIUM,
                LumoUtility.Padding.MEDIUM
        );
        resultsContainer.getStyle()
                .set("grid-template-columns", "repeat(auto-fill, minmax(350px, 1fr))");
        
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
            add(title, searchLayout, settingsLayout, resultsContainer);
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

        Set<String> selectedLanguages = languageSelector.getValue();
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
                () -> languageSelector.getValue(),
                qualitySelector::getValue,
                this::handleDownload
        );
        resultsContainer.add(card);
    }
    
    private void handleDownload(ContentMetadata content, DownloadTask.ContentType type,
                                Integer season, Integer episode,
                                Set<String> languages, String quality) {
        // Show immediate feedback
        Notification.show("Adding to queue...", 2000, Notification.Position.BOTTOM_END);

        // Run on separate thread to avoid blocking UI
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
                Notification notification = Notification.show(
                        "✓ Added to queue: " + task.getDisplayName(),
                        5000,
                        Notification.Position.BOTTOM_END
                );
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
}
