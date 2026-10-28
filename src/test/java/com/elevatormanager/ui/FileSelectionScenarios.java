package com.elevatormanager.ui;

import com.elevatormanager.io.ReadFailureScenarios;
import com.elevatormanager.model.SimulationConfig;
import com.elevatormanager.simulation.BatchImportResult;
import com.elevatormanager.simulation.SimulationController;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import javax.swing.SwingUtilities;

/** T46: resultados del selector en EDT; no crea diálogos nativos en las pruebas headless. */
public final class FileSelectionScenarios {
    private FileSelectionScenarios() { }
    public static void cancellingSelectionPreservesBatchAndReceipt() throws Exception {
        try(var rig=new Rig()){
            var receipt=get(rig.c.importRequests(rig.file("01-minimo.txt")));var old=rig.c.preparedBatch().orElseThrow();
            var probe=new Probe(rig.c,Optional::empty);probe.request();
            check(probe.next().outcome()==RequestImportHandler.Outcome.CANCELLED,"Cancelar no es archivo inválido");
            check(rig.c.preparedBatch().orElseThrow()==old && rig.c.isCurrent(receipt),"Sin reemplazo ni nueva generación");
        }
    }
    public static void approvedSelectionImportsAndCallbacksRunOnEdt() throws Exception {
        try(var rig=new Rig()){
            Path file=rig.file("03-sentidos-mixtos.txt");var probe=new Probe(rig.c,()->Optional.of(file));probe.request();
            var result=probe.next();check(result.outcome()==RequestImportHandler.Outcome.FINISHED
                    && result.imported().orElseThrow().outcome()==BatchImportResult.Outcome.IMPORTED,"Selección aprobada");
            check(rig.c.preparedBatch().orElseThrow().requests().size()==6,"Seis instrucciones confirmadas sin Start");
        }
    }
    public static void invalidSelectionPreservesBatchAndHandlerCanRetry() throws Exception {
        try(var rig=new Rig()){
            get(rig.c.importRequests(rig.file("01-minimo.txt")));var old=rig.c.preparedBatch().orElseThrow();
            var selected=new AtomicReference<>(rig.file("08-error-ultima-linea.txt"));var probe=new Probe(rig.c,()->Optional.of(selected.get()));
            probe.request();check(probe.next().imported().orElseThrow().outcome()==BatchImportResult.Outcome.INVALID,"Archivo inválido");
            check(rig.c.preparedBatch().orElseThrow()==old,"Conserva anterior");selected.set(rig.file("03-sentidos-mixtos.txt"));probe.request();
            check(probe.next().imported().orElseThrow().outcome()==BatchImportResult.Outcome.IMPORTED,"Mismo manejador permite corregir");
        }
    }
    public static void missingSelectionPreservesBatchAndAllowsRecovery() throws Exception {
        try(var rig=new Rig()){
            get(rig.c.importRequests(rig.file("01-minimo.txt")));var old=rig.c.preparedBatch().orElseThrow();
            var selected=new AtomicReference<>(rig.dir.resolve("ausente.txt"));var probe=new Probe(rig.c,()->Optional.of(selected.get()));probe.request();
            check(probe.next().imported().orElseThrow().diagnostics().get(0).code()==com.elevatormanager.io.BatchDiagnostic.Code.READ_ERROR,"Lectura fallida visible");
            check(rig.c.preparedBatch().orElseThrow()==old,"No borra previo");selected.set(rig.file("06-bom-crlf.txt"));probe.request();
            check(probe.next().imported().orElseThrow().outcome()==BatchImportResult.Outcome.IMPORTED,"Puede volver a seleccionar");
        }
    }
    public static void emptySelectedFileIsRejectedInsteadOfCancellation() throws Exception {
        try(var rig=new Rig()){
            Path file=rig.file("15-vacio.txt");var probe=new Probe(rig.c,()->Optional.of(file));probe.request();var result=probe.next();
            check(result.outcome()==RequestImportHandler.Outcome.FINISHED && result.imported().orElseThrow().outcome()==BatchImportResult.Outcome.INVALID,"Vacío es archivo rechazado");
            check(result.imported().orElseThrow().diagnostics().get(0).code()==com.elevatormanager.io.BatchDiagnostic.Code.EMPTY_BATCH,"Causa precisa");
        }
    }
    public static void requestOutsideResetDoesNotOpenSelector() throws Exception {
        try(var rig=new Rig()){
            get(rig.c.start());var opened=new AtomicInteger();var probe=new Probe(rig.c,()->{opened.incrementAndGet();return Optional.empty();});probe.request();
            check(probe.next().outcome()==RequestImportHandler.Outcome.NOT_ALLOWED && opened.get()==0,"No abre diálogo fuera de RESET");
        }
    }
    public static void requestingOffEdtIsRejected() throws Exception {
        try(var rig=new Rig()){
            var probe=new Probe(rig.c,Optional::empty);boolean rejected=false;
            try{probe.handler.requestImport();}catch(IllegalStateException expected){rejected=true;}
            check(rejected && probe.results.isEmpty(),"No ejecuta selector ni callbacks fuera del EDT");
        }
    }
    public static void selectorFailureIsReportedOnEdt() throws Exception {
        try(var rig=new Rig()){
            var original=new IllegalStateException("selector no disponible");var probe=new Probe(rig.c,()->{throw original;});probe.request();
            check(probe.failure()==original && probe.results.isEmpty() && rig.c.preparedBatch().isEmpty(),"Fallo del selector conservado");
        }
    }
    public static void nullSelectionIsReportedWithoutMutatingBatch() throws Exception {
        try(var rig=new Rig()){
            var old=get(rig.c.importRequests(rig.file("01-minimo.txt"))).batch().orElseThrow();var probe=new Probe(rig.c,()->null);probe.request();
            check(probe.failure() instanceof NullPointerException && rig.c.preparedBatch().orElseThrow()==old,"No interpreta null como aprobación ni borra lote");
        }
    }
    public static void stateChangeDuringDialogIsRevalidatedByController() throws Exception {
        try(var rig=new Rig()){
            Path file=rig.file("01-minimo.txt");var start=new AtomicReference<CompletableFuture<SimulationController.Result>>();
            var probe=new Probe(rig.c,()->{start.set(rig.c.start());return Optional.of(file);});probe.request();get(start.get());
            check(probe.next().imported().orElseThrow().outcome()==BatchImportResult.Outcome.REJECTED,"Aprobar un diálogo antiguo no autoriza importar");
            check(rig.c.preparedBatch().isEmpty(),"Sin lote confirmado fuera de RESET");
        }
    }
    public static void nestedModalInvocationDoesNotOpenAnotherSelector() throws Exception {
        try(var rig=new Rig()){
            Path file=rig.file("01-minimo.txt");var holder=new AtomicReference<Probe>();var opens=new AtomicInteger();
            var probe=new Probe(rig.c,()->{opens.incrementAndGet();holder.get().handler.requestImport();return Optional.of(file);});holder.set(probe);probe.request();
            check(probe.next().outcome()==RequestImportHandler.Outcome.NOT_ALLOWED,"Protege reentrada del diálogo modal");
            check(probe.next().imported().orElseThrow().outcome()==BatchImportResult.Outcome.IMPORTED && opens.get()==1,"Solo una selección");
        }
    }
    public static void delayedResultIsMarkedStaleBeforeVisualApplication() throws Exception {
        try(var rig=new Rig()){
            Path file=rig.file("01-minimo.txt");var delivery=new LinkedBlockingQueue<Runnable>();var results=new LinkedBlockingQueue<RequestImportHandler.Result>();
            var handler=new RequestImportHandler(rig.c,()->Optional.of(file),result->{check(SwingUtilities.isEventDispatchThread(),"Resultado en EDT");results.add(result);},
                    error->{throw new AssertionError(error);},delivery::add);
            SwingUtilities.invokeAndWait(handler::requestImport);Runnable callback=take(delivery);
            get(rig.c.start());get(rig.c.reset());SwingUtilities.invokeAndWait(callback);
            check(take(results).outcome()==RequestImportHandler.Outcome.STALE && rig.c.preparedBatch().isEmpty(),"Respuesta antigua no repone preparado");
        }
    }
    private static final class Probe{
        final BlockingQueue<RequestImportHandler.Result> results=new LinkedBlockingQueue<>();
        final BlockingQueue<Throwable> failures=new LinkedBlockingQueue<>();final RequestImportHandler handler;
        Probe(SimulationController c,Supplier<Optional<Path>> selector){
            handler=new RequestImportHandler(c,selector,result->{check(SwingUtilities.isEventDispatchThread(),"Callback de resultado en EDT");results.add(result);},
                    error->{check(SwingUtilities.isEventDispatchThread(),"Callback de error en EDT");failures.add(error);});
        }
        void request()throws Exception{SwingUtilities.invokeAndWait(handler::requestImport);}
        RequestImportHandler.Result next()throws Exception{return take(results);}
        Throwable failure()throws Exception{return take(failures);}
    }
    private static final class Rig implements AutoCloseable{
        final Path dir=Files.createTempDirectory("t46-ui-");final SimulationController c=new SimulationController(new SimulationConfig(2,8,1,1),0);
        Rig()throws Exception{}
        Path file(String name)throws Exception{return ReadFailureScenarios.copy(name,dir);}
        @Override public void close()throws Exception{get(c.closeAsync());check(c.awaitTermination(Duration.ofSeconds(10)),"Sesión y lectura cerradas");
            try(var paths=Files.walk(dir)){for(Path p:paths.sorted(Comparator.reverseOrder()).toList()){Files.delete(p);}}}
    }
    private static <T>T take(BlockingQueue<T> queue)throws Exception{T value=queue.poll(10,TimeUnit.SECONDS);check(value!=null,"Falta callback");return value;}
    private static <T>T get(CompletableFuture<T> future)throws Exception{return future.get(15,TimeUnit.SECONDS);}
    private static void check(boolean condition,String message){if(!condition){throw new AssertionError(message);}}
    public static void main(String[]args)throws Exception{
        cancellingSelectionPreservesBatchAndReceipt();approvedSelectionImportsAndCallbacksRunOnEdt();invalidSelectionPreservesBatchAndHandlerCanRetry();
        missingSelectionPreservesBatchAndAllowsRecovery();emptySelectedFileIsRejectedInsteadOfCancellation();requestOutsideResetDoesNotOpenSelector();
        requestingOffEdtIsRejected();selectorFailureIsReportedOnEdt();nullSelectionIsReportedWithoutMutatingBatch();
        stateChangeDuringDialogIsRevalidatedByController();nestedModalInvocationDoesNotOpenAnotherSelector();delayedResultIsMarkedStaleBeforeVisualApplication();
        System.out.println("T46-UI: 12 escenarios satisfactorios.");
    }
}
