# T23 — Implementar desplazamiento entre pisos

- **Proyecto:** Elevator Manager.
- **Fecha:** 2026-10-21, Guatemala.
- **Versión:** 0.1.0.
- **Fase:** 4 — Construir el motor concurrente de elevadores.
- **Estado:** implementación terminada y escenarios ejecutados; Terminada integración y regresión Maven en el proyecto.
- **Base validada:** T22, 59 pruebas, commits `fe9a467` y `316f11f`.

## 1. Objetivo

Desplazar una cabina un piso a la vez, con la duración compartida de `SimulationConfig`. El piso lógico permanece en el último piso alcanzado durante todo el tramo. La llegada solo ocurre al vencer el tiempo y ser confirmada por el propietario del estado.

Se conservan las firmas de configuración, solicitudes, comandos, snapshots y API pública del trabajador de T22. No se añaden dependencias ni se cambia el POM.

## 2. Archivos del paquete

El ZIP contiene rutas relativas directamente bajo `src/` y `docs/`. Extraer en una carpeta temporal y copiar su contenido a la raíz Maven conservando rutas y reemplazando únicamente los tres archivos existentes indicados.

| Acción | Ruta |
| --- | --- |
| Reemplazar | `src/main/java/com/elevatormanager/model/ElevatorState.java` |
| Reemplazar | `src/main/java/com/elevatormanager/simulation/ElevatorWorker.java` |
| Reemplazar | `src/test/java/com/elevatormanager/simulation/ElevatorWorkerScenarios.java` |
| Crear | `src/test/java/com/elevatormanager/simulation/ElevatorMovementScenarios.java` |
| Crear | `src/test/java/com/elevatormanager/simulation/ElevatorMovementTest.java` |
| Crear | `docs/implementacion/T23-desplazamiento-entre-pisos.md` |

Conservar `ElevatorWorkerTest.java` y las otras pruebas. La modificación del escenario T22 cambia su afirmación histórica sobre ausencia de movimiento y usa un tiempo amplio de movimiento para probar admisión independientemente del desplazamiento.

## 3. Modelo físico

`ElevatorState` conserva siguiente piso, objetivo comprometido y referencia monotónica de inicio del tramo. Continúa siendo privado y con un solo escritor; sus métodos nuevos no son una API segura para mutarlo desde GUI o manejador.

| Método | Contrato |
| --- | --- |
| `beginJourney(targetFloor, nowNanos)` | Desde IDLE sin compromiso, fija dirección y primer tramo adyacente. Mismo piso devuelve false sin mutación. |
| `beginNextLeg(nowNanos)` | Inicia únicamente el siguiente tramo del objetivo vigente. |
| `movementRemainingNanos(nowNanos)` | Consulta restante sin cambiar campos ni confirmar llegada. |
| `completeLegIfDue(nowNanos)` | Confirma como máximo una llegada, solo al vencer el tramo. |
| `getCommittedFloor()` | Objetivo físico vigente como OptionalInt. |
| `snapshot(referenceNanos)` | Publica fase, siguiente piso, compromiso y restante coherentes. |

Ejemplo con 100 ms entre pisos y objetivo 4: al salir de 1, `currentFloor=1`, `nextFloor=2`, dirección UP y fase MOVING. A los 50 ms el piso lógico sigue siendo 1. Al confirmar los 100 ms llega a 2 y comienza, si corresponde, otro tramo completo hacia 3. El objetivo lejano nunca se usa como sustituto del siguiente piso.

Al llegar al objetivo, queda IDLE y conserva la dirección de viaje. No se cambia automáticamente a UP: ese valor solo es obligatorio en el inicio y al completar reset.

## 4. Temporización y concurrencia

- Producción usa `System.nanoTime()`. Las pruebas físicas pasan instantes explícitos.
- Se calcula restante mediante diferencias, sin sumar deadlines susceptibles de desbordarse.
- La conversión ms→ns es exacta dentro del máximo validado por `SimulationConfig`.
- Se soporta el cruce normal del límite firmado de nanoTime; cada intervalo medido debe ser menor que 2^63 ns. Un reloj retrocedido o intervalo no representable se rechaza.
- Un despertar tardío confirma una sola llegada. El siguiente tramo empieza con duración completa y no consume un sobrante del tramo anterior.
- Sin mensajes ni movimiento, el worker usa `Condition.await()`.
- En movimiento, usa `Condition.awaitNanos(restante)`, que libera el lock y permite recibir nuevas órdenes o cierre.
- Cada iteración reevalúa cierre, plazo y mensajes. Una señal o despertar espurio no demuestra que el tramo haya vencido.
- Un flujo de mensajes no posterga indefinidamente una llegada vencida: el plazo se comprueba antes de extraer otro servicio.
- Admitir solicitudes durante movimiento no reinicia el reloj ni cambia el objetivo vigente.
- La publicación sigue siendo atómica y los resultados se emiten fuera del lock.

Los snapshots son capturas. No se refrescan por sondeo: al recibir órdenes o cambiar físicamente la fase se publica otro. Una UI futura podrá interpolar la referencia temporal sin confirmar llegadas.

## 5. Límite de T23

El trabajador se aproxima al objetivo del primer comando aceptado cuando está libre. Es una conexión mínima de movimiento, no el algoritmo definitivo de selección/agenda de T25.

Si ese objetivo coincide con el piso actual, espera allí. Si es otro piso, recorre sus tramos y, al alcanzarlo, activa una espera interna de servicio. Las solicitudes permanecen en la agenda y el trabajador sigue recibiendo mensajes y cierre; no inicia por sí solo el siguiente objetivo mientras falta resolver el servicio.

Esta frontera es intencional: **llegar no equivale a atender**. T24 incorporará la parada temporizada, atención compatible y continuación. T25 completará la elección por cercanía, orden de admisión y barridos por sentido, incluyendo paradas intermedias y ampliación de frontera. T23 no se presenta como una simulación funcional completa ni como cierre de esas tarjetas.

Las llamadas de sentido contrario se conservan: el viaje de aproximación no las atiende automáticamente. Tampoco se apagan destinos internos al llegar.

Se conserva el rechazo explícito de Reset operativo hasta fase 6. El cierre local de T22 despierta la espera temporal, termina realmente el hilo y conserva el último snapshot histórico para inspección. Ese snapshot puede describir el tramo interrumpido: la disponibilidad del trabajador se obtiene de `lifecycle()`, no de su fase histórica. La proyección de cierre/cancelación global se completará en fase 6.

La primitiva física de iniciar recorrido exige autorización del planificador; no comprueba que el objetivo pertenezca a una agenda. Esto permite probar bajada y tramos sin mezclarla con admisión o servicio.

## 6. Pruebas

| Escenario nuevo | Verificación |
| --- | --- |
| adjacentAndDeadline | Tramo adyacente, sin llegada anticipada ni llegada repetida. |
| lateWakeupDoesNotSkipFloors | Un despertar tardío no salta pisos y el tramo siguiente exige su duración. |
| upwardAndDownwardJourney | Recorridos físicos completos en ambos sentidos; IDLE conserva DOWN. |
| invalidTargetsAreAtomic | Rechazos sin cambiar estado; objetivo Integer.MAX_VALUE sin desbordar el tramo. |
| commitmentCannotBeReplacedMidJourney | Una orden nueva no reinicia el tiempo ni permite invertir el compromiso. |
| snapshotsDoNotConfirmArrivals | Progreso/restante coherentes; consultar no muta revisión ni confirma llegada. |
| clockWrapAndMaximumDuration | Diferencias del reloj a través del wrap y duración máxima representable. |
| singleFloorAndCurrentFloor | Edificio P=1 y selección del piso actual sin desplazamiento. |
| workerMovesWithoutServingRequests | Movimiento real por hilo, tiempo mínimo de dos tramos y pendientes conservados. |
| closeWakesTimedWait | Cierre despierta un tramo configurado para durar un minuto y termina dentro del plazo de prueba. |

Las ocho pruebas físicas usan reloj explícito y no esperas reales. Las dos pruebas de integración tienen plazo máximo de cinco segundos; observan snapshots con una espera breve de diagnóstico, exclusivamente en pruebas. No se comprueba igualdad exacta de tiempos del planificador ni un límite máximo estricto del recorrido físico. El cierre tiene plazo acotado.

## 7. Verificación ejecutada aquí

Compilación de producción y escenarios con Java 17 y clases existentes de modelo/protocolo. Ejecución real de los escenarios nuevos y de T22:

```text
T23: 10 escenarios satisfactorios.
T22: 7 escenarios satisfactorios.
```

Maven no está instalado en este entorno. Aquí no se ejecutaron los adaptadores JUnit ni la regresión íntegra de las 59 pruebas previas. Los resultados Maven siguientes son esperados hasta que David comparta su ejecución real.

## 8. Validación de integración

Desde la raíz del proyecto en PowerShell:

```powershell
mvn "-Dtest=ElevatorWorkerTest,ElevatorMovementTest" test
mvn clean verify
```

Esperado: 17 pruebas específicas y 69 acumuladas si se conservan las 59 anteriores; cero fallos, errores y omitidas; `BUILD SUCCESS` y JAR generado. No se modifica todavía el arranque Swing; el movimiento se valida por pruebas.

## 9. Cierre y commit después de validar

Solo cuando ambas ejecuciones sean satisfactorias:

1. Cambiar el estado de este documento a `Terminada; integración y regresión Maven satisfactorias`.
2. Registrar los conteos y resultado reales en la sección 7, conservando la distinción entre verificación local y Maven.
3. Marcar T23 con `[x]` en `docs/01-analisis-y-plan.md`.
4. Revisar y crear el commit:

```powershell
git status --short
git add -- src/main/java/com/elevatormanager/model/ElevatorState.java src/main/java/com/elevatormanager/simulation/ElevatorWorker.java src/test/java/com/elevatormanager/simulation/ElevatorWorkerScenarios.java src/test/java/com/elevatormanager/simulation/ElevatorMovementScenarios.java src/test/java/com/elevatormanager/simulation/ElevatorMovementTest.java docs/implementacion/T23-desplazamiento-entre-pisos.md docs/01-analisis-y-plan.md
git diff --cached --check
git diff --cached --stat
git diff --cached
git commit -m "T23: implementar desplazamiento temporizado entre pisos"
git status --short
git log -1 --oneline
```

No incluir archivos de otras tareas ya preparados sin revisarlos. La actualización del plan se hace en el proyecto actual para conservar sus checks aprobados. El ZIP no contiene un plan antiguo que pueda sobrescribirlos.

Próxima tarjeta, después de validar y cerrar T23: **T24 — Implementar parada y atención**.

## 10. Historial

| Versión | Fecha | Cambio |
| --- | --- | --- |
| 0.1.0 | 2026-10-21 | Tramos temporizados, integración del trabajador, diez escenarios y ajuste de regresión T22. |
