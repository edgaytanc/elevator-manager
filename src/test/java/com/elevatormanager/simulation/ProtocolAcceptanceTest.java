package com.elevatormanager.simulation;

import com.elevatormanager.diagnostics.ProtocolAcceptanceDemo;
import com.elevatormanager.diagnostics.ProtocolAcceptanceDemo.Scenario;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Ejecuta los actores y las comprobaciones de la demo; conserva la traza si falla. */
@Timeout(60)
class ProtocolAcceptanceTest {
    @Test void invalid_uuid() throws Exception { verify(Scenario.INVALID_UUID); }
    @Test void invalid_floor() throws Exception { verify(Scenario.INVALID_FLOOR); }
    @Test void invalid_direction() throws Exception { verify(Scenario.INVALID_DIRECTION); }
    @Test void unknown_command() throws Exception { verify(Scenario.UNKNOWN_COMMAND); }
    @Test void unknown_elevator() throws Exception { verify(Scenario.UNKNOWN_ELEVATOR); }
    @Test void wrong_recipient() throws Exception { verify(Scenario.WRONG_RECIPIENT); }
    @Test void extra_argument() throws Exception { verify(Scenario.EXTRA_ARGUMENT); }
    @Test void unsupported_version() throws Exception { verify(Scenario.UNSUPPORTED_VERSION); }
    @Test void oversized_frame() throws Exception { verify(Scenario.OVERSIZED_FRAME); }
    @Test void wrong_role() throws Exception { verify(Scenario.WRONG_ROLE); }
    @Test void active_replay() throws Exception { verify(Scenario.ACTIVE_REPLAY); }
    @Test void completed_replay() throws Exception { verify(Scenario.COMPLETED_REPLAY); }
    @Test void conflicting_replay() throws Exception { verify(Scenario.CONFLICTING_REPLAY); }
    @Test void coalesced_service() throws Exception { verify(Scenario.COALESCED_SERVICE); }
    @Test void reset_in_flight() throws Exception { verify(Scenario.RESET_IN_FLIGHT); }
    @Test void queued_before_reset() throws Exception { verify(Scenario.QUEUED_BEFORE_RESET); }
    @Test void late_old_frame() throws Exception { verify(Scenario.LATE_OLD_FRAME); }
    @Test void future_frame() throws Exception { verify(Scenario.FUTURE_FRAME); }
    @Test void status_replay() throws Exception { verify(Scenario.STATUS_REPLAY); }
    @Test void parallel_cabins() throws Exception { verify(Scenario.PARALLEL_CABINS); }
    @Test void manager_round_trip() throws Exception { verify(Scenario.MANAGER_ROUND_TRIP); }
    @Test void controller_extras() throws Exception { verify(Scenario.CONTROLLER_EXTRAS); }

    private static void verify(Scenario scenario) throws Exception {
        var bytes = new ByteArrayOutputStream();
        try (var out = new PrintStream(bytes, true, StandardCharsets.UTF_8)) {
            try { ProtocolAcceptanceDemo.run(scenario, out); }
            catch (Exception | AssertionError failure) {
                throw new AssertionError(bytes.toString(StandardCharsets.UTF_8), failure);
            }
        }
        assertTrue(bytes.toString(StandardCharsets.UTF_8).contains("T66_CASE_OK " + scenario));
    }
}
