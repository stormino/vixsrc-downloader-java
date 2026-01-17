package com.github.stormino.exception;

import java.util.List;

/**
 * Exception thrown when track merging fails.
 */
public class MergeException extends DownloadException {

    private final List<String> inputFiles;
    private final String outputFile;
    private final Integer exitCode;

    public MergeException(String message, List<String> inputFiles, String outputFile) {
        super(message);
        this.inputFiles = inputFiles;
        this.outputFile = outputFile;
        this.exitCode = null;
    }

    public MergeException(String message, List<String> inputFiles, String outputFile, Integer exitCode) {
        super(message);
        this.inputFiles = inputFiles;
        this.outputFile = outputFile;
        this.exitCode = exitCode;
    }

    public MergeException(String message, Throwable cause, List<String> inputFiles, String outputFile) {
        super(message, cause);
        this.inputFiles = inputFiles;
        this.outputFile = outputFile;
        this.exitCode = null;
    }

    public List<String> getInputFiles() {
        return inputFiles;
    }

    public String getOutputFile() {
        return outputFile;
    }

    public Integer getExitCode() {
        return exitCode;
    }
}
