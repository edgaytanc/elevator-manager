# T41 — Configurar logging por elevador

- **Fecha:** 2026-10-28, Guatemala.
- **Fase:** 7 — Implementar logs y carga de solicitudes.
- **Base:** ZIP actualizado, commit `f39eb3a`, árbol limpio. David confirmó cierre de fase 6; el contexto anterior reporta 418 pruebas Maven satisfactorias.
- **Estado:** cerrada. David confirmó 16 pruebas específicas, 434 acumuladas, BUILD SUCCESS, diagnóstico correcto y commit `7cc0c03`. Esta ficha conserva el alcance y los comandos de la entrega original T41; T42 incorpora el manejador y los eventos físicos.

## Alcance

Cada cabina tiene su Logger independiente con dos handlers propios: FileHandler UTF-8 en append y salida a consola. Los nombres son elevador1.log, elevador2.log, etc. No se usa el logger raíz para publicar ni se modifican sus handlers. Consultar loggerFor repetidamente devuelve el mismo objeto sin agregar handlers. La cantidad de cabinas es configurable, no está fijada en dos; el diagnóstico utiliza dos como muestra.

EventFormatter hereda de Formatter y produce fecha ISO-8601 UTC, nivel, ID y mensaje con parámetros resueltos. Los saltos de línea del mensaje se escapan; las excepciones conservan su traza a continuación. No se confunde este ID con el nombre del logger ni con el hilo productor. Los archivos no incluyen cabeceras XML.

FlushingConsoleHandler hereda de StreamHandler para vaciar cada publicación y conservar abierta la salida compartida. Serializa publicación y flush sobre el mismo OutputStream, de modo que mensajes grandes de distintas cabinas no mezclen sus fragmentos. La escritura es síncrona: no introduce colas ni consumidores adicionales. El orden entre cabinas depende del planificador y no representa un orden físico global.

## Propiedad y cierre

LoggerFactory implementa AutoCloseable y utiliza OwnedLogHandlers de T39. close desconecta, vacía y cierra los handlers; intenta todas las liberaciones incluso ante fallos, conserva las causas y admite llamadas repetidas. Después del cierre, loggerFor rechaza consultas y los loggers obtenidos antes ya no tienen los handlers propios conectados. El llamador debe terminar los productores antes de cerrar el logging.

Puede transferirse la propiedad al controlador con registerResource y debe comprobarse APPLIED antes de arrancar productores. Si devuelve REJECTED, la fábrica sigue siendo responsabilidad del llamador y debe cerrarse. El controlador termina sus actores antes de liberar los recursos; cualquier consumidor futuro de eventos deberá terminar y drenar antes de cerrar la fábrica. La prueba controllerOwnsAndReleasesLogging verifica el contrato con una sesión real.

Un lock de archivo .logging.lock impide que dos fábricas cooperantes usen el mismo directorio simultáneamente, incluso entre JVM en sistemas que soportan FileLock. Se resuelve la ruta real para compartir el bloqueo también mediante alias de ruta. El archivo de coordinación permanece vacío tras cerrar; lo que se libera es su bloqueo. No debe borrarse manualmente mientras haya sesiones activas. No es un registro de cabina. Los .lck temporales de FileHandler sí se liberan al cerrar.

Los porcentajes en rutas se escapan antes de pasarlos al patrón de FileHandler. Los errores de apertura se propagan y una configuración parcial libera sus recursos. Un fallo puede dejar archivos vacíos ya creados: no se borra contenido anterior. La exclusión protege únicamente a fábricas que respetan este protocolo; herramientas externas que abran directamente los mismos archivos no participan del contrato. Se recomienda almacenamiento local con soporte de locks.

## Límite de la tarjeta

T41 configura y verifica destinos y recursos. T42 conectará los eventos físicos de los actores y las decisiones del manejador, y completará la política de incidencias de escritura. No se declara que los eventos de movimiento ya estén registrados. La ventana mínima sigue sin crear una sesión configurada; ejecutar el JAR habitual conserva ese comportamiento.

LoggingDemo es una comprobación manual separada. Sus dos hilos publican cinco mensajes PRUEBA_LOGGING por cabina, terminan y después cierran los handlers. Esos mensajes no describen movimientos del simulador. Se utiliza append: repetir el diagnóstico agrega cinco registros más a cada archivo.

## Archivos de esta tarjeta

| Archivo | Función |
| --- | --- |
| src/main/java/com/elevatormanager/logging/LoggerFactory.java | Configuración y propiedad de recursos por sesión. |
| src/main/java/com/elevatormanager/logging/EventFormatter.java | Fecha, nivel, ID y texto legible. |
| src/main/java/com/elevatormanager/diagnostics/LoggingDemo.java | Diagnóstico manual con dos productores paralelos. |
| src/test/java/com/elevatormanager/logging/LoggingScenarios.java | 16 escenarios independientes. |
| src/test/java/com/elevatormanager/logging/LoggingTest.java | 16 adaptadores JUnit con límite externo de 60 s. |
| docs/01-analisis-y-plan.md | Cierre confirmado de fase 6 y avance T41. |
| docs/implementacion/T41-codigo-completo.md | Los cinco fuentes completos, sin elipsis. |
| docs/comprobaciones/T41-validacion-local.md | Evidencia y límites de validación. |
| docs/implementacion/T41-logging-por-elevador.md | Esta ficha y comandos de incorporación. |

## Validación en Windows

El ZIP contiene el proyecto fuente completo, con pom.xml en la raíz y rutas Maven conservadas; excluye .git, target y logs generados. Extraer sobre la raíz del proyecto local, conservando su .git. Antes de sobrescribir, comprobar que no hay cambios personales pendientes.

```powershell
Set-Location 'D:\Users\David\Desktop\workspace\java\elevator-manager'
git status --short
Expand-Archive -LiteralPath "$HOME\Downloads\T41-logging-por-elevador.zip" -DestinationPath . -Force
mvn -Dtest=LoggingTest test
mvn "-Dtest=LoggingTest,OrderlyShutdownTest,LifecycleRaceTest" test
mvn clean verify
java -cp .\target\classes com.elevatormanager.diagnostics.LoggingDemo
Get-Content .\logs\elevador1.log
Get-Content .\logs\elevador2.log
```

Resultados esperados: 16 específicas, 72 seleccionadas y 434 acumuladas, sin fallos, errores u omisiones y con BUILD SUCCESS. Son expectativas sobre la base de 418 pruebas; confirmar el resultado real. En un directorio nuevo el diagnóstico produce cinco registros por archivo y diez en consola. Cada archivo solo debe tener su ID, caracteres UTF-8 legibles y un registro por muestra. Una segunda ejecución debe terminar sin bloqueo y agregar cinco registros por cabina.

Las marcas de tiempo llevan Z porque se expresan en UTC; para Guatemala se resta seis horas. La fecha de documentación se expresa en Guatemala.

## Commit sugerido

Después de validar Maven y revisar los archivos, actualizar la casilla T41 y el estado del plan con los resultados reales. Solo entonces incorporar esta tarjeta:

```powershell
$archivosT41 = @(
    'src/main/java/com/elevatormanager/logging/LoggerFactory.java',
    'src/main/java/com/elevatormanager/logging/EventFormatter.java',
    'src/main/java/com/elevatormanager/diagnostics/LoggingDemo.java',
    'src/test/java/com/elevatormanager/logging/LoggingScenarios.java',
    'src/test/java/com/elevatormanager/logging/LoggingTest.java',
    'docs/01-analisis-y-plan.md',
    'docs/implementacion/T41-logging-por-elevador.md',
    'docs/implementacion/T41-codigo-completo.md',
    'docs/comprobaciones/T41-validacion-local.md'
)
git diff --check
git add -- $archivosT41
git diff --cached --check
git diff --cached --stat
git commit -m "T41: configurar logging por elevador"
git status --short
```

No se crea un commit automáticamente en esta entrega. Tras confirmar T41, la siguiente tarjeta es T42.
