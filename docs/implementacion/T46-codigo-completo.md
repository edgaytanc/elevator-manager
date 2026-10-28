# T46 — Código completo

Los nueve fuentes nuevos o modificados se reproducen íntegros. El ZIP contiene el resto del proyecto, los 22 archivos de ejemplo y su catálogo, además de la documentación.

## src/main/java/com/elevatormanager/io/RequestFileReader.java

```java
package com.elevatormanager.io;

import com.elevatormanager.model.Direction;
import com.elevatormanager.model.SimulationConfig;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import static com.elevatormanager.io.BatchDiagnostic.Code.*;
import static com.elevatormanager.io.RequestFileFormat.*;

/**
 * Parser síncrono con configuración inmutable. Invocar lecturas fuera del EDT.
 * No confirma importaciones ni conoce el controlador o los trabajadores.
 */
public final class RequestFileReader {
    @FunctionalInterface interface InputOpener { InputStream open(Path path) throws IOException; }
    private final InputOpener opener;

    public RequestFileReader() { this(path -> Files.newInputStream(path)); }

    /** Punto de prueba del paquete para fallos de apertura/lectura/cierre, sin modificar permisos. */
    RequestFileReader(InputOpener opener) { this.opener = Objects.requireNonNull(opener, "opener"); }

    private static final String GRAMMAR = "HALL <piso> <UP|DOWN> o CABIN <idElevador> <piso>";

    /** Abre y cierra su propio stream, incluso ante límite, interrupción o error. */
    public BatchReadResult read(Path path, SimulationConfig config) {
        Objects.requireNonNull(path, "path"); Objects.requireNonNull(config, "config");
        try {
            checkInterrupted();
            byte[] content;
            try (InputStream input = opener.open(path)) { content = readBounded(input); }
            return parse(content, config);
        } catch (IOException | SecurityException error) { return readError(error); }
    }

    /** Lee desde la posición actual; el stream prestado se conserva abierto y pertenece al llamador. */
    public BatchReadResult read(InputStream input, SimulationConfig config) {
        Objects.requireNonNull(input, "input"); Objects.requireNonNull(config, "config");
        try { return parse(readBounded(input), config); }
        catch (IOException | SecurityException error) { return readError(error); }
    }

    /** Copia de entrada acotada y decodificación estricta; resultado independiente del array original. */
    public BatchReadResult parse(byte[] content, SimulationConfig config) {
        Objects.requireNonNull(content, "content"); Objects.requireNonNull(config, "config");
        if (content.length > MAX_FILE_BYTES) {
            return failure(FILE_TOO_LARGE, OptionalInt.empty(), "El archivo supera 1 MiB.", "Hasta " + MAX_FILE_BYTES + " bytes.", null);
        }
        try { checkInterrupted(); } catch (InterruptedIOException error) { return readError(error); }
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(content.clone())).toString();
        } catch (CharacterCodingException error) {
            return failure(INVALID_UTF8, OptionalInt.empty(), "El archivo no contiene UTF-8 válido.", "UTF-8 estricto, sin sustitución de bytes.", error);
        }
        if (text.startsWith("\uFEFF")) { text = text.substring(1); }
        var entries = new ArrayList<RequestEntry>();
        var errors = new ArrayList<BatchDiagnostic>();
        int start = 0;
        int lineNumber = 1;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\r' && (i + 1 == text.length() || text.charAt(i + 1) != '\n')
                    || c == '\u0085' || c == '\u2028' || c == '\u2029') {
                return failure(INVALID_LINE_ENDING, OptionalInt.of(lineNumber),
                        "Terminador de línea no permitido.", "Utilizar LF o CRLF.", null);
            }
            if (c == '\n' || c == '\r') {
                if (lineNumber > MAX_LINES) { return tooManyLines(); }
                if (Thread.currentThread().isInterrupted()) { return interrupted(); }
                parseLine(text.substring(start, i), lineNumber, config, entries, errors);
                if (c == '\r') { i++; }
                start = i + 1;
                lineNumber++;
            }
        }
        if (start < text.length()) {
            if (lineNumber > MAX_LINES) { return tooManyLines(); }
            if (Thread.currentThread().isInterrupted()) { return interrupted(); }
            parseLine(text.substring(start), lineNumber, config, entries, errors);
            lineNumber++;
        }
        if (!errors.isEmpty()) { return BatchReadResult.invalid(errors); }
        if (entries.isEmpty()) {
            return failure(EMPTY_BATCH, OptionalInt.empty(), "El archivo no contiene instrucciones.", "Incluir al menos una instrucción: " + GRAMMAR, null);
        }
        return BatchReadResult.valid(new PreparedRequestBatch(config, entries, lineNumber - 1, content.length));
    }

    /** A lo sumo límite + 1 bytes, también si el archivo crece o el stream no termina. */
    private static byte[] readBounded(InputStream input) throws IOException {
        var output = new ByteArrayOutputStream(8192);
        var buffer = new byte[8192];
        while (output.size() <= MAX_FILE_BYTES) {
            checkInterrupted();
            int count = input.read(buffer, 0, Math.min(buffer.length, MAX_FILE_BYTES + 1 - output.size()));
            if (count < 0) { break; }
            if (count == 0) {
                int value = input.read();
                if (value < 0) { break; }
                output.write(value);
            } else { output.write(buffer, 0, count); }
        }
        return output.toByteArray();
    }

    private static void parseLine(String line, int number, SimulationConfig config,
            List<RequestEntry> entries, List<BatchDiagnostic> errors) {
        try {
            if (line.indexOf('\uFEFF') >= 0) { throw issue(INVALID_BOM, "BOM repetido o fuera del inicio del archivo."); }
            int first = 0;
            while (first < line.length() && line.charAt(first) == ' ') { first++; }
            boolean comment = first < line.length() && line.charAt(first) == '#';
            for (int i = 0; i < line.length();) {
                int cp = line.codePointAt(i);
                if (Character.isISOControl(cp) || (!comment && cp > 126)) {
                    throw issue(INVALID_CHARACTER, "Carácter prohibido; se requieren separadores ASCII y texto permitido.");
                }
                i += Character.charCount(cp);
            }
            if (line.codePointCount(0, line.length()) > MAX_LINE_CODE_POINTS) {
                throw issue(LINE_TOO_LONG, "La línea supera " + MAX_LINE_CODE_POINTS + " puntos de código Unicode.");
            }
            if (comment || first == line.length()) { return; }
            int last = line.length();
            while (line.charAt(last - 1) == ' ') { last--; }
            String[] tokens = line.substring(first, last).split(" +");
            String kind = tokens[0];
            if (!kind.equals("HALL") && !kind.equals("CABIN")) { throw issue(UNKNOWN_INSTRUCTION, "Instrucción desconocida."); }
            if (tokens.length != 3) { throw issue(INVALID_ARITY, "Se requieren exactamente tres tokens, sin comentario final."); }
            String expected = kind.equals("HALL") ? "HALL <piso> <UP|DOWN>" : "CABIN <idElevador> <piso>";
            RequestEntry entry;
            if (kind.equals("HALL")) {
                int floor = integer(tokens[1]);
                if (floor < 1 || floor > config.floorCount()) { throw issue(FLOOR_OUT_OF_RANGE, "El piso debe estar entre 1 y " + config.floorCount() + "."); }
                if (!tokens[2].equals("UP") && !tokens[2].equals("DOWN")) { throw issue(INVALID_DIRECTION, "La dirección debe ser UP o DOWN."); }
                entry = new RequestEntry.Hall(number, floor, Direction.valueOf(tokens[2]));
            } else {
                int id = integer(tokens[1]);
                if (id < 1 || id > config.elevatorCount()) { throw issue(ELEVATOR_OUT_OF_RANGE, "El ID debe estar entre 1 y " + config.elevatorCount() + "."); }
                int floor = integer(tokens[2]);
                if (floor < 1 || floor > config.floorCount()) { throw issue(FLOOR_OUT_OF_RANGE, "El piso debe estar entre 1 y " + config.floorCount() + "."); }
                entry = new RequestEntry.Cabin(number, id, floor);
            }
            var validation = entry.validateAgainst(config);
            if (!validation.isValid()) {
                var error = validation.errors().get(0);
                throw new LineIssue(BatchDiagnostic.Code.valueOf(error.code()), error.message(), expected);
            }
            entries.add(entry);
        } catch (LineIssue error) {
            errors.add(new BatchDiagnostic(error.code, OptionalInt.of(number), error.getMessage(), error.expected, Optional.empty()));
        }
    }

    private static int integer(String token) throws LineIssue {
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            if (c < '0' || c > '9') { throw issue(INVALID_INTEGER, "Se requieren dígitos ASCII sin signo ni decimales."); }
        }
        int value = 0;
        for (int i = 0; i < token.length(); i++) {
            int digit = token.charAt(i) - '0';
            if (value > (Integer.MAX_VALUE - digit) / 10) { throw issue(INTEGER_OUT_OF_RANGE, "El valor supera " + Integer.MAX_VALUE + "."); }
            value = value * 10 + digit;
        }
        return value;
    }
    private static LineIssue issue(BatchDiagnostic.Code code, String message) { return new LineIssue(code, message, GRAMMAR); }
    private static final class LineIssue extends Exception {
        private static final long serialVersionUID = 1L;
        final BatchDiagnostic.Code code;
        final String expected;
        LineIssue(BatchDiagnostic.Code code, String message, String expected) { super(message); this.code = code; this.expected = expected; }
    }
    private static BatchReadResult failure(BatchDiagnostic.Code code, OptionalInt line, String message, String expected, Exception cause) {
        return BatchReadResult.invalid(List.of(new BatchDiagnostic(code, line, message, expected, Optional.ofNullable(cause))));
    }
    private static BatchReadResult tooManyLines() {
        return failure(TOO_MANY_LINES, OptionalInt.of(MAX_LINES + 1), "El archivo supera " + MAX_LINES + " líneas físicas.", "Hasta " + MAX_LINES + " líneas, incluidos comentarios y vacías.", null);
    }
    private static BatchReadResult readError(Exception error) {
        return failure(READ_ERROR, OptionalInt.empty(), "No se pudo completar la lectura del archivo.", "Archivo legible y lectura no interrumpida.", error);
    }
    private static BatchReadResult interrupted() { return readError(new InterruptedIOException("Lectura o análisis interrumpido.")); }
    private static void checkInterrupted() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) { throw new InterruptedIOException("Lectura o análisis interrumpido."); }
    }
}
```

## src/main/java/com/elevatormanager/ui/RequestImportHandler.java

```java
package com.elevatormanager.ui;

import com.elevatormanager.simulation.BatchImportResult;
import com.elevatormanager.simulation.SimulationController;
import java.awt.Component;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javax.swing.JFileChooser;
import javax.swing.SwingUtilities;
import javax.swing.filechooser.FileNameExtensionFilter;

/** T46: selección en EDT; cancelar el diálogo no llama al lector ni cancela otra lectura. */
public final class RequestImportHandler {
    public enum Outcome { CANCELLED, NOT_ALLOWED, FINISHED, STALE }
    public record Result(Outcome outcome, String detail, Optional<BatchImportResult> imported) {
        public Result {
            Objects.requireNonNull(outcome, "outcome"); Objects.requireNonNull(detail, "detail");
            Objects.requireNonNull(imported, "imported");
            if (detail.isBlank() || imported.isPresent() != (outcome == Outcome.FINISHED || outcome == Outcome.STALE)) {
                throw new IllegalArgumentException("Resultado de selección incoherente.");
            }
        }
    }
    private final SimulationController controller;
    private final Supplier<Optional<Path>> selector;
    private final Consumer<Result> onResult;
    private final Consumer<Throwable> onFailure;
    private final Consumer<Runnable> postToEdt;
    private boolean choosing;

    public RequestImportHandler(SimulationController controller, Supplier<Optional<Path>> selector,
            Consumer<Result> onResult, Consumer<Throwable> onFailure) {
        this(controller, selector, onResult, onFailure, SwingUtilities::invokeLater);
    }

    /** El paquete puede diferir la entrega para probar respuestas obsoletas sin bloquear el EDT. */
    RequestImportHandler(SimulationController controller, Supplier<Optional<Path>> selector,
            Consumer<Result> onResult, Consumer<Throwable> onFailure, Consumer<Runnable> postToEdt) {
        this.controller = Objects.requireNonNull(controller, "controller");
        this.selector = Objects.requireNonNull(selector, "selector");
        this.onResult = Objects.requireNonNull(onResult, "onResult");
        this.onFailure = Objects.requireNonNull(onFailure, "onFailure");
        this.postToEdt = Objects.requireNonNull(postToEdt, "postToEdt");
    }

    public static RequestImportHandler forDialog(SimulationController controller, Component owner,
            Consumer<Result> onResult, Consumer<Throwable> onFailure) {
        return new RequestImportHandler(controller, () -> choose(owner), onResult, onFailure);
    }

    public void requestImport() {
        requireEdt();
        if (choosing || !controller.state().allowsRequestImport()) {
            onResult.accept(new Result(Outcome.NOT_ALLOWED,
                    choosing ? "Ya existe un selector abierto." : "La carga de archivos requiere RESET.", Optional.empty()));
            return;
        }
        Optional<Path> selected;
        choosing = true;
        try { selected = Objects.requireNonNull(selector.get(), "El selector no devolvió una selección válida."); }
        catch (RuntimeException error) { onFailure.accept(error); return; }
        finally { choosing = false; }
        if (selected.isEmpty()) {
            onResult.accept(new Result(Outcome.CANCELLED, "Selección cancelada; lote preparado conservado.", Optional.empty()));
            return;
        }
        try {
            controller.importRequests(selected.orElseThrow()).whenComplete((result, error) ->
                    postToEdt.accept(() -> finish(result, error)));
        } catch (RuntimeException error) { onFailure.accept(error); }
    }

    private void finish(BatchImportResult result, Throwable error) {
        requireEdt();
        if (error != null) {
            Throwable cause = error instanceof java.util.concurrent.CompletionException && error.getCause() != null
                    ? error.getCause() : error;
            onFailure.accept(cause);
            return;
        }
        boolean current = controller.isCurrent(result);
        onResult.accept(new Result(current ? Outcome.FINISHED : Outcome.STALE,
                current ? result.detail() : "La simulación o la lectura cambió; este resultado ya no se aplica.", Optional.of(result)));
    }

    private static Optional<Path> choose(Component owner) {
        requireEdt();
        var chooser = new JFileChooser();
        chooser.setDialogTitle("Cargar solicitudes");
        chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
        chooser.setFileFilter(new FileNameExtensionFilter("Archivos de solicitudes (*.txt)", "txt"));
        // El filtro de texto es una ayuda: otras extensiones siguen permitidas por el contrato.
        int decision = chooser.showOpenDialog(owner);
        if (decision == JFileChooser.CANCEL_OPTION) { return Optional.empty(); }
        if (decision != JFileChooser.APPROVE_OPTION || chooser.getSelectedFile() == null) {
            throw new IllegalStateException("No se pudo completar la selección del archivo.");
        }
        return Optional.of(chooser.getSelectedFile().toPath());
    }
    private static void requireEdt() {
        if (!SwingUtilities.isEventDispatchThread()) { throw new IllegalStateException("La selección de archivos requiere EDT."); }
    }
}
```

## src/main/java/com/elevatormanager/diagnostics/FileSelectionDemo.java

```java
package com.elevatormanager.diagnostics;

import com.elevatormanager.model.SimulationConfig;
import com.elevatormanager.simulation.SimulationController;
import com.elevatormanager.ui.RequestImportHandler;
import com.elevatormanager.ui.WindowCloseHandler;
import java.awt.BorderLayout;
import java.awt.GraphicsEnvironment;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;

/** T46: selector real para revisar cancelación y errores; no sustituye la ventana principal. */
public final class FileSelectionDemo {
    private FileSelectionDemo() { }
    public static void main(String[] args) {
        if (GraphicsEnvironment.isHeadless()) { throw new IllegalStateException("El diagnóstico requiere un entorno gráfico."); }
        var controller = new SimulationController(new SimulationConfig(2,8,30,10),0);
        SwingUtilities.invokeLater(() -> {
            try { show(controller); }
            catch (RuntimeException | Error error) { controller.closeAsync(); throw error; }
        });
    }
    private static void show(SimulationController controller) {
        var frame = new JFrame("T46 — Archivos de solicitudes");
        frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        var content = new JPanel(new BorderLayout(12,12));
        content.setBorder(BorderFactory.createEmptyBorder(16,16,16,16));
        content.add(new JLabel("Configuración: 2 elevadores, 8 pisos. Cargar no inicia movimientos."),BorderLayout.NORTH);
        var messages = new JTextArea(10,72);
        messages.setEditable(false); messages.setLineWrap(true); messages.setWrapStyleWord(true);
        messages.setText("RESET. Ningún lote preparado.\n");
        content.add(new JScrollPane(messages),BorderLayout.CENTER);
        var load = new JButton("Cargar archivo");
        var close = new JButton("Cerrar");
        var handler = RequestImportHandler.forDialog(controller,frame,result -> {
            messages.append(result.detail()+"\n");
            if (result.outcome() == RequestImportHandler.Outcome.FINISHED) { result.imported().ifPresent(imported -> {
                for(var diagnostic:imported.diagnostics()) {
                    messages.append((diagnostic.lineNumber().isPresent()?"Línea "+diagnostic.lineNumber().getAsInt()+": ":"")
                            +diagnostic.message()+"\n");
                    diagnostic.cause().ifPresent(cause -> messages.append("Causa: "+cause.getClass().getSimpleName()+"\n"));
                }
            }); }
            messages.append("Lote preparado actual: "+controller.preparedBatch().map(b->b.requests().size()).orElse(0)+" solicitudes.\n");
        },error -> messages.append("No se pudo completar la selección: "+error.getMessage()+"\n"));
        load.addActionListener(event -> handler.requestImport());
        var closing = new WindowCloseHandler(controller::closeAsync,
                ()->{load.setEnabled(false);close.setEnabled(false);messages.append("Cerrando...\n");},
                frame::dispose,error->{close.setEnabled(true);messages.append("No se confirmó el cierre: "+error.getMessage()+"\n");});
        close.addActionListener(event -> closing.requestClose());
        frame.addWindowListener(new WindowAdapter(){@Override public void windowClosing(WindowEvent event){closing.requestClose();}});
        var buttons = new JPanel();buttons.add(load);buttons.add(close);content.add(buttons,BorderLayout.SOUTH);
        frame.setContentPane(content);frame.pack();frame.setLocationRelativeTo(null);frame.setVisible(true);
    }
}
```

## src/test/java/com/elevatormanager/io/ReadFailureScenarios.java

```java
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
```

## src/test/java/com/elevatormanager/io/ReadFailureTest.java

```java
package com.elevatormanager.io;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Adaptadores JUnit de los escenarios T46. */
@Timeout(60)
class ReadFailureTest {
    @Test void validCatalogFilesMatchTheirConfigurations() throws Exception {
        ReadFailureScenarios.validCatalogFilesMatchTheirConfigurations();
    }
    @Test void invalidCatalogFilesMatchDiagnosticsAndLines() throws Exception {
        ReadFailureScenarios.invalidCatalogFilesMatchDiagnosticsAndLines();
    }
    @Test void intentionalBytesArePreserved() throws Exception {
        ReadFailureScenarios.intentionalBytesArePreserved();
    }
    @Test void repeatedFixtureKeepsOrderAndAllEntries() throws Exception {
        ReadFailureScenarios.repeatedFixtureKeepsOrderAndAllEntries();
    }
    @Test void deniedOpenPreservesOriginalCause() throws Exception {
        ReadFailureScenarios.deniedOpenPreservesOriginalCause();
    }
    @Test void securityFailureAtOpenIsReported() throws Exception {
        ReadFailureScenarios.securityFailureAtOpenIsReported();
    }
    @Test void partialReadFailureClosesOwnedStreamAndRejectsBatch() throws Exception {
        ReadFailureScenarios.partialReadFailureClosesOwnedStreamAndRejectsBatch();
    }
    @Test void closeFailureRejectsOtherwiseValidRead() throws Exception {
        ReadFailureScenarios.closeFailureRejectsOtherwiseValidRead();
    }
    @Test void primaryReadErrorRetainsSuppressedCloseError() throws Exception {
        ReadFailureScenarios.primaryReadErrorRetainsSuppressedCloseError();
    }
    @Test void sizeLimitRejectionAlsoClosesOwnedStream() throws Exception {
        ReadFailureScenarios.sizeLimitRejectionAlsoClosesOwnedStream();
    }
    @Test void sameReaderRecoversAfterTransientDeniedOpen() throws Exception {
        ReadFailureScenarios.sameReaderRecoversAfterTransientDeniedOpen();
    }
    @Test void independentDeniedReadsDoNotShareResultState() throws Exception {
        ReadFailureScenarios.independentDeniedReadsDoNotShareResultState();
    }
}
```

## src/test/java/com/elevatormanager/ui/FileSelectionScenarios.java

```java
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
```

## src/test/java/com/elevatormanager/ui/FileSelectionTest.java

```java
package com.elevatormanager.ui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Adaptadores JUnit de los escenarios T46. */
@Timeout(60)
class FileSelectionTest {
    @Test void cancellingSelectionPreservesBatchAndReceipt() throws Exception {
        FileSelectionScenarios.cancellingSelectionPreservesBatchAndReceipt();
    }
    @Test void approvedSelectionImportsAndCallbacksRunOnEdt() throws Exception {
        FileSelectionScenarios.approvedSelectionImportsAndCallbacksRunOnEdt();
    }
    @Test void invalidSelectionPreservesBatchAndHandlerCanRetry() throws Exception {
        FileSelectionScenarios.invalidSelectionPreservesBatchAndHandlerCanRetry();
    }
    @Test void missingSelectionPreservesBatchAndAllowsRecovery() throws Exception {
        FileSelectionScenarios.missingSelectionPreservesBatchAndAllowsRecovery();
    }
    @Test void emptySelectedFileIsRejectedInsteadOfCancellation() throws Exception {
        FileSelectionScenarios.emptySelectedFileIsRejectedInsteadOfCancellation();
    }
    @Test void requestOutsideResetDoesNotOpenSelector() throws Exception {
        FileSelectionScenarios.requestOutsideResetDoesNotOpenSelector();
    }
    @Test void requestingOffEdtIsRejected() throws Exception {
        FileSelectionScenarios.requestingOffEdtIsRejected();
    }
    @Test void selectorFailureIsReportedOnEdt() throws Exception {
        FileSelectionScenarios.selectorFailureIsReportedOnEdt();
    }
    @Test void nullSelectionIsReportedWithoutMutatingBatch() throws Exception {
        FileSelectionScenarios.nullSelectionIsReportedWithoutMutatingBatch();
    }
    @Test void stateChangeDuringDialogIsRevalidatedByController() throws Exception {
        FileSelectionScenarios.stateChangeDuringDialogIsRevalidatedByController();
    }
    @Test void nestedModalInvocationDoesNotOpenAnotherSelector() throws Exception {
        FileSelectionScenarios.nestedModalInvocationDoesNotOpenAnotherSelector();
    }
    @Test void delayedResultIsMarkedStaleBeforeVisualApplication() throws Exception {
        FileSelectionScenarios.delayedResultIsMarkedStaleBeforeVisualApplication();
    }
}
```

## src/test/java/com/elevatormanager/simulation/ImportReadErrorScenarios.java

```java
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
```

## src/test/java/com/elevatormanager/simulation/ImportReadErrorTest.java

```java
package com.elevatormanager.simulation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Adaptadores JUnit de los escenarios T46. */
@Timeout(60)
class ImportReadErrorTest {
    @Test void catalogErrorsPreserveBatchAndSessionRecovers() throws Exception {
        ImportReadErrorScenarios.catalogErrorsPreserveBatchAndSessionRecovers();
    }
    @Test void accessDeniedPreservesBatchAndSamePathCanRecover() throws Exception {
        ImportReadErrorScenarios.accessDeniedPreservesBatchAndSamePathCanRecover();
    }
    @Test void partialReadFailureKeepsPreparedAndNextImportWorks() throws Exception {
        ImportReadErrorScenarios.partialReadFailureKeepsPreparedAndNextImportWorks();
    }
    @Test void cancellingSelectorDoesNotCancelAnotherActiveRead() throws Exception {
        ImportReadErrorScenarios.cancellingSelectorDoesNotCancelAnotherActiveRead();
    }
    @Test void readErrorDoesNotPreventResetAndFreshImport() throws Exception {
        ImportReadErrorScenarios.readErrorDoesNotPreventResetAndFreshImport();
    }
    @Test void selectedFileMayDisappearBeforeReadWithoutBreakingSession() throws Exception {
        ImportReadErrorScenarios.selectedFileMayDisappearBeforeReadWithoutBreakingSession();
    }
    @Test void repeatedErrorsAndRecoveryReleaseSessionThreads() throws Exception {
        ImportReadErrorScenarios.repeatedErrorsAndRecoveryReleaseSessionThreads();
    }
    @Test void everyValidDistributedExampleRunsWithRealActors() throws Exception {
        ImportReadErrorScenarios.everyValidDistributedExampleRunsWithRealActors();
    }
}
```
