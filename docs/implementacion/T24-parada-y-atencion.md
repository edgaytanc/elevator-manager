# T24 — Implementar parada y atención

- **Proyecto:** Elevator Manager.
- **Fecha:** 2026-10-21, Guatemala.
- **Versión:** 0.1.0.
- **Fase:** 4 — Construir el motor concurrente de elevadores.
- **Estado:** implementación terminada; escenarios ejecutados; terminada integración y regresión Maven.
- **Base:** T23 validada con 69 pruebas; no se presupone un hash de commit no comunicado.

## 1. Objetivo y aceptación

Implementar una parada con el tiempo compartido de `SimulationConfig`, atender solicitudes compatibles y publicar sus indicadores. Una solicitud deja de figurar pendiente al iniciar su servicio, aunque todavía falte terminar la parada.

La llegada física, la admisión de comandos y el servicio siguen siendo hechos diferentes. Una llamada externa exige coincidencia de piso y dirección de servicio; un destino interno exige coincidencia de cabina y piso.

## 2. Archivos

El ZIP contiene siete archivos con rutas relativas bajo `src/` y `docs/`. Extraer primero en una carpeta temporal y copiar conservando rutas a la raíz Maven.

| Acción | Ruta |
| --- | --- |
| Reemplazar | `src/main/java/com/elevatormanager/model/ElevatorState.java` |
| Reemplazar | `src/main/java/com/elevatormanager/simulation/ElevatorWorker.java` |
| Reemplazar | `src/test/java/com/elevatormanager/simulation/ElevatorMovementScenarios.java` |
| Reemplazar | `src/test/java/com/elevatormanager/simulation/ElevatorMovementTest.java` |
| Crear | `src/test/java/com/elevatormanager/simulation/ElevatorDwellScenarios.java` |
| Crear | `src/test/java/com/elevatormanager/simulation/ElevatorDwellTest.java` |
| Crear | `docs/implementacion/T24-parada-y-atencion.md` |

Conservar el resto del proyecto, incluyendo el POM, `ElevatorWorkerTest.java` y la versión de `ElevatorWorkerScenarios.java` entregada en T23. No se añaden dependencias ni se cambia la entrada Swing.

La prueba de integración de movimiento se actualiza: ahora debe observar DWELLING y la eliminación del destino servido, conservando el pendiente que está detrás. Sus otras nueve pruebas físicas mantienen su propósito.

## 3. Operaciones del modelo

`ElevatorState` continúa siendo privado de un trabajador y no es thread-safe. Solo ese propietario invoca las transiciones. Los demás consumidores reciben snapshots.

| Operación | Comportamiento |
| --- | --- |
| `beginDwell(direction, now)` | Desde IDLE alineada, abre parada si hay solicitudes compatibles y devuelve sus identidades. Retira exactamente esas solicitudes. |
| `serveDuringDwell(now)` | Retira nuevas solicitudes compatibles solo mientras la parada sigue abierta, sin cambiar su instante inicial. |
| `dwellRemainingNanos(now)` | Consulta pura del restante de parada. |
| `phaseRemainingNanos(now)` | Consulta pura del restante de movimiento o parada. |
| `completeDwellIfDue(now)` | Cambia DWELLING a IDLE una sola vez, al vencer el tiempo. |

Las listas de solicitudes servidas son copias inmutables. El snapshot anterior conserva su contenido. El snapshot nuevo ya no muestra los destinos o llamadas atendidos: esa será la fuente de los indicadores de la futura GUI.

No se abre una parada vacía. Abrir o atender servicio exige cabina físicamente alineada; no se sirve al piso recién dejado durante MOVING. Una parada intermedia conserva el compromiso de recorrido y no autoriza invertirlo.

## 4. Semántica del servicio

| Situación | Resultado |
| --- | --- |
| Cabina en piso de un destino interno | Retira ese destino al comenzar servicio. |
| Destino interno y externa compatible en el mismo piso | Se atienden en una misma parada con una sola duración. |
| Externa DOWN durante parada UP | Permanece pendiente. |
| Nueva solicitud compatible durante parada todavía abierta | Se atiende sin reiniciar el tiempo. |
| Solicitud compatible procesada después de vencer la parada | Se conserva hasta abrir una nueva parada completa. |
| Llegada a piso sin servicio compatible y con compromiso por delante | Continúa al siguiente tramo. |
| Llegada intermedia con servicio compatible | Abre parada y luego continúa el compromiso tras agotar su duración. |
| Llegada a frontera sin servicio en dirección de viaje, con externa contraria | Puede cambiar dirección para abrir el servicio solicitado. |
| Externas de ambos sentidos en frontera | Se atienden mediante paradas separadas y duración completa para cada sentido. |
| No quedan pendientes ni compromiso | Permanece IDLE y espera bloqueada. |

Una cabina libre que sube de 1 a recoger una llamada 3/DOWN puede servirla en DOWN al llegar a 3. Si existe un compromiso UP hasta un piso superior, no invierte en 3 para esa recogida.

La expiración se decide con reloj monotónico y es comprobada antes de extraer otra orden. Los despertares espurios o nuevas órdenes no terminan ni reinician una parada. Las conversiones y diferencias mantienen las reglas numéricas de T23, incluido el cruce normal del límite firmado de nanoTime con intervalos representables.

## 5. Integración del trabajador

El worker usa `awaitNanos` tanto en MOVING como en DWELLING. La espera libera el lock local del buzón: nuevas órdenes y cierre pueden despertarlo. Sin trabajo usa `await`. No se introduce sondeo activo en producción ni espera en EDT.

Tras admitir un comando, confirmar una llegada o terminar una parada, el trabajador evalúa la siguiente transición local. Primero intenta servicio en su dirección actual; si conserva compromiso y no hay servicio, inicia el siguiente tramo. Sin compromiso puede abrir una parada de sentido opuesto en ese mismo piso.

Para permitir continuidad después de servir, la selección provisional toma el primer destino interno pendiente; si no hay internas, la primera externa. Conserva el orden local de sus mapas. **No es el algoritmo definitivo ni una garantía de eficiencia o ausencia de inanición.** T25 implementará la agenda ordenada por sentido, selección inicial por cercanía, desempates y ampliación de frontera. No se declara esa tarjeta completada.

No se amplía la frontera durante movimiento en T24. El trabajo nuevo por delante o detrás permanece en la agenda y puede servirse al pasar si es compatible, respetando el compromiso vigente.

## 6. Canal de hechos de servicio

El nuevo record anidado `ElevatorWorker.Service` contiene ID de elevador, época, revisión, piso, dirección, instante monotónico, indicador de apertura y solicitudes servidas. `openedStop=true` representa una parada nueva; false, incorporación a una parada todavía abierta.

`awaitService(Duration)` permite a un consumidor lógico recibir estos hechos. Es un canal separado de `awaitResult(Duration)`: ACCEPTED sigue significando incorporación a agenda, no llegada ni servicio. El consumidor futuro debe correlacionar por UUID/época; no asumir que un orden de lectura entre canales representa un único canal global.

El productor prepara los eventos bajo el lock y los encola después de liberarlo, antes de procesar otra transición. No ejecuta callbacks de consumidores. Los snapshots pueden omitir estados intermedios de pintura; los eventos de servicio no se descartan en ejecución normal.

Las colas son fiables en memoria durante ejecución normal, no un historial duradero frente a caída de JVM. T27 integrará el vocabulario completo de salida/llegada/cambio de dirección/finalización. El manejador posterior deberá drenar y distribuir los servicios; no se deben agregar consumidores que compitan por la misma cola. El visor gráfico tendrá su propia política de memoria.

La admisión heredada deduplica identidades mientras están pendientes y rechaza duplicados funcionales activos. T26 implementará la política completa de transporte/idempotencia por commandId y repetición de mensajes ya atendidos. T24 no promete ejecución exactamente una vez frente a retransmisión de comandos ya completados.

## 7. Cierre y alcance pendiente

El cierre técnico de T22 despierta también la espera de parada. Conserva el último snapshot histórico; la disponibilidad real del trabajador se obtiene de `lifecycle()`. El cierre/cancelación global y la transición física publicada TERMINATED se completarán en fase 6, junto con Stop y reset.

El reset operativo continúa rechazado explícitamente. No se simula la eliminación global de pendientes ni retorno a piso 1 mediante este cierre local. Los fallos inesperados siguen visibles en `failure()` y las órdenes aún encoladas reciben rechazo al finalizar. Una excepción fatal después de mutar servicio puede dejar un resultado incierto: no se finge una confirmación ni se reasigna silenciosamente.

## 8. Verificación ejecutada aquí

Se compiló el código de producción y los escenarios contra Java 17 y el modelo/protocolo existentes. Se ejecutaron:

```text
T24: 15 escenarios satisfactorios.
T23: 10 escenarios satisfactorios.
T22: 7 escenarios satisfactorios.
```

| Escenario nuevo | Comprobación |
| --- | --- |
| cabinServedAtStopStart | Retiro al iniciar servicio, snapshot anterior intacto y lista inmutable. |
| oppositeHallRemainsPending | Servicio UP conserva la externa DOWN del mismo piso. |
| compatibleRequestsShareOneStop | Interna y externa compatible comparten duración; otros pisos permanecen pendientes. |
| noServiceDoesNotOpenStop | Sin compatibles no abre parada ni modifica revisión/dirección. |
| dwellCompletesOnceAtDeadline | No termina antes del plazo y no confirma dos veces. |
| compatibleArrivalDoesNotRestartTimer | Una compatible se incorpora sin reiniciar el reloj. |
| oppositeDirectionRequiresAnotherStop | Otro sentido requiere terminar y abrir otra parada completa. |
| expiredStopCannotAcceptLateService | Una solicitud tardía no entra en parada vencida. |
| motionAndCommittedDirectionAreProtected | No sirve durante tránsito ni invierte compromiso; continúa tras la parada intermedia. |
| dwellSupportsClockWrapAndMaximum | Diferencias monotónicas y duración máxima sin overflow. |
| workerApproachesAndServesRequestedDirection | Aproximación UP, recogida DOWN en frontera y evento inmutable. |
| workerSeparatesOppositeServices | Dos sentidos producen dos aperturas con el tiempo debido entre ellas. |
| workerJoinsOpenStopWithoutNewOpening | La solicitud compatible genera incorporación, no otra apertura. |
| workerContinuesAfterFullDwell | Continúa con trabajo pendiente tras consumir parada y traslado completos. |
| closeWakesDwellWait | Cierra con una parada configurada de un minuto sin esperar ese minuto. |

Los diez escenarios de modelo usan reloj explícito, sin sleeps. Los cinco escenarios de integración reciben resultados/servicios mediante colas y usan plazos acotados de cinco segundos, sin depender de un sleep para ordenar acciones. Los límites inferiores de tiempo permiten retrasos del planificador; no se exige igualdad exacta de reloj de pared.

Maven no está disponible aquí. No se ejecutó la regresión íntegra de las 69 pruebas anteriores ni los adaptadores JUnit. Los conteos de la siguiente sección son esperados hasta tener la salida real del proyecto.

## 9. Validación de integración

Desde la raíz Maven en PowerShell:

```powershell
mvn "-Dtest=ElevatorWorkerTest,ElevatorMovementTest,ElevatorDwellTest" test
mvn clean verify
```

Esperado: 32 pruebas específicas y 84 acumuladas si se conservan las 69 anteriores. Deben quedar cero fallos, errores y omitidas, `BUILD SUCCESS` y JAR generado. El arranque de la GUI no cambia: todavía no se integra el formulario ni el controlador de sesión.

## 10. Cierre y commit tras validar

Una vez que ambas ejecuciones sean satisfactorias:

1. Actualizar el estado de este documento a `Terminada; integración y regresión Maven satisfactorias`.
2. Registrar los conteos y fecha reales de Maven, sin confundirlos con los escenarios ejecutados aquí.
3. Marcar T24 con `[x]` en `docs/01-analisis-y-plan.md`.
4. Revisar y crear el commit:

```powershell
git status --short
git add -- src/main/java/com/elevatormanager/model/ElevatorState.java src/main/java/com/elevatormanager/simulation/ElevatorWorker.java src/test/java/com/elevatormanager/simulation/ElevatorMovementScenarios.java src/test/java/com/elevatormanager/simulation/ElevatorMovementTest.java src/test/java/com/elevatormanager/simulation/ElevatorDwellScenarios.java src/test/java/com/elevatormanager/simulation/ElevatorDwellTest.java docs/implementacion/T24-parada-y-atencion.md docs/01-analisis-y-plan.md
git diff --cached --check
git diff --cached --stat
git diff --cached
git commit -m "T24: implementar parada temporizada y atencion compatible"
git status --short
git log -1 --oneline
```

Revisar que no se incluyan cambios preparados de otra tarea. El ZIP no contiene un plan antiguo que pueda sobrescribir los checks actuales. Próxima tarjeta, después de validar y cerrar: **T25 — Implementar agenda ordenada por sentido**.

## 11. Historial

| Versión | Fecha | Cambio |
| --- | --- | --- |
| 0.1.0 | 2026-10-21 | Parada temporizada, servicio compatible, eventos de servicio y quince escenarios. |
