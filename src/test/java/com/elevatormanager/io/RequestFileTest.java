package com.elevatormanager.io;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Adaptadores JUnit de los 48 escenarios del parser y sus contratos. */
@Timeout(60)
class RequestFileTest {
    @Test void minimalCabinBatch() throws Exception {
        RequestFileScenarios.minimalCabinBatch();
    }
    @Test void mixedBatchPreservesPhysicalOrder() throws Exception {
        RequestFileScenarios.mixedBatchPreservesPhysicalOrder();
    }
    @Test void repeatedEntriesAreNotRemoved() throws Exception {
        RequestFileScenarios.repeatedEntriesAreNotRemoved();
    }
    @Test void commentsAllowUnicode() throws Exception {
        RequestFileScenarios.commentsAllowUnicode();
    }
    @Test void asciiSpacesAreAccepted() throws Exception {
        RequestFileScenarios.asciiSpacesAreAccepted();
    }
    @Test void mixedLfAndCrlfHaveNoPhantomLine() throws Exception {
        RequestFileScenarios.mixedLfAndCrlfHaveNoPhantomLine();
    }
    @Test void finalNewlineIsOptional() throws Exception {
        RequestFileScenarios.finalNewlineIsOptional();
    }
    @Test void oneInitialBomIsAccepted() throws Exception {
        RequestFileScenarios.oneInitialBomIsAccepted();
    }
    @Test void repeatedBomIsRejected() throws Exception {
        RequestFileScenarios.repeatedBomIsRejected();
    }
    @Test void interiorBomIsRejectedEvenInComments() throws Exception {
        RequestFileScenarios.interiorBomIsRejectedEvenInComments();
    }
    @Test void truncatedUtf8HasOriginalCodingCause() throws Exception {
        RequestFileScenarios.truncatedUtf8HasOriginalCodingCause();
    }
    @Test void malformedUtf8IsNeverReplaced() throws Exception {
        RequestFileScenarios.malformedUtf8IsNeverReplaced();
    }
    @Test void emptyVariantsRejectWithoutFakeLine() throws Exception {
        RequestFileScenarios.emptyVariantsRejectWithoutFakeLine();
    }
    @Test void controlsAreInvalidInsideCommentsAndBlankLines() throws Exception {
        RequestFileScenarios.controlsAreInvalidInsideCommentsAndBlankLines();
    }
    @Test void unicodeIsNotAcceptedInInstructions() throws Exception {
        RequestFileScenarios.unicodeIsNotAcceptedInInstructions();
    }
    @Test void unknownInstructionsAndControlsAreRejected() throws Exception {
        RequestFileScenarios.unknownInstructionsAndControlsAreRejected();
    }
    @Test void directionsAreExactUppercaseTokens() throws Exception {
        RequestFileScenarios.directionsAreExactUppercaseTokens();
    }
    @Test void arityIsExactAndInlineCommentsAreRejected() throws Exception {
        RequestFileScenarios.arityIsExactAndInlineCommentsAreRejected();
    }
    @Test void signedDecimalAndExponentNumbersAreRejected() throws Exception {
        RequestFileScenarios.signedDecimalAndExponentNumbersAreRejected();
    }
    @Test void integerOverflowIsRejectedWithoutWrapping() throws Exception {
        RequestFileScenarios.integerOverflowIsRejectedWithoutWrapping();
    }
    @Test void leadingZerosDoNotImplyNumericOverflow() throws Exception {
        RequestFileScenarios.leadingZerosDoNotImplyNumericOverflow();
    }
    @Test void floorBoundsAreChecked() throws Exception {
        RequestFileScenarios.floorBoundsAreChecked();
    }
    @Test void cabinIdBoundsAreCheckedBeforeFollowingArguments() throws Exception {
        RequestFileScenarios.cabinIdBoundsAreCheckedBeforeFollowingArguments();
    }
    @Test void impossibleHallDirectionsAreRejected() throws Exception {
        RequestFileScenarios.impossibleHallDirectionsAreRejected();
    }
    @Test void singleFloorBuildingAcceptsOnlyCabinRequests() throws Exception {
        RequestFileScenarios.singleFloorBuildingAcceptsOnlyCabinRequests();
    }
    @Test void everyInvalidLineHasOneOrderedDiagnostic() throws Exception {
        RequestFileScenarios.everyInvalidLineHasOneOrderedDiagnostic();
    }
    @Test void finalLineErrorRejectsEntireBatch() throws Exception {
        RequestFileScenarios.finalLineErrorRejectsEntireBatch();
    }
    @Test void invalidReadCannotChangePreviousValidBatch() throws Exception {
        RequestFileScenarios.invalidReadCannotChangePreviousValidBatch();
    }
    @Test void exactByteLimitIsAccepted() throws Exception {
        RequestFileScenarios.exactByteLimitIsAccepted();
    }
    @Test void oneByteOverLimitIsRejected() throws Exception {
        RequestFileScenarios.oneByteOverLimitIsRejected();
    }
    @Test void exactlyTenThousandPhysicalLinesAreAccepted() throws Exception {
        RequestFileScenarios.exactlyTenThousandPhysicalLinesAreAccepted();
    }
    @Test void tenThousandAndOneLinesAreRejected() throws Exception {
        RequestFileScenarios.tenThousandAndOneLinesAreRejected();
    }
    @Test void supplementaryCodePointsUseUnicodeLength() throws Exception {
        RequestFileScenarios.supplementaryCodePointsUseUnicodeLength();
    }
    @Test void overlongLinesAreRejectedWithoutTruncation() throws Exception {
        RequestFileScenarios.overlongLinesAreRejectedWithoutTruncation();
    }
    @Test void bareCarriageReturnIsRejected() throws Exception {
        RequestFileScenarios.bareCarriageReturnIsRejected();
    }
    @Test void unicodeLineSeparatorsAreRejectedEvenInComments() throws Exception {
        RequestFileScenarios.unicodeLineSeparatorsAreRejectedEvenInComments();
    }
    @Test void diagnosticsUsePhysicalLinesAfterBomAndComments() throws Exception {
        RequestFileScenarios.diagnosticsUsePhysicalLinesAfterBomAndComments();
    }
    @Test void realPathIsReadAndReleased() throws Exception {
        RequestFileScenarios.realPathIsReadAndReleased();
    }
    @Test void missingPathReturnsReadErrorWithCause() throws Exception {
        RequestFileScenarios.missingPathReturnsReadErrorWithCause();
    }
    @Test void directoryCannotBeReadAsRequestFile() throws Exception {
        RequestFileScenarios.directoryCannotBeReadAsRequestFile();
    }
    @Test void streamFailureKeepsOriginalExceptionAndNoPartialBatch() throws Exception {
        RequestFileScenarios.streamFailureKeepsOriginalExceptionAndNoPartialBatch();
    }
    @Test void borrowedStreamsRemainOpenOnSuccessAndFailure() throws Exception {
        RequestFileScenarios.borrowedStreamsRemainOpenOnSuccessAndFailure();
    }
    @Test void unboundedStreamConsumesOnlyLimitPlusOneBytes() throws Exception {
        RequestFileScenarios.unboundedStreamConsumesOnlyLimitPlusOneBytes();
    }
    @Test void zeroLengthBulkReadsDoNotLoseBytes() throws Exception {
        RequestFileScenarios.zeroLengthBulkReadsDoNotLoseBytes();
    }
    @Test void interruptionPreservesFlagAndRejectsRead() throws Exception {
        RequestFileScenarios.interruptionPreservesFlagAndRejectsRead();
    }
    @Test void sharedReaderSupportsIndependentConcurrentReads() throws Exception {
        RequestFileScenarios.sharedReaderSupportsIndependentConcurrentReads();
    }
    @Test void resultAndBatchAreImmutableAndRejectInvalidConstruction() throws Exception {
        RequestFileScenarios.resultAndBatchAreImmutableAndRejectInvalidConstruction();
    }
    @Test void parserAgreesWithExistingDomainRules() throws Exception {
        RequestFileScenarios.parserAgreesWithExistingDomainRules();
    }
}
