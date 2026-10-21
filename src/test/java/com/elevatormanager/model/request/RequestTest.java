package com.elevatormanager.model.request;

import com.elevatormanager.model.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RequestTest {
    private static final Instant NOW = Instant.parse("2026-10-21T06:00:00Z");
    private static final SimulationConfig CONFIG = new SimulationConfig(2, 5, 100, 200);

    private HallRequest hall(int floor, Direction direction) {
        return new HallRequest(UUID.randomUUID(), 0, floor, NOW, direction);
    }

    private CabinRequest cabin(int floor, int elevatorId) {
        return new CabinRequest(UUID.randomUUID(), 0, floor, NOW, elevatorId);
    }

    private List<String> codes(Request request, SimulationConfig config) {
        return request.validateAgainst(config).errors().stream()
                .map(ValidationError::code).toList();
    }

    @Test
    void mixedCollectionUsesPolymorphicValidationAndDescription() {
        List<Request> requests = List.of(hall(3, Direction.UP), cabin(5, 2));
        for (Request request : requests) {
            assertTrue(request.validateAgainst(CONFIG).isValid());
        }
        assertEquals(List.of("Llamada externa: piso 3, dirección UP",
                "Destino interno: elevador 2, piso 5"),
                requests.stream().map(Request::describe).toList());
        List<Request> invalid = List.of(hall(5, Direction.UP), cabin(4, 3));
        assertEquals(List.of("HALL_UP_AT_TOP", "ELEVATOR_OUT_OF_RANGE"),
                invalid.stream().flatMap(r -> r.validateAgainst(CONFIG).errors().stream())
                        .map(ValidationError::code).toList());
    }

    @Test
    void hallDirectionsRespectBothBuildingBoundaries() {
        assertEquals(List.of("HALL_UP_AT_TOP"), codes(hall(5, Direction.UP), CONFIG));
        assertEquals(List.of("HALL_DOWN_AT_BOTTOM"), codes(hall(1, Direction.DOWN), CONFIG));
        assertTrue(hall(1, Direction.UP).validateAgainst(CONFIG).isValid());
        assertTrue(hall(5, Direction.DOWN).validateAgainst(CONFIG).isValid());
    }

    @Test
    void singleFloorAcceptsCabinDestinationButRejectsBothHallDirections() {
        SimulationConfig single = new SimulationConfig(1, 1, 1, 1);
        for (Direction direction : Direction.values()) {
            assertFalse(hall(1, direction).validateAgainst(single).isValid());
        }
        assertTrue(cabin(1, 1).validateAgainst(single).isValid());
    }

    @Test
    void commonFloorErrorDoesNotProduceMisleadingHallDirectionError() {
        for (Direction direction : Direction.values()) {
            assertEquals(List.of("FLOOR_OUT_OF_RANGE"), codes(hall(6, direction), CONFIG));
        }
        assertEquals(List.of("FLOOR_OUT_OF_RANGE"), codes(cabin(6, 1), CONFIG));
    }

    @Test
    void cabinErrorsKeepCommonBeforeIndependentSpecificError() {
        assertEquals(List.of("ELEVATOR_OUT_OF_RANGE"), codes(cabin(4, 3), CONFIG));
        assertEquals(List.of("FLOOR_OUT_OF_RANGE", "ELEVATOR_OUT_OF_RANGE"),
                codes(cabin(6, 3), CONFIG));
    }

    @Test
    void constructorsRejectStructurallyInvalidNumbers() {
        UUID id = UUID.randomUUID();
        assertThrows(IllegalArgumentException.class,
                () -> new HallRequest(id, -1, 1, NOW, Direction.UP));
        assertThrows(IllegalArgumentException.class,
                () -> new CabinRequest(id, -1, 1, NOW, 1));
        for (int invalid : new int[]{0, -1, Integer.MIN_VALUE}) {
            assertThrows(IllegalArgumentException.class, () -> hall(invalid, Direction.UP));
            assertThrows(IllegalArgumentException.class, () -> cabin(invalid, 1));
            assertThrows(IllegalArgumentException.class, () -> cabin(1, invalid));
        }
    }

    @Test
    void requiredReferencesCannotBeNull() {
        UUID id = UUID.randomUUID();
        assertThrows(NullPointerException.class, () -> new HallRequest(null, 0, 1, NOW, Direction.UP));
        assertThrows(NullPointerException.class, () -> new CabinRequest(null, 0, 1, NOW, 1));
        assertThrows(NullPointerException.class, () -> new HallRequest(id, 0, 1, null, Direction.UP));
        assertThrows(NullPointerException.class, () -> new CabinRequest(id, 0, 1, null, 1));
        assertThrows(NullPointerException.class, () -> hall(1, null));
        assertThrows(NullPointerException.class, () -> hall(1, Direction.UP).validateAgainst(null));
        assertThrows(NullPointerException.class, () -> cabin(1, 1).validateAgainst(null));
    }

    @Test
    void identityUsesUuidRatherThanFunctionalDuplicateKey() {
        UUID id = UUID.randomUUID();
        Request first = new HallRequest(id, 0, 3, NOW, Direction.UP);
        Request sameId = new HallRequest(id, 0, 3, NOW, Direction.UP);
        Request differentId = hall(3, Direction.UP);
        assertEquals(first, sameId);
        assertEquals(first.hashCode(), sameId.hashCode());
        assertNotEquals(first, differentId);
        assertNotEquals(first, null);
        assertNotEquals(first, id);
        assertEquals(2, new HashSet<>(List.of(first, sameId, differentId)).size());
        // El registro de admisión deberá rechazar reutilizar un UUID con otro contenido.
        Request conflicting = new CabinRequest(id, 1, 4, NOW, 2);
        assertEquals(first, conflicting);
        assertEquals(conflicting, first);
    }

    @Test
    void validationPreservesMetadataAndDoesNotCheckActiveEpoch() {
        UUID id = UUID.randomUUID();
        Request request = new CabinRequest(id, 7, 5, NOW, 2);
        assertTrue(request.validateAgainst(CONFIG).isValid());
        assertEquals(id, request.getId());
        assertEquals(7L, request.getEpoch());
        assertEquals(5, request.getFloor());
        assertEquals(NOW, request.getCreatedAt());
        assertEquals(2, ((CabinRequest) request).getElevatorId());
        assertEquals(Direction.DOWN, hall(3, Direction.DOWN).getDirection());
    }

    @Test
    void validationResultCopiesErrorsAndPreservesCombinationOrder() {
        List<ValidationError> source = new ArrayList<>();
        source.add(new ValidationError("A", "Primer error"));
        ValidationResult result = new ValidationResult(source);
        source.clear();
        assertEquals(1, result.errors().size());
        assertThrows(UnsupportedOperationException.class, () -> result.errors().clear());
        assertEquals(List.of("A", "B"), result.combine(
                ValidationResult.invalid("B", "Segundo error")).errors().stream()
                .map(ValidationError::code).toList());
        assertTrue(ValidationResult.valid().isValid());
    }

    @Test
    void diagnosticsRejectMissingCodesMessagesAndNullEntries() {
        assertThrows(NullPointerException.class, () -> new ValidationError(null, "Error"));
        assertThrows(NullPointerException.class, () -> new ValidationError("A", null));
        assertThrows(IllegalArgumentException.class, () -> new ValidationError(" ", "Error"));
        assertThrows(IllegalArgumentException.class, () -> new ValidationError("A", " "));
        assertThrows(NullPointerException.class, () -> new ValidationResult(null));
        List<ValidationError> entries = new ArrayList<>();
        entries.add(null);
        assertThrows(NullPointerException.class, () -> new ValidationResult(entries));
    }
}
