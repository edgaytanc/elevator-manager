# T64 — Resultados locales

- Fecha: 2026-11-02, Guatemala.
- Base aceptada: T63 en 6c8d7d3, 76 específicas/1,002 totales confirmadas por David, BUILD SUCCESS y árbol limpio.
- Entorno: OpenJDK 17.0.20, Linux headless; compilador real mediante módulo jdk.compiler, release 17 y UTF-8. Maven no disponible.

## Compilación y pruebas específicas

128 archivos Java de producción/soporte/escenarios independientes compilados. Se excluyen los adaptadores que importan JUnit porque no están instaladas sus dependencias en el entorno; no se crean sustitutos falsos de JUnit.

```text
T64_OK rawDestinationIsParsedOnActor
T64_OK rawPickupUsesTransmittedDirectionAndUuid
T64_OK changedTextChangesPhysicalDestination
T64_OK compatibilitySubmitReconstructsDistinctObjects
T64_OK explicitTimestampContextSurvives
T64_OK defaultTimestampIsStableNotWallClock
T64_OK ackPrecedesPhysicalCompletion
T64_OK moveArrivalAndStopAreTextualEvents
T64_OK directionChangesAreReportedInText
T64_OK malformedCommandDoesNotMutateAndWorkerRecovers
T64_OK invalidFloorIsRejectedBeforeMotion
T64_OK impossibleDirectionIsRejected
T64_OK unknownConfiguredElevatorIsRejected
T64_OK wrongExistingTargetCannotMoveThisCabin
T64_OK staleAndFutureServiceEpochsAreRejected
T64_OK badUuidDoesNotCreateRequest
T64_OK ackInjectedIntoCommandQueueIsNotExecuted
T64_OK extraTokensDoNotPartiallyExecute
T64_OK oversizedFrameIsRejectedOnActor
T64_OK framingNormalizationIsAppliedAtReceiver
T64_OK malformedBurstCannotKillActor
T64_OK conditionalSendChecksSnapshotBeforeEnqueue
T64_OK compatibleConditionalSendAlsoSerializes
T64_OK enqueueIsNotAdmissionWhileStopped
T64_OK serviceFifoIsPreserved
T64_OK aliasesHaveCanonicalAckAndEachOwnTerminal
T64_OK sameCommandReplayDoesNotDuplicatePhysicalService
T64_OK servedReplayReturnsKnownTerminalWithoutAnotherStop
T64_OK conflictingIdCannotReplaceDestination
T64_OK resetQueueAlsoCarriesAndParsesText
T64_OK resetCancelsActiveCommandBeforeReturnCompletes
T64_OK resetDiscardsQueuedTextBeforeAdmission
T64_OK malformedResetFailsFutureWithoutAdoptingEpoch
T64_OK serviceCannotEnterThroughResetPriority
T64_OK resetCannotEnterThroughOrdinaryServiceQueue
T64_OK controlsPendingT65AreRejectedWithoutFakeAck
T64_OK textualObserversDoNotStealNativeEvents
T64_OK unsubscribeStopsFutureFrames
T64_OK closeCancelsAcceptedPendingText
T64_OK closedOrNewWorkerRefusesToEnqueue
T64_OK concurrentProducersKeepEveryCorrelation
T64_OK mailboxProtectsEdtAndNegativeTimeout
T64_OK managerDispatchUsesTextAndStillConfirmsService
T64_OK controllerDestinationsAndResetUseText
T64_OK pauseStopAndResumeRetainTextualWork
T64_OK syntaxCodecNeverGrantsAdmission
T64_OK closeTerminatesAcceptedResetInText
T64_OK closeRejectsQueuedTextWithoutFakeAcceptance
T64_RESULT scenarios=48; failures=0
```

48 escenarios específicos, cero fallos. Son los mismos 48 métodos expuestos por TextTransportTest para Maven. Incluyen varias entradas por escenario; cada iteración no se cuenta como otra prueba JUnit. Las pruebas ejecutan actores reales y cierran/unen sus hilos.

## Regresión independiente

| Grupo | Escenarios | Resultado |
| --- | --- | --- |
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
| ui.CabinAnimationScenarios | 18 | Correcto |
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

Total previo: 957 escenarios en 46 grupos. Con los 48 de T64: 1,005 escenarios independientes. El main de algunas tarjetas agrupa casos de manera diferente a sus adaptadores JUnit: este total no equivale a una ejecución Surefire/Maven. Los mensajes de error de logging inyectados intencionalmente en sus pruebas forman parte de esos escenarios; todos sus procesos terminaron con código 0.

Después del último ajuste para cancelar un RESET aceptado al cerrar, se repitieron OrderlyShutdownScenarios, CabinResetScenarios y ExecutionEventScenarios, sin contarlos dos veces. Las comprobaciones de pausa, controles y animación pasaron en esta entrega; no se observó aquí el timeout inicial registrado durante T63.

## Traza real del transporte

TextTransportDemo se ejecutó desde clases y desde un JAR auxiliar con los mismos actores y métodos de producción. Salida de la ejecución del JAR:

```text
T64_ORDER V1 CMD 41 EPOCH 0 ELEVATOR 1 PICKUP 12345678-1234-1234-1234-123456789abc 3 UP
T64_RX thread=elevator-worker-1; reconstructed=true; sameContent=true
T64_WIRE V1 ACK 41 EPOCH 0 ELEVATOR 1 ACCEPTED
T64_WIRE V1 MOVE 2 EPOCH 0 ELEVATOR 1 FROM 1 TO 2 DIRECTION UP
T64_WIRE V1 ARRIVED 3 EPOCH 0 ELEVATOR 1 FLOOR 2
T64_WIRE V1 MOVE 4 EPOCH 0 ELEVATOR 1 FROM 2 TO 3 DIRECTION UP
T64_WIRE V1 ARRIVED 5 EPOCH 0 ELEVATOR 1 FLOOR 3
T64_WIRE V1 STOPPED_AT 6 EPOCH 0 ELEVATOR 1 FLOOR 3 SERVICE UP
T64_WIRE V1 EVENT 41 EPOCH 0 ELEVATOR 1 COMPLETED
T64_WIRE V1 ERROR 42 EPOCH 0 ELEVATOR 1 UNKNOWN_COMMAND
T64_TRANSPORT_OK; managerStatus=SERVED; workerAlive=true
```

T64_ORDER describe la orden reportada por el manejador; las líneas T64_WIRE proceden del canal textual real de la cabina. El receptor reconstruyó otro Command con igual contenido y procesó en elevator-worker-1; completó una recogida compatible y el manejador confirmó SERVED. El mensaje FLY se envió después por el mismo buzón y produjo UNKNOWN_COMMAND sin terminar al trabajador.

El JAR auxiliar es solo una comprobación local y no sustituye al ejecutable Maven ni se incluye en el proyecto entregado.

## Pendientes de validación local

En Windows: 48 pruebas TextTransportTest, 1,050 totales esperadas (1,002 anteriores + 48 nuevas), cero fallos/errores/omitidas y BUILD SUCCESS. Demo final T64_TRANSPORT_OK. No se atribuye esa ejecución Maven a este entorno. La aceptación nativa y el commit de David siguen pendientes.
