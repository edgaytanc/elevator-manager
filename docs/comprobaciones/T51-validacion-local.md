# T51 — Validación local

> Cierre confirmado al iniciar T52: 40 específicas, 144 combinadas y 722 acumuladas, BUILD SUCCESS, commit `ff29a67`, árbol limpio y capturas del visor en RESET/RUNNING con 112 registros. El informe siguiente conserva el estado original de entrega.

- **Fecha:** 2026-10-28, Guatemala.
- **Base:** T50, commit `1d3378b`, 682 pruebas Maven satisfactorias, ejecución y capturas confirmadas por David.
- **Entorno:** Linux, OpenJDK 17.0.20, headless; Maven no instalado.
- **Resultado:** 32 grupos independientes con salida 0, 677 escenarios (637 anteriores y 40 nuevos).

## Compilación y alcance

Se utilizó JavaCompiler del módulo jdk.compiler con `--release 17 -encoding UTF-8`, desde las fuentes actuales a un directorio externo nuevo para T51. Se excluyeron adaptadores *Test.java que requieren dependencias JUnit. src/main/resources estuvo en el classpath. No se utilizaron binarios del target del usuario ni del ZIP adjunto.

Los 677 escenarios independientes no equivalen a una ejecución de las 722 pruebas JUnit esperadas. Los adaptadores JUnit, Maven y JFrame nativo permanecen pendientes en Windows. No se modificaron POM ni versiones de dependencias.

| Grupo | Salida | Duración s |
| --- | ---: | ---: |
| DirectionPickupScenarios | 0 | 9.31 |
| ElevatorSelectionScenarios | 0 | 1.534 |
| ManagerWorkerScenarios | 0 | 1.182 |
| PendingConfirmationScenarios | 0 | 1.652 |
| PickupTimeEstimatorScenarios | 0 | 0.202 |
| ScalabilityScenarios | 0 | 4.569 |
| ReadFailureScenarios | 0 | 0.097 |
| RequestFileScenarios | 0 | 0.247 |
| LoggingScenarios | 0 | 0.363 |
| SessionLoggingScenarios | 0 | 1.912 |
| ModelRulesScenarios | 0 | 0.117 |
| CabinResetScenarios | 0 | 7.347 |
| ConcurrentAdmissionScenarios | 0 | 0.191 |
| DirectionalAgendaScenarios | 0 | 3.426 |
| ElevatorDwellScenarios | 0 | 0.669 |
| ElevatorMovementScenarios | 0 | 0.194 |
| ElevatorWorkerScenarios | 0 | 0.168 |
| ExecutionEventScenarios | 0 | 0.926 |
| GlobalResetScenarios | 0 | 7.822 |
| ImportReadErrorScenarios | 0 | 1.217 |
| LifecycleRaceScenarios | 0 | 2.877 |
| OrderlyShutdownScenarios | 0 | 1.572 |
| ParallelTimingScenarios | 0 | 4.647 |
| RequestImportScenarios | 0 | 1.703 |
| SimulationControllerScenarios | 0 | 1.538 |
| StopContinuationScenarios | 0 | 5.526 |
| CabinPanelsScenarios | 0 | 4.5 |
| ConfigurationFormScenarios | 0 | 1.252 |
| EventViewerScenarios | 0 | 1.976 |
| FileSelectionScenarios | 0 | 1.232 |
| HallCallsScenarios | 0 | 2.464 |
| SystemControlsScenarios | 0 | 4.798 |

## Evidencia nueva

Los 40 escenarios cubren búfer vacío, secuencias/cursor, límites de cantidad y caracteres, recorte individual/metadatos, saltos de línea, listas inmutables, argumentos inválidos, gaps contados una sola vez, lectores independientes, cuatro productores concurrentes, rechazo de null sin mutación parcial, integridad de archivos con 1200 eventos, registro largo completo en archivo, excepción completa/resumen, eventos reales de manejador/cabina, independencia de awaitEvent, historial entre épocas, limpieza sin borrar archivos/búfer, cursor tras limpiar, límite por refresco, líneas/documento acotados, recortes comunicados, ausencia de duplicados, panel sin sesión/logging, separación de sesiones, seguimiento desactivado sin detener ingesta, EDT, navegación directa de 25 cabinas, rechazo de IDs inválidos, geometría para distintos escritorios, composición real con logging y consulta después de cerrar.

SessionLog, FileHandler, trazas, controlador, manejador y cabinas son reales en los escenarios de integración. La prueba de desbordamiento cuenta 1200 marcadores en manejador.log mientras el búfer conserva como máximo 500 entradas. La de registro largo comprueba un sufijo final en el archivo y su ausencia de la copia truncada. Se espera flushAsync fuera del EDT antes de comparar bytes/texto del archivo; no se da por hecho que una copia visual sustituya la escritura.

Los eventos de dominio se generan con solicitudes reales y se observan en el visor. Las pruebas de retención publican también estados de logging identificados con T51_* para producir volúmenes reproducibles sin viajes artificiales. No se alteran permisos ni destinos permanentes del proyecto. Los directorios de logs de prueba se eliminan después del cierre; los controladores y el pool de productores se terminan.

La comprobación de Seguir últimos verifica que desactivarlo conserva la ingesta y el archivo; no acredita por sí sola el comportamiento de rueda/selección del ratón en Windows. Las pruebas de geometría son funciones puras que no crean JFrame. La navegación de 25 cabinas vincula snapshots reales y selecciona la pestaña 25 sin enviar trabajo a ella.

## Revisión visual y paquete

Se inspeccionaron renders de SystemControlPanel a 1180×820 y 720×480, con logging y solicitudes reales en sesión temporal. En el grande se ven configuración, cabina con destino 8 amarillo, recogida 4/Subir y registros de ambos actores. En el pequeño permanecen accesibles controles/visor; el contenido fuera del área inicial tiene barras de desplazamiento. Los divisores permiten redistribuir espacio. El render no incluye decoración nativa ni garantiza foco, DPI, rueda, área útil real o X de JFrame en Windows.

Se verificó `git diff --check`. El Markdown de código completo se comparó con los nueve Java nuevos/modificados; no contiene fragmentos omitidos. Se verificaron CRC y bytes de cada entrada del ZIP. Incluye todas las fuentes Java, recursos previos, nuevas pruebas y documentación; excluye .git, target y logs generados.

## Pendiente en Windows

```powershell
mvn "-Dtest=EventViewerTest" test
mvn "-Dtest=EventViewerTest,CabinPanelsTest,HallCallsTest,SystemControlsTest,ConfigurationFormTest" test
mvn clean verify
java -jar .\target\elevator-manager-0.1.0-SNAPSHOT.jar
```

Se esperan 40 específicas, 144 combinadas y 722 acumuladas, sin fallos, errores u omisiones y BUILD SUCCESS. Seguir el [recorrido visual T51](../implementacion/T51-visor-eventos-y-tamano.md). T51 permanece pendiente de resultados de David. T52 y T53 siguen pendientes; no se cierra fase 8.

Commit sugerido: `T51: agregar visor de eventos acotado y adaptar ventana`.
