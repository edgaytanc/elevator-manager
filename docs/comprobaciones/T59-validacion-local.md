# T59 — Validación local de preparación

- **Fecha:** 1 de noviembre de 2026, Guatemala.
- **Base aceptada por David:** T58 en commit `712b24a`; 12 pruebas específicas y 868 totales correctas, BUILD SUCCESS, git status --short sin salida. El árbol de trabajo de preparación conserva el historial original del ZIP; no se inventa ni recrea el commit local de David.
- **Entorno:** Linux, OpenJDK 17.0.20, Swing headless; sin Maven ni escritorio.
- **Compilación limpia:** 72 fuentes de producción y 44 fuentes independientes de prueba/soporte, con jdk.compiler, --release 17, UTF-8 y directorio externo a target. No se reutilizan las clases del ZIP.
- **T59:** 18 escenarios específicos satisfactorios, incluyendo reloj virtual, raster y actores reales.
- **Regresión:** los 43 ejecutables de pruebas terminaron con código 0 (42 grupos Scenarios y ScalabilityEvaluation). Se comprobó que no quedó ningún método main sin ejecutar.
- **Maven/JUnit:** no se compilaron ni ejecutaron aquí los adaptadores dependientes de JUnit. Hay 18 nuevos; el total Maven esperado es 886. Esta ejecución no se presenta como BUILD SUCCESS Maven.

## Resultados finales de regresión

| Grupo | Código de salida | Duración s |
| --- | ---: | ---: |
| BuildingPanelScenarios | 0 | 1.620 |
| CabinAnimationScenarios | 0 | 1.294 |
| CabinPanelsScenarios | 0 | 4.563 |
| CabinResetScenarios | 0 | 7.344 |
| ConcurrentAdmissionScenarios | 0 | 0.198 |
| ConfigurationFormScenarios | 0 | 1.259 |
| DirectionPickupScenarios | 0 | 9.313 |
| DirectionalAgendaScenarios | 0 | 3.441 |
| EdtSafetyScenarios | 0 | 3.136 |
| ElevatorDwellScenarios | 0 | 0.666 |
| ElevatorMovementScenarios | 0 | 0.184 |
| ElevatorSelectionScenarios | 0 | 1.530 |
| ElevatorWorkerScenarios | 0 | 0.153 |
| EventViewerScenarios | 0 | 2.000 |
| ExecutionEventScenarios | 0 | 0.931 |
| FileSelectionScenarios | 0 | 1.218 |
| GlobalPauseScenarios | 0 | 6.323 |
| GlobalResetScenarios | 0 | 7.818 |
| GuiJourneyScenarios | 0 | 12.562 |
| HallCallsScenarios | 0 | 2.288 |
| ImportReadErrorScenarios | 0 | 1.194 |
| LifecycleRaceScenarios | 0 | 2.965 |
| LoggingScenarios | 0 | 0.370 |
| ManagerWorkerScenarios | 0 | 1.190 |
| ModelRulesScenarios | 0 | 0.118 |
| OrderlyShutdownScenarios | 0 | 1.583 |
| ParallelTimingScenarios | 0 | 4.644 |
| PausableClockScenarios | 0 | 0.106 |
| PauseControlsScenarios | 0 | 6.070 |
| PauseCoordinatorScenarios | 0 | 0.097 |
| PausePhaseScenarios | 0 | 24.649 |
| PausePriorityScenarios | 0 | 3.820 |
| PendingConfirmationScenarios | 0 | 1.647 |
| PickupTimeEstimatorScenarios | 0 | 0.170 |
| ReadFailureScenarios | 0 | 0.105 |
| RequestFileScenarios | 0 | 0.278 |
| RequestImportScenarios | 0 | 1.781 |
| ScalabilityEvaluation | 0 | 5.955 |
| ScalabilityScenarios | 0 | 4.625 |
| SessionLoggingScenarios | 0 | 1.935 |
| SimulationControllerScenarios | 0 | 1.527 |
| StopContinuationScenarios | 0 | 5.524 |
| SystemControlsScenarios | 0 | 4.756 |

## Oráculos y comprobaciones

La interpolación se comprueba en inicio, mitad, final y sentidos opuestos. Los casos de tiempo usan marcas virtuales con distintos orígenes y valores negativos, Frame repetido/nuevo del mismo tramo, entrega demorada, avance previamente consumido, ausencia de muestra, muestra detenida, wrap de long y duración máxima. Se comprueban límites del tramo y que solo el snapshot confirma el piso lógico. El raster verifica una cabina en la coordenada intermedia; el redimensionado cambia coordenadas sin reiniciar el progreso.

La integración usa controlador y actores reales con traslado de 10000 ms. Se captura fuera del EDT, se comprueba la muestra en el dominio activo y se representa con reloj de presentación virtual sin alterar el snapshot del actor. Cada escenario que crea recursos exige su cierre. La suite específica pasó antes de la regresión y volvió a pasar dentro de ella. No hubo fallos en los 43 grupos finales ni cambios de producción después de esa compilación.

Se inspeccionó además un raster Swing de dos cabinas en sentidos opuestos a 0, 250, 500 y 750 ms: posiciones intermedias coherentes y cabinas dentro de sus huecos. El programa auxiliar de esa inspección se compiló en un directorio separado de pruebas para compartir el cargador de clases de la aplicación y acceder al constructor de paquete. No se añadió a fuentes ni entregables.

Se revisó el cambio respecto del ZIP T58: solo cinco archivos de producción (cuatro modificados y CabinMotion nuevo), dos archivos nuevos de pruebas, cuatro documentos T59, el plan y la aceptación registrada de T58. No cambian la jerarquía de solicitudes, el motor de transiciones, los trabajadores, la configuración Maven ni las dependencias. MainWindow.java, el documento de arquitectura y todos los archivos restantes conservan los bytes de la entrega anterior.

## Empaquetado y revisión

Se verificó git diff --check y se revisaron espacios finales de los archivos nuevos/modificados. T59-codigo-completo.md se cotejó con los siete archivos Java íntegros. El ZIP se verificó mediante CRC y comparación byte a byte de sus entradas con el árbol final; contiene el proyecto completo y conserva recursos, configuración y documentación anteriores. Excluye .git, target, logs, archivos .log y volcados JVM. No se genera un commit en nombre de David.

La inspección headless no acredita foco, interacción con ratón, barras nativas, rendimiento de escritorio ni sincronización inmediata de controles. El guion T59-recorrido-gui.md conserva la validación local; T60/T61 mantienen su alcance. T59 queda implementada, pendiente de Maven y aceptación visual; fase 10 continúa abierta.
