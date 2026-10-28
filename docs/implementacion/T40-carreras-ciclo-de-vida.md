# T40 — Probar carreras del ciclo de vida

- **Fecha:** 2026-10-28, Guatemala.
- **Fase:** 6 — Implementar ciclo de vida y reset.
- **Base:** T39, commit `74d467d`; David confirmó 32 pruebas específicas, 164 seleccionadas y 394 acumuladas, BUILD SUCCESS, comprobaciones de espacios y árbol limpio. La comprobación visual de Cerrar/X aún no fue confirmada.
- **Estado:** 24 escenarios nuevos implementados y comprobados localmente; pendiente de Maven en el equipo de David. No se declara cerrada todavía la fase 6.

## 1. Alcance y resultado

Se agregan LifecycleRaceScenarios y sus 24 adaptadores JUnit LifecycleRaceTest. No cambian los fuentes de producción ni pom.xml: las carreras comprobadas se resuelven con las barreras y correlaciones implementadas en T35–T39.

La aceptación de T40 cubre reset durante movimiento y parada de servicio, Stop repetido, reset desde detenido y cierre durante reset. Se amplía con admisiones concurrentes, mensajes diferidos al EDT, conservación de identidades Thread y cierre repetido de sesiones. El estrés general con ráfagas, pausas y todas las funciones futuras permanece en T69.

## 2. Matriz de los 24 escenarios

| N.º | Método de escenario | Comprobación |
| ---: | --- | --- |
| 1 | resetDuringMovementCancelsOldRoute | Retorno físico, descarte del destino anterior y servicio posterior. |
| 2 | resetDuringSeveralCabinJourneysIsGlobal | Cuatro cabinas en viaje; todas confirman el retorno de la misma época. |
| 3 | resetDuringDwellDoesNotWaitForDwell | Reset durante parada configurada en 60 s, sin esperar esa parada. |
| 4 | resetWhileDwellIsStoppedClearsHold | Reset desde parada congelada por Stop; no conserva HELD. |
| 5 | resetFromStoppedDiscardsPreservedDestination | Stop conserva el destino y reset lo invalida antes de continuar. |
| 6 | concurrentInitialStartCreatesOneSetOfActors | Ocho Start simultáneos, un único arranque efectivo. |
| 7 | concurrentStartFromStoppedResumesOnce | Ocho Start desde detenido, una continuación y las mismas referencias Thread. |
| 8 | concurrentStopWhenIdleHasOnePhysicalBarrier | Ocho Stop y una sola confirmación por cabina. |
| 9 | concurrentStopDuringMovementPreservesRoute | Stop simultáneo conserva la ruta, que se atiende después de Start. |
| 10 | startAndStopRaceHasAValidSerializableOutcome | Cuarenta carreras Start/Stop; resultado y estado final coherentes con el orden efectivo. |
| 11 | stopAndResetRaceCannotRestoreStoppedEpoch | Treinta carreras Stop/reset; correlación de época y descarte de ack Stop tardío. |
| 12 | startAndResetRaceCannotStartOldWork | Start antes, durante o después del reset, sin trabajo antiguo. |
| 13 | cabinAdmissionRacingResetDoesNotSurviveIt | Veinte carreras de admisión interna/reset; registro y agendas limpios. |
| 14 | hallAdmissionRacingResetDoesNotSurviveIt | Veinte carreras de llamada externa/reset; mensajes viejos no reingresan. |
| 15 | closeWhileResettingResolvesEveryRacingControl | Reset físico de tramo largo en curso; cierre resuelve reset, Start y Stop concurrentes. |
| 16 | closeWhileStoppingResolvesEveryRacingControl | Cierre no espera un tramo de 60 s y resuelve las barreras Stop pendientes. |
| 17 | initialStartRacingCloseCannotResurrectSession | Veinte sesiones con Start/cierre concurrentes; no reinician tras CLOSED. |
| 18 | admissionsRacingCloseAreResolvedWithoutServiceClaim | Admisiones internas/externas y cierre simultáneos; ENQUEUED no se convierte en servicio. |
| 19 | closingGateRejectsAllControlsUntilResourceRelease | Recurso retenido por latch; CLOSING rechaza controles y solo confirma al liberar. |
| 20 | delayedEdtMessagesAreRevalidatedBeforeApplication | Respuesta/evento extraídos antes de reset se entregan después al EDT y se descartan; mensaje nuevo sí se aplica. |
| 21 | bufferedHistoryAcrossEpochsDoesNotCompleteFreshWork | Seis épocas con historial acumulado; servicio nuevo correlacionado por UUID y commandId. |
| 22 | cancelledStopObserverDoesNotCancelResetOrClose | Cancelar futuros visibles no cancela la operación privada de control. |
| 23 | repeatedMixedControlsKeepExactThreadIdentities | Cien carreras mezcladas Stop/Start/reset con tres cabinas; época creciente e identidades constantes. |
| 24 | repeatedSessionsDoNotAccumulateOwnedThreads | Veinte sesiones con cierre concurrente; referencias capturadas terminadas. |

## 3. Cómo se provocan y verifican las carreras

race recibe acciones que devuelven CompletableFuture<Result>. Cada productor llega a un CountDownLatch de preparación y espera otro latch de salida. Se libera solo cuando todos están preparados. Los productores llaman a las APIs sin esperar la confirmación; después el escenario recoge los futuros y sus resultados. No se fija un orden artificial entre acciones: el coordinador puede procesarlas en cualquiera de los órdenes válidos.

Un plazo común de diez segundos cubre la preparación, los lanzamientos y las confirmaciones de cada carrera. Cada búsqueda de eventos usa también un plazo monotónico total de diez segundos, sin reiniciarlo por cada evento descartado. Los productores de prueba se interrumpen y se espera su terminación en finally. Los adaptadores JUnit añaden un límite externo de 90 segundos por escenario, incluidos sus bucles internos.

No se usa Thread.sleep para inferir estados. Las precondiciones se esperan mediante DEPARTED, COMMAND_COMPLETED, RESET_STARTED, confirmaciones de control y latches. El evento de partida observa un viaje real; no se fuerza el planificador a detenerse justo después de emitirlo. Los casos de cierre usan tiempos de movimiento/parada de 60,000 ms para comprobar que el cierre despierta las esperas y no aguarda esas duraciones.

Las carreras Start/Stop no exigen siempre APPLIED: Start antes de Stop puede ser UNCHANGED, durante STOPPING puede ser REJECTED y después de STOPPED puede ser APPLIED. Se comprueba la pareja outcome/estado y el estado final correspondiente. Stop/reset comprueba además la época del resultado. No se inventa un orden universal de prioridad para acciones que todavía no se procesaron.

## 4. Mensajes antiguos y estado nuevo

La prueba de entrega diferida captura una respuesta de admisión y un evento reales de la época cero. Una etapa diferida no los entrega hasta que reset confirma la nueva época. Su callback se ejecuta en el EDT real y consulta isCurrent antes de modificar los contadores de aplicación. Ambos mensajes se descartan; un servicio de la época nueva sí se aplica. El EDT no se bloquea para simular el retraso.

La prueba de historial acumula mensajes en seis épocas sin consumirlos y luego espera una confirmación nueva a través de awaitEvent. Comprueba época, identidad canónica y commandId, evitando confundir limpieza de mensajes con atención real.

Estas pruebas verifican el contrato de consumo y el filtro del controlador; las pantallas operativas futuras deben conservar esa revalidación al aplicar mensajes. No implementan formularios de la fase 9 ni un consumidor gráfico de producción nuevo.

## 5. Hilos y limpieza de las pruebas

Rig es propietario de cada controlador de prueba. En close solicita cierre, espera su terminación, comprueba CLOSED, ausencia de failure, terminación de manejador/cabinas y que ninguna referencia Thread capturada permanezca viva. Un recurso de prueba captura también la referencia del cerrador durante su liberación.

Las capturas de actores comparan objetos Thread, no solo cantidades o nombres; cada grupo esperado tiene N cabinas y un manejador. La base de hilos se toma antes de cada sesión y se excluye de las capturas posteriores. Esta identificación está diseñada para la ejecución secuencial predeterminada de Maven/Surefire del proyecto; no habilitar ejecución paralela de clases sin aislar antes la identificación de sesiones. Los productores de race tienen sus propios ejecutores temporales, que también se cierran y esperan.

## 6. Archivos entregados

- src/test/java/com/elevatormanager/simulation/LifecycleRaceScenarios.java: los 24 escenarios, fixture y main independiente.
- src/test/java/com/elevatormanager/simulation/LifecycleRaceTest.java: 24 adaptadores JUnit con Timeout.
- docs/implementacion/T40-codigo-completo.md: ambos fuentes completos.
- docs/comprobaciones/T40-validacion-local.md: evidencia de regresión y repeticiones finales.
- docs/01-analisis-y-plan.md: estado de fase 6 y ficha T40.
- Fichas de implementación y validación T39: Maven y commit realmente confirmados; comprobación visual aún pendiente.

## 7. Validación en Windows

Extraer el ZIP sobre el proyecto local sin borrar el repositorio ni sus archivos. No incluye .git, target ni archivos de logs. Si hay cambios personales sin guardar, conservarlos antes de sobrescribir fuentes.

```powershell
Set-Location 'D:\Users\David\Desktop\workspace\java\elevator-manager'
git status --short
Expand-Archive -LiteralPath "$HOME\Downloads\T40-carreras-ciclo-de-vida.zip" -DestinationPath . -Force
mvn -Dtest=LifecycleRaceTest test
mvn "-Dtest=LifecycleRaceTest,OrderlyShutdownTest,GlobalResetTest,CabinResetTest,StopContinuationTest,SimulationControllerTest,ManagerWorkerTest,ExecutionEventTest" test
mvn clean verify
```

Resultados esperados sobre la base T39 confirmada: **24 específicas, 188 seleccionadas y 418 acumuladas**, sin fallos, errores u omisiones y con BUILD SUCCESS. Las iteraciones internas no suman nuevos casos JUnit. Para buscar variaciones del planificador opcionalmente:

```powershell
1..5 | ForEach-Object {
    mvn -Dtest=LifecycleRaceTest test
    if ($LASTEXITCODE -ne 0) { throw "Fallo de T40 en repeticion $_" }
}
```

Antes de cerrar formalmente la fase 6 también falta confirmar la revisión visual de T39: ejecutar dos veces el JAR; cerrar primero con Cerrar y después con la X, comprobando retorno al prompt en ambos casos.

```powershell
java -jar .\target\elevator-manager-0.1.0-SNAPSHOT.jar
```

## 8. Incorporación y commit sugerido

Después de validar Maven, actualizar el estado y la casilla T40 del plan con las cifras reales. La casilla T39 queda condicionada a la comprobación visual pendiente. Para incorporar los ocho archivos de esta entrega:

```powershell
$archivosT40 = @(
    'src/test/java/com/elevatormanager/simulation/LifecycleRaceScenarios.java',
    'src/test/java/com/elevatormanager/simulation/LifecycleRaceTest.java',
    'docs/01-analisis-y-plan.md',
    'docs/implementacion/T40-carreras-ciclo-de-vida.md',
    'docs/implementacion/T40-codigo-completo.md',
    'docs/comprobaciones/T40-validacion-local.md',
    'docs/implementacion/T39-cierre-ordenado.md',
    'docs/comprobaciones/T39-validacion-local.md'
)
git diff --check
git add -- $archivosT40
git diff --cached --check
git diff --cached --stat
git commit -m "T40: probar carreras del ciclo de vida"
git status --short
```

La fase 6 se declara completada cuando T40 esté validada e incorporada y la comprobación pendiente de T39 esté confirmada. Después corresponde T41 de la fase 7.
