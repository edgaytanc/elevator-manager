# T25 — Implementar agenda ordenada por sentido

- **Proyecto:** Elevator Manager.
- **Fecha:** 2026-10-22, Guatemala.
- **Versión:** 0.1.0.
- **Fase:** 4 — Construir el motor concurrente de elevadores.
- **Estado:** implementación Terminada; escenarios ejecutados; Terminada integración y regresión Maven.
- **Base:** T24 validada con 84 pruebas y commit `95c44c1`.
- **Diseño aplicado:** invariantes INV-15 a INV-18 de T15 y contratos de estado privado/snapshots de T14.

## 1. Objetivo

Sustituir la selección provisional de T24 por barridos monótonos con frontera comprometida, paradas compatibles en orden espacial y conservación del trabajo diferido. Se mantienen movimiento y parada temporizados y la diferencia entre admisión, llegada y servicio.

La agenda se calcula a partir de pendientes validados. Sus mapas conservan solicitudes; la dirección, el piso actual y la frontera determinan el orden real de las paradas. No se confunde el orden de admisión con la ruta espacial ni se expone una cola mutable.

## 2. Archivos y copia

Extraer el ZIP en una carpeta temporal. Copiar su contenido a la raíz del proyecto Maven conservando las rutas relativas.

| Acción | Ruta |
| --- | --- |
| Reemplazar | `src/main/java/com/elevatormanager/model/ElevatorState.java` |
| Reemplazar | `src/main/java/com/elevatormanager/simulation/ElevatorWorker.java` |
| Crear | `src/main/java/com/elevatormanager/simulation/DirectionalAgenda.java` |
| Crear | `src/test/java/com/elevatormanager/simulation/DirectionalAgendaScenarios.java` |
| Crear | `src/test/java/com/elevatormanager/simulation/DirectionalAgendaTest.java` |
| Crear | `docs/implementacion/T25-agenda-ordenada-por-sentido.md` |

Conservar las pruebas y fuentes anteriores en sus versiones validadas de T24, incluyendo los cambios de los escenarios de movimiento hechos en T24. No se cambia el POM ni se agregan dependencias. El ZIP no contiene un plan viejo que sobrescriba sus tarjetas completadas.

## 3. Política de recorrido

| Situación | Decisión |
| --- | --- |
| Cabina libre sin barrido anterior | Elegir el piso pendiente más próximo. |
| Empate por distancia | Conservar el orden global de admisión local entre internas y externas. |
| Objetivo seleccionado y otras internas por delante | La frontera incluye la interna más lejana en la dirección elegida. |
| Nueva interna por delante durante movimiento/parada | Puede ampliar la frontera en el sentido vigente. |
| Nueva externa más allá de la frontera | Queda pendiente; no amplía por sí misma el barrido. |
| Pedido detrás | Se conserva, sin invertir el tramo o el recorrido comprometido. |
| Llegada intermedia con servicio compatible | Abrir parada, servir y continuar después de consumir su tiempo. |
| Externa intermedia de sentido contrario | Conservar para un recorrido compatible posterior. |
| Fin de frontera con trabajo detrás | Iniciar regreso hasta el extremo pendiente de ese lado, recogiendo las compatibles al pasar. |
| Fin de frontera sin trabajo detrás | Elegir el siguiente objetivo próximo restante o quedar IDLE. |
| Solicitud al piso recién dejado | Diferirla; la cabina ya está en tránsito. |

La política de regreso hasta el extremo pendiente evita zigzags entre llamadas que se admitieron en otro orden. Incluye solicitudes de sentido contrario como objetivos físicos diferidos: solo se sirven cuando coincide su dirección, eventualmente al invertir en una frontera.

En la selección inicial la distancia se compara con long. El orden de admisión resuelve los empates antes que el piso; como cada admisión local tiene posición única, no queda un empate adicional por piso. No se usa `createdAt` para ordenar: una marca de tiempo importada o repetida no reemplaza el orden en que la cabina aceptó el trabajo.

Se conserva UP como dirección inicial e histórica cuando está libre, pero una cabina IDLE puede iniciar DOWN si su siguiente objetivo está debajo.

## 4. Estado y separación de responsabilidades

`DirectionalAgenda` es una clase pura, sin hilos, clocks, GUI, I/O ni mutaciones del modelo. `selectTarget(currentFloor, pending, completedSweep)` recibe una copia de pendientes en orden de admisión y devuelve un OptionalInt.

`ElevatorState` mantiene un índice privado `admissionOrder` con las mismas referencias a solicitudes inmutables de las agendas interna/externa. Se inserta solo tras la validación y se elimina al servir. No es una segunda fuente independiente de solicitudes; conserva el orden conjunto para desempatar. Las tres estructuras se modifican solo por su propietario. Los casos de prueba verifican su coherencia y la inmutabilidad de las copias.

`getPendingRequestsInAdmissionOrder()` entrega una copia inmutable para la decisión local. No se modifica `ElevatorSnapshot`: la UI futura sigue leyendo sus listas diferenciadas y la frontera publicada.

`beginJourney()` compromete el objetivo y las internas por delante que ya estaban pendientes. `addPendingRequest()` amplía la frontera si incorpora una interna por delante durante una fase activa o compromiso vigente. Ese cambio pertenece a la misma revisión de admisión; no reinicia el movimiento ni la parada.

`ElevatorWorker` conserva el sentido del barrido que acaba de alcanzar su frontera hasta decidir el siguiente recorrido. Ese sentido se registra antes de que el servicio en la frontera pueda invertir la dirección. Por tanto, una recogida contraria en la frontera no hace olvidar cuál era el lado pendiente del regreso.

El control del barrido terminado se limpia al iniciar/continuar otro o al quedar sin trabajo. La admisión, decisión y transición permanecen bajo el lock local. Los resultados y servicios se entregan después de liberarlo, como en T24. No se añade ningún bloqueo global ni espera activa.

## 5. Ejemplos verificables

| Pendientes admitidos durante subida hacia 8 | Orden de servicio esperado |
| --- | --- |
| Interna 8; externa 5/UP; externa 3/DOWN | 5/UP, 8/UP, 3/DOWN. |
| Internas 8, 4 y 6, aunque se admitan en ese orden | 4/UP, 6/UP, 8/UP. |
| Interna 4; interna nueva 7; externa 2/DOWN | 4/UP, 7/UP, 2/DOWN. |
| Interna 6; externas 2/DOWN y 5/DOWN | 6/UP, 5/DOWN, 2/DOWN. |

Las solicitudes atendidas siguen retirándose al iniciar el servicio. Una parada compatible abierta acepta incorporaciones sin reiniciar duración. Una parada de sentido contrario exige una apertura distinta, conforme a T24.

## 6. Verificación ejecutada aquí

Se compiló producción y escenarios con Java 17, contra las clases existentes de modelo/protocolo. Las ejecuciones reales produjeron:

```text
T25: 20 escenarios satisfactorios.
T24: 15 escenarios satisfactorios.
T23: 10 escenarios satisfactorios.
T22: 7 escenarios satisfactorios.
```

Los diecisiete escenarios deterministas nuevos verifican selección mixta por cercanía; empate por admisión en lugar de timestamp/tipo; índice inmutable y limpieza; orden ascendente y descendente; llamadas detrás; recogida compatible; diferimiento contrario; ampliación de interna en tránsito; externa fuera de frontera; inclusión inicial de internas; ampliación durante parada sin reiniciar reloj; piso recién dejado; extremo de regreso; selección DOWN y ampliación dinámica DOWN; ausencia de trabajo/piso único/distancias extremas; duplicados pendientes sin reordenar ni alterar frontera.

Los tres escenarios de integración usan trabajadores reales y el canal de servicios: ruta clásica 5/8/3, ampliación 4/7/2 y regreso 6/5/2. Envían órdenes y esperan resultados/eventos con plazos de cinco segundos, sin sleeps para ordenar mensajes. Usan 100 ms por tramo y 20 ms de parada; las órdenes de un escenario deben llegar mientras el recorrido aún no haya pasado sus pisos intermedios. Una máquina detenida por depuración o muy saturada puede requerir revisar esa precondición temporal. El comportamiento del algoritmo y del reloj se verifica por separado con instantes explícitos.

El ayudante determinista de pruebas ejecuta las primitivas físicas y de servicio; los casos eligen explícitamente cada barrido. No reproduce la lógica de selección del worker.

Maven no está instalado aquí. No se ejecutaron los adaptadores JUnit ni la regresión íntegra de las 84 pruebas previas. Los conteos siguientes son esperados hasta recibir la salida del proyecto.

## 7. Alcance y tareas siguientes

T25 implementa la agenda de una cabina. La elección de qué cabina recibe una externa corresponde al manejador de fase 5. No se afirma mínimo global, ausencia de inanición bajo flujo ilimitado de nuevas solicitudes ni una estimación de tiempo de recogida ya implementada.

T26 completará inserción/deduplicación de transporte y repetición de mensajes ya atendidos. Sigue vigente la deduplicación de identidades pendientes del modelo y el rechazo funcional heredado; no se promete exactamente una ejecución frente a retransmisiones de comandos completados.

T27 ampliará los eventos de ejecución y T28 verificará paralelismo y tiempos. Stop, reset, pausa y cierre global pertenecen a sus tarjetas posteriores. El cierre local despierta y termina el hilo, conservando el último snapshot histórico como en T24.

## 8. Integración en PowerShell

Desde la raíz Maven:

```powershell
mvn "-Dtest=ElevatorWorkerTest,ElevatorMovementTest,ElevatorDwellTest,DirectionalAgendaTest" test
mvn clean verify
```

Esperado: 52 pruebas específicas y 104 acumuladas, si se conservan las 84 anteriores; cero fallos, errores y omitidas; `BUILD SUCCESS` y JAR generado. El arranque Swing conserva su comportamiento: la composición del simulador con GUI completa es posterior.

## 9. Cierre documental y commit tras validar

Una vez satisfactorias ambas ejecuciones:

1. Cambiar el estado de este documento a `Terminada; integración y regresión Maven satisfactorias`.
2. Registrar fecha, conteos y salida reales de Maven, conservando la distinción con la verificación local de escenarios.
3. Marcar T25 con `[x]` en `docs/01-analisis-y-plan.md`.
4. Revisar los cambios y crear el commit:

```powershell
git status --short
git add -- src/main/java/com/elevatormanager/model/ElevatorState.java src/main/java/com/elevatormanager/simulation/ElevatorWorker.java src/main/java/com/elevatormanager/simulation/DirectionalAgenda.java src/test/java/com/elevatormanager/simulation/DirectionalAgendaScenarios.java src/test/java/com/elevatormanager/simulation/DirectionalAgendaTest.java docs/implementacion/T25-agenda-ordenada-por-sentido.md docs/01-analisis-y-plan.md
git diff --cached --check
git diff --cached --stat
git diff --cached
git commit -m "T25: implementar agenda ordenada por sentido"
git status --short
git log -1 --oneline
```

Revisar que solo se incluyan cambios de esta tarjeta. Próxima tarea, después de validar y cerrar: **T26 — Implementar inserción concurrente y deduplicación**. La fase 4 continúa abierta.

## 10. Historial

| Versión | Fecha | Cambio |
| --- | --- | --- |
| 0.1.0 | 2026-10-22 | Selección por cercanía/admisión, barridos por sentido, ampliación interna, regreso ordenado y veinte escenarios. |
