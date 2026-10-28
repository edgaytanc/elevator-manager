package com.elevatormanager.io;

import com.elevatormanager.model.SimulationConfig;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static com.elevatormanager.io.BatchDiagnostic.Code.*;

/** T46: bytes distribuidos y fallos reales del contrato InputStream, sin depender de root/ACL. */
public final class ReadFailureScenarios {
    public record Example(String name,int cabins,int floors,int count,BatchDiagnostic.Code code,int line) {
        public SimulationConfig config(){return new SimulationConfig(cabins,floors,1,1);}
        public boolean valid(){return code==null;}
    }
    public static final List<Example> EXAMPLES=List.of(
        new Example("01-minimo.txt",1,4,1,null,0),new Example("02-varios-elevadores.txt",3,8,3,null,0),
        new Example("03-sentidos-mixtos.txt",2,8,6,null,0),new Example("04-repetidos.txt",2,8,6,null,0),
        new Example("05-un-piso.txt",1,1,1,null,0),new Example("06-bom-crlf.txt",2,8,2,null,0),
        new Example("07-espacios-comentarios.txt",2,8,2,null,0),
        new Example("08-error-ultima-linea.txt",2,8,0,HALL_UP_AT_TOP,4),new Example("09-piso-invalido.txt",2,8,0,FLOOR_OUT_OF_RANGE,2),
        new Example("10-elevador-invalido.txt",2,8,0,ELEVATOR_OUT_OF_RANGE,2),new Example("11-direccion-invalida.txt",2,8,0,INVALID_DIRECTION,2),
        new Example("12-tab-instruccion.txt",2,8,0,INVALID_CHARACTER,2),new Example("13-comentario-final.txt",2,8,0,INVALID_ARITY,2),
        new Example("14-utf8-invalido.txt",2,8,0,INVALID_UTF8,0),new Example("15-vacio.txt",2,8,0,EMPTY_BATCH,0),
        new Example("16-solo-comentarios.txt",2,8,0,EMPTY_BATCH,0),new Example("17-bom-interior.txt",2,8,0,INVALID_BOM,2),
        new Example("18-control-prohibido.txt",2,8,0,UNKNOWN_INSTRUCTION,2),new Example("19-varios-errores.txt",2,8,0,HALL_UP_AT_TOP,2),
        new Example("20-cr-aislado.txt",2,8,0,INVALID_LINE_ENDING,1),new Example("21-desbordamiento.txt",2,8,0,INTEGER_OUT_OF_RANGE,2),
        new Example("22-sentido-extremo.txt",2,8,0,HALL_DOWN_AT_BOTTOM,2));
    private static final SimulationConfig CONFIG=new SimulationConfig(2,8,1,1);
    private ReadFailureScenarios() { }

    public static void validCatalogFilesMatchTheirConfigurations() throws Exception {
        var reader=new RequestFileReader();
        for(var sample:EXAMPLES) {if(!sample.valid()){continue;}
            try(var stream=resource(sample.name())) {
                var result=reader.read(stream,sample.config());
                check(result.isValid() && result.batch().orElseThrow().entries().size()==sample.count(),"Ejemplo válido: "+sample.name());
            }
        }
    }
    public static void invalidCatalogFilesMatchDiagnosticsAndLines() throws Exception {
        var reader=new RequestFileReader();
        for(var sample:EXAMPLES) {if(sample.valid()){continue;}
            try(var stream=resource(sample.name())) {
                var result=reader.read(stream,sample.config());check(!result.isValid() && result.batch().isEmpty(),"Sin parcial: "+sample.name());
                var first=result.diagnostics().get(0);check(first.code()==sample.code(),"Código: "+sample.name());
                check(first.lineNumber().orElse(0)==sample.line(),"Línea: "+sample.name());
                if(sample.name().equals("19-varios-errores.txt")) {check(result.diagnostics().stream().map(d->d.lineNumber().orElseThrow()).toList().equals(List.of(2,3,4)),"Todos los errores");}
            }
        }
    }
    public static void intentionalBytesArePreserved() throws Exception {
        byte[] bom=bytes("06-bom-crlf.txt");check((bom[0]&255)==0xEF && (bom[1]&255)==0xBB && (bom[2]&255)==0xBF,"BOM real");
        check(new String(bom,java.nio.charset.StandardCharsets.UTF_8).contains("\r\n"),"CRLF real");
        check(new String(bytes("12-tab-instruccion.txt"),java.nio.charset.StandardCharsets.UTF_8).contains("\t"),"Tab real");
        check(bytes("15-vacio.txt").length==0,"Vacío real, no un comentario");
        check(new String(bytes("20-cr-aislado.txt"),java.nio.charset.StandardCharsets.UTF_8).contains("\rHALL"),"CR aislado real");
        var invalid=new RequestFileReader().parse(bytes("14-utf8-invalido.txt"),CONFIG);
        check(invalid.diagnostics().get(0).cause().orElseThrow() instanceof java.nio.charset.CharacterCodingException,"Bytes UTF-8 incorrectos reales");
    }
    public static void repeatedFixtureKeepsOrderAndAllEntries() throws Exception {
        var result=new RequestFileReader().parse(bytes("04-repetidos.txt"),CONFIG);var batch=result.batch().orElseThrow();
        check(batch.entries().size()==6 && batch.entries().stream().map(RequestEntry::lineNumber).toList().equals(List.of(2,3,4,5,6,7)),"Orden de las seis instrucciones");
        check(batch.entries().get(0).floor()==batch.entries().get(1).floor() && batch.entries().get(2).floor()==batch.entries().get(3).floor(),"Equivalencia numérica sin borrar repetidos");
    }
    public static void deniedOpenPreservesOriginalCause() {
        var cause=new AccessDeniedException("archivo-restringido.txt");var result=deniedReader(cause).read(Path.of("archivo-restringido.txt"),CONFIG);
        check(result.diagnostics().get(0).code()==READ_ERROR && result.diagnostics().get(0).cause().orElseThrow()==cause,"Acceso denegado conservado");
        check(result.batch().isEmpty() && result.diagnostics().get(0).lineNumber().isEmpty(),"Sin lote ni línea ficticia");
    }
    public static void securityFailureAtOpenIsReported() {
        var cause=new SecurityException("lectura denegada por proveedor");
        var result=new RequestFileReader(path->{throw cause;}).read(Path.of("archivo"),CONFIG);
        check(result.diagnostics().get(0).code()==READ_ERROR && result.diagnostics().get(0).cause().orElseThrow()==cause,"Causa SecurityException");
    }
    public static void partialReadFailureClosesOwnedStreamAndRejectsBatch() {
        var closes=new AtomicInteger();var cause=new IOException("lectura incompleta");
        var reader=new RequestFileReader(path->new InputStream(){
            private boolean first=true;
            @Override public int read()throws IOException{throw cause;}
            @Override public int read(byte[] buffer,int offset,int length)throws IOException{
                if(!first){throw cause;}first=false;byte[] prefix="CABIN 1 2\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                System.arraycopy(prefix,0,buffer,offset,prefix.length);return prefix.length;
            }
            @Override public void close(){closes.incrementAndGet();}
        });
        var result=reader.read(Path.of("archivo"),CONFIG);
        check(result.batch().isEmpty() && result.diagnostics().get(0).cause().orElseThrow()==cause && closes.get()==1,"No entrega prefijo y cierra recurso");
    }
    public static void closeFailureRejectsOtherwiseValidRead() {
        var cause=new IOException("cierre de archivo fallido");
        var reader=new RequestFileReader(path->new ByteArrayInputStream("CABIN 1 2".getBytes(java.nio.charset.StandardCharsets.UTF_8)){
            @Override public void close()throws IOException{throw cause;}
        });
        var result=reader.read(Path.of("archivo"),CONFIG);
        check(result.diagnostics().get(0).code()==READ_ERROR && result.diagnostics().get(0).cause().orElseThrow()==cause,"Cierre fallido no confirma validez");
    }
    public static void primaryReadErrorRetainsSuppressedCloseError() {
        var read=new IOException("causa primaria");var close=new IOException("causa al cerrar");
        var reader=new RequestFileReader(path->new InputStream(){
            @Override public int read()throws IOException{throw read;}
            @Override public void close()throws IOException{throw close;}
        });
        var cause=reader.read(Path.of("archivo"),CONFIG).diagnostics().get(0).cause().orElseThrow();
        check(cause==read && cause.getSuppressed().length==1 && cause.getSuppressed()[0]==close,"Jerarquía original de causas");
    }
    public static void sizeLimitRejectionAlsoClosesOwnedStream() {
        var closes=new AtomicInteger();
        var reader=new RequestFileReader(path->new InputStream(){
            @Override public int read(){return ' ';}
            @Override public int read(byte[] b,int offset,int length){java.util.Arrays.fill(b,offset,offset+length,(byte)' ');return length;}
            @Override public void close(){closes.incrementAndGet();}
        });
        var result=reader.read(Path.of("archivo"),CONFIG);
        check(result.diagnostics().get(0).code()==FILE_TOO_LARGE && closes.get()==1,"Cierra aunque el archivo exceda límite");
    }
    public static void sameReaderRecoversAfterTransientDeniedOpen() throws Exception {
        Path file=Files.createTempFile("t46-",".txt");var calls=new AtomicInteger();
        try {
            Files.write(file,bytes("01-minimo.txt"));
            var reader=new RequestFileReader(path->{if(calls.getAndIncrement()==0){throw new AccessDeniedException(path.toString());}return Files.newInputStream(path);});
            check(!reader.read(file,CONFIG).isValid() && reader.read(file,CONFIG).isValid(),"Siguiente lectura sigue funcionando");
        } finally {Files.delete(file);}
    }
    public static void independentDeniedReadsDoNotShareResultState() throws Exception {
        var reader=new RequestFileReader(path->{throw new AccessDeniedException(path.toString());});
        var pool=Executors.newFixedThreadPool(4);var futures=new java.util.ArrayList<Future<?>>();
        try {
            for(int i=0;i<16;i++){String name="denegado-"+i;futures.add(pool.submit(()->{
                var result=reader.read(Path.of(name),CONFIG);var cause=result.diagnostics().get(0).cause().orElseThrow();
                check(cause instanceof AccessDeniedException && ((AccessDeniedException)cause).getFile().equals(name),"Sin estado cruzado");
            }));}
            for(var future:futures){future.get(10,TimeUnit.SECONDS);}
        } finally {pool.shutdownNow();check(pool.awaitTermination(10,TimeUnit.SECONDS),"Lectores de prueba terminados");}
    }

    /** Solo pruebas: permite pasar el lector real con apertura denegada a la integración. */
    public static RequestFileReader deniedReader(AccessDeniedException cause){return new RequestFileReader(path->{throw cause;});}
    public static InputStream resource(String name)throws IOException{
        var stream=ReadFailureScenarios.class.getResourceAsStream("/examples/requests/"+name);
        if(stream==null){throw new IOException("Falta recurso de ejemplo: "+name);}return stream;
    }
    public static byte[] bytes(String name)throws IOException{try(var stream=resource(name)){return stream.readAllBytes();}}
    public static Path copy(String name,Path directory)throws IOException{return Files.write(directory.resolve(name),bytes(name));}
    private static void check(boolean condition,String message){if(!condition){throw new AssertionError(message);}}
    public static void main(String[]args)throws Exception{
        validCatalogFilesMatchTheirConfigurations();invalidCatalogFilesMatchDiagnosticsAndLines();intentionalBytesArePreserved();repeatedFixtureKeepsOrderAndAllEntries();
        deniedOpenPreservesOriginalCause();securityFailureAtOpenIsReported();partialReadFailureClosesOwnedStreamAndRejectsBatch();closeFailureRejectsOtherwiseValidRead();
        primaryReadErrorRetainsSuppressedCloseError();sizeLimitRejectionAlsoClosesOwnedStream();sameReaderRecoversAfterTransientDeniedOpen();independentDeniedReadsDoNotShareResultState();
        System.out.println("T46-IO: 12 escenarios satisfactorios.");
    }
}
