# T60 — Validación local de preparación

- **Fecha:** 1 de noviembre de 2026, Guatemala.
- **Base aceptada por David:** T59, commit `1cd6913`; 18 pruebas específicas y 886 totales correctas con BUILD SUCCESS y Git limpio. El árbol de preparación conserva el historial original del ZIP, sin recrear commits locales de David.
- **Entorno:** Linux, OpenJDK 17.0.20 y Swing headless; sin Maven ni escritorio nativo.
- **Compilación limpia:** 72 fuentes de producción y 46 fuentes independientes de prueba/soporte con jdk.compiler, --release 17, UTF-8 y directorio externo a target. No se reutilizaron las clases de los ZIP.
- **T60 específica:** 4 escenarios de ClockViewScenarios y 20 de AnimationSynchronizationScenarios correctos.
- **Regresión:** los 45 ejecutables de src/test/java finalizaron con código 0: 44 grupos Scenarios y ScalabilityEvaluation. No quedó ningún main sin ejecutar.
- **Maven/JUnit:** los adaptadores dependientes de JUnit no se compilaron ni ejecutaron en este entorno. AnimationSynchronizationTest añade 24 casos: total Maven esperado 910. No se presenta esta comprobación como BUILD SUCCESS Maven.

## Resultados finales

| Grupo | Código de salida | Duración s |
| --- | ---: | ---: |
| AnimationSynchronizationScenarios | 0 | 4.880 |
| BuildingPanelScenarios | 0 | 1.500 |
| CabinAnimationScenarios | 0 | 1.236 |
| CabinPanelsScenarios | 0 | 4.516 |
| CabinResetScenarios | 0 | 7.326 |
| ClockViewScenarios | 0 | 0.043 |
| ConcurrentAdmissionScenarios | 0 | 0.196 |
| ConfigurationFormScenarios | 0 | 1.262 |
| DirectionPickupScenarios | 0 | 9.331 |
| DirectionalAgendaScenarios | 0 | 3.432 |
| EdtSafetyScenarios | 0 | 3.177 |
| ElevatorDwellScenarios | 0 | 0.665 |
| ElevatorMovementScenarios | 0 | 0.202 |
| ElevatorSelectionScenarios | 0 | 1.514 |
| ElevatorWorkerScenarios | 0 | 0.155 |
| EventViewerScenarios | 0 | 2.132 |
| ExecutionEventScenarios | 0 | 0.924 |
| FileSelectionScenarios | 0 | 1.256 |
| GlobalPauseScenarios | 0 | 6.325 |
| GlobalResetScenarios | 0 | 7.826 |
| GuiJourneyScenarios | 0 | 12.831 |
| HallCallsScenarios | 0 | 2.323 |
| ImportReadErrorScenarios | 0 | 1.205 |
| LifecycleRaceScenarios | 0 | 3.065 |
| LoggingScenarios | 0 | 0.390 |
| ManagerWorkerScenarios | 0 | 1.190 |
| ModelRulesScenarios | 0 | 0.112 |
| OrderlyShutdownScenarios | 0 | 1.569 |
| ParallelTimingScenarios | 0 | 4.657 |
| PausableClockScenarios | 0 | 0.107 |
| PauseControlsScenarios | 0 | 5.746 |
| PauseCoordinatorScenarios | 0 | 0.095 |
| PausePhaseScenarios | 0 | 24.636 |
| PausePriorityScenarios | 0 | 3.781 |
| PendingConfirmationScenarios | 0 | 1.647 |
| PickupTimeEstimatorScenarios | 0 | 0.161 |
| ReadFailureScenarios | 0 | 0.103 |
| RequestFileScenarios | 0 | 0.237 |
| RequestImportScenarios | 0 | 1.693 |
| ScalabilityEvaluation | 0 | 5.881 |
| ScalabilityScenarios | 0 | 4.617 |
| SessionLoggingScenarios | 0 | 1.932 |
| SimulationControllerScenarios | 0 | 1.555 |
| StopContinuationScenarios | 0 | 5.515 |
| SystemControlsScenarios | 0 | 4.842 |

## Oráculos

El reloj virtual verifica igualdad exacta de marcas activas, exclusión de intervalos pausados y conservación de vistas antiguas. Se bloquea intencionalmente una fuente de tiempo mientras su llamador mantiene el lock de PausableClock y se exige que leer View en otro comprobador termine sin esperar ese lock. Los hilos auxiliares pertenecen solo a la prueba y deben terminar.

La proyección comprueba posiciones fraccionales con tolerancia 1e-9, pausa con snapshot MOVING anterior, llegada de HELD, continuación con HELD atrasado, ciclos, Stop adyacente, Start sin reset de posición, cambio de época sin colocación inmediata en 1, descenso y cierre tras Resume. Los casos de capturas bloquean la primera y, cuando corresponde, la segunda captura: se comprueba descarte antes de que un éxito posterior pudiera ocultar un resultado/error antiguo. Se mide un máximo de una captura concurrente y se verifica que cerrar cancela la captura fresca pendiente.

Las pruebas con actores reales verifican posición estable durante pausa, parada y reposo, continuidad del tramo al pulsar Stop desde pausa y posiciones intermedias descendentes RESET_RETURN. El reset termina en época 1, piso 1 y dirección UP. El recorrido por formulario y botones Swing hace Start, Pausar, Reanudar, Stop, Start, Reset y cierre real. No sustituye los actores por dobles ni manipula estados privados del modelo.

## Incidencia detectada y corrección

Las primeras ejecuciones específicas detectaron que SystemControlPanel podía disponer de una captura fresca tras Pausar y todavía conservar en BuildingPanel el último IDLE mostrado: la aplicación del Frame estaba dentro del gate de 100 ms del visor. La prueba observó 1.0 y después aproximadamente 1.01 pese a que el reloj ya estaba pausado. Se movió la aplicación del Frame disponible fuera de ese gate, al próximo tick de animación; la frecuencia de captura normal no se incrementó. El oráculo de posición estable no se relajó.

También se protegió el cierre en el panel frente a cualquier Frame posterior y se conservó la autorización temporal de HELD al congelar inmediatamente tras Resume. La ejecución específica final pasó con esas fuentes; posteriormente se ejecutó la regresión completa, sin fallos. No se cambiaron fuentes de producción después de esa compilación.

## Revisión de alcance y empaquetado

Se comparó con la entrega T59: siete archivos de producción modificados, tres archivos nuevos de prueba, cuatro documentos T60, el plan y el registro de aceptación T59. La herencia de solicitudes, los trabajadores, las transiciones del controlador, las reglas de Stop/reset, pom.xml y las dependencias permanecen intactos. El controlador y la barrera solo añaden accesos de observación temporal; el reloj publica anclajes inmutables sin sustituir sus condiciones o esperas existentes.

Se revisó git diff --check y la ausencia de espacios finales en fuentes/documentos T60. T60-codigo-completo.md se cotejó con los diez archivos Java entregados. El ZIP se verificó por CRC y por comparación byte a byte de cada entrada con el árbol final. Incluye el proyecto completo con recursos, pruebas y documentación anteriores; excluye .git, target, logs, archivos .log y volcados JVM. No se genera un commit en nombre de David.

La validación headless no acredita foco, ratón, apariencia de ventana, fluidez de escritorio ni cierre físico con X. Las comprobaciones nativas se describen en T60-recorrido-gui.md. T60 queda implementada y pendiente de Maven/aceptación visual local. La fase 10 continúa abierta y T61 permanece pendiente.
