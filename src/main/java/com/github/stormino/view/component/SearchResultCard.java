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
                LumoUtility.Background.CONTRAST_5,
                LumoUtility.BorderRadius.MEDIUM,
                LumoUtility.Padding.MEDIUM,
                LumoUtility.BoxShadow.SMALL
        );
        setSpacing(true);
        
        // Title
        H3 title = new H3(content.getTitle());
        title.addClassNames(LumoUtility.Margin.NONE, LumoUtility.FontSize.MEDIUM);
        
        // Metadata
        HorizontalLayout metadata = new HorizontalLayout();
        metadata.setSpacing(true);
        metadata.addClassNames(LumoUtility.Gap.SMALL);
        
        if (content.getYear() != null) {
            Span year = new Span(content.getYear().toString());
            year.addClassNames(LumoUtility.TextColor.SECONDARY);
            metadata.add(year);
        }
        
        if (content.getVoteAverage() != null) {
            Span rating = new Span(String.format("⭐ %.1f", content.getVoteAverage()));
            rating.addClassNames(LumoUtility.TextColor.WARNING);
            metadata.add(rating);
        }
        
        // TV-specific info
        if (type == DownloadTask.ContentType.TV) {
            if (content.getNumberOfSeasons() != null) {
                Span seasons = new Span(content.getNumberOfSeasons() + " seasons");
                seasons.addClassNames(LumoUtility.TextColor.SECONDARY);
                metadata.add(seasons);
            }
            if (content.getTotalEpisodes() != null) {
                Span episodes = new Span(content.getTotalEpisodes() + " episodes");
                episodes.addClassNames(LumoUtility.TextColor.SECONDARY);
                metadata.add(episodes);
            }
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
        
        add(title, metadata, overview, downloadBtn);
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
            seasonField.setValue(1);
            seasonField.setMin(1);
            seasonField.setStepButtonsVisible(true);
            seasonField.setWidthFull();
            
            IntegerField episodeField = new IntegerField("Episode");
            episodeField.setValue(1);
            episodeField.setMin(1);
            episodeField.setStepButtonsVisible(true);
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
