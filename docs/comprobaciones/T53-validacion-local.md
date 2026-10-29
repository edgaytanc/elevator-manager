# T53 — Validación local

- **Fecha:** 2026-10-29 UTC (2026-10-28 en Guatemala).
- **Base confirmada:** T52, commit 6eec38e, 754 pruebas Maven satisfactorias por David.
- **Entorno local:** Linux, OpenJDK 17.0.20, sin escritorio ni Maven instalado.
- **Compilación:** JavaCompiler, `--release 17 -encoding UTF-8`, todas las fuentes de producción y escenarios a un directorio externo. Sin reutilizar target; adaptadores JUnit excluidos de esa compilación.
- **Resultado:** 34 grupos independientes, 719 escenarios (709 anteriores + 10 de T53), salida 0 en todos. Esto no equivale a ejecutar las 764 pruebas JUnit esperadas.

## Regresión independiente

| Grupo | Salida | Duración s |
| --- | ---: | ---: |
| DirectionPickupScenarios | 0 | 9.319 |
| ElevatorSelectionScenarios | 0 | 1.545 |
| ManagerWorkerScenarios | 0 | 1.212 |
| PendingConfirmationScenarios | 0 | 1.658 |
| PickupTimeEstimatorScenarios | 0 | 0.179 |
| ScalabilityScenarios | 0 | 4.525 |
| ReadFailureScenarios | 0 | 0.119 |
| RequestFileScenarios | 0 | 0.274 |
| LoggingScenarios | 0 | 0.386 |
| SessionLoggingScenarios | 0 | 1.935 |
| ModelRulesScenarios | 0 | 0.109 |
| CabinResetScenarios | 0 | 7.329 |
| ConcurrentAdmissionScenarios | 0 | 0.201 |
| DirectionalAgendaScenarios | 0 | 3.437 |
| ElevatorDwellScenarios | 0 | 0.68 |
| ElevatorMovementScenarios | 0 | 0.236 |
| ElevatorWorkerScenarios | 0 | 0.183 |
| ExecutionEventScenarios | 0 | 0.957 |
| GlobalResetScenarios | 0 | 7.863 |
| ImportReadErrorScenarios | 0 | 1.196 |
| LifecycleRaceScenarios | 0 | 3.082 |
| OrderlyShutdownScenarios | 0 | 1.567 |
| ParallelTimingScenarios | 0 | 4.64 |
| RequestImportScenarios | 0 | 1.728 |
| SimulationControllerScenarios | 0 | 1.529 |
| StopContinuationScenarios | 0 | 5.506 |
| CabinPanelsScenarios | 0 | 4.527 |
| ConfigurationFormScenarios | 0 | 1.244 |
| EdtSafetyScenarios | 0 | 3.09 |
| EventViewerScenarios | 0 | 2.01 |
| FileSelectionScenarios | 0 | 1.2 |
| GuiJourneyScenarios | 0 | 13.016 |
| HallCallsScenarios | 0 | 2.115 |
| SystemControlsScenarios | 0 | 4.728 |

## Evidencia del recorrido

```text
T53_JOURNEY cabins=2 configure/start/hall/cabin/stop/resume/reset/import/start/close=OK
T53_JOURNEY cabins=1 configure/start/hall/cabin/stop/resume/reset/import/start/close=OK
T53: 10 escenarios integrados OK
```

Después de la regresión se reforzó la correlación del lote: todas sus instrucciones deben estar ENQUEUED y cada destino interno importado debe tener COMMAND_COMPLETED con su identidad en el archivo de su cabina. Se recompilaron las fuentes y se repitieron los diez escenarios con esas comprobaciones finales; resultado satisfactorio. Las repeticiones no se suman al número de escenarios.

La prueba de dos cabinas carga el ejemplo 23 del proyecto; la de una cabina crea un lote temporal compatible. Ambas confirman preparación sin movimiento, consumo del lote en Start, ausencia de redistribución en continuación, atención de llamadas y apagado de luces. La cancelación y los errores se ejercitan desde Cargar archivo. Los archivos de log se leen después de confirmar cierre, antes de eliminar el directorio temporal.

Las acciones usan widgets Swing en el EDT y el temporizador normal de actualización. El motor se consulta como oráculo, sin llamar directamente a sus operaciones de negocio para completar el recorrido. La apertura real usa archivos temporales y una copia de consola silenciada en el fixture. No se cambia el logging de producción. Se comprueban actores y capturador terminados al cerrar.

## Revisión del cambio

No se modifica código de producción ni pom.xml. Se incluyen dos fuentes de prueba, ejemplo 23, código completo y guion de aceptación. `git diff --check` sin errores. Se revisa que el ZIP contenga todas las fuentes y que sus entradas coincidan byte por byte con el proyecto, sin .git, target ni logs. El número de anotaciones @Test en el proyecto es 764.

## Validación pendiente en Windows

```powershell
mvn "-Dtest=GuiJourneyTest" test
mvn "-Dtest=GuiJourneyTest,EdtSafetyTest,EventViewerTest,CabinPanelsTest,HallCallsTest,SystemControlsTest,ConfigurationFormTest" test
mvn clean verify
java -jar .\target\elevator-manager-0.1.0-SNAPSHOT.jar
```

Se esperan 10 específicas, 186 de interfaz y 764 totales. Seguir [T53-recorrido-gui.md](T53-recorrido-gui.md) y aportar resultados/capturas/logs. Headless no demuestra foco, interacción física con ratón/teclado, diálogo del sistema, fluidez nativa, X ni redimensionado real. T53 y la fase 8 quedan pendientes de esa aceptación.

## Commit sugerido

```powershell
git diff --check
git add .
git diff --cached --check
git commit -m "T53: validar recorrido completo desde GUI"
git status --short
```
