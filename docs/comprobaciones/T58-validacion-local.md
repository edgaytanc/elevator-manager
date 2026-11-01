# T58 — Validación local de preparación

- **Fecha:** 1 de noviembre de 2026, Guatemala.
- **Entorno:** Linux, OpenJDK 17.0.20, Swing headless. Sin Maven ni escritorio nativo.
- **Base:** ZIP actualizado de David, HEAD 8237158 (T57), con dos cambios previos preservados.
- **Compilación limpia:** 71 fuentes de producción y 43 fuentes independientes de prueba/soporte, con el módulo jdk.compiler, --release 17 y UTF-8, en un directorio externo a target. No se reutilizaron clases compiladas del ZIP.
- **T58:** 12 escenarios nuevos satisfactorios, incluidos geometría, raster y controlador/actores reales.
- **Regresión:** todos los 42 ejecutables de src/test/java finalizaron con código 0: 41 grupos Scenarios y ScalabilityEvaluation. No quedaron métodos main sin ejecutar.
- **JUnit/Maven:** los adaptadores con dependencia JUnit no se compilaron ni ejecutaron en este entorno. Hay 12 adaptadores nuevos; desde los 856 anteriores se esperan 868 pruebas Maven. No se presenta esta ejecución como BUILD SUCCESS Maven.

## Resultados de regresión

| Grupo | Salida | Duración s |
| --- | ---: | ---: |
| BuildingPanelScenarios | 0 | 1.586 |
| CabinPanelsScenarios | 0 | 4.600 |
| CabinResetScenarios | 0 | 7.335 |
| ConcurrentAdmissionScenarios | 0 | 0.248 |
| ConfigurationFormScenarios | 0 | 1.250 |
| DirectionPickupScenarios | 0 | 9.308 |
| DirectionalAgendaScenarios | 0 | 3.439 |
| EdtSafetyScenarios | 0 | 3.086 |
| ElevatorDwellScenarios | 0 | 0.671 |
| ElevatorMovementScenarios | 0 | 0.184 |
| ElevatorSelectionScenarios | 0 | 1.529 |
| ElevatorWorkerScenarios | 0 | 0.171 |
| EventViewerScenarios | 0 | 1.979 |
| ExecutionEventScenarios | 0 | 0.933 |
| FileSelectionScenarios | 0 | 1.200 |
| GlobalPauseScenarios | 0 | 6.287 |
| GlobalResetScenarios | 0 | 7.792 |
| GuiJourneyScenarios | 0 | 12.520 |
| HallCallsScenarios | 0 | 2.311 |
| ImportReadErrorScenarios | 0 | 1.192 |
| LifecycleRaceScenarios | 0 | 3.012 |
| LoggingScenarios | 0 | 0.364 |
| ManagerWorkerScenarios | 0 | 1.199 |
| ModelRulesScenarios | 0 | 0.122 |
| OrderlyShutdownScenarios | 0 | 1.558 |
| ParallelTimingScenarios | 0 | 4.648 |
| PausableClockScenarios | 0 | 0.112 |
| PauseControlsScenarios | 0 | 5.873 |
| PauseCoordinatorScenarios | 0 | 0.083 |
| PausePhaseScenarios | 0 | 24.624 |
| PausePriorityScenarios | 0 | 3.783 |
| PendingConfirmationScenarios | 0 | 1.677 |
| PickupTimeEstimatorScenarios | 0 | 0.180 |
| ReadFailureScenarios | 0 | 0.098 |
| RequestFileScenarios | 0 | 0.234 |
| RequestImportScenarios | 0 | 1.695 |
| ScalabilityEvaluation | 0 | 5.971 |
| ScalabilityScenarios | 0 | 4.620 |
| SessionLoggingScenarios | 0 | 1.929 |
| SimulationControllerScenarios | 0 | 1.545 |
| StopContinuationScenarios | 0 | 5.315 |
| SystemControlsScenarios | 0 | 4.724 |

## Revisión y límites

La prueba inicial de centro P=1 tenía una expectativa aritmética incorrecta (184 en lugar de 174 px para 320 px de altura); se corrigió el oráculo antes de la ejecución específica satisfactoria y de la regresión final. No requirió cambio de producción. La primera ejecución manual de ReadFailureScenarios omitió src/main/resources del classpath y falló por ausencia de 01-minimo.txt; se corrigió el lanzador y los cuatro grupos adicionales se ejecutaron con los recursos presentes. No se modificó código de lectura ni se ocultaron fallos temporales repitiendo escenarios.

Se inspeccionó un raster Swing real de cuatro cabinas y diez pisos: etiquetas, huecos, orden y ubicación en piso 1 correctos. Se comprueban con raster automático posiciones diferentes y una región distante del edificio. Esta inspección no representa una captura de Windows ni prueba interacción con ratón, tamaño real de JFrame o barras nativas. El guion T58-recorrido-gui.md conserva esa aceptación pendiente.

No cambia el motor, sus tiempos ni el protocolo de solicitudes. Solo se añade BuildingPanel y se modifica SystemControlPanel en producción. MainWindow.java y docs/analisis_de_arquitectura_elevator_manager.md se compararon byte a byte con el ZIP de entrada y permanecen intactos. La configuración Maven no cambia y no hay dependencias nuevas.

Se comprobó git diff --check y la ausencia de espacios finales en archivos nuevos. Se cotejó que el código completo Markdown reproduce exactamente los cuatro Java de T58. El ZIP fue comprobado mediante CRC y comparación byte a byte de sus entradas con el árbol final; incluye el proyecto completo con fuentes, pruebas, recursos y documentación. Excluye .git, target, logs, archivos .log y volcados JVM. No se generó un commit en nombre de David.

T58 queda implementada y verificada en este entorno, pendiente de aceptación local; fase 10 continúa abierta. La confirmación del usuario cierra las fases 1 a 9, sin atribuir a esta entrega evidencia nativa nueva sobre T57.
