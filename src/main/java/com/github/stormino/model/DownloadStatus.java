package com.github.stormino.model;

public enum DownloadStatus {
    QUEUED("Queued"),
    EXTRACTING("Extracting URL"),
    DOWNLOADING("Downloading"),
    MERGING("Merging tracks"),
    COMPLETED("Completed"),
    FAILED("Failed"),
    CANCELLED("Cancelled");
    
    private final String displayName;
    
    DownloadStatus(String displayName) {
        this.displayName = displayName;
    }
    
    public String getDisplayName() {
        return displayName;
    }
}
