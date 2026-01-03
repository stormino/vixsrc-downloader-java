package com.github.stormino.view;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.formlayout.FormLayout;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.notification.NotificationVariant;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.IntegerField;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.theme.lumo.LumoUtility;
import com.github.stormino.config.VixSrcProperties;

@Route(value = "settings", layout = MainLayout.class)
@PageTitle("Settings | VixSrc Downloader")
public class SettingsView extends VerticalLayout {
    
    private final VixSrcProperties properties;
    
    private final TextField tmdbApiKeyField;
    private final TextField downloadPathField;
    private final TextField tempPathField;
    private final IntegerField parallelDownloadsField;
    private final IntegerField ytdlpConcurrencyField;
    private final TextField defaultQualityField;
    private final TextField defaultLanguagesField;
    
    public SettingsView(VixSrcProperties properties) {
        this.properties = properties;
        
        setSizeFull();
        setPadding(true);
        setSpacing(true);
        
        // Header
        H2 title = new H2("Settings");
        title.addClassNames(LumoUtility.Margin.Bottom.MEDIUM);
        
        // TMDB Section
        H3 tmdbHeader = new H3("TMDB Configuration");
        tmdbHeader.addClassNames(LumoUtility.Margin.Top.LARGE);
        
        Paragraph tmdbInfo = new Paragraph(
                "TMDB API key is required for search functionality and metadata. " +
                "Get your free API key at: https://www.themoviedb.org/settings/api"
        );
        tmdbInfo.addClassNames(LumoUtility.TextColor.SECONDARY, LumoUtility.FontSize.SMALL);
        
        FormLayout tmdbForm = new FormLayout();
        
        tmdbApiKeyField = new TextField("API Key");
        tmdbApiKeyField.setValue(properties.getTmdb().getApiKey() != null ? properties.getTmdb().getApiKey() : "");
        tmdbApiKeyField.setWidthFull();
        tmdbApiKeyField.setPlaceholder("your_tmdb_api_key");
        
        Paragraph tmdbNote = new Paragraph("⚠️ Note: API key changes require application restart");
        tmdbNote.addClassNames(
                LumoUtility.Background.WARNING_10,
                LumoUtility.Padding.SMALL,
                LumoUtility.BorderRadius.SMALL,
                LumoUtility.FontSize.SMALL
        );
        
        tmdbForm.add(tmdbApiKeyField);
        
        // Download Settings
        H3 downloadHeader = new H3("Download Configuration");
        downloadHeader.addClassNames(LumoUtility.Margin.Top.LARGE);
        
        FormLayout downloadForm = new FormLayout();
        
        downloadPathField = new TextField("Base Download Path");
        downloadPathField.setValue(properties.getDownload().getBasePath());
        downloadPathField.setWidthFull();
        downloadPathField.setReadOnly(true);
        
        tempPathField = new TextField("Temporary Path");
        tempPathField.setValue(properties.getDownload().getTempPath());
        tempPathField.setWidthFull();
        tempPathField.setReadOnly(true);
        
        parallelDownloadsField = new IntegerField("Parallel Downloads");
        parallelDownloadsField.setValue(properties.getDownload().getParallelDownloads());
        parallelDownloadsField.setMin(1);
        parallelDownloadsField.setMax(10);
        parallelDownloadsField.setStepButtonsVisible(true);
        parallelDownloadsField.setReadOnly(true);
        
        ytdlpConcurrencyField = new IntegerField("yt-dlp Concurrency");
        ytdlpConcurrencyField.setValue(properties.getDownload().getYtdlpConcurrency());
        ytdlpConcurrencyField.setMin(1);
        ytdlpConcurrencyField.setMax(20);
        ytdlpConcurrencyField.setStepButtonsVisible(true);
        ytdlpConcurrencyField.setReadOnly(true);
        
        defaultQualityField = new TextField("Default Quality");
        defaultQualityField.setValue(properties.getDownload().getDefaultQuality());
        defaultQualityField.setReadOnly(true);
        
        defaultLanguagesField = new TextField("Default Languages");
        defaultLanguagesField.setValue(properties.getDownload().getDefaultLanguages());
        defaultLanguagesField.setReadOnly(true);
        
        Paragraph configNote = new Paragraph(
                "⚠️ Download configuration is read-only. " +
                "Update via environment variables or application.yml and restart."
        );
        configNote.addClassNames(
                LumoUtility.Background.WARNING_10,
                LumoUtility.Padding.SMALL,
                LumoUtility.BorderRadius.SMALL,
                LumoUtility.FontSize.SMALL
        );
        
        downloadForm.add(
                downloadPathField,
                tempPathField,
                parallelDownloadsField,
                ytdlpConcurrencyField,
                defaultQualityField,
                defaultLanguagesField
        );
        
        // Extractor Settings
        H3 extractorHeader = new H3("Extractor Configuration");
        extractorHeader.addClassNames(LumoUtility.Margin.Top.LARGE);
        
        FormLayout extractorForm = new FormLayout();
        
        TextField baseUrlField = new TextField("VixSrc Base URL");
        baseUrlField.setValue(properties.getExtractor().getBaseUrl());
        baseUrlField.setWidthFull();
        baseUrlField.setReadOnly(true);
        
        IntegerField timeoutField = new IntegerField("Timeout (seconds)");
        timeoutField.setValue(properties.getExtractor().getTimeoutSeconds());
        timeoutField.setReadOnly(true);
        
        IntegerField maxRetriesField = new IntegerField("Max Retries");
        maxRetriesField.setValue(properties.getExtractor().getMaxRetries());
        maxRetriesField.setReadOnly(true);
        
        extractorForm.add(baseUrlField, timeoutField, maxRetriesField);
        
        // System Info
        H3 systemHeader = new H3("System Information");
        systemHeader.addClassNames(LumoUtility.Margin.Top.LARGE);
        
        VerticalLayout systemInfo = new VerticalLayout();
        systemInfo.setPadding(false);
        systemInfo.setSpacing(false);
        
        systemInfo.add(
                createInfoRow("Java Version", System.getProperty("java.version")),
                createInfoRow("OS", System.getProperty("os.name") + " " + System.getProperty("os.version")),
                createInfoRow("Available Processors", String.valueOf(Runtime.getRuntime().availableProcessors()))
        );
        
        // Check for yt-dlp and ffmpeg
        Button checkToolsBtn = new Button("Check Tools", e -> checkTools());
        checkToolsBtn.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        
        add(
                title,
                tmdbHeader,
                tmdbInfo,
                tmdbForm,
                tmdbNote,
                downloadHeader,
                downloadForm,
                configNote,
                extractorHeader,
                extractorForm,
                systemHeader,
                systemInfo,
                checkToolsBtn
        );
    }
    
    private Paragraph createInfoRow(String label, String value) {
        Paragraph p = new Paragraph(label + ": " + value);
        p.addClassNames(LumoUtility.Margin.Vertical.XSMALL);
        return p;
    }
    
    private void checkTools() {
        StringBuilder message = new StringBuilder("Tool Check Results:\n\n");
        
        boolean ytdlpAvailable = checkCommand("yt-dlp");
        message.append(ytdlpAvailable ? "✓ yt-dlp: Available\n" : "✗ yt-dlp: Not found\n");
        
        boolean ffmpegAvailable = checkCommand("ffmpeg");
        message.append(ffmpegAvailable ? "✓ ffmpeg: Available\n" : "✗ ffmpeg: Not found\n");
        
        if (!ytdlpAvailable && !ffmpegAvailable) {
            message.append("\n⚠️ No download tools found! Install yt-dlp or ffmpeg.");
            Notification.show(message.toString(), 5000, Notification.Position.MIDDLE)
                    .addThemeVariants(NotificationVariant.LUMO_ERROR);
        } else {
            Notification.show(message.toString(), 5000, Notification.Position.MIDDLE)
                    .addThemeVariants(NotificationVariant.LUMO_SUCCESS);
        }
    }
    
    private boolean checkCommand(String command) {
        try {
            Process process = new ProcessBuilder(command, "--version")
                    .redirectErrorStream(true)
                    .start();
            
            return process.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
