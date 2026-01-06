package com.github.stormino.view.component;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.combobox.MultiSelectComboBox;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.select.Select;
import com.vaadin.flow.component.textfield.IntegerField;
import com.vaadin.flow.theme.lumo.LumoUtility;
import com.github.stormino.model.ContentMetadata;
import com.github.stormino.model.DownloadTask;

import java.util.Set;
import java.util.function.Supplier;

/**
 * Card component for displaying search results
 */
public class SearchResultCard extends VerticalLayout {
    
    private final ContentMetadata content;
    private final DownloadTask.ContentType type;
    private final Supplier<Set<String>> defaultLanguagesSupplier;
    private final Supplier<String> defaultQualitySupplier;
    private final DownloadHandler downloadHandler;
    
    public interface DownloadHandler {
        void onDownload(ContentMetadata content, DownloadTask.ContentType type,
                       Integer season, Integer episode,
                       Set<String> languages, String quality);
    }
    
    public SearchResultCard(ContentMetadata content,
                           DownloadTask.ContentType type,
                           Supplier<Set<String>> defaultLanguagesSupplier,
                           Supplier<String> defaultQualitySupplier,
                           DownloadHandler downloadHandler) {
        this.content = content;
        this.type = type;
        this.defaultLanguagesSupplier = defaultLanguagesSupplier;
        this.defaultQualitySupplier = defaultQualitySupplier;
        this.downloadHandler = downloadHandler;
        
        createCard();
    }
    
    private void createCard() {
        addClassNames(
                LumoUtility.BorderRadius.MEDIUM,
                LumoUtility.Padding.MEDIUM,
                LumoUtility.BoxShadow.SMALL
        );
        setSpacing(true);

        // Set background color based on content type
        if (type == DownloadTask.ContentType.MOVIE) {
            getStyle().set("background-color", "#E3F2FD"); // Pastel blue for movies
        } else {
            getStyle().set("background-color", "#FFEBEE"); // Pastel red for TV shows
        }

        // Title row
        HorizontalLayout titleRow = new HorizontalLayout();
        titleRow.setSpacing(true);
        titleRow.setAlignItems(Alignment.BASELINE);
        titleRow.addClassNames(LumoUtility.Gap.SMALL);

        H3 title = new H3(content.getTitle());
        title.addClassNames(LumoUtility.Margin.NONE, LumoUtility.FontSize.MEDIUM);
        titleRow.add(title);

        // TV-specific info next to title
        if (type == DownloadTask.ContentType.TV) {
            if (content.getNumberOfSeasons() != null) {
                Span seasons = new Span("(" + content.getNumberOfSeasons() + " seasons");
                seasons.addClassNames(LumoUtility.TextColor.SECONDARY, LumoUtility.FontSize.SMALL);
                titleRow.add(seasons);
            }
            if (content.getTotalEpisodes() != null) {
                Span episodes = new Span("• " + content.getTotalEpisodes() + " episodes)");
                episodes.addClassNames(LumoUtility.TextColor.SECONDARY, LumoUtility.FontSize.SMALL);
                titleRow.add(episodes);
            }
        }

        // First row: Year and Rating
        HorizontalLayout firstRow = new HorizontalLayout();
        firstRow.setSpacing(true);
        firstRow.addClassNames(LumoUtility.Gap.SMALL);

        if (content.getYear() != null) {
            Span year = new Span(content.getYear().toString());
            year.addClassNames(LumoUtility.TextColor.SECONDARY);
            firstRow.add(year);
        }

        if (content.getVoteAverage() != null) {
            Span rating = new Span(String.format("⭐ %.1f", content.getVoteAverage()));
            rating.addClassNames(LumoUtility.TextColor.WARNING);
            firstRow.add(rating);
        }

        // Second row: TMDB ID
        HorizontalLayout secondRow = new HorizontalLayout();
        secondRow.setSpacing(true);
        secondRow.addClassNames(LumoUtility.Gap.SMALL);

        if (content.getTmdbId() != null) {
            Span tmdbId = new Span("TMDB: " + content.getTmdbId());
            tmdbId.addClassNames(LumoUtility.TextColor.SECONDARY, LumoUtility.FontSize.SMALL);
            secondRow.add(tmdbId);
        }
        
        // Overview
        Paragraph overview = new Paragraph(truncateOverview(content.getOverview()));
        overview.addClassNames(
                LumoUtility.TextColor.SECONDARY,
                LumoUtility.FontSize.SMALL,
                LumoUtility.Margin.Vertical.SMALL
        );
        
        // Download button
        Button downloadBtn = new Button("Download", VaadinIcon.DOWNLOAD.create());
        downloadBtn.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        downloadBtn.addClickListener(e -> openDownloadDialog());

        add(titleRow, firstRow, secondRow, overview, downloadBtn);
    }
    
    private void openDownloadDialog() {
        Dialog dialog = new Dialog();
        dialog.setHeaderTitle("Download: " + content.getTitle());
        dialog.setWidth("500px");
        
        VerticalLayout layout = new VerticalLayout();
        layout.setPadding(false);
        layout.setSpacing(true);
        
        // Language selector
        MultiSelectComboBox<String> languageSelector = new MultiSelectComboBox<>("Languages");
        languageSelector.setItems("en", "it", "es", "fr", "de", "pt", "ja", "ko", "ru", "zh");
        languageSelector.setValue(defaultLanguagesSupplier.get());
        languageSelector.setWidthFull();
        
        // Quality selector
        Select<String> qualitySelector = new Select<>();
        qualitySelector.setLabel("Quality");
        qualitySelector.setItems("best", "1080", "720", "worst");
        qualitySelector.setValue(defaultQualitySupplier.get());
        qualitySelector.setWidthFull();
        
        layout.add(languageSelector, qualitySelector);
        
        // TV-specific: Season/Episode selectors
        if (type == DownloadTask.ContentType.TV) {
            IntegerField seasonField = new IntegerField("Season");
            seasonField.setPlaceholder("All seasons");
            seasonField.setHelperText("Leave blank to download all seasons");
            seasonField.setMin(1);
            seasonField.setStepButtonsVisible(true);
            seasonField.setClearButtonVisible(true);
            seasonField.setWidthFull();

            IntegerField episodeField = new IntegerField("Episode");
            episodeField.setPlaceholder("All episodes");
            episodeField.setHelperText("Leave blank to download all episodes in season");
            episodeField.setMin(1);
            episodeField.setStepButtonsVisible(true);
            episodeField.setClearButtonVisible(true);
            episodeField.setWidthFull();

            layout.add(seasonField, episodeField);
            
            // Download button
            Button downloadBtn = new Button("Add to Queue", e -> {
                downloadHandler.onDownload(
                        content,
                        type,
                        seasonField.getValue(),
                        episodeField.getValue(),
                        languageSelector.getValue(),
                        qualitySelector.getValue()
                );
                dialog.close();
            });
            downloadBtn.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
            
            dialog.getFooter().add(downloadBtn);
            
        } else {
            // Movie: Direct download
            Button downloadBtn = new Button("Add to Queue", e -> {
                downloadHandler.onDownload(
                        content,
                        type,
                        null,
                        null,
                        languageSelector.getValue(),
                        qualitySelector.getValue()
                );
                dialog.close();
            });
            downloadBtn.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
            
            dialog.getFooter().add(downloadBtn);
        }
        
        Button cancelBtn = new Button("Cancel", e -> dialog.close());
        dialog.getFooter().add(cancelBtn);
        
        dialog.add(layout);
        dialog.open();
    }
    
    private String truncateOverview(String overview) {
        if (overview == null || overview.isBlank()) {
            return "No overview available.";
        }
        if (overview.length() > 150) {
            return overview.substring(0, 147) + "...";
        }
        return overview;
    }
}
