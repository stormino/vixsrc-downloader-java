package com.github.stormino.service.state;

import com.github.stormino.model.DownloadStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("DownloadStateMachine")
class DownloadStateMachineTest {

    private DownloadStateMachine stateMachine;
    private static final String TASK_ID = "test-task-123";

    @BeforeEach
    void setUp() {
        stateMachine = new DownloadStateMachine();
    }

    @Nested
    @DisplayName("isValidTransition")
    class IsValidTransitionTests {

        @Test
        @DisplayName("same state should always be valid (idempotent)")
        void sameStateShouldAlwaysBeValid() {
            for (DownloadStatus status : DownloadStatus.values()) {
                assertTrue(stateMachine.isValidTransition(status, status),
                        "Same state transition should be valid for " + status);
            }
        }

        @Test
        @DisplayName("QUEUED can transition to EXTRACTING")
        void queuedCanTransitionToExtracting() {
            assertTrue(stateMachine.isValidTransition(DownloadStatus.QUEUED, DownloadStatus.EXTRACTING));
        }

        @Test
        @DisplayName("QUEUED can transition to CANCELLED")
        void queuedCanTransitionToCancelled() {
            assertTrue(stateMachine.isValidTransition(DownloadStatus.QUEUED, DownloadStatus.CANCELLED));
        }

        @Test
        @DisplayName("QUEUED can transition to FAILED")
        void queuedCanTransitionToFailed() {
            assertTrue(stateMachine.isValidTransition(DownloadStatus.QUEUED, DownloadStatus.FAILED));
        }

        @Test
        @DisplayName("QUEUED cannot transition directly to DOWNLOADING")
        void queuedCannotTransitionDirectlyToDownloading() {
            assertFalse(stateMachine.isValidTransition(DownloadStatus.QUEUED, DownloadStatus.DOWNLOADING));
        }

        @Test
        @DisplayName("EXTRACTING can transition to DOWNLOADING")
        void extractingCanTransitionToDownloading() {
            assertTrue(stateMachine.isValidTransition(DownloadStatus.EXTRACTING, DownloadStatus.DOWNLOADING));
        }

        @Test
        @DisplayName("DOWNLOADING can transition to MERGING")
        void downloadingCanTransitionToMerging() {
            assertTrue(stateMachine.isValidTransition(DownloadStatus.DOWNLOADING, DownloadStatus.MERGING));
        }

        @Test
        @DisplayName("DOWNLOADING can transition directly to COMPLETED")
        void downloadingCanTransitionDirectlyToCompleted() {
            assertTrue(stateMachine.isValidTransition(DownloadStatus.DOWNLOADING, DownloadStatus.COMPLETED));
        }

        @Test
        @DisplayName("MERGING can transition to COMPLETED")
        void mergingCanTransitionToCompleted() {
            assertTrue(stateMachine.isValidTransition(DownloadStatus.MERGING, DownloadStatus.COMPLETED));
        }

        @Test
        @DisplayName("MERGING can transition to FAILED")
        void mergingCanTransitionToFailed() {
            assertTrue(stateMachine.isValidTransition(DownloadStatus.MERGING, DownloadStatus.FAILED));
        }
    }

    @Nested
    @DisplayName("terminal states")
    class TerminalStateTests {

        @ParameterizedTest
        @EnumSource(value = DownloadStatus.class, names = {"COMPLETED", "FAILED", "CANCELLED", "NOT_FOUND"})
        @DisplayName("terminal states cannot transition to other states")
        void terminalStatesCannotTransitionToOtherStates(DownloadStatus terminalState) {
            for (DownloadStatus target : DownloadStatus.values()) {
                if (target != terminalState) {
                    assertFalse(stateMachine.isValidTransition(terminalState, target),
                            terminalState + " should not transition to " + target);
                }
            }
        }

        @ParameterizedTest
        @EnumSource(value = DownloadStatus.class, names = {"COMPLETED", "FAILED", "CANCELLED", "NOT_FOUND"})
        @DisplayName("isTerminalState should return true for terminal states")
        void isTerminalStateShouldReturnTrueForTerminalStates(DownloadStatus terminalState) {
            assertTrue(stateMachine.isTerminalState(terminalState));
        }

        @ParameterizedTest
        @EnumSource(value = DownloadStatus.class, names = {"QUEUED", "EXTRACTING", "DOWNLOADING", "MERGING"})
        @DisplayName("isTerminalState should return false for non-terminal states")
        void isTerminalStateShouldReturnFalseForNonTerminalStates(DownloadStatus nonTerminalState) {
            assertFalse(stateMachine.isTerminalState(nonTerminalState));
        }
    }

    @Nested
    @DisplayName("transition")
    class TransitionTests {

        @Test
        @DisplayName("valid transition should return new state")
        void validTransitionShouldReturnNewState() {
            DownloadStatus result = stateMachine.transition(TASK_ID, DownloadStatus.QUEUED, DownloadStatus.EXTRACTING);
            assertEquals(DownloadStatus.EXTRACTING, result);
        }

        @Test
        @DisplayName("invalid transition should return current state")
        void invalidTransitionShouldReturnCurrentState() {
            DownloadStatus result = stateMachine.transition(TASK_ID, DownloadStatus.QUEUED, DownloadStatus.COMPLETED);
            assertEquals(DownloadStatus.QUEUED, result);
        }

        @Test
        @DisplayName("same state transition should return same state")
        void sameStateTransitionShouldReturnSameState() {
            DownloadStatus result = stateMachine.transition(TASK_ID, DownloadStatus.DOWNLOADING, DownloadStatus.DOWNLOADING);
            assertEquals(DownloadStatus.DOWNLOADING, result);
        }
    }

    @Nested
    @DisplayName("transitionOrThrow")
    class TransitionOrThrowTests {

        @Test
        @DisplayName("valid transition should return new state")
        void validTransitionShouldReturnNewState() {
            DownloadStatus result = stateMachine.transitionOrThrow(TASK_ID, DownloadStatus.QUEUED, DownloadStatus.EXTRACTING);
            assertEquals(DownloadStatus.EXTRACTING, result);
        }

        @Test
        @DisplayName("invalid transition should throw IllegalStateException")
        void invalidTransitionShouldThrowIllegalStateException() {
            IllegalStateException exception = assertThrows(IllegalStateException.class, () ->
                    stateMachine.transitionOrThrow(TASK_ID, DownloadStatus.QUEUED, DownloadStatus.COMPLETED));

            assertTrue(exception.getMessage().contains(TASK_ID));
            assertTrue(exception.getMessage().contains("QUEUED"));
            assertTrue(exception.getMessage().contains("COMPLETED"));
        }
    }

    @Nested
    @DisplayName("getValidNextStates")
    class GetValidNextStatesTests {

        @Test
        @DisplayName("QUEUED should have valid next states")
        void queuedShouldHaveValidNextStates() {
            Set<DownloadStatus> nextStates = stateMachine.getValidNextStates(DownloadStatus.QUEUED);

            assertTrue(nextStates.contains(DownloadStatus.EXTRACTING));
            assertTrue(nextStates.contains(DownloadStatus.CANCELLED));
            assertTrue(nextStates.contains(DownloadStatus.FAILED));
            assertFalse(nextStates.contains(DownloadStatus.DOWNLOADING));
        }

        @Test
        @DisplayName("terminal states should have no valid next states")
        void terminalStatesShouldHaveNoValidNextStates() {
            assertTrue(stateMachine.getValidNextStates(DownloadStatus.COMPLETED).isEmpty());
            assertTrue(stateMachine.getValidNextStates(DownloadStatus.FAILED).isEmpty());
            assertTrue(stateMachine.getValidNextStates(DownloadStatus.CANCELLED).isEmpty());
        }
    }

    @Nested
    @DisplayName("convenience methods")
    class ConvenienceMethodTests {

        @Test
        @DisplayName("canComplete should return true for DOWNLOADING and MERGING")
        void canCompleteShouldReturnTrueForDownloadingAndMerging() {
            assertTrue(stateMachine.canComplete(DownloadStatus.DOWNLOADING));
            assertTrue(stateMachine.canComplete(DownloadStatus.MERGING));
        }

        @Test
        @DisplayName("canComplete should return false for QUEUED")
        void canCompleteShouldReturnFalseForQueued() {
            assertFalse(stateMachine.canComplete(DownloadStatus.QUEUED));
        }

        @Test
        @DisplayName("canFail should return true for non-terminal states")
        void canFailShouldReturnTrueForNonTerminalStates() {
            assertTrue(stateMachine.canFail(DownloadStatus.QUEUED));
            assertTrue(stateMachine.canFail(DownloadStatus.EXTRACTING));
            assertTrue(stateMachine.canFail(DownloadStatus.DOWNLOADING));
            assertTrue(stateMachine.canFail(DownloadStatus.MERGING));
        }

        @Test
        @DisplayName("canFail should return false for COMPLETED")
        void canFailShouldReturnFalseForCompleted() {
            assertFalse(stateMachine.canFail(DownloadStatus.COMPLETED));
        }

        @Test
        @DisplayName("canFail should return true for FAILED (idempotent same-state transition)")
        void canFailShouldReturnTrueForFailedIdempotent() {
            // Same state transition is valid (idempotent)
            assertTrue(stateMachine.canFail(DownloadStatus.FAILED));
        }

        @Test
        @DisplayName("canCancel should return true for non-terminal states")
        void canCancelShouldReturnTrueForNonTerminalStates() {
            assertTrue(stateMachine.canCancel(DownloadStatus.QUEUED));
            assertTrue(stateMachine.canCancel(DownloadStatus.EXTRACTING));
            assertTrue(stateMachine.canCancel(DownloadStatus.DOWNLOADING));
            assertTrue(stateMachine.canCancel(DownloadStatus.MERGING));
        }

        @Test
        @DisplayName("canCancel should return false for COMPLETED")
        void canCancelShouldReturnFalseForCompleted() {
            assertFalse(stateMachine.canCancel(DownloadStatus.COMPLETED));
        }

        @Test
        @DisplayName("canCancel should return true for CANCELLED (idempotent same-state transition)")
        void canCancelShouldReturnTrueForCancelledIdempotent() {
            // Same state transition is valid (idempotent)
            assertTrue(stateMachine.canCancel(DownloadStatus.CANCELLED));
        }
    }

    @Nested
    @DisplayName("happy path workflow")
    class HappyPathWorkflowTests {

        @Test
        @DisplayName("complete download workflow should be valid")
        void completeDownloadWorkflowShouldBeValid() {
            // Simulate happy path: QUEUED -> EXTRACTING -> DOWNLOADING -> MERGING -> COMPLETED
            assertTrue(stateMachine.isValidTransition(DownloadStatus.QUEUED, DownloadStatus.EXTRACTING));
            assertTrue(stateMachine.isValidTransition(DownloadStatus.EXTRACTING, DownloadStatus.DOWNLOADING));
            assertTrue(stateMachine.isValidTransition(DownloadStatus.DOWNLOADING, DownloadStatus.MERGING));
            assertTrue(stateMachine.isValidTransition(DownloadStatus.MERGING, DownloadStatus.COMPLETED));
        }

        @Test
        @DisplayName("download without merge workflow should be valid")
        void downloadWithoutMergeWorkflowShouldBeValid() {
            // QUEUED -> EXTRACTING -> DOWNLOADING -> COMPLETED (no merge needed)
            assertTrue(stateMachine.isValidTransition(DownloadStatus.QUEUED, DownloadStatus.EXTRACTING));
            assertTrue(stateMachine.isValidTransition(DownloadStatus.EXTRACTING, DownloadStatus.DOWNLOADING));
            assertTrue(stateMachine.isValidTransition(DownloadStatus.DOWNLOADING, DownloadStatus.COMPLETED));
        }
    }
}
