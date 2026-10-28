# T42 — Validación local

- **Fecha:** 2026-10-28, Guatemala.
- **Base:** T41 validada por David en `7cc0c03`, 434 pruebas Maven y diagnóstico correcto.
- **Entorno:** Linux, OpenJDK 17.0.20, headless. Maven no está instalado.
- **Resultado:** compilación de producción y escenarios independientes con objetivo Java 17; 413 escenarios satisfactorios en 22 grupos. Comprobación final de T41 y tres repeticiones de T42 también satisfactorias.

## Compilación y regresión

Se compiló desde los .java actuales mediante el módulo jdk.compiler, sin reutilizar target ni las clases del ZIP. Se incluyeron todos los *Scenarios.java y ScalabilityEvaluation. Los adaptadores JUnit quedan pendientes de compilar y ejecutar con sus dependencias reales en Maven.

| Grupo | Escenarios | Salida | Duración s |
| --- | ---: | ---: | ---: |
| DirectionPickupScenarios | 20 | 0 | 9.669 |
| ElevatorSelectionScenarios | 18 | 0 | 1.680 |
| ManagerWorkerScenarios | 12 | 0 | 1.307 |
| PendingConfirmationScenarios | 26 | 0 | 1.808 |
| PickupTimeEstimatorScenarios | 20 | 0 | 0.322 |
| ScalabilityScenarios | 13 | 0 | 4.886 |
| LoggingScenarios | 16 | 0 | 0.591 |
| SessionLoggingScenarios | 24 | 0 | 2.106 |
| ModelRulesScenarios | 7 | 0 | 0.189 |
| CabinResetScenarios | 32 | 0 | 7.528 |
| ConcurrentAdmissionScenarios | 21 | 0 | 0.371 |
| DirectionalAgendaScenarios | 20 | 0 | 3.513 |
| ElevatorDwellScenarios | 15 | 0 | 0.786 |
| ElevatorMovementScenarios | 10 | 0 | 0.260 |
| ElevatorWorkerScenarios | 7 | 0 | 0.309 |
| ExecutionEventScenarios | 20 | 0 | 1.053 |
| GlobalResetScenarios | 33 | 0 | 8.317 |
| LifecycleRaceScenarios | 24 | 0 | 4.159 |
| OrderlyShutdownScenarios | 32 | 0 | 1.822 |
| ParallelTimingScenarios | 8 | 0 | 5.065 |
| SimulationControllerScenarios | 14 | 0 | 1.915 |
| StopContinuationScenarios | 21 | 0 | 8.175 |

Los 413 escenarios son 389 previos y 24 nuevos; no equivalen a toda la suite JUnit. La regresión precedió al refuerzo final de la detección de errores ocultados por PrintStream. Después se recompilaron todos los fuentes y se repitieron los dos grupos que ejercitan logging, con esta evidencia:

| Grupo final | Ejecución | Salida | Duración s |
| --- | ---: | ---: | ---: |
| LoggingScenarios | 1 | 0 | 0.712 |
| SessionLoggingScenarios | 2 | 0 | 2.290 |
| SessionLoggingScenarios | 3 | 0 | 2.415 |
| SessionLoggingScenarios | 4 | 0 | 2.425 |

Las tres ejecuciones de T42 repiten sus mismos 24 casos, no agregan pruebas JUnit nuevas. Los avisos ERROR_LOGGING y ERROR_CONSUMIDOR_LOGGING de esas pruebas son fallos provocados y verificados deliberadamente.

## Evidencia por escenario

| N.º | Escenario | Verificación |
| ---: | --- | --- |
| 1 | obligatoryEventsComeFromPhysicalActors | Subida, bajada, llegada, parada, cambio de dirección y reset desde actores reales. |
| 2 | realCabinsRemainIsolatedAndConsoleHasOneCopy | Tres cabinas separadas; cada línea se copia una sola vez en consola. |
| 3 | dispatchAdmissionAndServiceAreDifferentFacts | Decisión con ETA, envío, aceptación y servicio con UUID correlacionado. |
| 4 | duplicateHallRequestKeepsCanonicalIdentity | Alias externo conserva canónica y un único servicio físico. |
| 5 | resetKeepsOldHistoryWithoutCompletingCancelledWork | Historial de dos épocas y cancelación sin servicio inventado. |
| 6 | sequencesRemainUniqueAcrossResets | Tres resets mantienen secuencias continuas y únicas. |
| 7 | closingInFlightRecordsTerminationWithoutInventingArrival | Cierre en tramo de 60 s conserva terminación y no inventa llegada. |
| 8 | closingDrainsEveryQueuedEvent | 400 eventos encolados se drenan antes de cerrar. |
| 9 | appendDistinguishesSessions | Append conserva dos UUID de sesión diferentes. |
| 10 | barrierMakesPriorWritesVisible | Barrera confirma visibilidad de escrituras previas. |
| 11 | consumerFailureIsVisibleAndCleanupStillRuns | Fallo de enrutamiento del consumidor es visible y libera recursos. |
| 12 | consoleWriteFailureIsReportedOutOfBand | Consola averiada y PrintStream que oculta IOException se detectan; archivo sano persiste. |
| 13 | fileWriteFailurePreservesCauseAndHealthyConsole | StreamHandler de archivo con write averiado conserva causa y consola sana. |
| 14 | repeatedHandlerFailuresAreCountedAndFirstIsRetained | Reportes repetidos se cuentan; primera causa y error de cierre se conservan. |
| 15 | flushFailureIsDetected | Flush averiado al configurar rechaza apertura; flush posterior conserva código. |
| 16 | closeFailureDoesNotKeepFileLock | Fallo en close del handler no impide liberar lock de directorio. |
| 17 | managerSetupFailureRollsBackCabinResources | Falla al crear manejador.log revierte recursos de cabinas. |
| 18 | unusedControllerClosesConsumerAndFiles | Cierre antes de Start termina consumidor y permite reabrir. |
| 19 | closedSessionRejectsLatePublications | Publicaciones y barreras tardías se rechazan tras cierre idempotente. |
| 20 | sessionOwnershipCannotBeSharedOrMismatched | Se rechazan asociación incompatible y dos controladores sobre un mismo SessionLog. |
| 21 | openingAndClosingOnEdtAreRejected | Abrir/cerrar desde EDT se rechaza sin sellar prematuramente. |
| 22 | actorFailureKeepsExceptionTrace | Evento WORKER_FAILED inyectado conserva nivel SEVERE y traza original. |
| 23 | realManagerFailureIsLogged | Generador inválido provoca fallo de manejador real y causa en archivo. |
| 24 | degradedLoggingPreventsSuccessfulControllerClose | Incidencia visible en controlador; cierre excepcional, recursos libres y sin CLOSED falso. |

## Muestra de producción

EventLoggingDemo ejecutó dos cabinas y un manejador reales con subida, bajada, destinos internos, llamada externa y reset. Se verificó salida 0, retorno al prompt y presencia de los eventos obligatorios. En la ejecución observada generó 35 registros de cabina 1, 4 de cabina 2 y 8 del manejador; estas cantidades dependen del despacho y no son umbrales de aceptación. Se conservaron las confirmaciones DISPATCHED y SERVED. El diagnóstico no sustituye el JAR gráfico ni simula eventos mediante textos prefabricados.

## Límites

- No se ejecutaron Maven/JUnit ni una ventana JFrame aquí.
- La suite predeterminada es secuencial; pruebas que capturan System.err y las antiguas pruebas de identidad de hilos no deben ejecutarse concurrentemente sin aislamiento.
- Los fallos de archivo se inyectan con streams y StreamHandler reales, no se provocó un disco lleno ni se probaron permisos específicos de Windows.
- La fecha civil refleja publicación; el snapshot conserva el instante monotónico del actor. No se afirma un orden físico global.
- La cola no descarta entradas, pero no está acotada y puede crecer si el almacenamiento se retrasa. No hay durabilidad frente a caída abrupta ni recuperación automática de registros fallidos.
- La incidencia es consultable durante ejecución; no hay todavía panel gráfico de incidencias ni sesión configurada en la ventana mínima.

## Comprobación pendiente en Windows

```powershell
mvn -Dtest=SessionLoggingTest test
mvn "-Dtest=SessionLoggingTest,LoggingTest,OrderlyShutdownTest,LifecycleRaceTest" test
mvn clean verify
java -cp .\target\classes com.elevatormanager.diagnostics.EventLoggingDemo
```

Se esperan 24 específicas, 96 seleccionadas y 458 acumuladas. Confirmar los resultados reales y BUILD SUCCESS antes de cerrar T42. Commit sugerido: `T42: completar eventos obligatorios y errores`.


## Confirmación posterior de David — 2026-10-28

T42 cerrada en commit `ae5a1bf`: 24 pruebas específicas y 458 acumuladas, sin fallos, errores u omisiones, BUILD SUCCESS. EventLoggingDemo terminó correctamente y devolvió el prompt. Los avisos ERROR_LOGGING/ERROR_CONSUMIDOR_LOGGING de SessionLoggingTest corresponden a fallos inyectados que las pruebas verifican. Esta confirmación sustituye el estado pendiente Windows de la entrega original; no se recibió una ejecución separada de la selección de 96 pruebas.
