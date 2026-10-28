# T42 — Completar eventos obligatorios y errores

- **Fecha:** 2026-10-28, Guatemala.
- **Fase:** 7 — Implementar logs y carga de solicitudes.
- **Base:** T41 validada por David, commit `7cc0c03`, 16 pruebas específicas, 434 acumuladas y BUILD SUCCESS; LoggingDemo correcto.
- **Estado:** cerrada. David confirmó 24 pruebas específicas, 458 acumuladas, BUILD SUCCESS, EventLoggingDemo terminado con retorno al prompt y commit `ae5a1bf`. Esta ficha conserva el contenido y comandos de la entrega original.

## Resultado

Los eventos físicos existentes ahora tienen una ruta de logging de producción: cabinas → TraceMailbox → hilo SessionLog → LoggerFactory. El manejador publica en esa misma cola sus decisiones y los cambios reales de seguimiento de llamadas. Las publicaciones adicionales solo encolan datos; no hacen I/O ni consumen los canales existentes. El consumidor conserva los hechos de épocas anteriores, necesarios para reconstruir resets y cancelaciones.

| Hecho del motor | Registro de cabina |
| --- | --- |
| DEPARTED, dirección UP/DOWN | accion=SUBIENDO / accion=BAJANDO; piso dejado y siguiente piso. |
| ARRIVED | accion=LLEGADA; piso ya alcanzado. |
| STOP_STARTED / STOP_ENDED | accion=PARADA_RECOGER_DEJAR / FIN_PARADA. |
| DIRECTION_CHANGED | accion=CAMBIO_DIRECCION; dirección nueva del snapshot. |
| RESET_STARTED / RESET_COMPLETED | Inicio y final del retorno físico; modo y época. |
| COMMAND_ACCEPTED / COMMAND_COMPLETED | Admisión y servicio como hechos diferentes, con comando y UUID canónico. |
| COMMAND_CANCELLED / COMMAND_REJECTED | Cancelación y rechazo sin afirmar atención. |
| WORKER_FAILED / WORKER_TERMINATED | Fallo con causa y nivel SEVERE, o terminación normal. |

Se registran también los demás tipos de ExecutionEvent, incluidos duplicados, replays, Stop y reanudación. No se fabrican llegadas al cerrar en movimiento ni se convierten cancelaciones por reset en servicios.

## Registro del manejador

manejador.log utiliza la marca [manejador], y los archivos de cabina siguen siendo exclusivos de su ID. Cada decisión incluye resultado, UUID de solicitud, época, piso, sentido, cabina, comando, hilo productor y motivo de selección. La decisión DISPATCHED confirma envío al buzón; el cambio ASSIGNED confirma admisión y SERVED confirma servicio. Se registran también estados RECEIVED, CANCELLED y UNCONFIRMED cuando ocurren. Los alias de llamadas duplicadas conservan la identidad canónica.

Los reintentos PENDING son decisiones nuevas y llevan secuencias diferentes; pueden aparecer repetidamente mientras no exista opción de despacho. No son duplicaciones de handlers. Cada snapshot de seguimiento se registra solo si cambió respecto al publicado previamente. Inicio, reset, terminación y fallo del manejador tienen registros propios; el fallo conserva la excepción original.

## Identificación y tiempo

La fecha ISO-8601 con Z corresponde a la publicación en la cola de logging, no a una reconstrucción de la hora civil de la transición. El snapshot conserva nanos del reloj monotónico del actor, su revisión, piso, sentido, fase y modo. La combinación sesión + cabina + secuencia identifica el evento; sesión + secuencia identifica entradas del manejador. Las secuencias se mantienen durante resets, y el UUID de sesión diferencia reinicios sobre archivos en append.

No se impone un orden físico global entre actores. El consumidor respeta el orden de encolado y cada cabina conserva su orden de publicación. Archivo y consola reciben el mismo LogRecord con la misma fecha y texto. Los saltos de línea de mensajes se escapan; las excepciones conservan trazas multilínea.

## Composición y propiedad

Los constructores originales siguen disponibles y no crean archivos ni un hilo de logging automáticamente. El código de producción puede abrir SessionLog y pasarlo al nuevo constructor del controlador, como hace el programa completo EventLoggingDemo incluido en los fuentes. La ventana mínima conserva su composición anterior; aún no configura una sesión de simulación.

SessionLog.open y SessionLog.close rechazan el EDT, porque apertura, drenaje y cierre pueden esperar disco. La construcción correcta de SimulationController con un SessionLog compatible transfiere su propiedad. No se admite compartir el mismo SessionLog entre controladores. Si la construcción falla antes de transferir propiedad, el llamador debe cerrar el recurso.

El controlador cierra primero sus actores y después SessionLog. Este sella la cola, drena hasta un marcador FIFO, termina su consumidor y cierra LoggerFactory. Se conservan los eventos finales y las épocas anteriores. close es idempotente; una publicación tardía o una barrera después del sellado se rechazan expresamente. flushAsync devuelve una barrera de observación que solo cubre entradas previamente encoladas; no obliga al motor a producir un evento que aún no ocurrió.

La cola no está acotada y no descarta trazas ante disco lento. Puede crecer; no es una garantía de durabilidad frente a cierre abrupto, agotamiento de memoria o caída de la JVM. El registro se escribe desde un hilo propio; los actores no esperan a que el consumidor confirme cada publicación.

## Errores e incidencias

LoggerFactory instala un ErrorManager propio por destino antes de configurar su codificación. Captura errores de escritura, flush, formato y cierre reportados por java.util.logging. Conserva la primera incidencia con fecha, actor, destino, código y causa, y cuenta todos los reportes posteriores. Ese contador cuenta incidencias de handlers, no registros perdidos.

La consola verifica también PrintStream.checkError: System.err y otros PrintStream pueden ocultar IOException. La primera incidencia emite un aviso ERROR_LOGGING directamente por System.err, sin volver a usar los loggers ni el destino configurable averiado. Si también falla ese canal de emergencia, la incidencia permanece consultable por API. No se recurre a recursión de logging ni se promete recuperar la escritura perdida.

SessionLog.ioFailure expone la incidencia estructurada; SessionLog.failure y SimulationController.failure permiten conocer la degradación durante la ejecución. Fallos de procesamiento del consumidor también quedan conservados y emiten ERROR_CONSUMIDOR_LOGGING. Una barrera detecta el fallo y completa excepcionalmente.

La política mantiene la simulación activa mientras informa la incidencia: no provoca un reset ni una llegada artificial. Al cerrar se intentan todas las liberaciones y se sueltan los locks, pero el futuro de cierre completa excepcionalmente si hubo degradación. El controlador no afirma CLOSED exitoso; su estado permanece CLOSING y conserva failure. La causa y sus suprimidas permiten distinguir recursos liberados de una sesión con historial incompleto. No hay reintento automático ni reinicio de una sesión fallida.

Una falla durante inicialización rechaza la apertura y libera la configuración parcial. Los archivos existentes no se borran. La exclusión por .logging.lock y las reglas de append de T41 se conservan; el archivo de coordinación vacío puede permanecer tras el cierre. Los FileLock requieren soporte del sistema de archivos y no coordinan a herramientas externas que ignoren ese protocolo.

## Pruebas

Los 24 escenarios nuevos están en SessionLoggingScenarios y sus 24 adaptadores JUnit en SessionLoggingTest. Incluyen hechos físicos, decisiones y confirmaciones, alias, reset en movimiento, continuidad de secuencias, drenaje, independencia de canales, cierre en viaje, sesiones sucesivas y fallos de escritura, PrintStream, flush, cierre, inicialización y procesamiento del consumidor.

El fallo de archivo se inyecta en un StreamHandler real mediante un punto de prueba accesible solo al paquete; se evita depender de /dev/full o permisos específicos de Windows. Los errores de consola y cierre también usan streams controlados. El caso WORKER_FAILED inyecta un evento válido con su causa para verificar su presentación; el fallo del manejador sí se provoca en un actor real mediante un generador de comandos inválido.

Las pruebas esperan eventos, futuros y estados reales con plazos; no deducen estados a partir de dormir un tiempo fijo. El sondeo del estado SERVED observa la confirmación real del manejador. Las pruebas que capturan System.err requieren la ejecución secuencial predeterminada del proyecto; no habilitar ejecución paralela de clases sin aislar antes esos recursos globales.

## Instalación y validación en Windows

El ZIP contiene el proyecto fuente completo, con pom.xml en la raíz, rutas Maven, todos los .java y documentación .md. Excluye .git, target, registros generados y archivos de bloqueo. No reemplaza el historial Git local.

```powershell
Set-Location 'D:\Users\David\Desktop\workspace\java\elevator-manager'
git status --short
Expand-Archive -LiteralPath "$HOME\Downloads\T42-eventos-obligatorios-y-errores.zip" -DestinationPath . -Force
mvn -Dtest=SessionLoggingTest test
mvn "-Dtest=SessionLoggingTest,LoggingTest,OrderlyShutdownTest,LifecycleRaceTest" test
mvn clean verify
java -cp .\target\classes com.elevatormanager.diagnostics.EventLoggingDemo
Get-Content .\logs\t42\elevador1.log
Get-Content .\logs\t42\elevador2.log
Get-Content .\logs\t42\manejador.log
```

Se esperan 24 específicas, 96 seleccionadas y 458 acumuladas, sin fallos, errores u omisiones, con BUILD SUCCESS. Las pruebas de errores imprimen avisos intencionales ERROR_LOGGING y ERROR_CONSUMIDOR_LOGGING; el resultado de Maven y las aserciones distinguen esas pruebas de un fallo de la suite. Confirmar cifras reales antes de cerrar T42.

La muestra utiliza dos cabinas y un manejador reales, provoca subida y bajada, dos destinos internos, una llamada externa y reset global, y espera el cierre completo. Deben observarse los cinco tipos obligatorios y la decisión del manejador, y volver al prompt. Los archivos están en append: una segunda ejecución agrega otra sesión con UUID diferente. La cantidad de eventos de cada cabina puede variar según la cabina elegida; la muestra no fija un despacho artificial.

El diagnóstico anterior LoggingDemo sigue disponible para comprobar solamente los destinos de T41. El JAR normal sigue abriendo la ventana mínima; la muestra T42 se ejecuta por su clase indicada arriba.

## Commit sugerido

Tras validar y revisar los resultados, marcar T42 y actualizar el estado del plan con las cifras reales. Incorporar solo los archivos de esta tarjeta:

```powershell
$archivosT42 = @(
    'src/main/java/com/elevatormanager/logging/LogFailure.java',
    'src/main/java/com/elevatormanager/logging/TraceMailbox.java',
    'src/main/java/com/elevatormanager/logging/SessionLog.java',
    'src/main/java/com/elevatormanager/logging/LoggerFactory.java',
    'src/main/java/com/elevatormanager/logging/EventFormatter.java',
    'src/main/java/com/elevatormanager/simulation/ElevatorWorker.java',
    'src/main/java/com/elevatormanager/simulation/SimulationController.java',
    'src/main/java/com/elevatormanager/dispatch/ManagerWorker.java',
    'src/main/java/com/elevatormanager/diagnostics/EventLoggingDemo.java',
    'src/test/java/com/elevatormanager/logging/SessionLoggingScenarios.java',
    'src/test/java/com/elevatormanager/logging/SessionLoggingTest.java',
    'docs/01-analisis-y-plan.md',
    'docs/03-arquitectura.md',
    'docs/implementacion/T41-logging-por-elevador.md',
    'docs/comprobaciones/T41-validacion-local.md',
    'docs/implementacion/T42-eventos-obligatorios-y-errores.md',
    'docs/implementacion/T42-codigo-completo.md',
    'docs/comprobaciones/T42-validacion-local.md'
)
git diff --check
git add -- $archivosT42
git diff --cached --check
git diff --cached --stat
git commit -m "T42: completar eventos obligatorios y errores"
git status --short
```

No se crea un commit automáticamente en esta entrega. La siguiente tarjeta, después de confirmar T42, es T43: definir y documentar el formato del archivo de solicitudes.
