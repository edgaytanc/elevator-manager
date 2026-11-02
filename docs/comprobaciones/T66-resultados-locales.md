# T66 — Resultados observados

- **Fecha:** 2026-11-02, Guatemala.
- **Base aceptada por David:** T65, commit `0648e6d`; 64 específicas y 1,114 totales con BUILD SUCCESS.
- **Alcance local:** compilación y escenarios Java independientes; Maven no está instalado en este entorno.

## Resumen

| Comprobación | Resultado observado |
| --- | --- |
| Compilación Java real | 134 fuentes independientes, release 17, UTF-8; sin errores. |
| Demo T66 desde clases | 22 casos correctos; SHUTDOWN y join de los actores de cada caso. |
| Demo T66 desde JAR auxiliar | Los mismos 22 casos correctos; no se suman otra vez al total. |
| Ejecución aislada desde JAR | RESET_IN_FLIGHT correcto; scenarios=1. |
| Argumentos de CLI inválidos | Nombre desconocido y argumentos adicionales rechazados, exit 1 y sin marcador de éxito. |
| Regresión de T65 y anteriores | 48 grupos, 1,069 escenarios correctos en sus ejecuciones finales. |
| Total independiente | 1,091 = 1,069 anteriores + 22 nuevos; no equivale a la suite Maven. |
| Maven esperado en Windows | 22 específicas; 1,136 totales; BUILD SUCCESS. Pendiente. |
| Integridad de entrega | Código Java íntegro, ZIP completo, enlaces y git diff --check comprobados. |

## Incidencias y repetición

La primera ejecución de la nueva demo detectó que su cierre de dos cabinas compartía el ID 999. El registro global de identidad rechazó correctamente el segundo cierre con ID_CONFLICT. Se corrigió exclusivamente el cliente diagnóstico para usar 900 + ID de cabina. Después se reejecutó la demo completa desde clases y JAR; los 22 casos pasaron. No fue necesario cambiar el motor ni el protocolo aceptados en T65.

En la regresión, ui.CabinAnimationScenarios imprimió sus 18 comprobaciones satisfactorias, pero el proceso no terminó antes del límite externo de 75 segundos. El ejecutor registró exit 124 y falló la agregación, como corresponde; esa ejecución no se declaró limpia. Una repetición aislada del mismo grupo concluyó con exit 0 y las 18 comprobaciones correctas. No se modificaron pruebas, producción ni timeouts; la causa del bloqueo inicial de salida no quedó determinada. Los otros 47 grupos finalizaron con exit 0. El agregado final conserva esa repetición de forma explícita y cuenta cada escenario una sola vez.

No se usaron dependencias JUnit simuladas, omisiones, supresión de excepciones ni salidas forzadas con System.exit para hacer pasar los escenarios. Los errores inyectados de logging/lectura que aparecen en sus registros pertenecen a las verificaciones esperadas de esos grupos.

## Casos nuevos

```text
T66_CASE_OK INVALID_UUID
T66_CASE_OK INVALID_FLOOR
T66_CASE_OK INVALID_DIRECTION
T66_CASE_OK UNKNOWN_COMMAND
T66_CASE_OK UNKNOWN_ELEVATOR
T66_CASE_OK WRONG_RECIPIENT
T66_CASE_OK EXTRA_ARGUMENT
T66_CASE_OK UNSUPPORTED_VERSION
T66_CASE_OK OVERSIZED_FRAME
T66_CASE_OK WRONG_ROLE
T66_CASE_OK ACTIVE_REPLAY
T66_CASE_OK COMPLETED_REPLAY
T66_CASE_OK CONFLICTING_REPLAY
T66_CASE_OK COALESCED_SERVICE
T66_CASE_OK RESET_IN_FLIGHT
T66_CASE_OK QUEUED_BEFORE_RESET
T66_CASE_OK LATE_OLD_FRAME
T66_CASE_OK FUTURE_FRAME
T66_CASE_OK STATUS_REPLAY
T66_CASE_OK PARALLEL_CABINS
T66_CASE_OK MANAGER_ROUND_TRIP
T66_CASE_OK CONTROLLER_EXTRAS
T66_PROTOCOL_OK; scenarios=22; failures=0; threadsJoined=true
```

## Regresión previa

| Grupo | Escenarios | Resultado final |
| --- | ---: | --- |
| protocol.ProtocolCodecScenarios | 76 | Correcto |
| simulation.ElevatorWorkerScenarios | 7 | Correcto |
| simulation.ExecutionEventScenarios | 20 | Correcto |
| simulation.ConcurrentAdmissionScenarios | 21 | Correcto |
| simulation.CabinResetScenarios | 32 | Correcto |
| dispatch.ManagerWorkerScenarios | 12 | Correcto |
| dispatch.PendingConfirmationScenarios | 26 | Correcto |
| simulation.GlobalResetScenarios | 33 | Correcto |
| simulation.GlobalPauseScenarios | 17 | Correcto |
| simulation.RequestImportScenarios | 40 | Correcto |
| dispatch.DirectionPickupScenarios | 20 | Correcto |
| dispatch.ElevatorSelectionScenarios | 18 | Correcto |
| dispatch.PickupTimeEstimatorScenarios | 20 | Correcto |
| dispatch.ScalabilityScenarios | 13 | Correcto |
| io.ReadFailureScenarios | 12 | Correcto |
| io.RequestFileScenarios | 48 | Correcto |
| logging.LoggingScenarios | 16 | Correcto |
| logging.SessionLoggingScenarios | 24 | Correcto |
| model.ModelRulesScenarios | 7 | Correcto |
| simulation.DirectionalAgendaScenarios | 20 | Correcto |
| simulation.ElevatorDwellScenarios | 15 | Correcto |
| simulation.ElevatorMovementScenarios | 10 | Correcto |
| simulation.ImportReadErrorScenarios | 8 | Correcto |
| simulation.LifecycleRaceScenarios | 24 | Correcto |
| simulation.OrderlyShutdownScenarios | 32 | Correcto |
| simulation.ParallelTimingScenarios | 8 | Correcto |
| simulation.PausePhaseScenarios | 18 | Correcto |
| simulation.PausePriorityScenarios | 14 | Correcto |
| simulation.SimulationControllerScenarios | 14 | Correcto |
| simulation.StopContinuationScenarios | 21 | Correcto |
| simulation.time.ClockViewScenarios | 4 | Correcto |
| simulation.time.PausableClockScenarios | 17 | Correcto |
| simulation.time.PauseCoordinatorScenarios | 12 | Correcto |
| ui.AnimationSynchronizationScenarios | 20 | Correcto |
| ui.BuildingPanelScenarios | 12 | Correcto |
| ui.CabinAnimationScenarios | 18 | Correcto en repetición aislada |
| ui.CabinPanelsScenarios | 32 | Correcto |
| ui.ConfigurationFormScenarios | 16 | Correcto |
| ui.EdtSafetyScenarios | 32 | Correcto |
| ui.EventViewerScenarios | 40 | Correcto |
| ui.FileSelectionScenarios | 12 | Correcto |
| ui.GuiJourneyScenarios | 10 | Correcto |
| ui.HallCallsScenarios | 28 | Correcto |
| ui.PauseControlsScenarios | 14 | Correcto |
| ui.SystemControlsScenarios | 28 | Correcto |
| ui.VisualQualityScenarios | 16 | Correcto |
| simulation.TextTransportScenarios | 48 | Correcto |
| simulation.ProtocolIntegrationScenarios | 64 | Correcto |

## Reproducción y límites

La [traza local completa](T66-traza-local.md) proviene de la ejecución real desde el JAR auxiliar, no de una composición manual de respuestas. El JAR auxiliar se generó con las clases compiladas y los recursos de producción; no es un paquete Maven y no se incluye en la entrega.

El ZIP aporta los adaptadores JUnit reales para ejecutar `mvn clean verify` en Windows. El conteo esperado 1,136 se deriva de la base aceptada de 1,114 y los 22 adaptadores nuevos; todavía no se ha observado aquí. Las comprobaciones headless de renderizado no reemplazan el [recorrido GUI](T66-recorrido-gui.md). La entrega deja [T66/fase 11](T66-aceptacion-fase-11.md) pendientes de esa revisión local.
