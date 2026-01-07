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
                LumoUtility.Padding.SMALL,
                LumoUtility.BoxShadow.SMALL
        );
        setSpacing(false);
        getStyle()
                .set("gap", "0.5rem")
                .set("transition", "transform 0.2s ease, box-shadow 0.2s ease")
                .set("cursor", "default");

        // Set background color based on content type with hover effect
        if (type == DownloadTask.ContentType.MOVIE) {
            getStyle().set("background-color", "#E3F2FD"); // Pastel blue for movies
            getElement().addEventListener("mouseenter", e ->
                getStyle().set("box-shadow", "0 4px 12px rgba(33, 150, 243, 0.3)"));
            getElement().addEventListener("mouseleave", e ->
                getStyle().set("box-shadow", "0 1px 3px rgba(0, 0, 0, 0.12)"));
        } else {
            getStyle().set("background-color", "#FFEBEE"); // Pastel red for TV shows
            getElement().addEventListener("mouseenter", e ->
                getStyle().set("box-shadow", "0 4px 12px rgba(244, 67, 54, 0.3)"));
            getElement().addEventListener("mouseleave", e ->
                getStyle().set("box-shadow", "0 1px 3px rgba(0, 0, 0, 0.12)"));
        }

        // Title row
        HorizontalLayout titleRow = new HorizontalLayout();
        titleRow.setSpacing(false);
        titleRow.setAlignItems(Alignment.CENTER);
        titleRow.addClassNames(LumoUtility.Gap.XSMALL);
        titleRow.setPadding(false);
        titleRow.getStyle().set("flex-wrap", "wrap");

        H3 title = new H3(content.getTitle());
        title.addClassNames(LumoUtility.Margin.NONE);
        title.getStyle()
                .set("font-size", "1.1rem")
                .set("line-height", "1.3")
                .set("font-weight", "600");
        titleRow.add(title);

        // TV-specific info as badge
        if (type == DownloadTask.ContentType.TV && content.getNumberOfSeasons() != null) {
            Span badge = new Span(content.getNumberOfSeasons() + "S");
            badge.addClassNames(LumoUtility.FontSize.XSMALL, LumoUtility.TextColor.SECONDARY);
            badge.getStyle()
                    .set("background", "rgba(0,0,0,0.1)")
                    .set("padding", "0.15rem 0.4rem")
                    .set("border-radius", "0.25rem")
                    .set("font-weight", "500");
            titleRow.add(badge);

            if (content.getTotalEpisodes() != null) {
                Span epBadge = new Span(content.getTotalEpisodes() + "E");
                epBadge.addClassNames(LumoUtility.FontSize.XSMALL, LumoUtility.TextColor.SECONDARY);
                epBadge.getStyle()
                        .set("background", "rgba(0,0,0,0.1)")
                        .set("padding", "0.15rem 0.4rem")
                        .set("border-radius", "0.25rem")
                        .set("font-weight", "500");
                titleRow.add(epBadge);
            }
        }

        // Metadata row: Year, Rating, TMDB ID
        HorizontalLayout metaRow = new HorizontalLayout();
        metaRow.setSpacing(false);
        metaRow.addClassNames(LumoUtility.Gap.SMALL, LumoUtility.FontSize.SMALL);
        metaRow.setPadding(false);
        metaRow.getStyle().set("flex-wrap", "wrap");

        if (content.getYear() != null) {
            Span year = new Span(content.getYear().toString());
            year.addClassNames(LumoUtility.TextColor.SECONDARY);
            year.getStyle().set("font-weight", "500");
            metaRow.add(year);
        }

        if (content.getVoteAverage() != null) {
            Span rating = new Span(String.format("⭐ %.1f", content.getVoteAverage()));
            rating.getStyle().set("color", "#FFA000");
            metaRow.add(rating);
        }

        if (content.getTmdbId() != null) {
            Span tmdbId = new Span("ID: " + content.getTmdbId());
            tmdbId.addClassNames(LumoUtility.TextColor.TERTIARY, LumoUtility.FontSize.XSMALL);
            metaRow.add(tmdbId);
        }
        
        // Overview
        Paragraph overview = new Paragraph(truncateOverview(content.getOverview()));
        overview.addClassNames(
                LumoUtility.TextColor.SECONDARY,
                LumoUtility.FontSize.SMALL
        );
        overview.getStyle()
                .set("margin", "0")
                .set("line-height", "1.4");

        // Download button
        Button downloadBtn = new Button("Download", VaadinIcon.DOWNLOAD.create());
        downloadBtn.addThemeVariants(ButtonVariant.LUMO_PRIMARY, ButtonVariant.LUMO_SMALL);
        downloadBtn.addClickListener(e -> openDownloadDialog());
        downloadBtn.getStyle().set("margin-top", "0.25rem");

        add(titleRow, metaRow, overview, downloadBtn);
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
        languageSelector.setWidthFull();

        // Quality selector
        Select<String> qualitySelector = new Select<>();
        qualitySelector.setLabel("Quality");
        qualitySelector.setItems("best", "1080", "720", "worst");
        qualitySelector.setValue(defaultQualitySupplier.get());
        qualitySelector.setWidthFull();

        layout.add(languageSelector, qualitySelector);

        // Set default language after adding to layout
        Set<String> defaultLanguages = defaultLanguagesSupplier.get();
        if (defaultLanguages != null && !defaultLanguages.isEmpty()) {
            defaultLanguages.forEach(languageSelector::select);
        }
        
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
        if (overview.length() > 120) {
            return overview.substring(0, 117) + "...";
        }
        return overview;
    }
}
