# T44 — Validación local

- **Fecha:** 2026-10-28, Guatemala.
- **Base:** contrato T43 1.0.0; último commit confirmado T42 `ae5a1bf`, 458 pruebas Maven.
- **Entorno:** Linux, OpenJDK 17.0.20, headless; Maven no está instalado.
- **Resultado:** fuentes de producción y escenarios independientes compilados con objetivo Java 17. Los 48 escenarios T44 pasan; no cambian los fuentes anteriores del motor ni pom.xml.

## Comprobaciones ejecutadas

RequestFileScenarios: salida 0, 0.374 s, mensaje `T44: 48 escenarios satisfactorios.`. El tiempo observado no es una garantía de rendimiento.

Se compiló desde los fuentes actuales mediante el módulo jdk.compiler, sin reutilizar target ni clases del ZIP. Se incluyeron todos los *Scenarios.java y ScalabilityEvaluation; los adaptadores JUnit requieren las dependencias reales de Maven y no se ejecutaron aquí.

Se validaron también con RequestFileDemo los cinco archivos completos válidos y las 26 entradas inválidas de 07. Los códigos y líneas coinciden con el contrato, usando el parser Java real. Estos lanzamientos adicionales no aumentan la cantidad de pruebas JUnit.

El diagnóstico manual produjo cuatro descriptores para el archivo válido (salida 0), HALL_UP_AT_TOP en línea 4 sin lote parcial para el inválido (salida 1) y uso para argumentos incompletos (salida 2). No inició actores ni una simulación.

## Matriz de 48 escenarios

| N.º | Escenario | Comprobación |
| ---: | --- | --- |
| 1 | minimalCabinBatch | Descriptor CABIN y metadatos de un archivo mínimo. |
| 2 | mixedBatchPreservesPhysicalOrder | Tipos mixtos, orden físico y comentarios/vacías. |
| 3 | repeatedEntriesAreNotRemoved | Repetidos y ceros iniciales se conservan como entradas. |
| 4 | commentsAllowUnicode | Unicode permitido dentro de comentarios. |
| 5 | asciiSpacesAreAccepted | Espacios ASCII exteriores y múltiples. |
| 6 | mixedLfAndCrlfHaveNoPhantomLine | LF/CRLF mixtos sin línea final ficticia. |
| 7 | finalNewlineIsOptional | Última línea sin terminador. |
| 8 | oneInitialBomIsAccepted | BOM único al inicio cuenta bytes, no líneas. |
| 9 | repeatedBomIsRejected | BOM duplicado rechazado. |
| 10 | interiorBomIsRejectedEvenInComments | BOM interior rechazado incluso en comentarios. |
| 11 | truncatedUtf8HasOriginalCodingCause | UTF-8 truncado con causa original y sin línea falsa. |
| 12 | malformedUtf8IsNeverReplaced | UTF-8 malformado, sobrelargo, surrogate y bytes UTF-16 rechazados. |
| 13 | emptyVariantsRejectWithoutFakeLine | Vacío, BOM solo, espacios y comentarios producen EMPTY_BATCH. |
| 14 | controlsAreInvalidInsideCommentsAndBlankLines | Tabs, NUL, DEL y controles en comentarios/instrucciones. |
| 15 | unicodeIsNotAcceptedInInstructions | Unicode, NBSP y caracteres invisibles en instrucciones. |
| 16 | unknownInstructionsAndControlsAreRejected | Instrucciones desconocidas, minúsculas, controles y mensajes V1. |
| 17 | directionsAreExactUppercaseTokens | Direcciones exactas UP/DOWN. |
| 18 | arityIsExactAndInlineCommentsAreRejected | Aridad y comentarios finales. |
| 19 | signedDecimalAndExponentNumbersAreRejected | Signos, decimales, exponentes y formatos numéricos no admitidos. |
| 20 | integerOverflowIsRejectedWithoutWrapping | Desbordamiento protegido y aceptación de Integer.MAX_VALUE si el edificio lo admite. |
| 21 | leadingZerosDoNotImplyNumericOverflow | Ceros iniciales extensos sin falso desbordamiento. |
| 22 | floorBoundsAreChecked | Pisos cero y fuera del edificio. |
| 23 | cabinIdBoundsAreCheckedBeforeFollowingArguments | Cabinas cero/fuera de N y precedencia del primer argumento. |
| 24 | impossibleHallDirectionsAreRejected | Sentidos imposibles y extremos válidos. |
| 25 | singleFloorBuildingAcceptsOnlyCabinRequests | P = 1 permite destinos internos, sin llamadas externas válidas. |
| 26 | everyInvalidLineHasOneOrderedDiagnostic | Un diagnóstico por línea inválida, completo y ordenado. |
| 27 | finalLineErrorRejectsEntireBatch | Error en última línea rechaza todo el lote. |
| 28 | invalidReadCannotChangePreviousValidBatch | Lecturas inválidas no cambian un lote válido anterior. |
| 29 | exactByteLimitIsAccepted | 1 MiB exacto aceptado. |
| 30 | oneByteOverLimitIsRejected | Un byte sobre el límite rechazado. |
| 31 | exactlyTenThousandPhysicalLinesAreAccepted | 10,000 líneas físicas exactas aceptadas. |
| 32 | tenThousandAndOneLinesAreRejected | Línea 10,001 rechazada, incluidas vacías. |
| 33 | supplementaryCodePointsUseUnicodeLength | 4,096 puntos de código con caracteres suplementarios, no unidades UTF-16. |
| 34 | overlongLinesAreRejectedWithoutTruncation | 4,097 puntos de código y líneas de espacios excesivas rechazados sin truncar. |
| 35 | bareCarriageReturnIsRejected | CR aislado rechazado con línea conocida. |
| 36 | unicodeLineSeparatorsAreRejectedEvenInComments | U+0085/U+2028/U+2029 rechazados incluso en comentarios. |
| 37 | diagnosticsUsePhysicalLinesAfterBomAndComments | Línea física correcta tras BOM, comentarios y CRLF. |
| 38 | realPathIsReadAndReleased | Archivo real leído sin depender de extensión y handle liberado. |
| 39 | missingPathReturnsReadErrorWithCause | Archivo inexistente conserva causa de lectura. |
| 40 | directoryCannotBeReadAsRequestFile | Directorio rechazado como archivo de solicitudes. |
| 41 | streamFailureKeepsOriginalExceptionAndNoPartialBatch | Fallo tras prefijo válido conserva IOException por identidad y no entrega parcial. |
| 42 | borrowedStreamsRemainOpenOnSuccessAndFailure | Stream prestado permanece abierto tanto en éxito como en rechazo. |
| 43 | unboundedStreamConsumesOnlyLimitPlusOneBytes | Stream infinito consume como máximo límite + 1 bytes. |
| 44 | zeroLengthBulkReadsDoNotLoseBytes | Lectura de bloque de cero bytes usa alternativa sin perder contenido. |
| 45 | interruptionPreservesFlagAndRejectsRead | Interrupción conserva flag y causa; hilo de prueba termina. |
| 46 | sharedReaderSupportsIndependentConcurrentReads | 24 lecturas independientes con cuatro hilos comparten lector sin mezclar resultados. |
| 47 | resultAndBatchAreImmutableAndRejectInvalidConstruction | Copias defensivas, listas inmutables e invariantes de construcción. |
| 48 | parserAgreesWithExistingDomainRules | Matriz de reglas comparada con HallRequest y CabinRequest existentes. |

## Alcance de la evidencia

- Los bucles internos prueban varias entradas y configuraciones; siguen siendo 48 escenarios JUnit, no un caso JUnit por iteración.
- La prueba de concurrencia usa 24 operaciones sobre cuatro hilos, con señal de salida y futuros acotados; cierra el ejecutor y espera sus productores.
- La validación de dominio compara edificios de 1–3 cabinas y 1–8 pisos, destinos dentro/fuera de rango y ambas direcciones. Los códigos conservan las prioridades de campos documentadas para el archivo.
- Los límites de bytes y líneas usan contenido generado de tamaño exacto. La longitud Unicode se comprueba con caracteres suplementarios en comentarios.
- La propiedad de lectura se verifica con streams prestados observables y un archivo temporal que se elimina después de read(Path). En Linux, eliminar no demuestra por sí solo el cierre del handle; la estructura try-with-resources implementa ese cierre y la misma comprobación se ejecutará en Windows.
- No se implementó ni probó sustitución de lote en el controlador. El lector no recibe ningún lote anterior, y su resultado no contiene parcial alguno ante error; la confirmación e invalidación por RESET corresponden a T45.
- No se ejecutó Maven/JUnit, una interfaz gráfica ni una regresión completa nueva del motor. Al ser adiciones aisladas a io y un diagnóstico, se verificaron los escenarios del parser y la compilación conjunta; la suite Maven completa queda para el equipo de David.
- T46 ampliará archivos de muestra y errores de permisos/selector. No se presenta como probado un cambio de permisos específico de Windows.

## Validación pendiente en Windows

```powershell
mvn -Dtest=RequestFileTest test
mvn "-Dtest=RequestFileTest,ModelRulesTest,RequestTest,SimulationConfigTest" test
mvn clean verify
```

Se esperan 48 específicas, 72 seleccionadas y 506 acumuladas, sin fallos, errores u omisiones, con BUILD SUCCESS. Son expectativas sobre las 458 pruebas previamente confirmadas; registrar las cifras reales antes de cerrar T44.

Los comandos del CLI y de incorporación están en [ficha T44](../implementacion/T44-parser-y-validacion-lote.md). Commit sugerido: `T44: implementar parser y validacion de lote`.


## Confirmación posterior de David — 2026-10-28

T44 cerrada en commit `9ae324d`: 48 pruebas específicas y 506 acumuladas, sin fallos, errores u omisiones; ambas ejecuciones finalizaron con BUILD SUCCESS. Esta evidencia sustituye el estado pendiente de Maven de la entrega original. No se recibió ejecución separada de la selección de 72 pruebas ni confirmación manual del diagnóstico T44.
