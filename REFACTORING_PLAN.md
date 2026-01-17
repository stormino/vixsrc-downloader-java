# VixSrc Downloader - Comprehensive Refactoring Plan

## Executive Summary

This plan outlines systematic improvements to make the codebase more uniform, robust, and maintainable. Focus areas: code duplication, error handling, abstraction, consistency, and robustness.

---

## 1. Code Duplication & DRY Violations

### 1.1 Duplicate Utility Methods
**Problem**: `formatSpeed()` duplicated in 3 classes (VideoTrackDownloadStrategy, AudioTrackDownloadStrategy, DownloadTask)

**Solution**:
- Create `com.github.stormino.util.FormatUtils` utility class
- Consolidate: `formatSpeed()`, `formatSize()`, `formatDuration()`, `sanitizeFilename()`
- Add comprehensive unit tests

**Files**: VideoTrackDownloadStrategy.java:263-273, AudioTrackDownloadStrategy.java:243-253, DownloadTask.java:244-252

---

### 1.2 Duplicate Progress Calculation Logic
**Problem**: Speed/ETA calculations repeated across multiple strategies

**Solution**:
- Create `com.github.stormino.util.ProgressCalculator` utility class
- Methods: `calculateSpeed()`, `calculateEta()`, `calculateProgress()`
- Consistent calculation algorithm across all download types

**Files**: VideoTrackDownloadStrategy.java:107-126, AudioTrackDownloadStrategy.java:107-128, DownloadTask.java:177-214

---

### 1.3 Duplicate File Path Handling
**Problem**: `sanitizeFilename()` logic in DownloadQueueService duplicated conceptually

**Solution**:
- Create `com.github.stormino.util.PathUtils` utility class
- Methods: `sanitizeFilename()`, `createDirectoryStructure()`, `generateOutputPath()`
- Centralize all path manipulation logic

**Files**: DownloadQueueService.java:516-521, DownloadQueueService.java:277-307

---

## 2. Abstraction & Design Patterns

### 2.1 Track Download Strategy Pattern
**Problem**: VideoTrackDownloadStrategy, AudioTrackDownloadStrategy, SubtitleTrackDownloadStrategy have no common interface

**Solution**:
- Create `TrackDownloadStrategy` interface with method:
  ```java
  boolean downloadTrack(TrackDownloadRequest request, Consumer<ProgressUpdate> callback);
  ```
- Create `TrackDownloadRequest` DTO to encapsulate parameters (currently 8+ parameters)
- Implement interface in all strategy classes
- Simplifies TrackDownloadOrchestrator usage

**Files**: VideoTrackDownloadStrategy.java, AudioTrackDownloadStrategy.java, SubtitleTrackDownloadStrategy.java

---

### 2.2 Progress Parser Extraction
**Problem**: `FfmpegProgressParser` is a private inner class in DownloadExecutorService, not reusable

**Solution**:
- Extract to `com.github.stormino.service.parser.FfmpegProgressParser`
- Create `ProgressParser` interface
- Enable extensibility for other download tools (yt-dlp, aria2c, etc.)
- Add unit tests for parser logic

**Files**: DownloadExecutorService.java:249-402

---

### 2.3 Playlist Selection Logic
**Problem**: Variant/track selection logic embedded in strategies, not testable in isolation

**Solution**:
- Create `com.github.stormino.service.selector.PlaylistVariantSelector` class
- Extract methods: `selectVideoVariant()`, `selectAudioTrack()`, `selectSubtitleTrack()`
- Enable quality preference configuration (best, worst, specific resolution)
- Comprehensive unit tests for selection logic

**Files**: VideoTrackDownloadStrategy.java:208-247, AudioTrackDownloadStrategy.java:209-241

---

## 3. Error Handling & Robustness

### 3.1 Inconsistent Error Return Patterns
**Problem**: Mix of boolean returns, exceptions, and Optional - difficult to trace failures

**Solution**:
- Create `DownloadResult` class with status, error message, metadata
- Replace boolean returns with `DownloadResult` across all download methods
- Standardize: success/failure/partial success with detailed error context
- Example:
  ```java
  public class DownloadResult {
      private final boolean success;
      private final String errorMessage;
      private final Map<String, Object> metadata;
  }
  ```

**Files**: All strategy classes, DownloadExecutorService, TrackDownloadOrchestrator

---

### 3.2 Exception Handling Standardization
**Problem**: Catching generic `Exception`, inconsistent logging, swallowed exceptions

**Solution**:
- Create custom exception hierarchy:
  - `DownloadException` (base)
  - `PlaylistExtractionException`
  - `TrackDownloadException`
  - `MergeException`
  - `ConfigurationException`
- Consistent logging pattern: log.error for failures, log.warn for retries, log.debug for progress
- Never swallow exceptions without logging
- Propagate exceptions with context

**Files**: All service classes

---

### 3.3 Resource Cleanup Guarantees
**Problem**: Temp file cleanup not guaranteed on failure paths

**Solution**:
- Use try-with-resources for all file operations
- Create `TempFileManager` class to track and auto-cleanup temp files
- Register shutdown hooks for emergency cleanup
- Ensure `cleanupTempFiles()` called in finally blocks

**Files**: TrackDownloadOrchestrator.java:494-512, VideoTrackDownloadStrategy.java:188-192, AudioTrackDownloadStrategy.java:188-193

---

### 3.4 Null Safety & Validation
**Problem**: Minimal null checks, NPE risks, no input validation

**Solution**:
- Add `@NonNull` annotations from Lombok
- Use existing validation libraries:
  - **Apache Commons Lang3**: `Validate.notNull()`, `Validate.notEmpty()`, `Validate.isTrue()`
  - **Spring Validation**: `@Valid`, `@NotNull`, `@NotBlank` annotations
  - **Lombok**: `@NonNull` for constructor/method parameters
- Validate all external inputs (TMDB IDs, languages, quality settings)
- Use `Objects.requireNonNull()` for critical paths
- Use Optional for truly optional values

**Files**: All service classes, especially public methods

---

## 4. Configuration & Constants

### 4.1 Magic Numbers Elimination
**Problem**: Hardcoded values throughout codebase

**Solution**:
- Move to `VixSrcProperties` or create `DownloadConstants` class:
  ```java
  public class DownloadConstants {
      public static final long MAX_TIMEOUT_HOURS = 2;
      public static final String VIDEO_EXTENSION = ".mp4";
      public static final String AUDIO_EXTENSION = ".m4a";
      public static final String SUBTITLE_EXTENSION = ".vtt";
      public static final int PROGRESS_BROADCAST_INTERVAL_MS = 500;
  }
  ```
- Extract all hardcoded timeouts, file extensions, buffer sizes

**Current Examples**:
- `2` hours timeout (DownloadExecutorService.java:104, 204)
- File extensions scattered across code

---

### 4.2 Retry Configuration with Spring Retry
**Problem**: Retry logic hardcoded with `Integer.MAX_VALUE`, no user configurability, manual backoff implementation

**Solution**:
- Migrate to Spring Retry framework (`@Retryable`, `@Recover`)
- Add to `VixSrcProperties`:
  ```yaml
  vixsrc:
    extractor:
      max-retries: 2147483647  # Integer.MAX_VALUE by default (unlimited)
      max-retry-delay-ms: 30000
      retry-backoff-multiplier: 2
      retry-on-status-codes: [500, 502, 503, 504, 429]
  ```
- Use `@Retryable` annotations on HTTP calls:
  ```java
  @Retryable(
    maxAttempts = "${vixsrc.extractor.max-retries}",
    backoff = @Backoff(
      delay = "${vixsrc.extractor.retry-delay-ms}",
      multiplier = "${vixsrc.extractor.retry-backoff-multiplier}",
      maxDelay = "${vixsrc.extractor.max-retry-delay-ms}"
    )
  )
  ```
- Add `@Recover` methods for fallback behavior
- Users can override to set finite retry limits if desired
- Keep `Integer.MAX_VALUE` as default for current behavior
- Enable circuit breaker pattern via Spring Retry for persistent failures

**Benefits**:
- Declarative retry configuration
- User-configurable retry limits
- Standardized retry mechanisms
- Better monitoring and metrics
- Maintains unlimited retries by default

**Files**: HttpClientConfig.java:34, HttpClientConfig.java:84-149, VixSrcExtractorService.java

---

## 5. Consistency & Naming

### 5.1 Method Naming Standardization
**Problem**: Inconsistent verb usage (execute, download, process, extract)

**Solution**:
- Standardize naming convention:
  - `extract*` - for URL/playlist extraction
  - `download*` - for actual download operations
  - `parse*` - for parsing operations
  - `build*` - for construction operations
  - `validate*` - for validation operations
- Apply across all classes

**Examples**:
- `executeTrackDownload()` → `downloadTrack()`
- `executeMergeCommand()` → `mergeTracks()`
- `processTaskAsync()` → `downloadAsync()`

---

### 5.2 Variable Naming Consistency
**Problem**: Mixing `playlistUrl`, `playlist`, `playlistInfo`

**Solution**:
- Standardize:
  - `playlistUrl` - String URL
  - `playlist` - HlsPlaylist object
  - `playlistInfo` - PlaylistInfo metadata
  - `variant` - VideoVariant object
  - `track` - AudioTrack/SubtitleTrack object

---

## 6. Concurrency & Thread Safety

### 6.1 DownloadTask Thread Safety
**Problem**: Volatile fields but no atomic operations, race conditions possible

**Solution**:
- Use `AtomicReference<DownloadStatus>` for status
- Use `AtomicDouble` wrapper for progress (or synchronized setters)
- Document thread-safety guarantees
- Consider making DownloadTask immutable with builder for updates

**Files**: DownloadTask.java:37-46

---

### 6.2 Process Management Thread Safety
**Problem**: `ConcurrentHashMap<String, Process>` but manual process lifecycle management

**Solution**:
- Create `ProcessManager` class to encapsulate process lifecycle
- Synchronized process start/stop/cancel operations
- Track process trees for proper cleanup
- Add process timeout monitoring

**Files**: DownloadExecutorService.java:27, DownloadExecutorService.java:39-51

---

### 6.3 Queue Synchronization
**Problem**: `Queue<DownloadTask>` with manual synchronization, not using concurrent queue

**Solution**:
- Replace `LinkedList` with `ConcurrentLinkedQueue`
- Remove manual synchronization blocks
- Use `BlockingQueue` if needed for thread coordination
- Document queue access patterns

**Files**: DownloadQueueService.java:38, DownloadQueueService.java:125-127

---

## 7. Separation of Concerns

### 7.1 Extract Progress Broadcasting
**Problem**: Progress broadcasting logic mixed with download logic

**Solution**:
- Create `ProgressEventBuilder` class to construct ProgressUpdate objects
- Keep ProgressBroadcastService focused on broadcasting
- Strategies only call `broadcastProgress()`, don't construct updates
- Centralize progress update construction logic

**Files**: All strategy classes, TrackDownloadOrchestrator.java:514-550

---

### 7.2 Separate Command Building
**Problem**: ffmpeg command building embedded in strategies and orchestrator

**Solution**:
- Create `com.github.stormino.service.command.FfmpegCommandBuilder` class
- Methods: `buildConversionCommand()`, `buildMergeCommand()`, `buildDownloadCommand()`
- Encapsulate command-line argument logic
- Easier to test and modify

**Files**: VideoTrackDownloadStrategy.java:165-178, AudioTrackDownloadStrategy.java:167-179, TrackDownloadOrchestrator.java:322-465

---

### 7.3 Metadata Handling Separation
**Problem**: Metadata formatting logic in ContentMetadata and scattered in services

**Solution**:
- Create `MetadataFormatter` class for filename generation
- Create `DirectoryStructureManager` for path creation
- Keep `ContentMetadata` as pure data class
- Separate formatting from data

**Files**: DownloadQueueService.java:473-514

---

## 8. Testing Infrastructure

### 8.1 Add Unit Tests
**Current**: No tests implemented

**Solution**:
- Create test infrastructure:
  - `src/test/java` directory structure
  - Mock dependencies (OkHttpClient, ExecutorService)
  - Test utilities for common scenarios

**Priority Test Classes**:
1. `FormatUtilsTest` - utility method tests
2. `ProgressCalculatorTest` - calculation logic
3. `FfmpegProgressParserTest` - parsing logic
4. `PlaylistVariantSelectorTest` - selection logic
5. `PathUtilsTest` - path manipulation

---

### 8.2 Integration Tests
**Solution**:
- Create integration test framework
- Test download flow end-to-end (with mocked HTTP)
- Test concurrent download scenarios
- Test failure recovery

---

## 9. Documentation & Code Quality

### 9.1 Javadoc Standardization
**Problem**: Inconsistent javadoc coverage and quality

**Solution**:
- Add comprehensive javadoc to all public methods
- Document parameters, return values, exceptions
- Include usage examples for complex methods
- Document thread-safety guarantees

---

### 9.2 Logging Improvements
**Problem**: Inconsistent logging levels and messages

**Solution**:
- Standardize logging:
  - DEBUG: Detailed flow, progress updates
  - INFO: Key milestones (task started, completed)
  - WARN: Retryable failures, degraded functionality
  - ERROR: Non-recoverable failures
- Add correlation IDs for tracking related logs
- Structured logging with consistent message format

---

## 10. Architecture Improvements

### 10.1 Event-Driven Progress Updates
**Current**: Direct callback invocations

**Solution**:
- Consider Spring Events for progress updates
- Decouple progress producers from consumers
- Enable multiple progress listeners
- Better testability

---

### 10.2 Download State Machine
**Problem**: Download states managed ad-hoc, transitions not validated

**Solution**:
- Implement formal state machine for DownloadStatus transitions
- Validate state transitions
- Document valid state flows
- Prevent invalid state changes

**Valid Transitions**:
```
QUEUED → EXTRACTING → DOWNLOADING → MERGING → COMPLETED
                          ↓              ↓
                       FAILED        FAILED
        ↓
   CANCELLED
```

---

## Implementation Priority

### Phase 1: Foundation (High Impact, Low Risk)
1. Create utility classes (FormatUtils, PathUtils, ProgressCalculator)
2. Extract constants to DownloadConstants
3. Standardize method naming
4. Use existing validation libraries (Lombok @NonNull, Apache Commons Validate, Spring Validation)

### Phase 2: Abstraction (High Impact, Medium Risk)
1. Create TrackDownloadStrategy interface
2. Extract FfmpegProgressParser
3. Create DownloadResult class
4. Standardize error handling

### Phase 3: Robustness (Medium Impact, Low Risk)
1. Improve resource cleanup
2. Add custom exception hierarchy
3. Fix thread safety issues
4. Migrate to Spring Retry for configurable retry behavior

### Phase 4: Architecture (High Impact, High Risk)
1. Separate command building
2. Extract progress broadcasting
3. Implement state machine
4. Refactor concurrency model

### Phase 5: Quality (Medium Impact, Low Risk)
1. Add unit tests
2. Add integration tests
3. Improve documentation
4. Standardize logging

---

## Success Metrics

- **Code Duplication**: Reduce from ~15% to <5%
- **Test Coverage**: Increase from 0% to >70%
- **Cyclomatic Complexity**: Reduce max from ~20 to <10
- **Error Handling**: 100% of exceptions logged and handled
- **Documentation**: 100% of public APIs documented

---

## Risk Mitigation

1. **Regression Risk**: Implement comprehensive tests before refactoring
2. **Integration Risk**: Refactor incrementally, one component at a time
3. **Performance Risk**: Benchmark before/after major changes
4. **Compatibility Risk**: Maintain backward compatibility where possible

---

## Conclusion

This refactoring plan systematically addresses code quality issues while maintaining functionality. Implementation should be incremental, well-tested, and reversible. Focus on high-impact, low-risk changes first to build confidence.
