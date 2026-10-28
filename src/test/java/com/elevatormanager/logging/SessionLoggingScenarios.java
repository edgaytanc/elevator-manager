package com.elevatormanager.logging;

import com.elevatormanager.dispatch.HallCallSnapshot;
import com.elevatormanager.dispatch.ManagerWorker;
import com.elevatormanager.model.Direction;
import com.elevatormanager.model.ElevatorState;
import com.elevatormanager.model.SimulationConfig;
import com.elevatormanager.model.SimulationState;
import com.elevatormanager.model.request.CabinRequest;
import com.elevatormanager.model.request.HallRequest;
import com.elevatormanager.simulation.ElevatorWorker;
import com.elevatormanager.simulation.ExecutionEvent;
import com.elevatormanager.simulation.SimulationController;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import java.util.logging.ErrorManager;
import java.util.logging.FileHandler;
import java.util.logging.Level;
import java.util.logging.SimpleFormatter;
import java.util.logging.StreamHandler;
import javax.swing.SwingUtilities;
import static com.elevatormanager.simulation.ExecutionEvent.Type.*;

/** T42: eventos reales, drenaje, errores de handlers y regresión de canales independientes. */
public final class SessionLoggingScenarios {
    private SessionLoggingScenarios() { }

    public static void obligatoryEventsComeFromPhysicalActors() throws Exception {
        inDirectory(dir -> {
            try (var rig = new Rig(dir, 1, 5)) {
                get(rig.controller.start());
                get(rig.controller.submit(cabin(0, 4, 1))); rig.until(STOP_ENDED, 4);
                get(rig.controller.submit(cabin(0, 2, 1))); rig.until(STOP_ENDED, 2);
                get(rig.controller.reset()); get(rig.log.flushAsync());
            }
            String text = read(dir, 1);
            for (String action : new String[] { "SUBIENDO", "BAJANDO", "LLEGADA", "PARADA_RECOGER_DEJAR",
                    "CAMBIO_DIRECCION", "RESET_INICIADO", "RESET_COMPLETADO" }) {
                check(text.contains("accion=" + action + ";"), "Obligatorio: " + action);
            }
            check(text.contains("piso=1; siguiente=2; direccion=UP"), "Partida conserva piso lógico");
            check(text.contains("modo=RESET_RETURN"), "Retorno físico identificado");
            check(readManager(dir).contains("evento=MANAGER_RESET"), "Reset de manejador");
        });
    }

    public static void realCabinsRemainIsolatedAndConsoleHasOneCopy() throws Exception {
        inDirectory(dir -> {
            ByteArrayOutputStream console;
            try (var rig = new Rig(dir, 3, 5)) {
                console = rig.console; get(rig.controller.start());
                for (int id = 1; id <= 3; id++) { get(rig.controller.submit(cabin(0, id + 1, id))); }
                var served = new HashSet<Integer>();
                long deadline = deadline();
                while (served.size() < 3) {
                    var event = rig.next(deadline);
                    if (event.type() == COMMAND_COMPLETED) { served.add(event.elevatorId()); }
                }
            }
            String terminal = console.toString(StandardCharsets.UTF_8);
            for (int id = 1; id <= 3; id++) {
                for (String line : read(dir, id).lines().toList()) {
                    check(line.contains("[elevador=" + id + "]"), "Solo su cabina");
                    check(terminal.lines().filter(line::equals).count() == 1, "Una copia en consola");
                }
            }
            for (String line : readManager(dir).lines().toList()) {
                check(line.contains("[manejador]"), "Archivo exclusivo del manejador");
                check(terminal.lines().filter(line::equals).count() == 1, "Una copia de manejador");
            }
        });
    }

    public static void dispatchAdmissionAndServiceAreDifferentFacts() throws Exception {
        inDirectory(dir -> {
            var request = hall(0, 3, Direction.DOWN);
            try (var rig = new Rig(dir, 1, 10)) {
                get(rig.controller.start()); get(rig.controller.submit(request));
                rig.until(COMMAND_COMPLETED, 3);
                await(() -> rig.controller.calls().get(request.getId()).status() == HallCallSnapshot.Status.SERVED);
            }
            String manager = readManager(dir);
            check(manager.contains("resultado=DISPATCHED") && manager.contains("menor ETA="), "Decisión y motivo");
            check(manager.contains("estado=DISPATCHED") && manager.contains("estado=ASSIGNED")
                    && manager.contains("estado=SERVED"), "Tres hechos diferentes");
            check(manager.contains("solicitud=" + request.getId()), "UUID externo");
            check(read(dir, 1).contains("canonica=" + request.getId()), "Correlación cabina/manejador");
        });
    }

    public static void duplicateHallRequestKeepsCanonicalIdentity() throws Exception {
        inDirectory(dir -> {
            var original = hall(0, 4, Direction.UP); var duplicate = hall(0, 4, Direction.UP);
            try (var rig = new Rig(dir, 1, 100)) {
                get(rig.controller.start()); get(rig.controller.submit(original)); rig.until(DEPARTED, 1);
                get(rig.controller.submit(duplicate)); rig.until(COMMAND_COMPLETED, 4);
                await(() -> rig.controller.calls().get(duplicate.getId()) != null
                        && rig.controller.calls().get(duplicate.getId()).status() == HallCallSnapshot.Status.SERVED);
            }
            String manager = readManager(dir);
            check(manager.contains("resultado=DUPLICATE"), "Decisión duplicada explícita");
            check(manager.contains("solicitud=" + duplicate.getId() + "; canonica=" + original.getId()), "Alias preservado");
            check(read(dir, 1).lines().filter(line -> line.contains("evento=COMMAND_COMPLETED;")).count() == 1, "Un servicio canónico");
        });
    }

    public static void resetKeepsOldHistoryWithoutCompletingCancelledWork() throws Exception {
        inDirectory(dir -> {
            var request = cabin(0, 5, 1);
            try (var rig = new Rig(dir, 1, 100)) {
                get(rig.controller.start()); get(rig.controller.submit(request)); rig.until(DEPARTED, 1);
                get(rig.controller.reset());
            }
            String text = read(dir, 1);
            check(text.contains("epoca=0;") && text.contains("epoca=1;"), "No filtra historia por época vigente");
            check(text.contains("evento=COMMAND_CANCELLED;"), "Cancelación registrada");
            check(text.lines().noneMatch(line -> line.contains("evento=COMMAND_COMPLETED;")
                    && line.contains("canonica=" + request.getId())), "Cancelado no es servido");
        });
    }

    public static void sequencesRemainUniqueAcrossResets() throws Exception {
        inDirectory(dir -> {
            try (var rig = new Rig(dir, 1, 5)) {
                get(rig.controller.start());
                for (int epoch = 0; epoch < 3; epoch++) {
                    get(rig.controller.submit(cabin(epoch, 2, 1))); rig.until(STOP_ENDED, 2);
                    get(rig.controller.reset()); get(rig.controller.start());
                }
            }
            long previous = 0;
            for (String line : read(dir, 1).lines().toList()) {
                long sequence = Long.parseLong(line.split("secuencia=")[1].split(";")[0]);
                check(sequence == previous + 1, "Secuencia continua, sin duplicados ni pérdidas"); previous = sequence;
            }
            check(previous > 20 && read(dir, 1).contains("epoca=3;"), "Tres resets registrados");
        });
    }

    public static void closingInFlightRecordsTerminationWithoutInventingArrival() throws Exception {
        inDirectory(dir -> {
            try (var rig = new Rig(dir, 1, 60_000)) {
                get(rig.controller.start()); get(rig.controller.submit(cabin(0, 5, 1))); rig.until(DEPARTED, 1);
            }
            String text = read(dir, 1);
            check(text.contains("evento=WORKER_TERMINATED;"), "Drena último evento del actor");
            check(!text.contains("evento=ARRIVED;"), "No inventa llegada al cancelar viaje largo");
            check(readManager(dir).contains("evento=MANAGER_TERMINATED;"), "Drena final del manejador");
        });
    }

    public static void closingDrainsEveryQueuedEvent() throws Exception {
        inDirectory(dir -> {
            var log = SessionLog.open(dir, 1, new ByteArrayOutputStream());
            for (int i = 1; i <= 400; i++) { log.mailbox().cabin(sample(i, 1, 1, ARRIVED), null); }
            log.close();
            check(read(dir, 1).lines().count() == 400, "Drenaje FIFO completo");
            check(!log.isAlive(), "Consumidor terminado");
        });
    }

    public static void appendDistinguishesSessions() throws Exception {
        inDirectory(dir -> {
            String first;
            try (var rig = new Rig(dir, 1, 5)) { first = rig.log.sessionId(); get(rig.controller.start()); }
            try (var rig = new Rig(dir, 1, 5)) { check(!first.equals(rig.log.sessionId()), "Sesión distinta"); get(rig.controller.start()); }
            check(read(dir, 1).lines().map(line -> line.split("sesion=")[1].split(";")[0]).distinct().count() == 2,
                    "Historial append distingue secuencias reiniciadas");
        });
    }

    public static void barrierMakesPriorWritesVisible() throws Exception {
        inDirectory(dir -> {
            try (var log = SessionLog.open(dir, 1, new ByteArrayOutputStream())) {
                log.mailbox().cabin(sample(1, 1, 1, ARRIVED), null); get(log.flushAsync());
                check(read(dir, 1).contains("evento=ARRIVED;"), "Barrera después de publicación");
                check(log.isAlive(), "Consumidor permanece disponible");
            }
        });
    }

    public static void consumerFailureIsVisibleAndCleanupStillRuns() throws Exception {
        inDirectory(dir -> {
            var log = SessionLog.open(dir, 1, new ByteArrayOutputStream());
            log.mailbox().cabin(sample(1, 2, 2, ARRIVED), null);
            expect(ExecutionException.class, () -> get(log.flushAsync()));
            check(log.failure().isPresent(), "Error de enrutamiento visible");
            expect(IllegalStateException.class, log::close);
            check(!log.isAlive(), "Consumidor terminó tras fallo");
            try (var reopened = SessionLog.open(dir, 1, new ByteArrayOutputStream())) { get(reopened.flushAsync()); }
        });
    }

    public static void consoleWriteFailureIsReportedOutOfBand() throws Exception {
        inDirectory(dir -> {
            var emergency = new ByteArrayOutputStream(); PrintStream previous = System.err;
            var log = SessionLog.open(dir, 1, brokenWrite());
            try (var capture = new PrintStream(emergency, true, StandardCharsets.UTF_8)) {
                System.setErr(capture);
                log.mailbox().cabin(sample(1, 1, 1, ARRIVED), null);
                expect(ExecutionException.class, () -> get(log.flushAsync()));
                check(log.ioFailure().orElseThrow().destination().equals("consola"), "Destino fallido identificado");
                check(emergency.toString(StandardCharsets.UTF_8).contains("ERROR_LOGGING"), "Aviso de emergencia independiente");
                check(read(dir, 1).contains("evento=ARRIVED;"), "Archivo sano sigue escribiendo");
                expect(IllegalStateException.class, log::close);
            } finally { System.setErr(previous); }
            try (var swallowed = new PrintStream(brokenWrite(), true, StandardCharsets.UTF_8)) {
                var second = SessionLog.open(dir, 1, swallowed);
                second.mailbox().cabin(sample(1, 1, 1, ARRIVED), null);
                expect(ExecutionException.class, () -> get(second.flushAsync()));
                check(second.ioFailure().orElseThrow().cause().getMessage().contains("PrintStream"),
                        "Detecta IOException ocultada por PrintStream");
                expect(IllegalStateException.class, second::close);
            }
        });
    }

    public static void fileWriteFailurePreservesCauseAndHealthyConsole() throws Exception {
        inDirectory(dir -> {
            var console = new ByteArrayOutputStream();
            var factory = LoggerFactory.open(dir, 1, console, path -> path.getFileName().toString().equals("elevador1.log")
                    ? flushing(brokenWrite()) : new FileHandler(path.toString(), true));
            factory.loggerFor(1).info("ARCHIVO_FALLIDO");
            var incident = factory.failure().orElseThrow();
            check(incident.actor().equals("elevador=1") && incident.destination().endsWith("elevador1.log"), "Actor y archivo");
            check(incident.cause().getMessage().contains("escritura inyectada"), "Causa original");
            check(console.toString(StandardCharsets.UTF_8).contains("ARCHIVO_FALLIDO"), "Consola sana");
            expect(IllegalStateException.class, factory::close);
            try (var reopened = LoggerFactory.open(dir, 1, console)) { reopened.loggerFor(1).info("LIBRE"); }
        });
    }

    public static void repeatedHandlerFailuresAreCountedAndFirstIsRetained() throws Exception {
        inDirectory(dir -> {
            var factory = LoggerFactory.open(dir, 1, brokenWrite());
            factory.loggerFor(1).info("PRIMERO"); var first = factory.failure().orElseThrow();
            factory.loggerFor(1).info("SEGUNDO");
            check(factory.errorCount() >= 2, "No silencia reportes posteriores");
            check(factory.failure().orElseThrow() == first, "Primera causa conservada por identidad");
            expect(IllegalStateException.class, factory::close);
            expect(IllegalStateException.class, factory::close);
        });
    }

    public static void flushFailureIsDetected() throws Exception {
        inDirectory(dir -> {
            var fail = new java.util.concurrent.atomic.AtomicBoolean(true);
            var output = new OutputStream() {
                @Override public void write(int value) { }
                @Override public void flush() throws IOException {
                    if (fail.get()) { throw new IOException("flush inyectado"); }
                }
            };
            expect(IOException.class, () -> LoggerFactory.open(dir, 1, output));
            fail.set(false);
            var factory = LoggerFactory.open(dir, 1, output);
            fail.set(true); factory.loggerFor(1).info("VACIA");
            check(factory.failure().orElseThrow().code() == ErrorManager.FLUSH_FAILURE, "Código de flush");
            expect(IllegalStateException.class, factory::close);
        });
    }

    public static void closeFailureDoesNotKeepFileLock() throws Exception {
        inDirectory(dir -> {
            var factory = LoggerFactory.open(dir, 1, new ByteArrayOutputStream(), path -> new StreamHandler(
                    new ByteArrayOutputStream() { @Override public void close() throws IOException { throw new IOException("cierre inyectado"); } },
                    new SimpleFormatter()));
            factory.loggerFor(1).info("ANTES_CIERRE"); expect(IllegalStateException.class, factory::close);
            check(factory.failure().orElseThrow().code() == ErrorManager.CLOSE_FAILURE, "Código de close");
            try (var reopened = LoggerFactory.open(dir, 1, new ByteArrayOutputStream())) { reopened.loggerFor(1).info("REABIERTO"); }
        });
    }

    public static void managerSetupFailureRollsBackCabinResources() throws Exception {
        inDirectory(dir -> {
            expect(IOException.class, () -> LoggerFactory.open(dir, 2, new ByteArrayOutputStream(), path -> {
                if (path.getFileName().toString().equals("manejador.log")) { throw new IOException("No abrir manejador"); }
                return new FileHandler(path.toString(), true);
            }));
            try (var logs = LoggerFactory.open(dir, 2, new ByteArrayOutputStream())) { logs.managerLogger().info("RECUPERADO"); }
            check(readManager(dir).contains("RECUPERADO"), "Rollback completo");
        });
    }

    public static void unusedControllerClosesConsumerAndFiles() throws Exception {
        inDirectory(dir -> {
            SessionLog log;
            try (var rig = new Rig(dir, 2, 5)) { log = rig.log; }
            check(!log.isAlive(), "Cierre sin Start también termina consumidor");
            try (var reopened = SessionLog.open(dir, 2, new ByteArrayOutputStream())) { get(reopened.flushAsync()); }
        });
    }

    public static void closedSessionRejectsLatePublications() throws Exception {
        inDirectory(dir -> {
            var log = SessionLog.open(dir, 1, new ByteArrayOutputStream()); log.close(); log.close();
            expect(IllegalStateException.class, () -> log.mailbox().cabin(sample(1, 1, 1, ARRIVED), null));
            expect(IllegalStateException.class, log::flushAsync);
        });
    }

    public static void sessionOwnershipCannotBeSharedOrMismatched() throws Exception {
        inDirectory(dir -> {
            var log = SessionLog.open(dir, 1, new ByteArrayOutputStream());
            expect(IllegalArgumentException.class, () -> new SimulationController(new SimulationConfig(2, 6, 5, 5), 0, log));
            var controller = new SimulationController(new SimulationConfig(1, 6, 5, 5), 0, log);
            try {
                expect(IllegalStateException.class, () -> new SimulationController(new SimulationConfig(1, 6, 5, 5), 0, log));
            } finally { get(controller.closeAsync()); check(controller.awaitTermination(Duration.ofSeconds(10)), "Cerrador terminó"); }
            check(!log.isAlive(), "Propietario único liberado");
        });
    }

    public static void openingAndClosingOnEdtAreRejected() throws Exception {
        inDirectory(dir -> {
            var seen = new AtomicReference<Throwable>();
            SwingUtilities.invokeAndWait(() -> {
                try { SessionLog.open(dir, 1); } catch (Throwable error) { seen.set(error); }
            });
            check(seen.get() instanceof IllegalStateException, "Apertura fuera del EDT");
            try (var log = SessionLog.open(dir, 1, new ByteArrayOutputStream())) {
                seen.set(null);
                SwingUtilities.invokeAndWait(() -> { try { log.close(); } catch (Throwable error) { seen.set(error); } });
                check(seen.get() instanceof IllegalStateException && log.isAlive(), "Cierre rechazado no sella canal");
            }
        });
    }

    public static void actorFailureKeepsExceptionTrace() throws Exception {
        inDirectory(dir -> {
            try (var log = SessionLog.open(dir, 1, new ByteArrayOutputStream())) {
                log.mailbox().cabin(sample(1, 1, 1, WORKER_FAILED), new IllegalStateException("causa del actor"));
            }
            String text = read(dir, 1);
            check(text.contains("[SEVERE]") && text.contains("evento=WORKER_FAILED;"), "Nivel de fallo");
            check(text.contains("java.lang.IllegalStateException: causa del actor"), "Traza original preservada");
        });
    }

    public static void realManagerFailureIsLogged() throws Exception {
        inDirectory(dir -> {
            var config = new SimulationConfig(1, 6, 5, 5);
            try (var log = SessionLog.open(dir, 1, new ByteArrayOutputStream())) {
                var elevator = new ElevatorWorker(1, config, 0, log.mailbox());
                var manager = new ManagerWorker(config, 0, java.util.List.of(elevator), () -> 0, log.mailbox());
                try {
                    check(elevator.start() && manager.start(), "Actores reales"); manager.submit(hall(0, 3, Direction.UP));
                    check(manager.awaitTermination(Duration.ofSeconds(10)), "Manejador falla sin bloquear");
                    check(manager.failure().isPresent(), "Fallo provocado por generador inválido");
                } finally {
                    manager.close(); elevator.close();
                    check(manager.awaitTermination(Duration.ofSeconds(10)), "Manejador terminado");
                    check(elevator.awaitTermination(Duration.ofSeconds(10)), "Cabina terminada");
                }
            }
            check(readManager(dir).contains("evento=MANAGER_FAILED;")
                    && readManager(dir).contains("Generador de comandos agotado o inválido"), "Fallo y causa en historial");
        });
    }

    public static void degradedLoggingPreventsSuccessfulControllerClose() throws Exception {
        inDirectory(dir -> {
            var log = SessionLog.open(dir, 1, brokenWrite());
            var controller = new SimulationController(new SimulationConfig(1, 6, 5, 5), 0, log);
            get(controller.start()); get(controller.submit(cabin(0, 2, 1)));
            long deadline = deadline();
            while (controller.failure().isEmpty() && System.nanoTime() < deadline) { LockSupport.parkNanos(1_000_000); }
            check(controller.failure().isPresent(), "Incidencia visible durante ejecución");
            expect(ExecutionException.class, () -> get(controller.closeAsync()));
            check(controller.state() != SimulationState.CLOSED && !log.isAlive(), "No confirma cierre exitoso; consumidor liberado");
            check(controller.failure().isPresent(), "Causa conservada después del cierre");
            try (var reopened = SessionLog.open(dir, 1, new ByteArrayOutputStream())) { get(reopened.flushAsync()); }
        });
    }

    private static final class Rig implements AutoCloseable {
        final ByteArrayOutputStream console = new ByteArrayOutputStream();
        final SessionLog log;
        final SimulationController controller;
        Rig(Path dir, int count, long movement) throws IOException {
            log = SessionLog.open(dir, count, console);
            controller = new SimulationController(new SimulationConfig(count, 6, movement, 5), 0, log);
        }
        ExecutionEvent next(long deadline) throws Exception {
            long left = deadline - System.nanoTime(); check(left > 0, "Plazo de eventos agotado");
            return controller.awaitEvent(Duration.ofNanos(left)).orElseThrow(() -> new AssertionError("Falta evento"));
        }
        void until(ExecutionEvent.Type type, int floor) throws Exception {
            long deadline = deadline();
            while (true) { var event = next(deadline); if (event.type() == type && event.snapshot().currentFloor() == floor) { return; } }
        }
        @Override public void close() throws Exception {
            get(controller.closeAsync()); check(controller.awaitTermination(Duration.ofSeconds(10)), "Cierre completo");
            check(!log.isAlive() && controller.failure().isEmpty(), "Sin hilos ni fallos de logging");
        }
    }
    private static ExecutionEvent sample(long seq, int id, int count, ExecutionEvent.Type type) {
        var snapshot = new ElevatorState(id, new SimulationConfig(count, 6, 5, 5), 0).snapshot(System.nanoTime());
        return new ExecutionEvent(seq, type, snapshot, Optional.empty(), Optional.empty(), "Prueba del consumidor");
    }
    private static CabinRequest cabin(long epoch, int floor, int id) { return new CabinRequest(UUID.randomUUID(), epoch, floor, Instant.now(), id); }
    private static HallRequest hall(long epoch, int floor, Direction direction) { return new HallRequest(UUID.randomUUID(), epoch, floor, Instant.now(), direction); }
    private static OutputStream brokenWrite() {
        return new OutputStream() { @Override public void write(int value) throws IOException { throw new IOException("escritura inyectada"); } };
    }
    private static StreamHandler flushing(OutputStream output) {
        return new StreamHandler(output, new SimpleFormatter()) {
            @Override public synchronized void publish(java.util.logging.LogRecord record) { super.publish(record); flush(); }
        };
    }
    private static long deadline() { return System.nanoTime() + TimeUnit.SECONDS.toNanos(10); }
    private static void await(BooleanSupplier condition) {
        long deadline = deadline();
        while (!condition.getAsBoolean()) { check(System.nanoTime() < deadline, "Condición no alcanzada"); LockSupport.parkNanos(1_000_000); }
    }
    private static <T> T get(CompletableFuture<T> future) throws Exception { return future.get(15, TimeUnit.SECONDS); }
    private static String read(Path dir, int id) throws IOException { return Files.readString(dir.resolve("elevador" + id + ".log"), StandardCharsets.UTF_8); }
    private static String readManager(Path dir) throws IOException { return Files.readString(dir.resolve("manejador.log"), StandardCharsets.UTF_8); }
    private static void check(boolean condition, String message) { if (!condition) { throw new AssertionError(message); } }
    private interface Action { void run() throws Exception; }
    private interface DirectoryAction { void run(Path path) throws Exception; }
    private static void expect(Class<? extends Throwable> type, Action action) throws Exception {
        try { action.run(); }
        catch (Throwable error) { if (type.isInstance(error)) { return; } throw new AssertionError("Excepción inesperada", error); }
        throw new AssertionError("Falta excepción " + type.getSimpleName());
    }
    private static void inDirectory(DirectoryAction action) throws Exception {
        Path dir = Files.createTempDirectory("t42-");
        try { action.run(dir); }
        finally { try (var files = Files.walk(dir)) { for (Path p : files.sorted(Comparator.reverseOrder()).toList()) { Files.delete(p); } } }
    }
    public static void main(String[] args) throws Exception {
        obligatoryEventsComeFromPhysicalActors(); realCabinsRemainIsolatedAndConsoleHasOneCopy();
        dispatchAdmissionAndServiceAreDifferentFacts(); duplicateHallRequestKeepsCanonicalIdentity();
        resetKeepsOldHistoryWithoutCompletingCancelledWork(); sequencesRemainUniqueAcrossResets();
        closingInFlightRecordsTerminationWithoutInventingArrival(); closingDrainsEveryQueuedEvent();
        appendDistinguishesSessions(); barrierMakesPriorWritesVisible(); consumerFailureIsVisibleAndCleanupStillRuns();
        consoleWriteFailureIsReportedOutOfBand(); fileWriteFailurePreservesCauseAndHealthyConsole();
        repeatedHandlerFailuresAreCountedAndFirstIsRetained(); flushFailureIsDetected(); closeFailureDoesNotKeepFileLock();
        managerSetupFailureRollsBackCabinResources(); unusedControllerClosesConsumerAndFiles(); closedSessionRejectsLatePublications();
        sessionOwnershipCannotBeSharedOrMismatched(); openingAndClosingOnEdtAreRejected(); actorFailureKeepsExceptionTrace();
        realManagerFailureIsLogged(); degradedLoggingPreventsSuccessfulControllerClose();
        System.out.println("T42: 24 escenarios satisfactorios.");
    }
}
