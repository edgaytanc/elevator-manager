# T63 — Resultados locales

- Fecha: 2026-11-02, Guatemala.
- Entorno: OpenJDK 17.0.20, Linux headless; compilador real vía módulo `jdk.compiler`. Maven no disponible.
- Compilación: 125 archivos Java de producción/soporte/escenarios independientes, `--release 17`, UTF-8, sin errores. Los adaptadores JUnit esperan la ejecución Maven en Windows.

## Codec nuevo

```text
T63_OK pickupRoundTrip
T63_OK destinationRoundTrip
T63_OK resetRoundTrip
T63_OK stopRoundTrip
T63_OK startRoundTrip
T63_OK pauseRoundTrip
T63_OK resumeRoundTrip
T63_OK statusRoundTrip
T63_OK shutdownRoundTrip
T63_OK acceptedRoundTrip
T63_OK coalescedRoundTrip
T63_OK allErrorsRoundTrip
T63_OK completedRoundTrip
T63_OK allCancellationsRoundTrip
T63_OK allFailuresRoundTrip
T63_OK moveRoundTrip
T63_OK arrivedRoundTrip
T63_OK directionRoundTrip
T63_OK stoppedAtInternalExtremeRoundTrip
T63_OK idleStateRoundTrip
T63_OK movingStateRoundTrip
T63_OK dwellingStateRoundTrip
T63_OK heldMovingRoundTrip
T63_OK heldDwellingStopRoundTrip
T63_OK resetStateRoundTrip
T63_OK terminatedStateRoundTrip
T63_OK spontaneousStateRoundTrip
T63_OK zeroRemainingWhileMovingIsValid
T63_OK canonicalSpacingAndTerminators
T63_OK nullAndEmptyRejected
T63_OK exteriorSpacesRejected
T63_OK tabsBomUnicodeAndControlsRejected
T63_OK embeddedOrRepeatedTerminatorsRejected
T63_OK commentsQuotesAndPlaceholdersRejected
T63_OK unsupportedAndMalformedVersions
T63_OK unknownFamilyAndCommand
T63_OK commandHeaderMustBeCompleteAndCanonical
T63_OK headerOnlyRetainsCorrelation
T63_OK arityAndLabelsAreExact
T63_OK positiveLongAndIntMaximaRoundTrip
T63_OK numericBodiesAreCanonicalAndBounded
T63_OK uuidIsStrictAndPreserved
T63_OK directionsAndBuildingLimits
T63_OK singleFloorBuilding
T63_OK lexicalTypeTargetAndFloorPrecedence
T63_OK parserDoesNotApplySessionAdmission
T63_OK commandAndResponseChannelsAreDistinct
T63_OK unknownErrorReasonsAndStatusesRejected
T63_OK responseAritiesAreExact
T63_OK unknownErrorHeaderIsAllOrNothing
T63_OK physicalEventsMustBeCoherent
T63_OK stateCounterBounds
T63_OK stateHeldCombinations
T63_OK stateNextAndRemainingConstraints
T63_OK stateListsAreSortedUniqueAndStrict
T63_OK stateExternalPairsAreStrict
T63_OK stateResetAndTerminatedHaveNoPending
T63_OK stateProjectionDoesNotGenerateIdentityOrTime
T63_OK immutableStateListsAndDtoShapes
T63_OK exactFrameLimitIncludesTerminator
T63_OK oversizedUnicodeIsCheckedInBytes
T63_OK excessTokensDoNotAllocateUnboundedTokenArray
T63_OK hugeNumericAndUuidTokensAreControlled
T63_OK diagnosticsAreBoundedEscapedAndNotExecutable
T63_OK invalidMessageThenValidMessageRecovers
T63_OK encodingUsesConfigurationAndControlledFailure
T63_OK oversizedStateEncodingIsNotTruncated
T63_OK sameRevisionCanCarryDifferentRemaining
T63_OK randomRoundTripsRetainAllOperationalFields
T63_OK randomMalformedInputsNeverEscapeTheParser
T63_OK sharedCodecIsThreadSafe
T63_OK typedPickupBridgePreservesContentAndExplicitMetadata
T63_OK typedDestinationBridgePreservesContentAndExplicitMetadata
T63_OK typedResetBridgeAndOtherControlsRemainExplicit
T63_OK messageEqualityIncludesContentBeyondUuid
T63_OK malformedMutationsOfEveryFamilyAreControlled
T63_RESULT scenarios=76; failures=0
```

Los 76 escenarios incluyen bucles de casos y matrices: no se cuentan cada iteración como otra prueba JUnit. La igualdad de DTO compara campos, no solo UUID. El ensayo de cuatro hilos conserva el mismo codec compartido y cierra su executor.

## Regresión seleccionada

| Grupo | Escenarios | Resultado |
| --- | --- | --- |
| ModelRulesScenarios | 7 | Correcto |
| RequestFileScenarios | 48 | Correcto |
| ConcurrentAdmissionScenarios | 21 | Correcto |
| ExecutionEventScenarios | 20 | Correcto |
| DirectionPickupScenarios | 20 | Correcto |
| GlobalPauseScenarios | 17 | Correcto |
| AnimationSynchronizationScenarios | 20 | Timeout inicial; repetición completa correcta |
| VisualQualityScenarios | 16 | Correcto |

Regresión seleccionada: 169 escenarios en sus ejecuciones finales. Sumados a T63: 245. Esto no equivale a ejecutar todos los adaptadores JUnit del proyecto.

### Incidencia conservada de T60

En la primera ejecución, `systemButtonsSynchronizeAndCloseAnimation` agotó su espera a observar MOVING (línea 215 de la prueba original). La prueba pulsa un destino inmediatamente después de observar RUNNING; existe una actualización asíncrona de los controles. Es una posible condición temporal de la prueba, no una causa demostrada ni una corrección aplicada.

Se compiló separadamente la base T62 sin ningún código T63. La prueba aislada y luego la clase completa de 20 escenarios pasaron sobre esa base. La ejecución aislada con T63 también agotó la espera y la repetición posterior de la clase completa pasó. No se alteró código GUI ni se ampliaron sus plazos para ocultar el resultado. Queda como limitación/intermitencia a observar en Maven local.

Primer fallo:

```text
Exception in thread "main" java.lang.AssertionError: Plazo agotado
	at com.elevatormanager.ui.AnimationSynchronizationScenarios.check(AnimationSynchronizationScenarios.java:286)
	at com.elevatormanager.ui.AnimationSynchronizationScenarios.await(AnimationSynchronizationScenarios.java:296)
	at com.elevatormanager.ui.AnimationSynchronizationScenarios.systemButtonsSynchronizeAndCloseAnimation(AnimationSynchronizationScenarios.java:215)
	at com.elevatormanager.ui.AnimationSynchronizationScenarios.main(AnimationSynchronizationScenarios.java:308)
```

Repetición final con T63:

```text
T60: 20 escenarios de sincronización satisfactorios.
```

Clase completa sobre base T62:

```text
T60: 20 escenarios de sincronización satisfactorios.
```

## Demostración real del codec

Ejecutada desde clases compiladas y desde un JAR auxiliar local con el mismo main:

```text
T63_TX V1 CMD 42 EPOCH 0 ELEVATOR 1 PICKUP 12345678-1234-1234-1234-123456789abc 3 UP
T63_ROUND_TRIP sameContent=true; uuid=12345678-1234-1234-1234-123456789abc
T63_REJECT V1 ERROR 42 EPOCH 0 ELEVATOR 1 INVALID_DIRECTION
T63_CODEC_OK; transportIntegrated=false
```

La línea T63_REJECT es construida por el codec para demostrar el diagnóstico: no es una respuesta de un trabajador ni evidencia de transporte integrado. El JAR auxiliar se usó solo para comprobación y no sustituye el empaquetado Maven del proyecto.

## Pendientes

Maven en Windows: 76 pruebas específicas y total esperado 1,002 (926 base + 76 nuevas), todos con cero errores/fallos. No se afirma BUILD SUCCESS local sin esa ejecución. La GUI no cambia en T63; el transporte de órdenes, idempotencia y barreras quedan en T64–T65.
