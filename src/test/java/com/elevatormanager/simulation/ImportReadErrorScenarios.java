package com.elevatormanager.simulation;

import com.elevatormanager.io.BatchDiagnostic;
import com.elevatormanager.io.ReadFailureScenarios;
import com.elevatormanager.io.RequestFileReader;
import com.elevatormanager.model.SimulationConfig;
import com.elevatormanager.model.request.HallRequest;
import com.elevatormanager.dispatch.HallCallSnapshot;
import com.elevatormanager.protocol.Command;
import com.elevatormanager.ui.RequestImportHandler;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.SwingUtilities;

/** T46: recuperación de sesión, permisos inyectados, desaparición de archivo y ejemplos reales. */
public final class ImportReadErrorScenarios {
    private static final SimulationConfig CONFIG=new SimulationConfig(2,8,1,1);
    private static final RequestFileReader READER=new RequestFileReader();
    private ImportReadErrorScenarios(){}
    public static void catalogErrorsPreserveBatchAndSessionRecovers()throws Exception{
        inDirectory(dir->{try(var rig=new Rig(new SimulationController(CONFIG,0))){
            var old=get(rig.c.importRequests(ReadFailureScenarios.copy("01-minimo.txt",dir))).batch().orElseThrow();
            for(var sample:ReadFailureScenarios.EXAMPLES){if(sample.valid()){continue;}
                var result=get(rig.c.importRequests(ReadFailureScenarios.copy(sample.name(),dir)));
                check(result.outcome()==BatchImportResult.Outcome.INVALID && result.diagnostics().get(0).code()==sample.code(),"Error esperado: "+sample.name());
                check(rig.c.preparedBatch().orElseThrow()==old && rig.c.failure().isEmpty(),"No rompe sesión ni borra lote");
            }
            var fresh=get(rig.c.importRequests(ReadFailureScenarios.copy("06-bom-crlf.txt",dir))).batch().orElseThrow();get(rig.c.start());services(rig.c,fresh);
        }});
    }
    public static void accessDeniedPreservesBatchAndSamePathCanRecover()throws Exception{
        inDirectory(dir->{var denied=new AtomicBoolean();var cause=new AccessDeniedException("restringido.txt");var reader=ReadFailureScenarios.deniedReader(cause);
            try(var rig=new Rig(SimulationController.withBatchLoader(CONFIG,0,(path,config)->denied.get()?reader.read(path,config):READER.read(path,config)))){
                Path file=ReadFailureScenarios.copy("01-minimo.txt",dir);var old=get(rig.c.importRequests(file)).batch().orElseThrow();denied.set(true);
                var result=get(rig.c.importRequests(file));check(result.outcome()==BatchImportResult.Outcome.INVALID && result.diagnostics().get(0).cause().orElseThrow()==cause,"Causa original del lector real");
                check(rig.c.preparedBatch().orElseThrow()==old,"Mantiene preparado");denied.set(false);var fresh=get(rig.c.importRequests(file)).batch().orElseThrow();
                get(rig.c.start());services(rig.c,fresh);check(rig.c.failure().isEmpty(),"Recuperación y servicio real");
            }
        });
    }
    public static void partialReadFailureKeepsPreparedAndNextImportWorks()throws Exception{
        inDirectory(dir->{var fail=new AtomicBoolean();var cause=new IOException("lectura interrumpida después de un prefijo");
            try(var rig=new Rig(SimulationController.withBatchLoader(CONFIG,0,(path,config)->{
                if(!fail.get()){return READER.read(path,config);}
                try(var stream=new InputStream(){boolean first=true;
                    @Override public int read()throws IOException{throw cause;}
                    @Override public int read(byte[] b,int offset,int length)throws IOException{if(!first){throw cause;}first=false;byte[] prefix="CABIN 1 2\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);System.arraycopy(prefix,0,b,offset,prefix.length);return prefix.length;}
                }){return READER.read(stream,config);}
            }))){
                Path file=ReadFailureScenarios.copy("01-minimo.txt",dir);var old=get(rig.c.importRequests(file)).batch().orElseThrow();fail.set(true);
                var result=get(rig.c.importRequests(file));check(result.outcome()==BatchImportResult.Outcome.INVALID && result.diagnostics().get(0).cause().orElseThrow()==cause,"No confirma prefijo válido");
                check(rig.c.preparedBatch().orElseThrow()==old,"Conserva preparado");fail.set(false);check(get(rig.c.importRequests(file)).outcome()==BatchImportResult.Outcome.IMPORTED,"Nueva lectura sana");
            }
        });
    }
    public static void cancellingSelectorDoesNotCancelAnotherActiveRead()throws Exception{
        inDirectory(dir->{var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var calls=new AtomicInteger();
            try(var rig=new Rig(SimulationController.withBatchLoader(CONFIG,0,(path,config)->{
                if(calls.incrementAndGet()==2){entered.countDown();check(release.await(10,TimeUnit.SECONDS),"Liberar lectura");}return READER.read(path,config);
            }))){try{
                Path file=ReadFailureScenarios.copy("01-minimo.txt",dir);var old=get(rig.c.importRequests(file)).batch().orElseThrow();var pending=rig.c.importRequests(file);await(entered);
                var cancelled=new CompletableFuture<RequestImportHandler.Result>();var handler=new RequestImportHandler(rig.c,Optional::empty,cancelled::complete,cancelled::completeExceptionally);
                SwingUtilities.invokeAndWait(handler::requestImport);check(get(cancelled).outcome()==RequestImportHandler.Outcome.CANCELLED,"Diálogo cancelado");
                check(!pending.isDone() && rig.c.preparedBatch().orElseThrow()==old,"No invoca cancelImport sobre otra lectura");release.countDown();
                check(get(pending).outcome()==BatchImportResult.Outcome.IMPORTED,"Lectura previa sigue vigente");
            }finally{release.countDown();}}
        });
    }
    public static void readErrorDoesNotPreventResetAndFreshImport()throws Exception{
        inDirectory(dir->{var denied=new AtomicBoolean(true);var reader=ReadFailureScenarios.deniedReader(new AccessDeniedException("sin-acceso"));
            try(var rig=new Rig(SimulationController.withBatchLoader(CONFIG,0,(path,config)->denied.get()?reader.read(path,config):READER.read(path,config)))){
                Path file=ReadFailureScenarios.copy("01-minimo.txt",dir);check(get(rig.c.importRequests(file)).outcome()==BatchImportResult.Outcome.INVALID,"Error inicial");
                get(rig.c.reset());denied.set(false);var batch=get(rig.c.importRequests(file)).batch().orElseThrow();
                check(batch.epoch()==1,"Nueva época válida después del error");get(rig.c.start());services(rig.c,batch);
            }
        });
    }
    public static void selectedFileMayDisappearBeforeReadWithoutBreakingSession()throws Exception{
        inDirectory(dir->{Path oldFile=ReadFailureScenarios.copy("01-minimo.txt",dir);Path selected=ReadFailureScenarios.copy("03-sentidos-mixtos.txt",dir);
            var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
            try(var rig=new Rig(SimulationController.withBatchLoader(CONFIG,0,(path,config)->{
                if(path.equals(selected)){entered.countDown();check(release.await(10,TimeUnit.SECONDS),"Liberar apertura");}return READER.read(path,config);
            }))){try{
                var old=get(rig.c.importRequests(oldFile)).batch().orElseThrow();var pending=rig.c.importRequests(selected);await(entered);Files.delete(selected);release.countDown();
                var result=get(pending);check(result.outcome()==BatchImportResult.Outcome.INVALID && result.diagnostics().get(0).cause().orElseThrow() instanceof NoSuchFileException,"Desaparición real detectada");
                check(rig.c.preparedBatch().orElseThrow()==old && get(rig.c.importRequests(oldFile)).outcome()==BatchImportResult.Outcome.IMPORTED,"Conserva y permite continuar");
            }finally{release.countDown();}}
        });
    }
    public static void repeatedErrorsAndRecoveryReleaseSessionThreads()throws Exception{
        inDirectory(dir->{Path file=ReadFailureScenarios.copy("01-minimo.txt",dir);
            for(int i=0;i<10;i++){
                var denied=new AtomicBoolean(true);var reader=ReadFailureScenarios.deniedReader(new AccessDeniedException(file.toString()));
                var c=SimulationController.withBatchLoader(CONFIG,0,(path,config)->denied.get()?reader.read(path,config):READER.read(path,config));java.util.List<Thread> owned;
                try(var rig=new Rig(c)){
                    get(c.importRequests(file));denied.set(false);get(c.importRequests(file));get(c.start());
                    owned=Thread.getAllStackTraces().keySet().stream().filter(t->t.getName().equals("simulation-import-"+c.sessionId()) || t.getName().equals("simulation-controller")
                            || t.getName().startsWith("elevator-worker-") || t.getName().startsWith("manager-worker-")).toList();
                }
                check(owned.stream().noneMatch(Thread::isAlive),"Error y reintento no dejan hilos vivos");
            }
        });
    }
    public static void everyValidDistributedExampleRunsWithRealActors()throws Exception{
        inDirectory(dir->{for(var sample:ReadFailureScenarios.EXAMPLES){if(!sample.valid()){continue;}
            try(var rig=new Rig(new SimulationController(sample.config(),0))){
                var batch=get(rig.c.importRequests(ReadFailureScenarios.copy(sample.name(),dir))).batch().orElseThrow();
                check(rig.c.elevatorLifecycles().stream().allMatch(l->l==ElevatorWorker.Lifecycle.NEW),"Preparar no arranca");
                get(rig.c.start());check(rig.c.lastBatchDispatch().orElseThrow().entries().size()==sample.count(),"Una presentación por entrada");services(rig.c,batch);
            }
        }});
    }
    private static void services(SimulationController c,ImportedRequestBatch batch)throws Exception{
        var canonical=new HashMap<UUID,UUID>();var served=new HashSet<UUID>();long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
        while(true){var calls=c.calls();if(batch.requests().stream().allMatch(r->r instanceof HallRequest
                ?calls.containsKey(r.getId()) && calls.get(r.getId()).status()==HallCallSnapshot.Status.SERVED
                :canonical.containsKey(r.getId()) && served.contains(canonical.get(r.getId())))){return;}
            long left=deadline-System.nanoTime();check(left>0,"Falta servicio o alias confirmado");
            var optional=c.awaitEvent(Duration.ofNanos(Math.min(left,TimeUnit.MILLISECONDS.toNanos(100))));if(optional.isEmpty()){continue;}var event=optional.orElseThrow();
            check(event.type()!=ExecutionEvent.Type.WORKER_FAILED && event.type()!=ExecutionEvent.Type.COMMAND_REJECTED,"Sin rechazos físicos");
            if(event.command().isPresent() && event.canonicalRequestId().isPresent()){var command=event.command().orElseThrow();UUID id=command instanceof Command.Destination d?d.request().getId():command instanceof Command.Pickup p?p.request().getId():null;
                if(id!=null){canonical.put(id,event.canonicalRequestId().orElseThrow());}}
            if(event.type()==ExecutionEvent.Type.COMMAND_COMPLETED){event.canonicalRequestId().ifPresent(served::add);}
        }
    }
    private static final class Rig implements AutoCloseable{
        final SimulationController c;Rig(SimulationController c){this.c=c;}
        @Override public void close()throws Exception{get(c.closeAsync());check(c.awaitTermination(Duration.ofSeconds(10)),"Cierre real");check(c.failure().isEmpty(),"Error de usuario no mata sesión");}
    }
    private static <T>T get(CompletableFuture<T> future)throws Exception{return future.get(15,TimeUnit.SECONDS);}
    private static void await(CountDownLatch latch)throws Exception{check(latch.await(10,TimeUnit.SECONDS),"Falta barrera");}
    private static void check(boolean condition,String message){if(!condition){throw new AssertionError(message);}}
    private interface Action{void run(Path directory)throws Exception;}
    private static void inDirectory(Action action)throws Exception{Path directory=Files.createTempDirectory("t46-integration-");try{action.run(directory);}finally{try(var paths=Files.walk(directory)){for(Path p:paths.sorted(Comparator.reverseOrder()).toList()){Files.delete(p);}}}}
    public static void main(String[]args)throws Exception{
        catalogErrorsPreserveBatchAndSessionRecovers();accessDeniedPreservesBatchAndSamePathCanRecover();partialReadFailureKeepsPreparedAndNextImportWorks();
        cancellingSelectorDoesNotCancelAnotherActiveRead();readErrorDoesNotPreventResetAndFreshImport();selectedFileMayDisappearBeforeReadWithoutBreakingSession();
        repeatedErrorsAndRecoveryReleaseSessionThreads();everyValidDistributedExampleRunsWithRealActors();
        System.out.println("T46-Integracion: 8 escenarios satisfactorios.");
    }
}
