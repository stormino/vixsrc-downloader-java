package com.github.stormino.view;

import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.DetachEvent;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.notification.NotificationVariant;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.progressbar.ProgressBar;
import com.vaadin.flow.data.renderer.ComponentRenderer;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.shared.Registration;
import com.vaadin.flow.theme.lumo.LumoUtility;
import com.github.stormino.model.DownloadStatus;
import com.github.stormino.model.DownloadTask;
import com.github.stormino.model.ProgressUpdate;
import com.github.stormino.service.DownloadQueueService;
import com.github.stormino.service.ProgressBroadcastService;
import lombok.extern.slf4j.Slf4j;

import java.time.format.DateTimeFormatter;
import java.util.function.Consumer;

@Slf4j
@Route(value = "downloads", layout = MainLayout.class)
@PageTitle("Downloads | VixSrc Downloader")
public class DownloadQueueView extends VerticalLayout {

    private final DownloadQueueService downloadQueueService;
    private final ProgressBroadcastService progressBroadcastService;
    private final Grid<DownloadTask> grid;
    private Consumer<ProgressUpdate> progressListener;

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss");
    
    public DownloadQueueView(DownloadQueueService downloadQueueService,
                             ProgressBroadcastService progressBroadcastService) {
        this.downloadQueueService = downloadQueueService;
        this.progressBroadcastService = progressBroadcastService;
        
        setSizeFull();
        setPadding(true);
        setSpacing(true);
        
        // Header
        H2 title = new H2("Download Queue");
        title.addClassNames(LumoUtility.Margin.Bottom.MEDIUM);
        
        Button refreshBtn = new Button("Refresh", VaadinIcon.REFRESH.create());
        refreshBtn.addClickListener(e -> refreshGrid());
        
        HorizontalLayout header = new HorizontalLayout(title, refreshBtn);
        header.setWidthFull();
        header.setDefaultVerticalComponentAlignment(Alignment.CENTER);
        header.expand(title);
        
        // Grid
        grid = new Grid<>(DownloadTask.class, false);
        grid.addColumn(DownloadTask::getDisplayName)
                .setHeader("Title")
                .setFlexGrow(2);
        
        grid.addColumn(new ComponentRenderer<>(this::createStatusBadge))
                .setHeader("Status")
                .setWidth("120px");
        
        grid.addColumn(new ComponentRenderer<>(this::createProgressBar))
                .setHeader("Progress")
                .setWidth("200px");
        
        grid.addColumn(task -> {
            if (task.getBitrate() != null) {
                return task.getBitrate();
            }
            return "-";
        }).setHeader("Speed")
          .setWidth("100px");
        
        grid.addColumn(task -> {
            if (task.getCreatedAt() != null) {
                return task.getCreatedAt().format(TIME_FORMATTER);
            }
            return "-";
        }).setHeader("Created")
          .setWidth("100px");
        
        grid.addColumn(new ComponentRenderer<>(this::createActionButtons))
                .setHeader("Actions")
                .setWidth("100px");
        
        grid.setAllRowsVisible(false);
        grid.setHeight("600px");
        
        add(header, grid);
        
        refreshGrid();
    }
    
    @Override
    protected void onAttach(AttachEvent attachEvent) {
        super.onAttach(attachEvent);

        UI ui = attachEvent.getUI();

        // Register listener for progress updates
        progressListener = update -> {
            ui.access(() -> handleProgressUpdate(update));
        };

        progressBroadcastService.registerListener(progressListener);
        log.info("Progress listener registered");
    }

    @Override
    protected void onDetach(DetachEvent detachEvent) {
        super.onDetach(detachEvent);

        if (progressListener != null) {
            progressBroadcastService.unregisterListener(progressListener);
            log.info("Progress listener unregistered");
        }
    }
    
    private void handleProgressUpdate(ProgressUpdate update) {
        log.debug("Received update for task {}: {}%", update.getTaskId(), update.getProgress());

        downloadQueueService.getTask(update.getTaskId()).ifPresent(task -> {
            // Update task with progress
            if (update.getProgress() != null) {
                task.setProgress(update.getProgress());
            }
            if (update.getStatus() != null) {
                task.setStatus(update.getStatus());
            }
            if (update.getBitrate() != null) {
                task.setBitrate(update.getBitrate());
            }
            if (update.getDownloadedBytes() != null) {
                task.setDownloadedBytes(update.getDownloadedBytes());
            }
            if (update.getTotalBytes() != null) {
                task.setTotalBytes(update.getTotalBytes());
            }
            if (update.getErrorMessage() != null) {
                task.setErrorMessage(update.getErrorMessage());
            }

            // Refresh entire grid to ensure component renderers update
            grid.getDataProvider().refreshAll();
        });
    }
    
    private void refreshGrid() {
        grid.setItems(downloadQueueService.getAllTasks());
    }
    
    private Span createStatusBadge(DownloadTask task) {
        Span badge = new Span(task.getStatus().getDisplayName());
        badge.getElement().getThemeList().add("badge");
        
        switch (task.getStatus()) {
            case COMPLETED -> badge.getElement().getThemeList().add("success");
            case FAILED, CANCELLED -> badge.getElement().getThemeList().add("error");
            case DOWNLOADING -> badge.getElement().getThemeList().add("contrast");
            default -> badge.getElement().getThemeList().add("primary");
        }
        
        return badge;
    }
    
    private Div createProgressBar(DownloadTask task) {
        Div container = new Div();
        container.getStyle().set("width", "100%");

        ProgressBar progressBar = new ProgressBar();
        progressBar.getStyle().set("width", "100%");

        if (task.getProgress() != null) {
            progressBar.setValue(task.getProgress() / 100.0);
        } else {
            progressBar.setValue(0.0);
        }

        if (task.getStatus() == DownloadStatus.COMPLETED) {
            progressBar.setValue(1.0);
        }

        if (task.getStatus() == DownloadStatus.DOWNLOADING ||
            task.getStatus() == DownloadStatus.EXTRACTING) {
            progressBar.setIndeterminate(task.getProgress() == null || task.getProgress() == 0.0);
        }

        // Create text showing percentage
        Span progressText = new Span();
        progressText.getStyle()
                .set("font-size", "0.875rem")
                .set("color", "var(--lumo-secondary-text-color)")
                .set("margin-top", "0.25rem");

        if (task.getProgress() != null) {
            progressText.setText(String.format("%.1f%%", task.getProgress()));
        } else {
            progressText.setText("0.0%");
        }

        container.add(progressBar, progressText);
        return container;
    }
    
    private HorizontalLayout createActionButtons(DownloadTask task) {
        HorizontalLayout actions = new HorizontalLayout();
        actions.setSpacing(true);
        
        if (!task.isCompleted() && !task.isFailed()) {
            Button cancelBtn = new Button(VaadinIcon.CLOSE_SMALL.create());
            cancelBtn.addThemeVariants(ButtonVariant.LUMO_ERROR, ButtonVariant.LUMO_SMALL);
            cancelBtn.addClickListener(e -> {
                if (downloadQueueService.cancelTask(task.getId())) {
                    Notification.show("Download cancelled", 3000, Notification.Position.BOTTOM_END)
                            .addThemeVariants(NotificationVariant.LUMO_CONTRAST);
                    refreshGrid();
                }
            });
            actions.add(cancelBtn);
        }
        
        if (task.isFailed() && task.getErrorMessage() != null) {
            Button infoBtn = new Button(VaadinIcon.INFO_CIRCLE.create());
            infoBtn.addThemeVariants(ButtonVariant.LUMO_TERTIARY, ButtonVariant.LUMO_SMALL);
            infoBtn.addClickListener(e -> 
                    Notification.show(task.getErrorMessage(), 5000, Notification.Position.MIDDLE)
            );
            actions.add(infoBtn);
        }
        
        return actions;
    }
}
