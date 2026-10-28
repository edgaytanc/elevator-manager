# T45 — Integrar importación limitada a RESET

- **Fecha:** 2026-10-28, Guatemala.
- **Fase:** 7 — Implementar logs y carga de solicitudes.
- **Base:** T44 validada por David, commit `9ae324d`, 48 pruebas específicas y 506 acumuladas, BUILD SUCCESS.
- **Estado:** cerrada. David confirmó 40 pruebas específicas y 546 acumuladas, sin fallos, errores u omisiones, BUILD SUCCESS y commit `4f99454`. Esta ficha conserva el contenido de la entrega original. T46 completa los ejemplos y errores de lectura; la fase 7 aún no se declara cerrada.

## Resultado

SimulationController.importRequests(Path) conecta RequestFileReader con una sesión real. La llamada es apta para el EDT: agenda la captura del contexto y la lectura ocurre en un ejecutor I/O propio. El coordinador confirma el lote solo si sigue vigente la misma lectura, época, configuración y estado RESET, y no se ha solicitado cierre. El lector nunca confirma desde su hilo.

Importar no arranca cabinas ni manejador, no mueve elevadores y no consume IDs de comando. En RESET inicial los actores siguen NEW; después de un reset permanecen retenidos. Una confirmación correcta sustituye íntegramente el lote anterior. Los UUID nuevos se asignan por entrada en esa confirmación, y todas comparten un Instant de creación y la época vigente. Una nueva importación del mismo texto crea otras identidades.

## Estados, resultados y observación

| API o resultado | Significado |
| --- | --- |
| importRequests(Path) | Devuelve CompletableFuture<BatchImportResult>; null es un error de programación. |
| IMPORTED | Lote confirmado en RESET, todavía sin enviar ni ejecutar. |
| INVALID | Error de archivo o lector, con diagnósticos y causas; no sustituye el lote anterior. |
| REJECTED | Estado/contexto incompatible, configuración incorrecta o cierre. |
| SUPERSEDED | Otra lectura más reciente invalidó esta operación. |
| CANCELLED | Cancelación explícita mediante cancelImport. |
| preparedBatch() | Instantánea inmutable del lote confirmado que aún puede consumir Start. |
| lastBatchDispatch() | Informe inmutable del último intento único de envío, con línea, UUID y resultado. |
| isCurrent(BatchImportResult) | Filtro para respuestas diferidas: sesión, generación, época, estado y puerta de cierre. No autoriza una mutación. |

Solo RESET permite iniciar/confirmar una importación. RUNNING, STOPPING, STOPPED, RESETTING, CLOSING y CLOSED se rechazan antes de abrir el archivo. El mismo guard de estado excluye PAUSING/PAUSED cuando se implemente pausa; esos estados todavía no tienen un control público que permita provocarlos en esta versión.

Una respuesta IMPORTED puede volverse antigua antes de llegar a la interfaz. La vista debe consultar isCurrent al aplicarla y utilizar los snapshots actuales, sin mostrar un lote consumido como si aún estuviera preparado. El código no añade callbacks Swing ni modifica la ventana mínima.

## Política de lecturas simultáneas y cancelación

El ejecutor tiene un solo hilo y una cola de capacidad uno. Una nueva importación válida desde RESET invalida la petición anterior, completa su futuro como SUPERSEDED, cancela su tarea y elimina tareas canceladas de la cola. Solo se conserva una lectura en ejecución y, si esa aún no terminó, la última pendiente. No se crean hilos nuevos por cada pulsación ni una cola ilimitada de archivos.

La cancelación de la tarea solicita interrupción, pero un lector ya bloqueado puede ignorarla. Su resultado tardío se descarta por identidad/generación y no recupera la autoridad de confirmar, aunque el sistema haya vuelto a RESET. La lectura nueva puede esperar a que el lector anterior libere el único hilo; no se promete lectura inmediata frente a I/O que no responde.

cancelImport invalida la lectura vigente y conserva el lote ya confirmado. Si no hay lectura, no borra ese lote. La cancelación de un futuro visible con cancel(true) solo afecta a ese observador, como en los controles previos; no cancela la operación privada. Para cancelar trabajo se usa la API explícita. El selector gráfico de T46/interfaz aún no está conectado: cerrar un selector no se simula ejecutando lecturas vacías.

## Start y consumo único

Start efectivo desde RESET invalida cualquier lectura aún pendiente y usa el lote que ya estuviera confirmado. Si se está leyendo un reemplazo de un lote A y llega Start, se invalida esa lectura y se envía A; no se espera a un archivo todavía no confirmado.

El lote se marca consumido antes del primer envío. Se presentan sus solicitudes en orden de línea a submitNow, la misma ruta de validación y enrutamiento usada por submit manual: HALL al manejador y CABIN a su cabina. Los comandos se generan por el flujo normal y comparten el generador existente. No se deduplican líneas en el importador ni se cambia la planificación del motor.

BatchDispatchReport conserva un resultado por entrada. ENQUEUED confirma envío al buzón, no admisión ni servicio. Los comandos internos quedan correlacionados; la asignación externa ocurrirá en el manejador. Los actores pueden aceptar y atender en paralelo, en otro orden al del archivo.

Start repetido en RUNNING no vuelve a enviar. Stop y Start desde STOPPED continúan pendientes, sin reconstruir identidades ni reenviar el lote. Tras reset completo, los mismos actores pueden recibir otro lote de la nueva época; no se recrean hilos de cabina/manejador.

La atomicidad cubre la confirmación/reemplazo del lote, no la ejecución física de todo su contenido. Si llega cierre durante el bucle de envío, las entradas restantes pueden rechazarse. Si ocurre un fallo inesperado, se completa el informe con rechazos del resto y el controlador inicia cierre. No hay rollback de movimientos ni reintento automático del lote consumido.

## Reset, cierre y propiedad

Reset aceptado invalida lectura, lote preparado e informe anterior. Las respuestas de épocas anteriores no pueden sustituir un lote nuevo. Un reset rechazado por agotamiento de época/IDs no borra el lote como si hubiera sido aplicado.

Cerrar activa primero la puerta de cierre, invalida la lectura pendiente, borra el lote ejecutable y solicita apagado/interrupción del ejecutor I/O. El futuro de importación se resuelve sin esperar que una lectura retenida termine. El cierre general sí espera la terminación real del ejecutor, además de coordinador y actores, antes de liberar recursos de sesión y publicar CLOSED.

Si el I/O no responde a interrupción, el estado permanece CLOSING y awaitTermination puede devolver false. No se abandona un hilo no daemon ni se confirma liberación por timeout. Los archivos propios de RequestFileReader se cierran en su try-with-resources; los recursos de logging se cierran después de acabar productores y lector. awaitTermination rechaza esperas positivas desde el EDT, coordinador, cerrador o el propio hilo I/O.

Los errores de lectura habituales producen INVALID y conservan su causa; no convierten la sesión en fallida ni borran su lote anterior. El punto de inyección BatchLoader es accesible solo al paquete para pruebas de carreras, errores y configuraciones incompatibles; la API pública siempre usa RequestFileReader.

## Fuentes nuevos o modificados

| Fuente | Función |
| --- | --- |
| simulation/SimulationController.java | I/O asíncrono, contexto, confirmación, consumo y cierre. |
| simulation/ImportedRequestBatch.java | Lote confirmado, UUID, época, tiempo y correspondencia con descriptores. |
| simulation/BatchImportResult.java | Resultado de preparación y diagnósticos, ligado a sesión/generación. |
| simulation/BatchDispatchReport.java | Informe de envío completo y correlacionado por línea/UUID. |
| diagnostics/RequestImportDemo.java | Ejemplo ejecutable con parser, controlador, actores y logging reales. |
| simulation/RequestImportScenarios.java y RequestImportTest.java | 40 escenarios independientes y sus adaptadores JUnit. |

Las cuatro primeras rutas son relativas a src/main/java/com/elevatormanager; las pruebas pertenecen a src/test/java/com/elevatormanager/simulation. El [código completo](T45-codigo-completo.md) reproduce los siete fuentes íntegros. No cambia pom.xml ni los fuentes anteriores del parser o de los trabajadores.

## Instalación y validación en Windows

El ZIP contiene el proyecto fuente completo, con pom.xml en la raíz, todos los .java y documentación .md. Excluye .git, target y logs generados. Conservar el repositorio y los cambios personales locales antes de sobrescribir.

```powershell
Set-Location 'D:\Users\David\Desktop\workspace\java\elevator-manager'
git status --short
Expand-Archive -LiteralPath "$HOME\Downloads\T45-importacion-limitada-reset.zip" -DestinationPath . -Force
mvn -Dtest=RequestImportTest test
mvn "-Dtest=RequestImportTest,RequestFileTest,SimulationControllerTest,GlobalResetTest,OrderlyShutdownTest,LifecycleRaceTest" test
mvn clean verify
```

Se esperan 40 específicas, 191 seleccionadas y 546 acumuladas, sin fallos, errores ni omisiones y con BUILD SUCCESS. La selección suma 40 + 48 + 14 + 33 + 32 + 24; son expectativas sobre la base de 506, no una ejecución Maven local. Los avisos de error provocados por SessionLoggingTest siguen siendo intencionales en la suite completa.

## Muestra manual de importación real

```powershell
New-Item -ItemType Directory -Force .\logs\validacion-t45 | Out-Null
@'
# Dos elevadores y ocho pisos; no se mueven al importar
HALL 3 UP
CABIN 1 8
HALL 8 DOWN
CABIN 2 1
'@ | Set-Content -LiteralPath .\logs\validacion-t45\lote.txt -Encoding utf8
java -cp .\target\classes com.elevatormanager.diagnostics.RequestImportDemo .\logs\validacion-t45\lote.txt 2 8
$LASTEXITCODE
```

La muestra debe informar confirmación en RESET sin movimiento, enviar el lote una vez, confirmar sus servicios o alias activos, cerrar la sesión y devolver salida 0. Registra cabinas y manejador bajo logs/t45. Un archivo inválido se informa y devuelve salida 1 después de cerrar; argumentos incorrectos devuelven salida 2. La muestra configura movimientos de 30 ms y paradas de 10 ms y tiene un plazo de servicio de 30 s: es un diagnóstico para lotes pequeños, no una promesa para cualquier lote de 10,000 entradas.

Los repetidos internos se siguen por la identidad canónica de sus eventos; los externos, por el estado real de sus llamadas. La muestra no cuenta un ENQUEUED como servicio. La ventana habitual del JAR sigue siendo mínima y todavía no contiene el selector de importación.

## Commit sugerido

Después de validar y revisar, marcar T45 y actualizar el estado del plan con las cifras reales. Incorporar los dieciséis archivos de esta entrega:

```powershell
$archivosT45 = @(
    'src/main/java/com/elevatormanager/simulation/SimulationController.java',
    'src/main/java/com/elevatormanager/simulation/ImportedRequestBatch.java',
    'src/main/java/com/elevatormanager/simulation/BatchImportResult.java',
    'src/main/java/com/elevatormanager/simulation/BatchDispatchReport.java',
    'src/main/java/com/elevatormanager/diagnostics/RequestImportDemo.java',
    'src/test/java/com/elevatormanager/simulation/RequestImportScenarios.java',
    'src/test/java/com/elevatormanager/simulation/RequestImportTest.java',
    'docs/01-analisis-y-plan.md',
    'docs/03-arquitectura.md',
    'docs/06-pruebas.md',
    'docs/07-formato-archivo-solicitudes.md',
    'docs/implementacion/T44-parser-y-validacion-lote.md',
    'docs/comprobaciones/T44-validacion-local.md',
    'docs/implementacion/T45-importacion-limitada-reset.md',
    'docs/implementacion/T45-codigo-completo.md',
    'docs/comprobaciones/T45-validacion-local.md'
)
git diff --check
git add -- $archivosT45
git diff --cached --check
git diff --cached --stat
git commit -m "T45: integrar importacion limitada a RESET"
git status --short
```

No se crea un commit automáticamente en esta entrega. La siguiente tarjeta es T46: ejemplos y errores de lectura; no se declara terminada la fase 7 por completar T45.
