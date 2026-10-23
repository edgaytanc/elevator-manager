# T26 — Implementar inserción concurrente y deduplicación

- **Proyecto:** Elevator Manager.
- **Fecha:** 2026-10-22, Guatemala.
- **Versión:** 0.1.0.
- **Fase:** 4 — Construir el motor concurrente de elevadores.
- **Estado:** implementación Completada; escenarios ejecutados; Completada integración y regresión Maven.
- **Base:** T25 validada con 104 pruebas y commit `77b8c3d`.

## 1. Objetivo y alcance

Recibir comandos de varios productores mientras la cabina se mueve o espera en un piso. Evitar inserciones o servicios adicionales causados por retransmisiones; vincular duplicados pendientes a una solicitud canónica; permitir solicitar de nuevo un piso servido mediante identidades nuevas.

La deduplicación es local a cada cabina. La admisión global, la selección de cabina y el seguimiento global de externas se integran en sus tarjetas posteriores. La unidad de identidad del comando es cabina/época/commandId; la del pedido lógico es UUID con contenido inmutable.

## 2. Archivos y copia

El ZIP contiene cinco archivos con rutas relativas. Extraer en una carpeta temporal y copiar a la raíz Maven conservando las rutas.

| Acción | Ruta |
| --- | --- |
| Reemplazar | `src/main/java/com/elevatormanager/simulation/ElevatorWorker.java` |
| Crear | `src/main/java/com/elevatormanager/simulation/CommandLedger.java` |
| Crear | `src/test/java/com/elevatormanager/simulation/ConcurrentAdmissionScenarios.java` |
| Crear | `src/test/java/com/elevatormanager/simulation/ConcurrentAdmissionTest.java` |
| Crear | `docs/implementacion/T26-insercion-concurrente-y-deduplicacion.md` |

Conservar `ElevatorState.java`, `DirectionalAgenda.java`, solicitudes, comandos, snapshots y todas las pruebas en sus versiones validadas de T25. No se cambia el POM ni se añaden dependencias. El ZIP no contiene una copia antigua del plan.

## 3. Capas de deduplicación

| Caso | Respuesta y efecto |
| --- | --- |
| commandId nuevo y solicitud nueva sin clave funcional pendiente | ACCEPTED; incorpora una solicitud canónica. |
| Mismo comando operativo de la misma época | Reproduce su decisión original con `replay=true`; no reinserta. |
| Mismo commandId con otro contenido operativo | REJECTED; conserva el registro original. |
| Otro commandId para un UUID conocido y contenido idéntico | DUPLICATE, vinculado a su canónica; informa PENDING o SERVED. |
| UUID conocido con contenido incompatible en una admisión nueva | REJECTED sin modificar agenda o canónica. |
| UUID nuevo con clave funcional todavía pendiente | DUPLICATE; registra alias hacia la canónica existente. |
| UUID y commandId nuevos para un piso ya servido | ACCEPTED como una solicitud nueva. |

Claves funcionales dentro de la cabina/época vigentes: interna `(CABIN, piso)`; externa `(HALL, piso, dirección)`. CABIN y HALL no se mezclan; UP y DOWN tampoco.

La identidad del comando usa el contrato `Command.sameContent()` existente: compara su contenido operativo. Una retransmisión reconstruida puede traer otra marca `createdAt`, pero el registro canónico original nunca se sobrescribe por ella. Una admisión con otro commandId y UUID ya conocido sí valida su contenido inmutable completo, incluido `createdAt`, conforme a las reglas del modelo.

El commandId de una decisión rechazada por conflicto de identidad se conserva: su retransmisión reproduce ese rechazo. Un conflicto posterior con el contenido de ese commandId no lo reemplaza.

Mensajes para otra cabina, de otra época, fuera de los límites configurados o Reset todavía no autorizado se rechazan antes de registrar IDs locales. No pueden reservar o envenenar un commandId de servicio válido de esta cabina/época.

## 4. Solicitudes canónicas y alias

`CommandLedger` conserva tres índices privados:

- Historial de decisiones por época/commandId.
- Identidades UUID conocidas, incluida la metadata original y su canónica.
- Claves funcionales que todavía están pendientes.

El registro se crea sobre el estado vacío de una cabina. No es thread-safe: el worker es su único propietario, igual que el estado físico. Productores y lectores no acceden a estos mapas.

Solo la solicitud canónica entra en `ElevatorState`. Un alias no altera el orden de admisión, la frontera ni el temporizador. El resultado informa su `canonicalRequestId`; el servicio posterior contiene la identidad canónica. El consumidor futuro debe resolver esos vínculos para no esperar un evento de servicio separado por cada alias.

`recordService()` marca como SERVED las canónicas que el modelo acaba de retirar y libera sus claves funcionales antes de publicar/entregar el hecho de servicio. Los alias conservan el vínculo histórico. Una confirmación repetida de una canónica antigua no libera la clave que ya pertenece a una canónica nueva del mismo piso.

Los UUID servidos no se reciclan: para solicitar de nuevo ese piso se construyen un UUID y un commandId nuevos. Un alias de una solicitud ya servida no se reconecta silenciosamente a otro pedido que haya aparecido después.

## 5. Contrato de Result

Se conservan `command()`, `outcome()` y `detail()`. Se añaden:

| Campo | Interpretación |
| --- | --- |
| `canonicalRequestId()` | UUID al que debe vincularse el resultado; vacío para rechazo sin canónica. |
| `requestStatus()` | Estado lógico capturado: PENDING o SERVED. |
| `replay()` | Reproducción de una decisión previamente registrada para ese comando. |

`Outcome` incorpora DUPLICATE. Se conserva el constructor de tres argumentos de Result para compatibilidad de código fuente; los rechazos de mensajes abandonados por cierre utilizan ese constructor y no inventan metadata de admisión.

Una retransmisión de un comando originalmente aceptado sigue devolviendo ACCEPTED con `replay=true`, incluso si su solicitud ya está SERVED: es la reproducción de la admisión histórica, no un nuevo servicio. La misma regla conserva DUPLICATE o REJECTED si ese era el resultado original.

Cada Result es una captura inmutable. Un Result previo PENDING no cambia al atenderse el pedido; una consulta por retransmisión devuelve el estado actualizado. Los resultados y los servicios siguen circulando por sus canales separados, con un consumidor lógico por canal; la entrega entre dos lectores independientes no establece un orden global único.

## 6. Inserción concurrente y cierre

`submit()` conserva su contrato: true confirma encolado, no aceptación. La inserción y señal se protegen con el lock del buzón local. El trabajador serializa admisión, validación, deduplicación, movimiento y servicio bajo ese mismo lock; el estado sigue teniendo un solo escritor.

En MOVING y DWELLING la espera con Condition libera el lock. Nuevos mensajes despiertan al worker, pero no reinician la fase temporal. Las llegadas o fines de parada vencidos conservan su prioridad y las fronteras de T25 siguen vigentes. Los callbacks externos y las entregas por cola se hacen fuera del lock.

Un mensaje que alcanzó la cola obtiene un resultado, aunque el cierre local lo rechace antes de admitirlo. Un envío que devuelve false no genera resultado posterior. La carrera entre cierre y productores se resuelve con el lock del buzón y se verifica contando exactamente los encolados.

El cierre local termina el hilo y conserva el último snapshot histórico como en las tarjetas anteriores. Cancelación global, estado CLOSED/TERMINATED publicado, adopción de una nueva época y coordinación del reset se completarán en fase 6; no se introducen en T26.

## 7. Retención y límites de la garantía

Las decisiones, identidades y alias se retienen durante la vida del registro en memoria. No se expulsan por antigüedad mientras un comando todavía pueda retransmitirse, porque eso permitiría reactivar un pedido servido. La memoria de historial crece con las identidades recibidas en la sesión; no se presenta como caché acotada ni como persistencia duradera.

La garantía es admisión idempotente y servicio sin reactivación por retransmisión durante ejecución normal de esa cabina. No es una garantía distribuida de exactamente una ejecución ante caída de JVM. Los fallos fatales siguen siendo incidencias y pueden dejar un resultado incierto.

La integración futura de reset deberá cancelar pendientes y coordinar la limpieza/adopción de época del registro con la del modelo. No debe vaciar el historial vigente desde un productor o reutilizar una instancia con otra agenda sin esa coordinación. Al descartar una sesión terminada se liberan también sus registros.

## 8. Pruebas ejecutadas aquí

Compilación con Java 17 de producción y escenarios, contra las fuentes existentes. Ejecuciones reales:

```text
T26: 21 escenarios satisfactorios.
T25: 20 escenarios satisfactorios.
T24: 15 escenarios satisfactorios.
T23: 10 escenarios satisfactorios.
T22: 7 escenarios satisfactorios.
```

Los catorce casos deterministas de registro cubren admisión canónica; replay pendiente desde un objeto reconstruido sin mutar revisión; replay servido sin reinserción; conflicto de commandId; metadata canónica conservada; mismo UUID con otro comando; conflictos de contenido y tipo; alias funcional; separación CABIN/HALL/UP/DOWN; alias histórico frente a pedido nuevo; nueva identidad tras servicio; rechazos de ruta/época/reset/datos sin reserva; rechazo de identidad reproducible.

Los siete casos con hilos reales cubren:

| Caso | Resultado exigido |
| --- | --- |
| Seis productores y 180 solicitudes equivalentes durante movimiento | Una canónica y 179 alias; un pendiente. |
| Seis productores y 120 retransmisiones del mismo comando | Una decisión nueva, 119 replays y un pendiente. |
| Cuatro productores y 100 solicitudes distintas con lector concurrente de snapshots | Sin pérdida, revisión no decreciente y snapshots coherentes. |
| Cuatro productores y 80 mensajes durante DWELLING | Diez pendientes y 70 duplicados; no reinicia la parada. |
| Replay después de servicio y solicitud nueva al mismo piso | Retransmitir no sirve otra vez; UUID nuevo sí se atiende. |
| Mismo commandId en dos cabinas | Registros independientes. |
| Cuatro productores compitiendo con cierre | Un resultado por cada encolado; ninguno por los envíos rechazados antes de encolar. |

Las barreras/latches y colas ordenan la prueba, sin sleeps. El lector de snapshots usa un bucle diagnóstico con yield exclusivamente en pruebas. Los tiempos largos de movimiento/parada mantienen fases activas mientras se verifica la inserción; los tests cierran los workers mediante señal y join con plazo de cinco segundos. No se esperan esos minutos configurados.

Maven no está disponible aquí. No se ejecutaron los adaptadores JUnit ni la regresión íntegra de las 104 pruebas anteriores; los conteos siguientes son esperados hasta recibir la salida real del proyecto.

## 9. Validación en el proyecto

Desde PowerShell en la raíz Maven:

```powershell
mvn "-Dtest=ElevatorWorkerTest,ElevatorMovementTest,ElevatorDwellTest,DirectionalAgendaTest,ConcurrentAdmissionTest" test
mvn clean verify
```

Esperado: 73 pruebas específicas y 125 acumuladas si se conservan las 104 anteriores. Deben quedar cero fallos, errores y omitidas; `BUILD SUCCESS` y JAR generado. El arranque Swing conserva su comportamiento.

## 10. Cierre y commit después de validar

Una vez satisfactorias ambas ejecuciones:

1. Cambiar el estado de este documento a `Terminada; integración y regresión Maven satisfactorias`.
2. Registrar fecha, conteos y resultado reales de Maven, distinguiéndolos de la verificación local por escenarios.
3. Marcar T26 con `[x]` en `docs/01-analisis-y-plan.md`.
4. Revisar y crear el commit:

```powershell
git status --short
git add -- src/main/java/com/elevatormanager/simulation/ElevatorWorker.java src/main/java/com/elevatormanager/simulation/CommandLedger.java src/test/java/com/elevatormanager/simulation/ConcurrentAdmissionScenarios.java src/test/java/com/elevatormanager/simulation/ConcurrentAdmissionTest.java docs/implementacion/T26-insercion-concurrente-y-deduplicacion.md docs/01-analisis-y-plan.md
git diff --cached --check
git diff --cached --stat
git diff --cached
git commit -m "T26: implementar insercion concurrente y deduplicacion"
git status --short
git log -1 --oneline
```

Revisar que solo se incluyan cambios de esta tarjeta. Próxima: **T27 — Incorporar eventos de ejecución**. La fase 4 continúa abierta.

## 11. Historial

| Versión | Fecha | Cambio |
| --- | --- | --- |
| 0.1.0 | 2026-10-22 | Registro de comandos/identidades/alias, resultados correlacionables y veintiún escenarios de inserción y deduplicación. |
