# T45 — Validación local

- **Fecha:** 2026-10-28, Guatemala.
- **Base:** T44 validada en `9ae324d`, 48 pruebas específicas y 506 acumuladas, BUILD SUCCESS.
- **Entorno:** Linux, OpenJDK 17.0.20, headless; Maven no está instalado.
- **Resultado:** compilación Java 17, 501 escenarios independientes satisfactorios en 24 grupos. Los 40 escenarios nuevos de T45 también pasan tres repeticiones finales.

## Compilación y regresión

Se compilaron desde los fuentes actuales producción, todos los *Scenarios.java y ScalabilityEvaluation mediante el módulo jdk.compiler, sin usar target ni clases del ZIP. Los adaptadores JUnit quedan pendientes de ejecutar con sus dependencias reales en Maven.

| Grupo | Escenarios | Salida | Duración s |
| --- | ---: | ---: | ---: |
| DirectionPickupScenarios | 20 | 0 | 9.495 |
| ElevatorSelectionScenarios | 18 | 0 | 1.700 |
| ManagerWorkerScenarios | 12 | 0 | 1.443 |
| PendingConfirmationScenarios | 26 | 0 | 1.730 |
| PickupTimeEstimatorScenarios | 20 | 0 | 0.282 |
| ScalabilityScenarios | 13 | 0 | 4.817 |
| RequestFileScenarios | 48 | 0 | 0.471 |
| LoggingScenarios | 16 | 0 | 0.690 |
| SessionLoggingScenarios | 24 | 0 | 2.154 |
| ModelRulesScenarios | 7 | 0 | 0.189 |
| CabinResetScenarios | 32 | 0 | 7.563 |
| ConcurrentAdmissionScenarios | 21 | 0 | 0.342 |
| DirectionalAgendaScenarios | 20 | 0 | 3.507 |
| ElevatorDwellScenarios | 15 | 0 | 0.765 |
| ElevatorMovementScenarios | 10 | 0 | 0.236 |
| ElevatorWorkerScenarios | 7 | 0 | 0.239 |
| ExecutionEventScenarios | 20 | 0 | 1.018 |
| GlobalResetScenarios | 33 | 0 | 8.215 |
| LifecycleRaceScenarios | 24 | 0 | 3.486 |
| OrderlyShutdownScenarios | 32 | 0 | 1.671 |
| ParallelTimingScenarios | 8 | 0 | 4.739 |
| RequestImportScenarios | 40 | 0 | 1.906 |
| SimulationControllerScenarios | 14 | 0 | 1.665 |
| StopContinuationScenarios | 21 | 0 | 5.533 |

Los 501 escenarios son 461 previos y 40 nuevos; no equivalen a la suite JUnit completa. La clase del diagnóstico recibió después una mejora de presentación de causas y comprobación explícita de Start; se recompilaron todos los fuentes de producción y se comprobó su rechazo de un archivo inexistente. El controlador y los escenarios no cambiaron tras la regresión.

## Tres repeticiones finales de T45

| Ejecución | Escenarios | Salida | Duración s |
| ---: | ---: | ---: | ---: |
| 1 | 40 | 0 | 2.163 |
| 2 | 40 | 0 | 2.071 |
| 3 | 40 | 0 | 1.954 |

Son repeticiones de los mismos 40 casos y no 120 pruebas JUnit nuevas. Los tiempos son observaciones, no garantías. Las pruebas capturan hilos de actores de la sesión bajo la ejecución secuencial predeterminada; no habilitar ejecución paralela de clases sin aislar esa identificación.

## Matriz de 40 escenarios

| N.º | Escenario | Comprobación |
| ---: | --- | --- |
| 1 | initialImportDoesNotStartActors | Confirmar al inicio mantiene RESET, snapshots y actores NEW. |
| 2 | importAfterResetKeepsCabinsIdle | Confirmar tras retorno conserva cabinas y manejador retenidos. |
| 3 | startingServicesMixedImportedBatch | Lote mixto enviado y atendido por actores reales, con UUID correlacionados. |
| 4 | repeatedStartNeverReleasesBatchAgain | Veinte Start repetidos no vuelven a presentar el lote ni crean otro informe. |
| 5 | stopStartContinuesOriginalBatchRequests | Stop/Start conserva solicitudes e informe originales. |
| 6 | validReplacementIsAtomicAndUsesFreshIdentities | Reemplazo válido completo con identidades nuevas. |
| 7 | invalidFinalLinePreservesPreviousBatch | Error final conserva el objeto del lote previamente confirmado. |
| 8 | missingFilePreservesPreviousBatch | Archivo inexistente conserva el lote anterior y diagnóstico de lectura. |
| 9 | emptyFilePreservesPreviousBatch | Archivo vacío no significa borrar el lote. |
| 10 | duplicateEntriesRemainDistinctAtConfirmation | Repetidos conservan entradas y UUID propios antes de admisión al dominio. |
| 11 | runningImportIsRejectedBeforeReading | RUNNING rechaza antes de leer. |
| 12 | stoppedImportIsRejectedBeforeReading | STOPPED rechaza antes de leer. |
| 13 | stoppingImportIsRejectedBeforeReading | STOPPING en un tramo largo rechaza antes de leer. |
| 14 | resettingImportIsRejectedBeforeReading | RESETTING en un retorno pendiente rechaza antes de leer. |
| 15 | closingAndClosedImportsAreRejected | CLOSING y CLOSED rechazan sin abrir archivo. |
| 16 | startDuringReadInvalidatesLateSuccess | Start invalida una lectura retenida y descarta su éxito tardío. |
| 17 | resetDuringReadRejectsOldEpochEvenBackInReset | Reset incrementa época y descarta el resultado antiguo aunque vuelva a RESET. |
| 18 | resetClearsConfirmedBatchBeforeNextStart | Reset elimina el lote confirmado antes de otro Start. |
| 19 | closeWaitsForActualReaderTermination | Cierre resuelve la importación pero espera la salida real de un lector que ignora interrupción. |
| 20 | newestReadSupersedesAndPreservesOldBatchWhilePending | Lectura reciente sustituye la pendiente y conserva el lote anterior mientras espera. |
| 21 | repeatedSupersedingKeepsOneQueuedRead | Veinte sustituciones mantienen una sola tarea pendiente: solo se ejecutan la primera y la última. |
| 22 | explicitCancellationKeepsPreviousBatch | Cancelación explícita resuelve lectura y conserva preparado. |
| 23 | cancellingVisibleFutureDoesNotCancelOperation | Cancelar futuro visible no cancela la operación privada. |
| 24 | incompatibleConfigurationCannotReplaceBatch | Configuración incompatible no sustituye lote vigente. |
| 25 | loaderExceptionIsInvalidAndKeepsOriginalCause | Excepción del lector conserva causa original y permite seguir usando sesión. |
| 26 | nullLoaderResultCannotLeaveUnresolvedFuture | Resultado nulo del lector se informa como INVALID, sin futuro colgado. |
| 27 | edtAndCoordinatorStayResponsiveDuringRead | Invocación desde EDT, ping EDT y controles del coordinador responden mientras I/O está retenido. |
| 28 | confirmationDoesNotConsumeCommandIds | Leer/reemplazar/invalidar no consume IDs de comando; el primero se genera al enviar. |
| 29 | receiptsAreBoundToTheirSession | Recibo de una sesión no es vigente en otra con la misma época. |
| 30 | delayedReceiptIsRejectedAfterReplacement | Generación invalida un recibo diferido después de reemplazo. |
| 31 | confirmedRequestsShareTimeAndHaveUniqueIds | Un tiempo de confirmación y UUID distintos por instrucción. |
| 32 | dispatchReportPreservesOrderWithoutClaimingService | Informe conserva líneas, orden y UUID, y solo afirma ENQUEUED. |
| 33 | importingAfterResetKeepsActorThreadIdentities | Reset/importación/Start conservan las mismas referencias Thread de cabinas y manejador. |
| 34 | importingAfterCompletedResetUsesNewEpoch | Lote posterior al reset usa la nueva época y se atiende. |
| 35 | closeClearsConfirmedBatch | Cierre elimina lote todavía preparado. |
| 36 | importedAndManualRequestsUseSameAdmissionPath | Admisión manual e importada comparten generador/ruta sin reiniciar IDs. |
| 37 | cancellationWithoutReadDoesNotEraseBatch | Cancelar sin lectura no borra el lote confirmado. |
| 38 | staleInvalidResultCannotEraseNewerBatch | Un resultado inválido tardío no elimina el lote más reciente. |
| 39 | publicRecordsAreImmutableAndValidateCorrelation | Listas inmutables y rechazo de registros sin correlación, estado o resultados coherentes. |
| 40 | repeatedSessionsReleaseEveryOwnedThread | Diez sesiones con varias importaciones terminan todos los hilos propios capturados. |

Los lectores retenidos calculan un resultado antes de esperar y pueden ignorar interrupciones de forma controlada. Así se verifica que el controlador descarta datos tardíos incluso si son válidos, sin depender de que el parser falle por interrupción. Los latches se liberan en finally y todos los fixtures esperan cierre real; los plazos detectan bloqueos sin inferir estados por dormir.

## Muestras con API de producción

- Lote mixto: importación confirmada en RESET sin movimiento, Start único, servicios confirmados y cierre con salida 0.
- Lote repetido: se conservaron las cuatro entradas; el seguimiento por canónicas/alias terminó y cerró con salida 0.
- Lote inválido: rechazo INVALID con HALL_UP_AT_TOP en línea 2 y salida 1, sin arranque ni envío del lote.
- Diagnóstico final con archivo inexistente: READ_ERROR, causa visible, salida 1 y liberación de sesión.

Se usaron el lector, controlador, actores y SessionLog reales. Los ejemplos temporales de verificación no se agregan como colección distribuida: esa entrega pertenece a T46. Los logs generados y archivos temporales no se incluyen en el ZIP.

## Límites de esta validación

- No se ejecutaron Maven/JUnit ni una ventana JFrame en este entorno.
- PAUSING/PAUSED aún no son estados alcanzables por controles públicos; el guard permite importar únicamente en RESET y también los excluye.
- El selector gráfico y las pruebas específicas de permisos siguen pendientes de sus tarjetas.
- No se promete interrumpir cualquier I/O externo bloqueado: el cierre espera su terminación real y un timeout no indica liberación.
- La atomicidad se verifica para confirmación y sustitución; no hay ejecución física transaccional ni rollback de un lote que empezó a enviarse.
- Las carreras provocadas no cubren todos los entrelazados posibles del planificador ni una caída abrupta del proceso.

## Comprobación pendiente en Windows

```powershell
mvn -Dtest=RequestImportTest test
mvn "-Dtest=RequestImportTest,RequestFileTest,SimulationControllerTest,GlobalResetTest,OrderlyShutdownTest,LifecycleRaceTest" test
mvn clean verify
```

Se esperan 40 específicas, 191 seleccionadas y 546 acumuladas. Confirmar los resultados reales y BUILD SUCCESS antes de cerrar T45. Commit sugerido: `T45: integrar importacion limitada a RESET`.
