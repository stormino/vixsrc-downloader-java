package com.github.stormino.service.state;

import com.github.stormino.model.DownloadStatus;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * State machine for managing download status transitions.
 * Validates state transitions and prevents invalid state changes.
 *
 * Valid state flow:
 * <pre>
 * QUEUED → EXTRACTING → DOWNLOADING → MERGING → COMPLETED
 *                           ↓             ↓
 *                        FAILED       FAILED
 *    ↓
 * CANCELLED
 * </pre>
 */
@Component
@Slf4j
public class DownloadStateMachine {

    private final Map<DownloadStatus, Set<DownloadStatus>> validTransitions;

    public DownloadStateMachine() {
        validTransitions = new EnumMap<>(DownloadStatus.class);
        initializeTransitions();
    }

    private void initializeTransitions() {
        // QUEUED can transition to: EXTRACTING, CANCELLED, FAILED
        validTransitions.put(DownloadStatus.QUEUED,
            EnumSet.of(DownloadStatus.EXTRACTING, DownloadStatus.CANCELLED, DownloadStatus.FAILED));

        // EXTRACTING can transition to: DOWNLOADING, FAILED, CANCELLED
        validTransitions.put(DownloadStatus.EXTRACTING,
            EnumSet.of(DownloadStatus.DOWNLOADING, DownloadStatus.FAILED, DownloadStatus.CANCELLED));

        // DOWNLOADING can transition to: MERGING, COMPLETED, FAILED, CANCELLED
        validTransitions.put(DownloadStatus.DOWNLOADING,
            EnumSet.of(DownloadStatus.MERGING, DownloadStatus.COMPLETED, DownloadStatus.FAILED, DownloadStatus.CANCELLED));

        // MERGING can transition to: COMPLETED, FAILED, CANCELLED
        validTransitions.put(DownloadStatus.MERGING,
            EnumSet.of(DownloadStatus.COMPLETED, DownloadStatus.FAILED, DownloadStatus.CANCELLED));

        // Terminal states (no transitions)
        validTransitions.put(DownloadStatus.COMPLETED, EnumSet.noneOf(DownloadStatus.class));
        validTransitions.put(DownloadStatus.FAILED, EnumSet.noneOf(DownloadStatus.class));
        validTransitions.put(DownloadStatus.CANCELLED, EnumSet.noneOf(DownloadStatus.class));

        // NOT_FOUND is terminal (for sub-tasks only)
        validTransitions.put(DownloadStatus.NOT_FOUND, EnumSet.noneOf(DownloadStatus.class));
    }

    /**
     * Check if a state transition is valid.
     *
     * @param currentState Current state
     * @param newState Desired new state
     * @return true if transition is valid
     */
    public boolean isValidTransition(@NonNull DownloadStatus currentState, @NonNull DownloadStatus newState) {
        if (currentState == newState) {
            // Same state is always valid (idempotent)
            return true;
        }

        Set<DownloadStatus> allowedTransitions = validTransitions.get(currentState);
        return allowedTransitions != null && allowedTransitions.contains(newState);
    }

    /**
     * Validate and perform state transition.
     *
     * @param taskId Task ID for logging
     * @param currentState Current state
     * @param newState Desired new state
     * @return New state if valid, current state if invalid
     */
    public DownloadStatus transition(
            @NonNull String taskId,
            @NonNull DownloadStatus currentState,
            @NonNull DownloadStatus newState) {

        if (isValidTransition(currentState, newState)) {
            if (currentState != newState) {
                log.debug("Task {} state transition: {} → {}", taskId, currentState, newState);
            }
            return newState;
        } else {
            log.warn("Task {} invalid state transition attempted: {} → {} (rejected)",
                    taskId, currentState, newState);
            return currentState;
        }
    }

    /**
     * Validate and perform state transition with exception on failure.
     *
     * @param taskId Task ID for logging
     * @param currentState Current state
     * @param newState Desired new state
     * @return New state
     * @throws IllegalStateException if transition is invalid
     */
    public DownloadStatus transitionOrThrow(
            @NonNull String taskId,
            @NonNull DownloadStatus currentState,
            @NonNull DownloadStatus newState) {

        if (!isValidTransition(currentState, newState)) {
            throw new IllegalStateException(String.format(
                    "Invalid state transition for task %s: %s → %s",
                    taskId, currentState, newState));
        }

        if (currentState != newState) {
            log.debug("Task {} state transition: {} → {}", taskId, currentState, newState);
        }
        return newState;
    }

    /**
     * Check if a state is terminal (no further transitions possible).
     *
     * @param state State to check
     * @return true if terminal state
     */
    public boolean isTerminalState(@NonNull DownloadStatus state) {
        Set<DownloadStatus> allowedTransitions = validTransitions.get(state);
        return allowedTransitions == null || allowedTransitions.isEmpty();
    }

    /**
     * Get all valid next states from current state.
     *
     * @param currentState Current state
     * @return Set of valid next states
     */
    public Set<DownloadStatus> getValidNextStates(@NonNull DownloadStatus currentState) {
        Set<DownloadStatus> states = validTransitions.get(currentState);
        return states != null ? EnumSet.copyOf(states) : EnumSet.noneOf(DownloadStatus.class);
    }

    /**
     * Check if current state can transition to completed.
     *
     * @param currentState Current state
     * @return true if can transition to COMPLETED
     */
    public boolean canComplete(@NonNull DownloadStatus currentState) {
        return isValidTransition(currentState, DownloadStatus.COMPLETED);
    }

    /**
     * Check if current state can transition to failed.
     *
     * @param currentState Current state
     * @return true if can transition to FAILED
     */
    public boolean canFail(@NonNull DownloadStatus currentState) {
        return isValidTransition(currentState, DownloadStatus.FAILED);
    }

    /**
     * Check if current state can be cancelled.
     *
     * @param currentState Current state
     * @return true if can transition to CANCELLED
     */
    public boolean canCancel(@NonNull DownloadStatus currentState) {
        return isValidTransition(currentState, DownloadStatus.CANCELLED);
    }
}
