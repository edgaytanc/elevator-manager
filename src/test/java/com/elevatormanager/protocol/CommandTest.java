package com.elevatormanager.protocol;

import com.elevatormanager.model.*;
import com.elevatormanager.model.request.*;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CommandTest {
    private static final Instant NOW = Instant.parse("2026-10-21T06:00:00Z");
    private static final SimulationConfig CONFIG = new SimulationConfig(2, 5, 100, 200);

    @Test
    void commandsPreserveRoutingCorrelationAndRequestIdentity() {
        HallRequest hall = new HallRequest(UUID.randomUUID(), 7, 3, NOW, Direction.UP);
        CabinRequest cabin = new CabinRequest(UUID.randomUUID(), 7, 5, NOW, 2);
        List<Command> commands = List.of(new Command.Pickup(41, 2, hall),
                new Command.Destination(42, 2, cabin), new Command.Reset(43, 8, 2));
        assertSame(hall, ((Command.Pickup) commands.get(0)).request());
        assertSame(cabin, ((Command.Destination) commands.get(1)).request());
        assertEquals(List.of(41L, 42L, 43L), commands.stream().map(Command::commandId).toList());
        assertEquals(List.of(7L, 7L, 8L), commands.stream().map(Command::epoch).toList());
        for (Command command : commands) {
            assertEquals(2, command.elevatorId());
            assertTrue(command.validateAgainst(CONFIG).isValid());
        }
    }

    @Test
    void constructorsRejectInvalidHeadersMissingPayloadAndWrongDestination() {
        HallRequest hall = new HallRequest(UUID.randomUUID(), 0, 3, NOW, Direction.UP);
        CabinRequest cabin = new CabinRequest(UUID.randomUUID(), 0, 4, NOW, 2);
        assertThrows(IllegalArgumentException.class, () -> new Command.Pickup(0, 1, hall));
        assertThrows(IllegalArgumentException.class, () -> new Command.Destination(-1, 2, cabin));
        assertThrows(IllegalArgumentException.class, () -> new Command.Reset(0, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new Command.Pickup(1, 0, hall));
        assertThrows(IllegalArgumentException.class, () -> new Command.Reset(1, -1, 1));
        assertThrows(IllegalArgumentException.class, () -> new Command.Reset(1, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new Command.Destination(1, 1, cabin));
        assertThrows(NullPointerException.class, () -> new Command.Pickup(1, 1, null));
        assertThrows(NullPointerException.class, () -> new Command.Destination(1, 1, null));
    }

    @Test
    void contextualValidationCombinesTargetAndRequestErrors() {
        HallRequest invalid = new HallRequest(UUID.randomUUID(), 0, 6, NOW, Direction.UP);
        Command pickup = new Command.Pickup(1, 3, invalid);
        assertEquals(List.of("UNKNOWN_ELEVATOR", "FLOOR_OUT_OF_RANGE"),
                pickup.validateAgainst(CONFIG).errors().stream().map(ValidationError::code).toList());
        Command destination = new Command.Destination(2, 3,
                new CabinRequest(UUID.randomUUID(), 0, 4, NOW, 3));
        assertFalse(destination.validateAgainst(CONFIG).isValid());
        assertFalse(new Command.Reset(3, 1, 3).validateAgainst(CONFIG).isValid());
        assertThrows(NullPointerException.class, () -> pickup.validateAgainst(null));
    }

    @Test
    void contentComparisonDetectsConflictsDespiteUuidBasedRequestEquality() {
        UUID id = UUID.randomUUID();
        Command original = new Command.Pickup(10, 1,
                new HallRequest(id, 2, 3, NOW, Direction.UP));
        Command retry = new Command.Pickup(10, 1,
                new HallRequest(id, 2, 3, NOW, Direction.UP));
        Command changed = new Command.Pickup(10, 1,
                new HallRequest(id, 2, 4, NOW, Direction.UP));
        assertTrue(original.sameContent(retry));
        assertFalse(original.sameContent(changed));
        assertFalse(original.sameContent(new Command.Reset(10, 2, 1)));
        assertFalse(original.sameContent(null));
        Command destination = new Command.Destination(11, 1,
                new CabinRequest(id, 2, 3, NOW, 1));
        assertFalse(destination.sameContent(new Command.Destination(11, 1,
                new CabinRequest(id, 2, 4, NOW, 1))));
        assertTrue(new Command.Reset(12, 3, 1).sameContent(new Command.Reset(12, 3, 1)));
        assertFalse(new Command.Reset(12, 3, 1).sameContent(new Command.Reset(12, 4, 1)));
    }
}
