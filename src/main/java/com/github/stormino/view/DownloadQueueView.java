package com.github.stormino.view;

import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.DetachEvent;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.notification.NotificationVariant;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.progressbar.ProgressBar;
import com.vaadin.flow.component.treegrid.TreeGrid;
import com.vaadin.flow.data.provider.hierarchy.TreeData;
import com.vaadin.flow.data.provider.hierarchy.TreeDataProvider;
import com.vaadin.flow.data.renderer.ComponentRenderer;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.theme.lumo.LumoUtility;
import com.github.stormino.model.DownloadStatus;
import com.github.stormino.model.DownloadSubTask;
import com.github.stormino.model.DownloadTask;
import com.github.stormino.model.ProgressUpdate;
import com.github.stormino.service.DownloadQueueService;
import com.github.stormino.service.ProgressBroadcastService;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

@Slf4j
@Route(value = "downloads", layout = MainLayout.class)
@PageTitle("Downloads | VixSrc Downloader")
public class DownloadQueueView extends VerticalLayout {

    private final DownloadQueueService downloadQueueService;
    private final ProgressBroadcastService progressBroadcastService;
    private final TreeGrid<DownloadItem> treeGrid;
    private Consumer<ProgressUpdate> progressListener;

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final long THROTTLE_MS = 200; // Max one refresh per task every 200ms
    private final Map<String, Long> lastUpdateTime = new ConcurrentHashMap<>();
    private final Map<String, DownloadItem> itemCache = new ConcurrentHashMap<>();
    
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

        // TreeGrid
        treeGrid = new TreeGrid<>();

        treeGrid.addHierarchyColumn(this::getItemDisplayName)
                .setHeader("Title")
                .setFlexGrow(3);

        treeGrid.addComponentColumn(this::createStatusBadge)
                .setHeader("Status")
                .setWidth("120px");

        treeGrid.addComponentColumn(this::createProgressBar)
                .setHeader("Progress")
                .setWidth("200px");

        treeGrid.addColumn(this::getItemSize)
                .setHeader("Downloaded")
                .setWidth("120px");

        treeGrid.addColumn(this::getItemSpeed)
                .setHeader("Speed")
                .setWidth("120px");

        treeGrid.addColumn(this::getItemEta)
                .setHeader("ETA")
                .setWidth("100px");

        treeGrid.addColumn(this::getItemCreatedTime)
                .setHeader("Created")
                .setWidth("100px");

        treeGrid.addComponentColumn(this::createActionButtons)
                .setHeader("Actions")
                .setWidth("100px");

        treeGrid.setHeight("600px");

        add(header, treeGrid);
        
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

        // Check if this is a new task not in cache (QUEUED status usually)
        String itemKey = update.getSubTaskId() != null
            ? update.getTaskId() + ":" + update.getSubTaskId()
            : update.getTaskId();

        if (!itemCache.containsKey(itemKey) && update.getStatus() == DownloadStatus.QUEUED) {
            // New task queued - do full refresh
            refreshGrid();
            return;
        }

        // Throttle updates - skip if too frequent for this task
        String throttleKey = update.getTaskId() + (update.getSubTaskId() != null ? ":" + update.getSubTaskId() : "");
        long now = System.currentTimeMillis();
        Long lastUpdate = lastUpdateTime.get(throttleKey);

        // Always allow terminal state updates and status changes
        boolean isTerminalUpdate = update.getStatus() == DownloadStatus.COMPLETED ||
                                   update.getStatus() == DownloadStatus.FAILED ||
                                   update.getStatus() == DownloadStatus.CANCELLED ||
                                   update.getStatus() == DownloadStatus.NOT_FOUND;

        if (!isTerminalUpdate && lastUpdate != null && (now - lastUpdate) < THROTTLE_MS) {
            // Still update the data model, but skip UI refresh
            updateTaskData(update);
            return;
        }

        lastUpdateTime.put(throttleKey, now);

        downloadQueueService.getTask(update.getTaskId()).ifPresent(task -> {
            boolean needsResort = false;

            if (update.getSubTaskId() != null) {
                // Sub-task update
                task.getSubTasks().stream()
                        .filter(st -> st.getId().equals(update.getSubTaskId()))
                        .findFirst()
                        .ifPresent(subTask -> {
                            if (update.getProgress() != null) {
                                subTask.setProgress(update.getProgress());
                            }
                            if (update.getStatus() != null) {
                                subTask.setStatus(update.getStatus());
                            }

                            // Handle speed/eta: clear for terminal states, update for active states
                            if (subTask.getStatus() == DownloadStatus.COMPLETED ||
                                subTask.getStatus() == DownloadStatus.FAILED ||
                                subTask.getStatus() == DownloadStatus.CANCELLED ||
                                subTask.getStatus() == DownloadStatus.NOT_FOUND) {
                                // For terminal states, explicitly clear speed/eta regardless of update values
                                subTask.setDownloadSpeed(null);
                                subTask.setEtaSeconds(null);
                            } else {
                                // For active states, only update if present in update
                                if (update.getDownloadSpeed() != null) {
                                    subTask.setDownloadSpeed(update.getDownloadSpeed());
                                } else if (update.getBitrate() != null) {
                                    subTask.setDownloadSpeed(update.getBitrate());
                                }
                                if (update.getEtaSeconds() != null) {
                                    subTask.setEtaSeconds(update.getEtaSeconds());
                                }
                            }

                            if (update.getDownloadedBytes() != null) {
                                subTask.setDownloadedBytes(update.getDownloadedBytes());
                            }
                            if (update.getTotalBytes() != null) {
                                subTask.setTotalBytes(update.getTotalBytes());
                            }
                            if (update.getErrorMessage() != null) {
                                subTask.setErrorMessage(update.getErrorMessage());
                            }
                        });
            } else {
                // Parent task update
                DownloadStatus oldStatus = task.getStatus();

                if (update.getProgress() != null) {
                    task.setProgress(update.getProgress());
                }
                if (update.getStatus() != null) {
                    task.setStatus(update.getStatus());
                    // Check if status changed between active/inactive
                    if (oldStatus != update.getStatus() &&
                        (isActiveStatus(oldStatus) != isActiveStatus(update.getStatus()))) {
                        needsResort = true;
                    }
                }

                // Handle speed/eta: clear for terminal states, update for active states
                if (task.getStatus() == DownloadStatus.COMPLETED ||
                    task.getStatus() == DownloadStatus.FAILED ||
                    task.getStatus() == DownloadStatus.CANCELLED) {
                    // For terminal states, explicitly clear speed/eta/bitrate regardless of update values
                    task.setDownloadSpeed(null);
                    task.setEtaSeconds(null);
                    task.setBitrate(null);
                } else {
                    // For active states, only update if present in update
                    if (update.getDownloadSpeed() != null) {
                        task.setDownloadSpeed(update.getDownloadSpeed());
                    } else if (update.getBitrate() != null) {
                        task.setBitrate(update.getBitrate());
                    }
                    if (update.getEtaSeconds() != null) {
                        task.setEtaSeconds(update.getEtaSeconds());
                    }
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
            }

            // Refresh grid - full refresh if resort needed, otherwise just refresh specific item
            if (needsResort) {
                refreshGrid();
            } else {
                // Refresh the affected item
                DownloadItem item = itemCache.get(itemKey);
                if (item != null) {
                    treeGrid.getDataProvider().refreshItem(item);
                }

                // If this is a subtask update, also refresh parent to update aggregated progress
                if (update.getSubTaskId() != null) {
                    DownloadItem parentItem = itemCache.get(update.getTaskId());
                    if (parentItem != null) {
                        treeGrid.getDataProvider().refreshItem(parentItem);
                    }
                }
            }
        });
    }

    private void updateTaskData(ProgressUpdate update) {
        // Update data model without UI refresh (for throttled updates)
        downloadQueueService.getTask(update.getTaskId()).ifPresent(task -> {
            if (update.getSubTaskId() != null) {
                task.getSubTasks().stream()
                        .filter(st -> st.getId().equals(update.getSubTaskId()))
                        .findFirst()
                        .ifPresent(subTask -> {
                            if (update.getProgress() != null) subTask.setProgress(update.getProgress());
                            if (update.getDownloadSpeed() != null) subTask.setDownloadSpeed(update.getDownloadSpeed());
                            if (update.getDownloadedBytes() != null) subTask.setDownloadedBytes(update.getDownloadedBytes());
                            if (update.getTotalBytes() != null) subTask.setTotalBytes(update.getTotalBytes());
                            if (update.getEtaSeconds() != null) subTask.setEtaSeconds(update.getEtaSeconds());
                        });
            } else {
                if (update.getProgress() != null) task.setProgress(update.getProgress());
                if (update.getDownloadSpeed() != null) task.setDownloadSpeed(update.getDownloadSpeed());
                if (update.getDownloadedBytes() != null) task.setDownloadedBytes(update.getDownloadedBytes());
                if (update.getTotalBytes() != null) task.setTotalBytes(update.getTotalBytes());
                if (update.getEtaSeconds() != null) task.setEtaSeconds(update.getEtaSeconds());
            }
        });
    }

    private void refreshGrid() {
        TreeData<DownloadItem> treeData = new TreeData<>();
        itemCache.clear();

        // Sort tasks: in-progress first, then alphabetically
        var sortedTasks = downloadQueueService.getAllTasks().stream()
                .sorted((t1, t2) -> {
                    // In-progress tasks always first
                    boolean t1Active = isActiveStatus(t1.getStatus());
                    boolean t2Active = isActiveStatus(t2.getStatus());

                    if (t1Active && !t2Active) return -1;
                    if (!t1Active && t2Active) return 1;

                    // Then alphabetically by display name
                    return t1.getDisplayName().compareToIgnoreCase(t2.getDisplayName());
                })
                .toList();

        for (DownloadTask task : sortedTasks) {
            // Parent item (wraps DownloadTask)
            DownloadItem parentItem = new DownloadItem(task, null);
            treeData.addItem(null, parentItem);
            itemCache.put(task.getId(), parentItem);

            // Child items (wrap DownloadSubTask)
            for (DownloadSubTask subTask : task.getSubTasks()) {
                DownloadItem childItem = new DownloadItem(task, subTask);
                treeData.addItem(parentItem, childItem);
                itemCache.put(task.getId() + ":" + subTask.getId(), childItem);
            }
        }

        TreeDataProvider<DownloadItem> dataProvider = new TreeDataProvider<>(treeData);
        treeGrid.setDataProvider(dataProvider);

        // Items collapsed by default (user preference)
    }

    private boolean isActiveStatus(DownloadStatus status) {
        return status == DownloadStatus.DOWNLOADING ||
               status == DownloadStatus.EXTRACTING ||
               status == DownloadStatus.MERGING;
    }

    private String getItemDisplayName(DownloadItem item) {
        if (item.isParent()) {
            return item.getTask().getDisplayName();
        } else {
            return item.getSubTask().getDisplayName();
        }
    }

    private String getItemSize(DownloadItem item) {
        Long downloaded;

        if (item.isParent()) {
            downloaded = item.getTask().getAggregatedDownloadedBytes();
        } else {
            downloaded = item.getSubTask().getDownloadedBytes();
        }

        if (downloaded == null || downloaded == 0) {
            return "";
        }

        return formatBytes(downloaded);
    }

    private String formatBytes(long bytes) {
        if (bytes >= 1_000_000_000) {
            return String.format("%.2f GB", bytes / 1_000_000_000.0);
        } else if (bytes >= 1_000_000) {
            return String.format("%.2f MB", bytes / 1_000_000.0);
        } else if (bytes >= 1_000) {
            return String.format("%.2f KB", bytes / 1_000.0);
        } else {
            return bytes + " B";
        }
    }

    private String getItemSpeed(DownloadItem item) {
        if (item.isParent()) {
            // Use aggregated download speed for parent
            String speed = item.getTask().getAggregatedDownloadSpeed();
            if (speed != null) {
                return speed;
            }
            // Fallback to bitrate
            String bitrate = item.getTask().getBitrate();
            return bitrate != null ? bitrate : "";
        } else {
            String speed = item.getSubTask().getDownloadSpeed();
            return speed != null ? speed : "";
        }
    }

    private String getItemEta(DownloadItem item) {
        Long etaSeconds;
        if (item.isParent()) {
            etaSeconds = item.getTask().getAggregatedEtaSeconds();
        } else {
            etaSeconds = item.getSubTask().getEtaSeconds();
        }

        if (etaSeconds == null || etaSeconds <= 0) {
            return "";
        }

        return formatDuration(etaSeconds);
    }

    private String formatDuration(long seconds) {
        if (seconds < 60) {
            return String.format("%ds", seconds);
        } else if (seconds < 3600) {
            long mins = seconds / 60;
            long secs = seconds % 60;
            return String.format("%dm %ds", mins, secs);
        } else {
            long hours = seconds / 3600;
            long mins = (seconds % 3600) / 60;
            return String.format("%dh %dm", hours, mins);
        }
    }

    private String getItemCreatedTime(DownloadItem item) {
        if (item.isParent()) {
            if (item.getTask().getCreatedAt() != null) {
                return item.getTask().getCreatedAt().format(TIME_FORMATTER);
            }
        }
        return "";
    }
    
    private Span createStatusBadge(DownloadItem item) {
        DownloadStatus status = item.isParent()
                ? item.getTask().getStatus()
                : item.getSubTask().getStatus();

        Span badge = new Span(status.getDisplayName());
        badge.getElement().getThemeList().add("badge");

        switch (status) {
            case COMPLETED -> badge.getElement().getThemeList().add("success");
            case FAILED, CANCELLED -> badge.getElement().getThemeList().add("error");
            case NOT_FOUND -> badge.getElement().getThemeList().add("warning");
            case DOWNLOADING -> badge.getElement().getThemeList().add("contrast");
            case MERGING -> badge.getElement().getThemeList().add("primary");
            default -> badge.getElement().getThemeList().add("primary");
        }

        return badge;
    }

    private Div createProgressBar(DownloadItem item) {
        Div container = new Div();
        container.getStyle().set("width", "100%");

        ProgressBar progressBar = new ProgressBar();
        progressBar.getStyle().set("width", "100%");

        Double progress;
        DownloadStatus status;

        if (item.isParent()) {
            // Show aggregated progress if has sub-tasks
            progress = item.getTask().getSubTasks().isEmpty()
                    ? item.getTask().getProgress()
                    : item.getTask().getAggregatedProgress();
            status = item.getTask().getStatus();
        } else {
            progress = item.getSubTask().getProgress();
            status = item.getSubTask().getStatus();
        }

        if (progress != null) {
            progressBar.setValue(progress / 100.0);
        } else {
            progressBar.setValue(0.0);
        }

        if (status == DownloadStatus.COMPLETED) {
            progressBar.setValue(1.0);
        }

        if (status == DownloadStatus.DOWNLOADING ||
                status == DownloadStatus.EXTRACTING) {
            progressBar.setIndeterminate(progress == null || progress == 0.0);
        }

        // Create text showing percentage
        Span progressText = new Span();
        progressText.getStyle()
                .set("font-size", "0.875rem")
                .set("color", "var(--lumo-secondary-text-color)")
                .set("margin-top", "0.25rem");

        if (progress != null) {
            progressText.setText(String.format("%.1f%%", progress));
        } else {
            progressText.setText("0.0%");
        }

        container.add(progressBar, progressText);
        return container;
    }

    private HorizontalLayout createActionButtons(DownloadItem item) {
        HorizontalLayout actions = new HorizontalLayout();
        actions.setSpacing(true);

        // Only show actions for parent items
        if (!item.isParent()) {
            return actions;
        }

        DownloadTask task = item.getTask();

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

    /**
     * Wrapper class for TreeGrid items
     */
    @Data
    private static class DownloadItem {
        private final DownloadTask task;
        private final DownloadSubTask subTask;  // null for parent items

        public boolean isParent() {
            return subTask == null;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof DownloadItem)) return false;
            DownloadItem that = (DownloadItem) o;

            if (isParent()) {
                return that.isParent() && task.getId().equals(that.task.getId());
            } else {
                return !that.isParent() && subTask.getId().equals(that.subTask.getId());
            }
        }

        @Override
        public int hashCode() {
            return isParent() ? task.getId().hashCode() : subTask.getId().hashCode();
        }
    }
}
