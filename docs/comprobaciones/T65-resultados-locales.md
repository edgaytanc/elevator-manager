# T65 — Resultados locales

- Fecha: 2026-11-02, Guatemala.
- Base aceptada por David: T64, 36af413, 48 específicas/1,050 totales, BUILD SUCCESS y árbol limpio.
- Entorno: OpenJDK 17.0.20, Linux headless; compilador real jdk.compiler, release 17 y UTF-8. Maven no disponible.
- Compilación: 133 fuentes de producción/soporte/escenarios independientes. Los adaptadores JUnit esperan sus dependencias reales con Maven en Windows; no se crean sustitutos falsos.

## Pruebas específicas

```text
T65_OK ackAloneNeverCompletesTicket
T65_OK statusRequiresStateAndCompletion
T65_OK completionBeforeAckIsIgnored
T65_OK stateBeforeAckCannotSatisfyStatus
T65_OK wrongCommandIdCannotCompleteTicket
T65_OK wrongCabinCannotCompleteTicket
T65_OK wrongEpochCannotCompleteTicket
T65_OK duplicateTerminalCompletesOnlyOnce
T65_OK coalescedAckCannotAdmitControl
T65_OK errorBeforeAckIsARealRejection
T65_OK failedTerminalFailsTicket
T65_OK cancelledTerminalFailsTicket
T65_OK unknownErrorHeaderDoesNotRejectSomeoneElse
T65_OK malformedResponsesDoNotEscapeOrGenerateLoops
T65_OK sameTicketCanBeReRegisteredWithoutNewExecution
T65_OK registeringChangedContentWithSameIdIsRejected
T65_OK cancellingPublicFutureDoesNotCancelPrivateTicket
T65_OK closeFailsOutstandingAndRefusesNewTickets
T65_OK twoCabinsRequireTheirOwnExactResponses
T65_OK resetRequiresCorrelatedStateNotSpontaneousState
T65_OK resetCompletionAtAnotherFloorIsIgnored
T65_OK oldEpochStateNeverOverwritesNewState
T65_OK lowerRevisionStateDoesNotOverwriteNewerState
T65_OK equalRevisionMayPublishSmallerRemaining
T65_OK unsolicitedCorrelatedStateIsIgnored
T65_OK historicalReplyCanFinishWithoutInstallingOldState
T65_OK replayAfterCompletedStatusCannotRollbackCache
T65_OK sessionIdentityIncludesDestinationAndEpoch
T65_OK rejectedReservationCannotRemoveAnotherOwner
T65_OK managerAckMeansAssignedNotServed
T65_OK managerArrivalCannotMeanService
T65_OK managerRequiresCompatibleStopBeforeCompleted
T65_OK managerWrongDirectionStopCannotServeCall
T65_OK managerOldAttemptResponseCannotAssignNewAttempt
T65_OK managerOldEpochTerminalCannotServeNewEpoch
T65_OK managerDuplicateCompletionDoesNotReopenService
T65_OK managerFailedServiceIsNotAutomaticallyReassigned
T65_OK statusRunsOnWorkerAndHasExactCorrelation
T65_OK statusWorksWhileSafelyStopped
T65_OK statusReplayPreservesOriginalSnapshot
T65_OK controllerPortCannotSendPickup
T65_OK managerPortCannotSendDestinationOrControl
T65_OK wrongRoleCannotReplayAnAuthorizedStatus
T65_OK sameIdWithChangedContentIsRejectedBeforeEpoch
T65_OK sharedIdentityRegistryDetectsCrossCabinConflict
T65_OK strictResetRejectsSkippedOrEqualEpoch
T65_OK resetPublishesFinalStateBeforeCompletion
T65_OK oldResetReplayCannotMoveOrChangeEpochAgain
T65_OK laterResetSupersedesReturnWithoutTeleportation
T65_OK strictStaleQueryCannotChangeCurrentState
T65_OK stopAndStartCommandsControlPhysicalWork
T65_OK duplicateNewStopIsInvalidButReplayIsIdempotent
T65_OK shutdownCommandTerminatesActualThread
T65_OK shutdownCanCloseAnotherEpoch
T65_OK controllerStatusAndCacheUseParsedText
T65_OK statusDuringPauseDoesNotAdvanceClockOrRemaining
T65_OK resetFromPauseReturnsPhysicallyAndClearsCachedGeneration
T65_OK resetWaitsForAllCabinReturnsNotJustAcks
T65_OK resetFromStoppedClearsPendingDestinations
T65_OK controllerCloseInPauseJoinsResponseConsumer
T65_OK queryingClosedControllerFailsWithoutHanging
T65_OK invalidStatusTargetDoesNotAffectHealthySession
T65_OK queriesRacingCloseNeverLeaveFuturePending
T65_OK stopQueuedBehindInitialStartStillStopsSession
T65_RESULT scenarios=64; failures=0
```

64 escenarios correctos. Los mismos 64 métodos están adaptados en ProtocolIntegrationTest. Hay varios mensajes y casos de datos por escenario, sin multiplicar el conteo JUnit por cada iteración.

## Regresión previa

| Grupo | Escenarios | Resultado final |
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
| simulation.TextTransportScenarios | 48 | Correcto |

1,005 escenarios previos en 47 grupos; con los 64 nuevos: 1,069 independientes. No equivale a una ejecución Surefire ni a las 1,114 pruebas Maven esperadas. Los errores de logging inyectados intencionalmente forman parte de sus casos; los procesos terminan con código 0.

## Correcciones comprobadas durante la integración

La primera integración mostró que esperar respuestas START dejaba una ventana de importación mientras el estado seguía en RESET. Se añadió una compuerta de arranque y revalidación al confirmar lectura. Stop conserva el despacho previo que estaba en vuelo; no se rechaza su encolado por observar la cabina ya detenida. Las consultas contra un coordinador cerrado ahora completan con error incluso cuando schedule devuelve un recibo REJECTED en lugar de lanzar una excepción.

Las pruebas nuevas cierran y unen el controlador antes de comparar identidades de hilos, para no confundir sesiones anteriores todavía cerrándose con una fuga. SystemControlsScenarios y AnimationSynchronizationScenarios esperan a que el botón esté habilitado antes del clic. El primer recorrido SystemControls agotó una espera por simular Stop antes de aplicar en EDT el recibo de Start; con la condición de disponibilidad correcta, pasó. No se aumentaron plazos, eliminaron aserciones, omitieron casos ni capturaron fallos como éxitos.

Los escenarios previos de importación/telemetría se adaptan a los nuevos controles reales: START consume un ID por cabina y la recepción cuenta los frames de control además del servicio. Los conteos de casos anteriores permanecen iguales.

## Demo real

Ejecutada desde clases y desde un JAR auxiliar con actores reales. STATUS durante PAUSED conserva el reloj; reset confirma ambas cabinas en la generación nueva; cierre incluye join del consumidor de respuestas.

```text
T65_STATUS cmd=6; cabin=1; epoch=0; phase=HELD; remaining_ms=98; clockFrozen=true
T65_WIRE V1 ACK 1 EPOCH 0 ELEVATOR 1 ACCEPTED
T65_WIRE V1 STATE 1 EPOCH 0 ELEVATOR 1 REV 0 FLOOR 1 NEXT - DIRECTION UP PHASE IDLE SUSPENDED - HOLD - MODE NORMAL REMAINING_MS 0 INTERNAL - EXTERNAL -
T65_WIRE V1 EVENT 1 EPOCH 0 ELEVATOR 1 COMPLETED
T65_WIRE V1 ACK 2 EPOCH 0 ELEVATOR 2 ACCEPTED
T65_WIRE V1 STATE 2 EPOCH 0 ELEVATOR 2 REV 0 FLOOR 1 NEXT - DIRECTION UP PHASE IDLE SUSPENDED - HOLD - MODE NORMAL REMAINING_MS 0 INTERNAL - EXTERNAL -
T65_WIRE V1 EVENT 2 EPOCH 0 ELEVATOR 2 COMPLETED
T65_WIRE V1 ACK 3 EPOCH 0 ELEVATOR 1 ACCEPTED
T65_WIRE V1 ACK 5 EPOCH 0 ELEVATOR 2 ACCEPTED
T65_WIRE V1 STATE 5 EPOCH 0 ELEVATOR 2 REV 0 FLOOR 1 NEXT - DIRECTION UP PHASE IDLE SUSPENDED - HOLD - MODE NORMAL REMAINING_MS 0 INTERNAL - EXTERNAL -
T65_WIRE V1 EVENT 5 EPOCH 0 ELEVATOR 2 COMPLETED
T65_WIRE V1 ACK 4 EPOCH 0 ELEVATOR 1 ACCEPTED
T65_WIRE V1 STATE 4 EPOCH 0 ELEVATOR 1 REV 6 FLOOR 3 NEXT 4 DIRECTION UP PHASE HELD SUSPENDED MOVING HOLD PAUSE MODE NORMAL REMAINING_MS 98 INTERNAL 6 EXTERNAL -
T65_WIRE V1 EVENT 4 EPOCH 0 ELEVATOR 1 COMPLETED
T65_WIRE V1 ACK 6 EPOCH 0 ELEVATOR 1 ACCEPTED
T65_WIRE V1 STATE 6 EPOCH 0 ELEVATOR 1 REV 6 FLOOR 3 NEXT 4 DIRECTION UP PHASE HELD SUSPENDED MOVING HOLD PAUSE MODE NORMAL REMAINING_MS 98 INTERNAL 6 EXTERNAL -
T65_WIRE V1 EVENT 6 EPOCH 0 ELEVATOR 1 COMPLETED
T65_WIRE V1 EVENT 3 EPOCH 0 ELEVATOR 1 CANCELLED RESET
T65_WIRE V1 ACK 7 EPOCH 1 ELEVATOR 1 ACCEPTED
T65_WIRE V1 ACK 8 EPOCH 1 ELEVATOR 2 ACCEPTED
T65_WIRE V1 STATE 8 EPOCH 1 ELEVATOR 2 REV 2 FLOOR 1 NEXT - DIRECTION UP PHASE IDLE SUSPENDED - HOLD - MODE NORMAL REMAINING_MS 0 INTERNAL - EXTERNAL -
T65_WIRE V1 EVENT 8 EPOCH 1 ELEVATOR 2 COMPLETED
T65_WIRE V1 STATE 7 EPOCH 1 ELEVATOR 1 REV 15 FLOOR 1 NEXT - DIRECTION UP PHASE IDLE SUSPENDED - HOLD - MODE NORMAL REMAINING_MS 0 INTERNAL - EXTERNAL -
T65_WIRE V1 EVENT 7 EPOCH 1 ELEVATOR 1 COMPLETED
T65_WIRE V1 ACK 9 EPOCH 1 ELEVATOR 1 ACCEPTED
T65_WIRE V1 STATE 9 EPOCH 1 ELEVATOR 1 REV 15 FLOOR 1 NEXT - DIRECTION UP PHASE IDLE SUSPENDED - HOLD - MODE NORMAL REMAINING_MS 0 INTERNAL - EXTERNAL -
T65_WIRE V1 EVENT 9 EPOCH 1 ELEVATOR 1 COMPLETED
T65_WIRE V1 ACK 10 EPOCH 1 ELEVATOR 2 ACCEPTED
T65_WIRE V1 STATE 10 EPOCH 1 ELEVATOR 2 REV 2 FLOOR 1 NEXT - DIRECTION UP PHASE IDLE SUSPENDED - HOLD - MODE NORMAL REMAINING_MS 0 INTERNAL - EXTERNAL -
T65_WIRE V1 EVENT 10 EPOCH 1 ELEVATOR 2 COMPLETED
T65_WIRE V1 ACK 12 EPOCH 1 ELEVATOR 2 ACCEPTED
T65_WIRE V1 EVENT 12 EPOCH 1 ELEVATOR 2 COMPLETED
T65_WIRE V1 ACK 11 EPOCH 1 ELEVATOR 1 ACCEPTED
T65_WIRE V1 EVENT 11 EPOCH 1 ELEVATOR 1 COMPLETED
T65_INTEGRATION_OK; epoch=1; state=CLOSED; responsesJoined=true
```

T65_WIRE contiene cadenas reales leídas de la suscripción. No se fabrica un ACK ni un terminal para simular la barrera. El JAR auxiliar solo es una comprobación local y no sustituye el empaquetado Maven entregado.

## Pendientes locales

En Windows: ProtocolIntegrationTest 64; suite completa esperada 1,114 (1,050 base + 64), sin fallos/errores/omitidas y BUILD SUCCESS. Demo termina con T65_INTEGRATION_OK, epoch=1, state=CLOSED y responsesJoined=true. No se afirma ejecución Maven aquí. La revisión de David y su commit siguen pendientes; T66 no está iniciada.
