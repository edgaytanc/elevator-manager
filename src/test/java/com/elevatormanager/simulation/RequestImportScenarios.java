package com.elevatormanager.simulation;

import com.elevatormanager.io.BatchDiagnostic;
import com.elevatormanager.io.BatchReadResult;
import com.elevatormanager.io.RequestFileReader;
import com.elevatormanager.model.SimulationConfig;
import com.elevatormanager.model.SimulationState;
import com.elevatormanager.model.request.CabinRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import javax.swing.SwingUtilities;
import static com.elevatormanager.simulation.ExecutionEvent.Type.*;
import static com.elevatormanager.simulation.BatchImportResult.Outcome.*;

/** T45: confirmación serializada, carreras con lectores retenidos y actores reales. */
public final class RequestImportScenarios {
    private static final SimulationConfig CONFIG = new SimulationConfig(2,8,5,5);
    private static final RequestFileReader READER = new RequestFileReader();
    private RequestImportScenarios() { }

    public static void initialImportDoesNotStartActors() throws Exception {
        inDirectory(dir -> {
            try (var rig = new Rig(CONFIG)) {
                var before = rig.c.snapshots();
                var result = get(rig.c.importRequests(file(dir,"a","CABIN 1 4\nHALL 3 UP")));
                check(result.outcome()==IMPORTED && rig.c.state()==SimulationState.RESET,"Confirmado en RESET");
                check(rig.c.snapshots().equals(before) && rig.c.elevatorLifecycles().stream().allMatch(x -> x==ElevatorWorker.Lifecycle.NEW),"Sin movimiento ni arranque");
                check(rig.c.managerLifecycle()==com.elevatormanager.dispatch.ManagerWorker.Lifecycle.NEW,"Manejador sin iniciar");
            }
        });
    }
    public static void importAfterResetKeepsCabinsIdle() throws Exception {
        inDirectory(dir -> {
            try (var rig = new Rig(CONFIG)) {
                get(rig.c.start()); get(rig.c.submit(cabin(0,3,1))); rig.until(COMMAND_COMPLETED,3);
                get(rig.c.reset()); var before=rig.c.snapshots();
                check(get(rig.c.importRequests(file(dir,"a","CABIN 1 7"))).outcome()==IMPORTED,"Importación tras reset");
                check(rig.c.snapshots().equals(before) && rig.c.state()==SimulationState.RESET,"Confirmar no reanuda actores retenidos");
            }
        });
    }
    public static void startingServicesMixedImportedBatch() throws Exception {
        inDirectory(dir -> {
            try (var rig=new Rig(CONFIG)) {
                var batch=get(rig.c.importRequests(file(dir,"a","CABIN 1 4\nHALL 3 UP\nCABIN 2 6"))).batch().orElseThrow();
                get(rig.c.start()); rig.served(batch);
                check(rig.c.preparedBatch().isEmpty() && rig.c.lastBatchDispatch().orElseThrow().entries().size()==3,"Lote consumido e informado");
            }
        });
    }
    public static void repeatedStartNeverReleasesBatchAgain() throws Exception {
        inDirectory(dir -> {
            try(var rig=new Rig(CONFIG)) {
                var batch=get(rig.c.importRequests(file(dir,"a","CABIN 1 4"))).batch().orElseThrow(); get(rig.c.start());
                var report=rig.c.lastBatchDispatch().orElseThrow();
                for(int i=0;i<20;i++) { check(get(rig.c.start()).outcome()==SimulationController.Outcome.UNCHANGED,"Start repetido"); }
                check(rig.c.lastBatchDispatch().orElseThrow()==report,"No regenera informe ni solicitudes"); rig.served(batch);
            }
        });
    }
    public static void stopStartContinuesOriginalBatchRequests() throws Exception {
        inDirectory(dir -> {
            try(var rig=new Rig(new SimulationConfig(2,8,100,5))) {
                var batch=get(rig.c.importRequests(file(dir,"a","CABIN 1 5"))).batch().orElseThrow();get(rig.c.start());rig.until(DEPARTED,1);
                get(rig.c.stop());var report=rig.c.lastBatchDispatch().orElseThrow();get(rig.c.start());rig.served(batch);
                check(rig.c.lastBatchDispatch().orElseThrow()==report,"Continúa pendientes sin reimportar");
            }
        });
    }
    public static void validReplacementIsAtomicAndUsesFreshIdentities() throws Exception {
        inDirectory(dir -> {
            try(var rig=new Rig(CONFIG)) {
                var a=get(rig.c.importRequests(file(dir,"a","CABIN 1 2"))).batch().orElseThrow();
                var b=get(rig.c.importRequests(file(dir,"b","CABIN 2 6\nHALL 3 UP"))).batch().orElseThrow();
                check(rig.c.preparedBatch().orElseThrow()==b && !a.id().equals(b.id()),"Reemplazo completo");
                check(b.requests().stream().noneMatch(r -> r.getId().equals(a.requests().get(0).getId())),"Identidades nuevas");
            }
        });
    }
    public static void invalidFinalLinePreservesPreviousBatch() throws Exception {
        inDirectory(dir -> {
            try(var rig=new Rig(CONFIG)) {
                var old=get(rig.c.importRequests(file(dir,"a","CABIN 1 2"))).batch().orElseThrow();
                var result=get(rig.c.importRequests(file(dir,"b","# final\nHALL 3 UP\nCABIN 1 8\nHALL 8 UP")));
                check(result.outcome()==INVALID && result.diagnostics().get(0).lineNumber().orElseThrow()==4,"Error final");
                check(rig.c.preparedBatch().orElseThrow()==old,"No sustituye parcial");
            }
        });
    }
    public static void missingFilePreservesPreviousBatch() throws Exception {
        inDirectory(dir -> {
            try(var rig=new Rig(CONFIG)) {
                var old=get(rig.c.importRequests(file(dir,"a","CABIN 1 2"))).batch().orElseThrow();
                var bad=get(rig.c.importRequests(dir.resolve("ausente")));
                check(bad.outcome()==INVALID && bad.diagnostics().get(0).code()==BatchDiagnostic.Code.READ_ERROR,"Error de lectura");
                check(rig.c.preparedBatch().orElseThrow()==old,"Conserva anterior");
            }
        });
    }
    public static void emptyFilePreservesPreviousBatch() throws Exception {
        inDirectory(dir -> {
            try(var rig=new Rig(CONFIG)) {
                var old=get(rig.c.importRequests(file(dir,"a","CABIN 1 2"))).batch().orElseThrow();
                var bad=get(rig.c.importRequests(file(dir,"b","# vacío\n\n")));
                check(bad.outcome()==INVALID && bad.diagnostics().get(0).code()==BatchDiagnostic.Code.EMPTY_BATCH,"Vacío no es vaciado");
                check(rig.c.preparedBatch().orElseThrow()==old,"Mantiene preparado");
            }
        });
    }
    public static void duplicateEntriesRemainDistinctAtConfirmation() throws Exception {
        inDirectory(dir -> {
            try(var rig=new Rig(CONFIG)) {
                var batch=get(rig.c.importRequests(file(dir,"a","CABIN 1 6\nCABIN 1 006\nHALL 3 UP\nHALL 003 UP"))).batch().orElseThrow();
                check(batch.requests().size()==4 && batch.requests().stream().map(r->r.getId()).distinct().count()==4,"Cuatro identidades sin deduplicar archivo");
                get(rig.c.start());check(rig.c.lastBatchDispatch().orElseThrow().entries().size()==4,"Cada entrada se presenta al dominio");
            }
        });
    }
    public static void runningImportIsRejectedBeforeReading() throws Exception {
        var reads=new AtomicInteger();
        try(var rig=new Rig(CONFIG,(p,c)->{reads.incrementAndGet();return parsed("CABIN 1 2",c);})) {
            get(rig.c.start());check(get(rig.c.importRequests(Path.of("a"))).outcome()==REJECTED && reads.get()==0,"No lee desde RUNNING");
        }
    }
    public static void stoppedImportIsRejectedBeforeReading() throws Exception {
        var reads=new AtomicInteger();
        try(var rig=new Rig(CONFIG,(p,c)->{reads.incrementAndGet();return parsed("CABIN 1 2",c);})) {
            get(rig.c.start());get(rig.c.stop());check(get(rig.c.importRequests(Path.of("a"))).state()==SimulationState.STOPPED && reads.get()==0,"No lee desde STOPPED");
        }
    }
    public static void stoppingImportIsRejectedBeforeReading() throws Exception {
        var reads=new AtomicInteger();
        try(var rig=new Rig(new SimulationConfig(2,8,60_000,5),(p,c)->{reads.incrementAndGet();return parsed("CABIN 1 2",c);})) {
            get(rig.c.start());get(rig.c.submit(cabin(0,8,1)));rig.until(DEPARTED,1);rig.c.stop();
            var response=get(rig.c.importRequests(Path.of("a")));
            check(response.outcome()==REJECTED && response.state()==SimulationState.STOPPING && reads.get()==0,"No lee desde STOPPING");
        }
    }
    public static void resettingImportIsRejectedBeforeReading() throws Exception {
        var reads=new AtomicInteger();
        try(var rig=new Rig(new SimulationConfig(2,8,60_000,5),(p,c)->{reads.incrementAndGet();return parsed("CABIN 1 2",c);})) {
            get(rig.c.start());get(rig.c.submit(cabin(0,8,1)));rig.until(DEPARTED,1);rig.c.reset();
            var response=get(rig.c.importRequests(Path.of("a")));
            check(response.outcome()==REJECTED && response.state()==SimulationState.RESETTING && reads.get()==0,"No lee durante retorno físico");
        }
    }
    public static void closingAndClosedImportsAreRejected() throws Exception {
        var reads=new AtomicInteger();var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        var c=SimulationController.withBatchLoader(CONFIG,0,(p,config)->{reads.incrementAndGet();return parsed("CABIN 1 2",config);});
        try {
            get(c.registerResource(()->{entered.countDown();check(release.await(10,TimeUnit.SECONDS),"Liberar recurso");}));
            var closed=c.closeAsync();await(entered);
            check(get(c.importRequests(Path.of("a"))).outcome()==REJECTED,"Rechaza CLOSING");release.countDown();get(closed);
            check(get(c.importRequests(Path.of("a"))).state()==SimulationState.CLOSED && reads.get()==0,"Rechaza CLOSED sin lectura");
        } finally {release.countDown();get(c.closeAsync());check(c.awaitTermination(Duration.ofSeconds(10)),"Cerrador termina");}
    }
    public static void startDuringReadInvalidatesLateSuccess() throws Exception {
        var gate=new GateLoader(1);
        try(var rig=new Rig(CONFIG,gate)) {
            try {
                var pending=rig.c.importRequests(Path.of("a"));await(gate.entered);get(rig.c.start());
                check(get(pending).outcome()==REJECTED && rig.c.preparedBatch().isEmpty(),"Start invalida de inmediato");
                gate.release.countDown();check(rig.c.lastBatchDispatch().isEmpty(),"No había lote confirmado que ejecutar");
            } finally {gate.release.countDown();}
        }
    }
    public static void resetDuringReadRejectsOldEpochEvenBackInReset() throws Exception {
        var gate=new GateLoader(1);
        try(var rig=new Rig(CONFIG,gate)) {
            try {
                var pending=rig.c.importRequests(Path.of("a"));await(gate.entered);get(rig.c.reset());
                check(get(pending).outcome()==REJECTED && rig.c.state()==SimulationState.RESET && rig.c.epoch()==1,"Nueva época RESET");
                gate.release.countDown();var fresh=get(rig.c.importRequests(Path.of("b"))).batch().orElseThrow();
                check(fresh.epoch()==1 && fresh.requests().get(0).getFloor()==6,"Solo confirma lectura nueva");
            } finally {gate.release.countDown();}
        }
    }
    public static void resetClearsConfirmedBatchBeforeNextStart() throws Exception {
        inDirectory(dir -> {
            try(var rig=new Rig(CONFIG)) {
                get(rig.c.importRequests(file(dir,"a","CABIN 1 4")));get(rig.c.reset());
                check(rig.c.preparedBatch().isEmpty() && rig.c.lastBatchDispatch().isEmpty(),"Reset elimina lote");get(rig.c.start());
                check(rig.c.lastBatchDispatch().isEmpty(),"No revive después de Start");
            }
        });
    }
    public static void closeWaitsForActualReaderTermination() throws Exception {
        var gate=new GateLoader(1);var c=SimulationController.withBatchLoader(CONFIG,0,gate);
        try {
            var pending=c.importRequests(Path.of("a"));await(gate.entered);var closing=c.closeAsync();
            check(get(pending).outcome()==REJECTED,"Cierre resuelve importación pendiente");
            check(!c.awaitTermination(Duration.ZERO) && !closing.isDone(),"No afirma CLOSED mientras lector sigue vivo");
            gate.release.countDown();get(closing);check(c.awaitTermination(Duration.ofSeconds(10)),"Cierre real confirmado");
            check(!gate.thread.get().isAlive(),"Hilo I/O terminado");
        } finally {gate.release.countDown();get(c.closeAsync());c.awaitTermination(Duration.ofSeconds(10));}
    }
    public static void newestReadSupersedesAndPreservesOldBatchWhilePending() throws Exception {
        var gate=new GateLoader(2);
        try(var rig=new Rig(CONFIG,gate)) {
            try {
                var old=get(rig.c.importRequests(Path.of("a"))).batch().orElseThrow();
                var obsolete=rig.c.importRequests(Path.of("b"));await(gate.entered);var latest=rig.c.importRequests(Path.of("c"));
                check(get(obsolete).outcome()==SUPERSEDED && rig.c.preparedBatch().orElseThrow()==old,"Mantiene confirmado mientras lee");
                gate.release.countDown();var fresh=get(latest).batch().orElseThrow();
                check(rig.c.preparedBatch().orElseThrow()==fresh && fresh.requests().get(0).getFloor()==7,"Gana petición nueva");
            } finally {gate.release.countDown();}
        }
    }
    public static void repeatedSupersedingKeepsOneQueuedRead() throws Exception {
        var gate=new GateLoader(1);
        try(var rig=new Rig(CONFIG,gate)) {
            try {
                var first=rig.c.importRequests(Path.of("a"));await(gate.entered);
                var futures=new ArrayList<CompletableFuture<BatchImportResult>>();
                for(int i=0;i<20;i++) {futures.add(rig.c.importRequests(Path.of("c")));}
                check(get(first).outcome()==SUPERSEDED,"Primera invalidada");
                for(int i=0;i<19;i++) {check(get(futures.get(i)).outcome()==SUPERSEDED,"Pendiente sustituida");}
                gate.release.countDown();check(get(futures.get(19)).outcome()==IMPORTED,"Última confirmada");
                check(gate.calls.get()==2 && gate.threads.size()==1,"Solo lector en curso y último en cola; mismo hilo");
            } finally {gate.release.countDown();}
        }
    }
    public static void explicitCancellationKeepsPreviousBatch() throws Exception {
        var gate=new GateLoader(2);
        try(var rig=new Rig(CONFIG,gate)) {
            try {
                var old=get(rig.c.importRequests(Path.of("a"))).batch().orElseThrow();var pending=rig.c.importRequests(Path.of("b"));await(gate.entered);
                check(get(rig.c.cancelImport()).outcome()==CANCELLED && get(pending).outcome()==CANCELLED,"Cancelación explícita");
                check(rig.c.preparedBatch().orElseThrow()==old,"No borra lote previo");gate.release.countDown();
                check(get(rig.c.importRequests(Path.of("c"))).outcome()==IMPORTED,"Puede leer después");
            } finally {gate.release.countDown();}
        }
    }
    public static void cancellingVisibleFutureDoesNotCancelOperation() throws Exception {
        var gate=new GateLoader(1);
        try(var rig=new Rig(CONFIG,gate)) {
            try {
                var observer=rig.c.importRequests(Path.of("a"));await(gate.entered);check(observer.cancel(true),"Observador cancelado");
                gate.release.countDown();until(()->rig.c.preparedBatch().isPresent());
                check(observer.isCancelled() && rig.c.preparedBatch().orElseThrow().requests().get(0).getFloor()==2,"Operación privada sigue vigente");
            } finally {gate.release.countDown();}
        }
    }
    public static void incompatibleConfigurationCannotReplaceBatch() throws Exception {
        try(var rig=new Rig(CONFIG,(p,c)->parsed("CABIN 1 2",p.toString().equals("bad")?new SimulationConfig(1,4,1,1):c))) {
            var old=get(rig.c.importRequests(Path.of("a"))).batch().orElseThrow();var result=get(rig.c.importRequests(Path.of("bad")));
            check(result.outcome()==REJECTED && rig.c.preparedBatch().orElseThrow()==old,"Revalida configuración");
        }
    }
    public static void loaderExceptionIsInvalidAndKeepsOriginalCause() throws Exception {
        var cause=new IOException("fallo inyectado");
        try(var rig=new Rig(CONFIG,(p,c)->{if(p.toString().equals("bad")){throw cause;}return parsed("CABIN 1 2",c);})) {
            var result=get(rig.c.importRequests(Path.of("bad")));
            check(result.outcome()==INVALID && result.diagnostics().get(0).cause().orElseThrow()==cause,"Causa por identidad");
            check(get(rig.c.importRequests(Path.of("a"))).outcome()==IMPORTED && rig.c.failure().isEmpty(),"Error de archivo no mata sesión");
        }
    }
    public static void nullLoaderResultCannotLeaveUnresolvedFuture() throws Exception {
        try(var rig=new Rig(CONFIG,(p,c)->null)) {
            var result=get(rig.c.importRequests(Path.of("a")));
            check(result.outcome()==INVALID && result.diagnostics().get(0).cause().orElseThrow() instanceof NullPointerException,"Lector nulo se informa");
        }
    }
    public static void edtAndCoordinatorStayResponsiveDuringRead() throws Exception {
        var gate=new GateLoader(1);
        try(var rig=new Rig(CONFIG,gate)) {
            try {
                var future=new AtomicReference<CompletableFuture<BatchImportResult>>();
                SwingUtilities.invokeAndWait(()->future.set(rig.c.importRequests(Path.of("a"))));await(gate.entered);
                var ping=new CompletableFuture<Boolean>();SwingUtilities.invokeLater(()->ping.complete(SwingUtilities.isEventDispatchThread()));
                check(get(ping) && gate.thread.get().getName().startsWith("simulation-import-"),"EDT libre e I/O en hilo propio");
                check(get(rig.c.stop()).outcome()==SimulationController.Outcome.UNCHANGED,"Coordinador no bloqueado por I/O");
                get(rig.c.cancelImport());gate.release.countDown();check(get(future.get()).outcome()==CANCELLED,"Cancelación responde");
            } finally {gate.release.countDown();}
        }
    }
    public static void confirmationDoesNotConsumeCommandIds() throws Exception {
        inDirectory(dir -> {
            try(var rig=new Rig(CONFIG)) {
                get(rig.c.importRequests(file(dir,"a","CABIN 1 2")));get(rig.c.importRequests(file(dir,"b","CABIN 2 3")));
                get(rig.c.importRequests(file(dir,"bad","HALL 8 UP")));get(rig.c.start());
                check(rig.c.lastBatchDispatch().orElseThrow().entries().get(0).result().command().orElseThrow().commandId()==1,"Primer comando solo al enviar");
            }
        });
    }
    public static void receiptsAreBoundToTheirSession() throws Exception {
        try(var a=new Rig(CONFIG,(p,c)->parsed("CABIN 1 2",c));var b=new Rig(CONFIG,(p,c)->parsed("CABIN 1 2",c))) {
            var result=get(a.c.importRequests(Path.of("a")));
            check(a.c.isCurrent(result) && !b.c.isCurrent(result),"Misma época no autoriza otra sesión");get(a.c.start());
            check(!a.c.isCurrent(result),"Respuesta anterior a Start ya no representa lote preparado");
        }
    }
    public static void delayedReceiptIsRejectedAfterReplacement() throws Exception {
        try(var rig=new Rig(CONFIG,(p,c)->parsed("CABIN 1 2",c))) {
            var old=get(rig.c.importRequests(Path.of("a")));var fresh=get(rig.c.importRequests(Path.of("b")));
            check(!rig.c.isCurrent(old) && rig.c.isCurrent(fresh),"Generación invalida respuesta tardía");
        }
    }
    public static void confirmedRequestsShareTimeAndHaveUniqueIds() throws Exception {
        try(var rig=new Rig(CONFIG,(p,c)->parsed("CABIN 1 2\nCABIN 1 2\nHALL 3 UP",c))) {
            var batch=get(rig.c.importRequests(Path.of("a"))).batch().orElseThrow();
            check(batch.requests().stream().allMatch(r->r.getEpoch()==0 && r.getCreatedAt().equals(batch.confirmedAt())),"Un Instant de confirmación y época");
            check(batch.requests().stream().map(r->r.getId()).distinct().count()==3,"UUID por instrucción, incluidos repetidos");
        }
    }
    public static void dispatchReportPreservesOrderWithoutClaimingService() throws Exception {
        try(var rig=new Rig(CONFIG,(p,c)->parsed("# origen\nCABIN 1 7\nHALL 2 UP\n\nCABIN 2 6\nHALL 5 DOWN",c))) {
            var batch=get(rig.c.importRequests(Path.of("a"))).batch().orElseThrow();get(rig.c.start());var report=rig.c.lastBatchDispatch().orElseThrow();
            check(report.entries().stream().map(BatchDispatchReport.Entry::lineNumber).toList().equals(List.of(2,3,5,6)),"Orden de presentación");
            check(report.entries().stream().allMatch(e->e.result().outcome()==SimulationController.Outcome.ENQUEUED),"Solo confirma envío");
            for(int i=0;i<4;i++) {check(report.entries().get(i).requestId().equals(batch.requests().get(i).getId()),"UUID correlacionado");}
            check(report.entries().get(1).result().command().isEmpty(),"HALL aún no es orden asignada al confirmar envío");
        }
    }
    public static void importingAfterResetKeepsActorThreadIdentities() throws Exception {
        try(var rig=new Rig(CONFIG,(p,c)->parsed("CABIN 1 2",c))) {
            get(rig.c.start());var before=actorThreads();get(rig.c.reset());get(rig.c.importRequests(Path.of("a")));get(rig.c.start());
            check(actorThreads().equals(before) && before.size()==3,"No crea nuevas cabinas ni manejador");
        }
    }
    public static void importingAfterCompletedResetUsesNewEpoch() throws Exception {
        try(var rig=new Rig(CONFIG,(p,c)->parsed("CABIN 1 3",c))) {
            get(rig.c.reset());var batch=get(rig.c.importRequests(Path.of("a"))).batch().orElseThrow();
            check(batch.epoch()==1 && batch.requests().get(0).getEpoch()==1,"Época actual confirmada");get(rig.c.start());rig.served(batch);
        }
    }
    public static void closeClearsConfirmedBatch() throws Exception {
        try(var rig=new Rig(CONFIG,(p,c)->parsed("CABIN 1 2",c))) {
            get(rig.c.importRequests(Path.of("a")));get(rig.c.closeAsync());check(rig.c.preparedBatch().isEmpty(),"Cierre no conserva lote ejecutable");
        }
    }
    public static void importedAndManualRequestsUseSameAdmissionPath() throws Exception {
        try(var rig=new Rig(CONFIG,(p,c)->parsed("CABIN 1 2",c))) {
            var batch=get(rig.c.importRequests(Path.of("a"))).batch().orElseThrow();get(rig.c.start());rig.served(batch);
            var manual=cabin(0,4,1);var submitted=get(rig.c.submit(manual));rig.until(COMMAND_COMPLETED,4);
            check(submitted.command().orElseThrow().commandId()>rig.c.lastBatchDispatch().orElseThrow().entries().get(0).result().command().orElseThrow().commandId(),"Generador compartido sin reinicio");
        }
    }
    public static void cancellationWithoutReadDoesNotEraseBatch() throws Exception {
        try(var rig=new Rig(CONFIG,(p,c)->parsed("CABIN 1 2",c))) {
            var batch=get(rig.c.importRequests(Path.of("a"))).batch().orElseThrow();get(rig.c.cancelImport());
            check(rig.c.preparedBatch().orElseThrow()==batch,"Cancelar selector sin lectura no borra preparado");
        }
    }
    public static void staleInvalidResultCannotEraseNewerBatch() throws Exception {
        var gate=new GateLoader(2,true);
        try(var rig=new Rig(CONFIG,gate)) {
            try {
                get(rig.c.importRequests(Path.of("a")));var obsolete=rig.c.importRequests(Path.of("b"));await(gate.entered);
                var latest=rig.c.importRequests(Path.of("c"));check(get(obsolete).outcome()==SUPERSEDED,"Error viejo invalidado");
                gate.release.countDown();var fresh=get(latest).batch().orElseThrow();check(rig.c.preparedBatch().orElseThrow()==fresh,"Error tardío no borra nuevo");
            } finally {gate.release.countDown();}
        }
    }
    public static void publicRecordsAreImmutableAndValidateCorrelation() throws Exception {
        try(var rig=new Rig(CONFIG,(p,c)->parsed("CABIN 1 2",c))) {
            var result=get(rig.c.importRequests(Path.of("a")));var batch=result.batch().orElseThrow();
            expect(UnsupportedOperationException.class,()->batch.requests().clear());
            expect(IllegalArgumentException.class,()->new ImportedRequestBatch(batch.id(),0,batch.confirmedAt(),batch.source(),List.of(cabin(0,3,1))));
            expect(IllegalArgumentException.class,()->new BatchImportResult(IMPORTED,SimulationState.RUNNING,0,rig.c.sessionId(),1,"mal",Optional.of(batch),List.of()));
            get(rig.c.start());var report=rig.c.lastBatchDispatch().orElseThrow();expect(UnsupportedOperationException.class,()->report.entries().clear());
            expect(IllegalArgumentException.class,()->new BatchDispatchReport(batch,List.of()));
            expect(IllegalArgumentException.class,()->new BatchDispatchReport(batch,List.of(new BatchDispatchReport.Entry(1,batch.requests().get(0).getId(),
                    new SimulationController.Result(SimulationController.Outcome.APPLIED,SimulationState.RUNNING,"No es un envío",Optional.empty(),0)))));
            expect(NullPointerException.class,()->rig.c.importRequests(null));
        }
    }
    public static void repeatedSessionsReleaseEveryOwnedThread() throws Exception {
        for(int i=0;i<10;i++) {
            var rig=new Rig(CONFIG,(p,c)->parsed("CABIN 1 2",c));
            List<Thread> owned;
            try {
                for(int j=0;j<3;j++) {get(rig.c.importRequests(Path.of("a")));}get(rig.c.start());
                owned=Thread.getAllStackTraces().keySet().stream().filter(t->t.getName().equals("simulation-import-"+rig.c.sessionId())
                        || t.getName().equals("simulation-controller") || t.getName().startsWith("elevator-worker-") || t.getName().startsWith("manager-worker-")).toList();
            } finally {rig.close();}
            check(owned.stream().noneMatch(Thread::isAlive),"Sin hilos de sesión tras cerrar");
        }
    }

    private static final class GateLoader implements SimulationController.BatchLoader {
        final int blockedCall;final boolean invalidBlocked;final AtomicInteger calls=new AtomicInteger();
        final CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        final AtomicReference<Thread> thread=new AtomicReference<>();
        final java.util.Set<Thread> threads=java.util.concurrent.ConcurrentHashMap.newKeySet();
        GateLoader(int blockedCall){this(blockedCall,false);}
        GateLoader(int blockedCall,boolean invalidBlocked){this.blockedCall=blockedCall;this.invalidBlocked=invalidBlocked;}
        @Override public BatchReadResult read(Path path,SimulationConfig config) {
            int call=calls.incrementAndGet();threads.add(Thread.currentThread());
            String text=path.toString().equals("a")?"CABIN 1 2":path.toString().equals("b")?"CABIN 1 6":"CABIN 1 7";
            var parsed=parsed(call==blockedCall && invalidBlocked?"HALL 8 UP":text,config);
            if(call==blockedCall) {
                thread.set(Thread.currentThread());entered.countDown();boolean interrupted=false;
                try {
                    long deadline=deadline();
                    while(true) {
                        long left=deadline-System.nanoTime();check(left>0,"Lector retenido no liberado");
                        try {check(release.await(left,TimeUnit.NANOSECONDS),"Liberar lector");break;}
                        catch(InterruptedException ignored){interrupted=true;}
                    }
                } finally {if(interrupted){Thread.currentThread().interrupt();}}
            }
            return parsed;
        }
    }
    private static final class Rig implements AutoCloseable {
        final SimulationController c;
        Rig(SimulationConfig config){c=new SimulationController(config,0);}
        Rig(SimulationConfig config,SimulationController.BatchLoader loader){c=SimulationController.withBatchLoader(config,0,loader);}
        void until(ExecutionEvent.Type type,int floor)throws Exception {
            long deadline=deadline();while(true){var event=next(deadline);if(event.type()==type && event.snapshot().currentFloor()==floor){return;}}
        }
        ExecutionEvent next(long deadline)throws Exception {
            long left=deadline-System.nanoTime();check(left>0,"Plazo de evento agotado");
            return c.awaitEvent(Duration.ofNanos(left)).orElseThrow(()->new AssertionError("Falta evento"));
        }
        void served(ImportedRequestBatch batch)throws Exception {
            var pending=new HashSet<UUID>();batch.requests().forEach(r->pending.add(r.getId()));long deadline=deadline();
            while(!pending.isEmpty()) {var event=next(deadline);if(event.type()==COMMAND_COMPLETED){event.canonicalRequestId().ifPresent(pending::remove);}}
        }
        @Override public void close()throws Exception {get(c.closeAsync());check(c.awaitTermination(Duration.ofSeconds(10)),"Cierre real");check(c.failure().isEmpty(),"Sin fallos de sesión");}
    }
    private static java.util.Set<Thread> actorThreads(){var set=new HashSet<Thread>();Thread.getAllStackTraces().keySet().stream().filter(t->t.getName().startsWith("elevator-worker-") || t.getName().startsWith("manager-worker-")).forEach(set::add);return set;}
    private static BatchReadResult parsed(String text,SimulationConfig config){return READER.parse(text.getBytes(StandardCharsets.UTF_8),config);}
    private static CabinRequest cabin(long epoch,int floor,int id){return new CabinRequest(UUID.randomUUID(),epoch,floor,Instant.now(),id);}
    private static Path file(Path directory,String name,String text)throws IOException{return Files.writeString(directory.resolve(name+".txt"),text,StandardCharsets.UTF_8);}
    private static long deadline(){return System.nanoTime()+TimeUnit.SECONDS.toNanos(10);}
    private static void await(CountDownLatch latch)throws InterruptedException{check(latch.await(10,TimeUnit.SECONDS),"Barrera no alcanzada");}
    private static void until(BooleanSupplier condition){long deadline=deadline();while(!condition.getAsBoolean()){check(System.nanoTime()<deadline,"Condición no alcanzada");LockSupport.parkNanos(1_000_000);}}
    private static <T>T get(CompletableFuture<T> future)throws Exception{return future.get(15,TimeUnit.SECONDS);}
    private static void check(boolean condition,String message){if(!condition){throw new AssertionError(message);}}
    private interface Action{void run()throws Exception;}
    private interface DirectoryAction{void run(Path path)throws Exception;}
    private static void expect(Class<? extends Throwable> type,Action action)throws Exception{try{action.run();}catch(Throwable error){if(type.isInstance(error)){return;}throw new AssertionError("Excepción inesperada",error);}throw new AssertionError("Falta excepción "+type.getSimpleName());}
    private static void inDirectory(DirectoryAction action)throws Exception{Path directory=Files.createTempDirectory("t45-");try{action.run(directory);}finally{try(var paths=Files.walk(directory)){for(Path path:paths.sorted(Comparator.reverseOrder()).toList()){Files.delete(path);}}}}
    public static void main(String[]args)throws Exception {
        initialImportDoesNotStartActors();importAfterResetKeepsCabinsIdle();startingServicesMixedImportedBatch();repeatedStartNeverReleasesBatchAgain();
        stopStartContinuesOriginalBatchRequests();validReplacementIsAtomicAndUsesFreshIdentities();invalidFinalLinePreservesPreviousBatch();
        missingFilePreservesPreviousBatch();emptyFilePreservesPreviousBatch();duplicateEntriesRemainDistinctAtConfirmation();
        runningImportIsRejectedBeforeReading();stoppedImportIsRejectedBeforeReading();stoppingImportIsRejectedBeforeReading();resettingImportIsRejectedBeforeReading();
        closingAndClosedImportsAreRejected();startDuringReadInvalidatesLateSuccess();resetDuringReadRejectsOldEpochEvenBackInReset();
        resetClearsConfirmedBatchBeforeNextStart();closeWaitsForActualReaderTermination();newestReadSupersedesAndPreservesOldBatchWhilePending();
        repeatedSupersedingKeepsOneQueuedRead();explicitCancellationKeepsPreviousBatch();cancellingVisibleFutureDoesNotCancelOperation();
        incompatibleConfigurationCannotReplaceBatch();loaderExceptionIsInvalidAndKeepsOriginalCause();nullLoaderResultCannotLeaveUnresolvedFuture();
        edtAndCoordinatorStayResponsiveDuringRead();confirmationDoesNotConsumeCommandIds();receiptsAreBoundToTheirSession();delayedReceiptIsRejectedAfterReplacement();
        confirmedRequestsShareTimeAndHaveUniqueIds();dispatchReportPreservesOrderWithoutClaimingService();importingAfterResetKeepsActorThreadIdentities();
        importingAfterCompletedResetUsesNewEpoch();closeClearsConfirmedBatch();importedAndManualRequestsUseSameAdmissionPath();
        cancellationWithoutReadDoesNotEraseBatch();staleInvalidResultCannotEraseNewerBatch();publicRecordsAreImmutableAndValidateCorrelation();repeatedSessionsReleaseEveryOwnedThread();
        System.out.println("T45: 40 escenarios satisfactorios.");
    }
}
