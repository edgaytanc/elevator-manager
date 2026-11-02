# T61 — Validación local de preparación

- **Fecha:** 2 de noviembre de 2026, Guatemala.
- **Base aceptada:** T60 `6883d66`, 24 pruebas específicas y 910 totales con BUILD SUCCESS, staging sin observaciones y Git limpio según salida de David. No se recrea ese commit en el árbol de preparación.
- **Entorno:** Linux, OpenJDK 17.0.20, Swing headless; sin Maven ni escritorio nativo.
- **Compilación limpia:** 72 fuentes de producción y 47 fuentes independientes de prueba/soporte con jdk.compiler, --release 17 y UTF-8, en clases externas a target. No se reutilizan clases del ZIP.
- **Específica:** 16 escenarios de VisualQualityScenarios correctos.
- **Regresión final:** 46 ejecutables correctos: 45 grupos Scenarios y ScalabilityEvaluation. No quedó ningún método main sin ejecutar.
- **Maven/JUnit:** los adaptadores dependientes de JUnit no se compilaron ni ejecutaron aquí. VisualQualityTest añade 16 casos; total Maven esperado 926. No se presenta esta ejecución como BUILD SUCCESS Maven.

## Regresión final

| Grupo | Salida | Duración s |
| --- | ---: | ---: |
| AnimationSynchronizationScenarios | 0 | 4.883 |
| BuildingPanelScenarios | 0 | 1.526 |
| CabinAnimationScenarios | 0 | 1.256 |
| CabinPanelsScenarios | 0 | 4.510 |
| CabinResetScenarios | 0 | 7.334 |
| ClockViewScenarios | 0 | 0.061 |
| ConcurrentAdmissionScenarios | 0 | 0.198 |
| ConfigurationFormScenarios | 0 | 1.232 |
| DirectionPickupScenarios | 0 | 9.300 |
| DirectionalAgendaScenarios | 0 | 3.424 |
| EdtSafetyScenarios | 0 | 3.068 |
| ElevatorDwellScenarios | 0 | 0.661 |
| ElevatorMovementScenarios | 0 | 0.183 |
| ElevatorSelectionScenarios | 0 | 1.514 |
| ElevatorWorkerScenarios | 0 | 0.164 |
| EventViewerScenarios | 0 | 2.006 |
| ExecutionEventScenarios | 0 | 0.927 |
| FileSelectionScenarios | 0 | 1.215 |
| GlobalPauseScenarios | 0 | 6.295 |
| GlobalResetScenarios | 0 | 7.813 |
| GuiJourneyScenarios | 0 | 12.564 |
| HallCallsScenarios | 0 | 2.209 |
| ImportReadErrorScenarios | 0 | 1.206 |
| LifecycleRaceScenarios | 0 | 2.970 |
| LoggingScenarios | 0 | 0.339 |
| ManagerWorkerScenarios | 0 | 1.187 |
| ModelRulesScenarios | 0 | 0.108 |
| OrderlyShutdownScenarios | 0 | 1.586 |
| ParallelTimingScenarios | 0 | 4.652 |
| PausableClockScenarios | 0 | 0.118 |
| PauseControlsScenarios | 0 | 5.781 |
| PauseCoordinatorScenarios | 0 | 0.096 |
| PausePhaseScenarios | 0 | 24.637 |
| PausePriorityScenarios | 0 | 3.870 |
| PendingConfirmationScenarios | 0 | 1.666 |
| PickupTimeEstimatorScenarios | 0 | 0.185 |
| ReadFailureScenarios | 0 | 0.088 |
| RequestFileScenarios | 0 | 0.229 |
| RequestImportScenarios | 0 | 1.702 |
| ScalabilityEvaluation | 0 | 6.010 |
| ScalabilityScenarios | 0 | 4.562 |
| SessionLoggingScenarios | 0 | 1.910 |
| SimulationControllerScenarios | 0 | 1.533 |
| StopContinuationScenarios | 0 | 5.368 |
| SystemControlsScenarios | 0 | 4.802 |
| VisualQualityScenarios | 0 | 1.905 |

## Revisión visual y recursos

Se comprobó raster de ejes adheridos tras desplazamiento en ambos sentidos, P=1, viewport reducido, fuente de 24 puntos e IDs largos. Se inspeccionó una imagen Java2D de 20/100 y 10000/100000 en el extremo inferior derecho. La inspección inicial mostró un resto de título parcialmente recortado junto al margen fijo; se corrigió omitiendo esos títulos y se añadió el oráculo de ausencia de texto parcial. Se recompilaron las fuentes y la regresión final utiliza esa corrección. No hubo fallos de los grupos finales.

Los casos nuevos comprueban decisión de repintado en pausa/reposo/subpíxel, vista oculta mediante RepaintManager, caché por columnas visibles, ausencia de hilos por fotograma, pulsos EDT e identidad/cierre de actores reales. La tabla de mediciones y líneas originales T61_THREADS/T61_PULSE/T61_RENDER está en T61-resultados-locales.md. Son medidas headless del pintor, no perfiles de heap/proceso ni FPS nativos.

Solo cambia BuildingPanel en producción. Los temporizadores, capturador, reloj, actores, transiciones, solicitudes con herencia y configuración Maven conservan los bytes de T60. Se añaden únicamente VisualQualityScenarios y su adaptador JUnit en pruebas, seis documentos T61 y actualizaciones del plan/registro T60. No hay dependencias ni hilos nuevos de producción.

## Empaquetado y aceptación

Se revisó git diff --check y ausencia de espacios finales en archivos nuevos/modificados. T61-codigo-completo.md se cotejó con los tres Java íntegros. El ZIP contiene el proyecto completo y fue verificado por CRC y comparación byte a byte de sus entradas; se excluyen .git, target, logs, archivos .log y volcados JVM. No se generó un commit en nombre de David.

La prueba específica pasó antes de la regresión. La inspección de títulos parciales motivó una corrección posterior y una nueva compilación/regresión completa con fuentes definitivas. No se ocultaron fallos temporales mediante repeticiones hasta obtener verde y no se modificó producción después de la ejecución final.

T61 queda implementada y pendiente de Maven/revisión nativa. La fase 10 permanece abierta según T61-aceptacion-fase-10.md; T62 no se inicia.
