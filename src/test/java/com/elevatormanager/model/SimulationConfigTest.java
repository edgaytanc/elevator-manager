package com.elevatormanager.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SimulationConfigTest {

    @Test
    void acceptsMinimumConfiguration() {
        assertEquals(new SimulationConfig(1, 1, 1, 1),
                SimulationConfig.fromText("1", "1", "1", "1"));
    }

    @Test
    void preservesValuesAndTrimsInput() {
        SimulationConfig config = SimulationConfig.fromText(" 2 ", "10", "1500", "2000");
        assertAll(
                () -> assertEquals(2, config.elevatorCount()),
                () -> assertEquals(10, config.floorCount()),
                () -> assertEquals(1500L, config.movementTimeMillis()),
                () -> assertEquals(2000L, config.dwellTimeMillis()));
    }

    @Test
    void rejectsNonPositiveValuesEvenWithDirectConstruction() {
        for (int value : new int[]{0, -1, Integer.MIN_VALUE}) {
            assertThrows(IllegalArgumentException.class,
                    () -> new SimulationConfig(value, 1, 1, 1));
            assertThrows(IllegalArgumentException.class,
                    () -> new SimulationConfig(1, value, 1, 1));
        }
        for (long value : new long[]{0, -1, Long.MIN_VALUE}) {
            assertThrows(IllegalArgumentException.class,
                    () -> new SimulationConfig(1, 1, value, 1));
            assertThrows(IllegalArgumentException.class,
                    () -> new SimulationConfig(1, 1, 1, value));
        }
    }

    @Test
    void rejectsMalformedOrMissingTextInEveryField() {
        for (String invalid : new String[]{null, "", " ", "abc", "1.5", "1,000",
                "1e3", "9223372036854775808", "-9223372036854775809", "0", "-1"}) {
            for (int field = 0; field < 4; field++) {
                String[] input = {"1", "1", "1", "1"};
                input[field] = invalid;
                assertThrows(IllegalArgumentException.class, () -> SimulationConfig.fromText(
                        input[0], input[1], input[2], input[3]));
            }
        }
    }

    @Test
    void acceptsNumericBoundariesWithoutAllocatingCabinsOrFloors() {
        long max = SimulationConfig.MAX_TIME_MILLIS;
        SimulationConfig expected = new SimulationConfig(
                Integer.MAX_VALUE, Integer.MAX_VALUE, max, max);
        assertEquals(expected, SimulationConfig.fromText(
                "2147483647", "2147483647", Long.toString(max), Long.toString(max)));
        assertDoesNotThrow(() -> Math.multiplyExact(expected.movementTimeMillis(), 1_000_000L));
    }

    @Test
    void rejectsCountOverflowAndUnsafeNanosecondConversion() {
        assertThrows(IllegalArgumentException.class,
                () -> SimulationConfig.fromText("2147483648", "1", "1", "1"));
        assertThrows(IllegalArgumentException.class,
                () -> SimulationConfig.fromText("1", "2147483648", "1", "1"));
        long excessive = SimulationConfig.MAX_TIME_MILLIS + 1;
        assertThrows(IllegalArgumentException.class,
                () -> new SimulationConfig(1, 1, excessive, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new SimulationConfig(1, 1, 1, excessive));
        assertThrows(IllegalArgumentException.class,
                () -> SimulationConfig.fromText("1", "1", Long.toString(excessive), "1"));
        assertThrows(IllegalArgumentException.class,
                () -> SimulationConfig.fromText("1", "1", "1", Long.toString(excessive)));
    }
}
