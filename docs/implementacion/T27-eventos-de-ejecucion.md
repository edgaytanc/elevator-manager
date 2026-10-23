# T27 — Incorporar eventos de ejecución

- **Proyecto:** Elevator Manager.
- **Versión:** 0.1.0-SNAPSHOT; Java 17.
- **Fecha:** 2026-10-23.
- **Fase:** 4 — Construir el motor concurrente de elevadores.
- **Base confirmada:** `75b84a2` — T26: implementar inserción concurrente y deduplicación; árbol limpio.
- **Estado:** implementación finalizada; 20 escenarios nuevos y 73 de regresión ejecutados con Java 17. Finalizada integración y validación Maven en el proyecto de David.

## 1. Objetivo y alcance

Emitir salida, llegada, parada, cambio de dirección, aceptación y finalización de comandos, con identidad de cabina, solicitud cuando corresponda y orden interpretable por cabina.

Se conserva el trabajador independiente, su buzón, su espera mediante Condition, el movimiento por tramos, el servicio al abrir la parada y la deduplicación de T26. Esta tarea agrega el contrato de eventos en memoria. Los consumidores de registro, la consola y su persistencia corresponden a tareas posteriores.

## 2. Archivos y cambios

| Ruta | Acción | Responsabilidad |
| --- | --- | --- |
| src/main/java/com/elevatormanager/simulation/ExecutionEvent.java | Nuevo | Evento inmutable con tipo, secuencia, instantánea y correlación. |
| src/main/java/com/elevatormanager/simulation/ElevatorWorker.java | Reemplazar | Captura y entrega ordenada de eventos; API awaitEvent. |
| src/main/java/com/elevatormanager/simulation/CommandLedger.java | Reemplazar | Asociar comandos distintos con su solicitud canónica y producir finalizaciones una sola vez. |
| src/test/java/com/elevatormanager/simulation/ExecutionEventScenarios.java | Nuevo | Veinte escenarios independientes de JUnit. |
| src/test/java/com/elevatormanager/simulation/ExecutionEventTest.java | Nuevo | Veinte pruebas JUnit que ejecutan esos escenarios. |
| docs/implementacion/T27-eventos-de-ejecucion.md | Nuevo | Contrato, decisiones y validación de la tarea. |

El ZIP conserva estas rutas desde la raíz Maven. No contiene un proyecto alternativo, pom.xml, binarios ni archivos de tareas anteriores. Extraer sobre la raíz de elevator-manager y aceptar la sustitución de las dos clases indicadas.

## 3. Contrato del evento

ExecutionEvent contiene sequence, type, snapshot, command opcional, canonicalRequestId opcional y detail. ElevatorSnapshot es profundamente inmutable y Command contiene solicitudes inmutables. Ningún evento conserva una referencia al modelo mutable.

La instantánea proporciona cabina, época, revisión, piso lógico, próximo piso, sentido, fase, tiempos y agenda. Los accesores elevatorId(), epoch() y referenceNanos() facilitan la correlación. En eventos de comando, su solicitud está en Destination.request() o Pickup.request(). Un Reset rechazado no contiene una solicitud de servicio.

- **sequence:** empieza en 1 para cada instancia de trabajador arrancada y aumenta por evento. Es el orden total local del flujo.
- **revision:** aumenta cuando cambia el modelo. Varios eventos pueden compartir revisión, por ejemplo un replay o las finalizaciones de varios comandos asociados al mismo servicio.
- **referenceNanos:** reloj monotónico de esta JVM, apto para diferencias de tiempo; no es una fecha civil ni un reloj para ordenar procesos distintos.
- **elevatorId y epoch:** identifican al trabajador que emite. Si el comando se dirigió a otra cabina o época, sus encabezados originales permanecen disponibles y el evento es de rechazo.

No existe una secuencia global entre cabinas ni una promesa de orden basada en el momento en que productores concurrentes llaman a submit. El orden observable es el que serializa el actor.

## 4. Tipos y momento de emisión

| Tipo | Hecho |
| --- | --- |
| COMMAND_ACCEPTED | Una solicitud nueva entra en la agenda. No confirma llegada ni servicio. |
| COMMAND_DUPLICATE | Un commandId nuevo se vincula a una identidad de solicitud ya conocida o a una canónica pendiente equivalente. |
| COMMAND_REJECTED | El mensaje no puede admitirse, incluso al descartarlo del buzón durante el cierre. |
| COMMAND_REPLAYED | Retransmisión del mismo commandId y contenido operativo: se devuelve su decisión histórica sin ejecutar de nuevo. Puede ser una decisión originalmente rechazada. |
| DEPARTED | Comienza un tramo adyacente. El piso lógico sigue siendo el último alcanzado. |
| ARRIVED | Termina realmente un tramo; el piso lógico ya es el nuevo. Se emite también al pasar por un piso sin atender solicitudes. |
| DIRECTION_CHANGED | El sentido cambió; no se emite si permanece igual. |
| STOP_STARTED | Se abre una parada con servicio compatible. |
| COMMAND_COMPLETED | La solicitud canónica asociada a ese comando ya fue atendida. |
| STOP_ENDED | Venció la duración de parada. No es la confirmación de atención. |
| WORKER_TERMINATED | El hilo arrancado terminó por cierre local normal. |
| WORKER_FAILED | El hilo terminó con un fallo detectado; failure() conserva la causa. |

El cierre antes de start() no crea un hilo ni un evento terminal. Un fallo al crear/arrancar el hilo se informa mediante start()/failure(), no mediante un flujo que nunca arrancó. El evento terminal no cambia artificialmente la fase física de la última instantánea.

## 5. Orden de transiciones

Un destino desde piso 1 hasta piso 3 produce:

1. COMMAND_ACCEPTED.
2. DEPARTED con piso lógico 1 y próximo piso 2.
3. ARRIVED en piso 2.
4. DEPARTED con piso lógico 2 y próximo piso 3.
5. ARRIVED en piso 3.
6. STOP_STARTED.
7. COMMAND_COMPLETED para el comando asociado.
8. STOP_ENDED cuando vence la parada.

No se completa la solicitud al pasar por un piso intermedio. Si la recogida en piso 3 es DOWN y la aproximación era UP, DIRECTION_CHANGED aparece entre ARRIVED y STOP_STARTED. Si después de una parada se inicia el regreso, el orden es STOP_ENDED, DIRECTION_CHANGED y DEPARTED.

Una solicitud en el piso actual puede producir aceptación, apertura de parada y finalización sin inventar desplazamiento. Una solicitud compatible recibida durante una parada abierta produce aceptación y finalización, sin otra apertura y sin reiniciar su duración.

Los eventos capturan el estado inmediatamente después de la transición que describen. Un cambio de sentido y una apertura/salida pueden pertenecer a una misma transición atómica del modelo y compartir instantánea, revisión y tiempo; la secuencia distingue su orden lógico.

## 6. Finalización y deduplicación

La clave de comando sigue siendo (epoch, commandId), dentro de cada cabina. El registro conserva para cada solicitud canónica una lista de comandos distintos que esperan su servicio. markServed los retira y genera confirmaciones; drainCompletions entrega una copia inmutable y vacía las confirmaciones listas.

- Dos comandos distintos con solicitudes funcionalmente equivalentes pendientes comparten una atención física. Cada comando recibe su propia COMMAND_COMPLETED, con su comando original y el mismo canonicalRequestId.
- Reenviar el mismo comando, antes o después del servicio, produce COMMAND_REPLAYED y su Result histórico actualizado; no agrega una nueva finalización.
- Un commandId nuevo que referencia un UUID ya servido produce COMMAND_DUPLICATE y una finalización inmediata por satisfacción histórica. No abre otra parada. Su instantánea representa el estado al reconocer esa satisfacción y puede estar en otro piso; el piso solicitado permanece en el comando.
- Una solicitud nueva tras atender la anterior necesita identidades nuevas, conforme a T26.
- Un conflicto de commandId o UUID no reemplaza la identidad ni la finalización válida original.
- Repetir markServed no recrea confirmaciones ya retiradas.
- Cerrar o fallar con una solicitud todavía pendiente no la convierte en servida. El terminal permite detectar que ese flujo dejó de progresar; no se implementan cancelaciones globales ni reasignación automática.

Las listas asociadas a una canónica mantienen el orden de admisión de sus comandos. No se promete un orden global de finalización por commandId entre solicitudes distintas: se completan según el servicio efectivo.

## 7. Concurrencia y consumo

Solo el hilo de la cabina genera eventos y secuencias. Prepara cada lote en sus transiciones locales y lo entrega a una LinkedBlockingQueue después de liberar el lock del buzón, antes de ejecutar otra transición. No ejecuta callbacks ni I/O y no espera a un consumidor para continuar. Las capturas de terminal y rechazo del buzón abandonado ocurren al finalizar, cuando el actor ya es el único propietario del estado.

awaitEvent(Duration) extrae el siguiente evento, devuelve Optional.empty al agotarse el plazo y rechaza tiempos negativos. La espera es interrumpible. No debe invocarse con espera desde el EDT. Cada cola tiene un único consumidor lógico; si hacen falta varios destinos, ese consumidor debe distribuir los eventos.

Result y Service se conservan como proyecciones de compatibilidad para las tareas y pruebas anteriores. No compiten con awaitEvent: cada API tiene su propia cola. Para un consumidor nuevo que necesita el historial completo, usar ExecutionEvent como fuente y evitar contabilizar además Service como otra atención. No hay orden contractual entre lecturas realizadas en colas distintas.

Las colas y el historial son fiables solo en memoria de este proceso y no tienen límite de capacidad. Un consumidor debe drenarlos durante la ejecución; no hay persistencia, recuperación tras caída ni garantía distribuida de ejecución única. Este contrato evita descartar hechos lógicos para ahorrar memoria silenciosamente.

## 8. Validación realizada

Compilación de producción y escenarios con el compilador de Java 17 y --release 17. Ejecución directa de:

| Grupo | Escenarios satisfactorios |
| --- | ---: |
| ExecutionEventScenarios, T27 | 20 |
| ConcurrentAdmissionScenarios, T26 | 21 |
| DirectionalAgendaScenarios, T25 | 20 |
| ElevatorDwellScenarios, T24 | 15 |
| ElevatorMovementScenarios, T23 | 10 |
| ElevatorWorkerScenarios, T22 | 7 |
| Total | 93 |

Los veinte escenarios nuevos verifican recorrido adyacente y orden, recogida con inversión, alias de solicitudes, replay pendiente y servido, satisfacción histórica, rechazos, conflictos, atención en piso actual, incorporación a parada, inmutabilidad, secuencias por cabina, cierre durante movimiento, timeout, 24 retransmisiones concurrentes, salida posterior al fin de parada, replay de rechazo, destinatario incorrecto, idempotencia del registro y cierre con 200 mensajes en el buzón.

No se ejecutó Maven en este entorno. La integración completa y las 52 pruebas anteriores del modelo/protocolo deben confirmarse en el proyecto de David.

## 9. Validación en PowerShell

Desde D:\Users\David\Desktop\workspace\java\elevator-manager:

```powershell
mvn "-Dtest=ElevatorWorkerTest,ElevatorMovementTest,ElevatorDwellTest,DirectionalAgendaTest,ConcurrentAdmissionTest,ExecutionEventTest" test
mvn clean verify
```

Esperado, si no hay otras pruebas agregadas: 93 pruebas específicas y 145 en la regresión completa; cero fallos, errores u omisiones y BUILD SUCCESS.

## 10. Cierre y commit después de validar

Después de obtener ambos BUILD SUCCESS:

1. Marcar únicamente T27 como [x] en docs/01-analisis-y-plan.md.
2. Actualizar el estado de este documento a implementada y validada, anotando los resultados Maven reales. Mantener T28 pendiente.
3. Revisar y crear el commit:

```powershell
git status --short
git add -- src/main/java/com/elevatormanager/simulation/ExecutionEvent.java src/main/java/com/elevatormanager/simulation/ElevatorWorker.java src/main/java/com/elevatormanager/simulation/CommandLedger.java src/test/java/com/elevatormanager/simulation/ExecutionEventScenarios.java src/test/java/com/elevatormanager/simulation/ExecutionEventTest.java docs/implementacion/T27-eventos-de-ejecucion.md docs/01-analisis-y-plan.md
git diff --cached --check
git diff --cached
git commit -m "T27: incorporar eventos de ejecucion"
git status --short
git log -1 --oneline
```

Los comandos agregan rutas explícitas para mantener el commit limitado a esta tarea. El paquete no sobrescribe el plan general, porque su versión actual se mantiene en el repositorio de David.
