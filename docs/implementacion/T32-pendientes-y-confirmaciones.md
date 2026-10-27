# T32 — Coordinar pedidos pendientes y confirmaciones

- **Proyecto:** Elevator Manager.
- **Fecha:** 2026-10-26.
- **Versión:** 0.1.0-SNAPSHOT; objetivo Java 17.
- **Fase:** 5 — Implementar el Elevator Manager y la asignación.
- **Base confirmada:** T31, commit `5731c87`, 203 pruebas sin fallos, BUILD SUCCESS y árbol limpio.
- **Estado:** implementación finalizada; compilación Java 17 y 145 escenarios locales satisfactorios. 

## 1. Objetivo

Conservar una llamada externa desde su recepción hasta su servicio compatible confirmado. Registrar recepción, envío, admisión y atención como hechos diferentes; deduplicar llamadas compartidas entre cabinas y gestionar rechazos sin perder pendientes ni atenderlos prematuramente.

Se conserva la selección ETA/ID de T31, el estimador de T30, el registro local CommandLedger, la jerarquía Request/HallRequest/CabinRequest y los hilos independientes del manejador y las cabinas. Esta tarea no incorpora Start/Stop/Reset global ni la interfaz definitiva.

## 2. Archivos del ZIP

| Archivo | Acción y función |
| --- | --- |
| src/main/java/com/elevatormanager/dispatch/HallCallSnapshot.java | Nuevo: vista pública inmutable de una identidad y su estado. |
| src/main/java/com/elevatormanager/dispatch/HallRequestLedger.java | Nuevo: registro canónico global, alias, intentos y confirmaciones. |
| src/main/java/com/elevatormanager/dispatch/ManagerWorker.java | Reemplazar completo: seguimiento, suscripción y reintentos. |
| src/main/java/com/elevatormanager/simulation/ExecutionEventMailbox.java | Nuevo: cola adicional de eventos y señales de despertar. |
| src/main/java/com/elevatormanager/simulation/ElevatorWorker.java | Reemplazar completo: suscripción adicional sin cambiar los canales existentes. |
| src/test/java/com/elevatormanager/dispatch/PendingConfirmationScenarios.java | Nuevo: 26 escenarios deterministas y con hilos reales. |
| src/test/java/com/elevatormanager/dispatch/PendingConfirmationTest.java | Nuevo: 26 pruebas JUnit de los mismos escenarios. |
| src/test/java/com/elevatormanager/dispatch/ManagerWorkerScenarios.java | Reemplazar completo: expectativas PENDING/DUPLICATE y protección del EDT. |
| src/test/java/com/elevatormanager/dispatch/ElevatorSelectionScenarios.java | Reemplazar completo: conservar como pendiente al cerrarse todas las cabinas. |
| docs/implementacion/T32-pendientes-y-confirmaciones.md | Nuevo: decisiones, contratos e integración. |
| docs/comprobaciones/T32-validacion-local.md | Nuevo: evidencia de validación local. |

El ZIP contiene versiones completas de estos once archivos, con rutas desde la raíz Maven. Se incorpora sobre el proyecto de T31, sin agregar otro pom.xml ni reconstruir el proyecto. Las clases wrapper ManagerWorkerTest y ElevatorSelectionTest no cambian.

## 3. Estados y confirmaciones

| Estado de HallCallSnapshot | Hecho confirmado |
| --- | --- |
| RECEIVED | Llamada válida retenida por el manejador, pendiente de envío o reintento. |
| DISPATCHED | Pickup encolado en una cabina; falta confirmación de admisión. |
| ASSIGNED | COMMAND_ACCEPTED, COMMAND_DUPLICATE o replay aceptado correlacionado confirma registro en la cabina. |
| SERVED | COMMAND_COMPLETED del intento vigente, con canónica receptora correlacionada y snapshot en DWELLING, piso y sentido solicitados. |
| CANCELLED | El manejador cerró antes de enviar la llamada. |
| UNCONFIRMED | Existe incertidumbre sobre una llamada enviada; no se afirma atención ni se reasigna automáticamente. |

Una llegada o apertura de parada por sí sola no marca SERVED. Tampoco lo hacen el envío al buzón o la aceptación en agenda. Una confirmación de servicio que no corresponde al piso/sentido solicitado conserva la llamada en UNCONFIRMED para revisión.

El flujo habitual es RECEIVED, DISPATCHED, ASSIGNED y SERVED. Un rechazo confirmado vuelve a RECEIVED. Cada transición observada del registro se publica antes de procesar la siguiente. Las vistas ya entregadas no cambian.

## 4. Identidad, alias y deduplicación global

Hay una canónica activa por piso y sentido dentro de la época del manejador. Una solicitud equivalente con UUID distinto se registra como alias y comparte estado, comando e intentos con esa canónica. No genera un comando adicional ni puede asignarse a otra cabina mientras la llamada siga activa.

La repetición de un UUID con el mismo contenido conserva su relación histórica, incluso después de SERVED. El mismo UUID con piso, dirección, época o createdAt diferentes se rechaza sin modificar el original. Después del servicio, una pulsación realmente nueva necesita UUID nuevo y crea otra canónica, aunque coincidan piso y sentido. Un alias histórico nunca se une a esa nueva llamada.

La canónica global y la de CommandLedger de una cabina pueden diferir. El manejador conserva ambas identidades: la global identifica a los usuarios/alias y la receptora correlaciona las confirmaciones. Por ejemplo, una cabina puede tener una recogida equivalente previa y admitir el nuevo Pickup como duplicado local. El servicio se confirma con el UUID receptor que notificó esa admisión.

HallRequestLedger solo es escrito por el hilo del manejador. No es thread-safe por sí mismo; el público consume HallCallSnapshot y mapas inmutables. Se presupone un único ManagerWorker propietario de las llamadas externas de una sesión. La coordinación de Start en fase 6 evitará duplicar ese actor.

## 5. Responsabilidades separadas

- HallRequestLedger coordina llamadas externas entre cabinas, mantiene alias, estado global e intentos.
- CommandLedger conserva la autoridad local de admisión, identidad de comando y servicio canónico de cada cabina.
- ElevatorWorker mueve y sirve físicamente, generando ExecutionEvent.
- ManagerWorker consume su suscripción y procesa las confirmaciones sin leer el estado mutable ni ejecutar trabajo en Swing.

Los destinos internos siguen dirigidos a la cabina; esta tarea no los convierte en llamadas externas ni los incorpora al registro global. El generador de IDs de comandos sigue siendo compartido entre todos los productores de la sesión.

## 6. API pública del manejador

Se mantienen constructor, start, submit, awaitResult, close, awaitTermination, lifecycle, failure y diagnóstico del hilo.

| API nueva | Contrato |
| --- | --- |
| calls() | Mapa inmutable UUID → HallCallSnapshot, con canónicas, alias e historial. |
| call(UUID) | Vista de una identidad; Optional.empty si no está registrada. |
| awaitUpdate(Duration) | Canal de cambios de seguimiento; un consumidor lógico. Duration.ZERO permite sondeo. |

HallCallSnapshot contiene la solicitud original de esa identidad, canonicalRequestId global, status, comando vigente/último opcional, número de envíos e información de seguimiento.

awaitResult conserva un resultado por entrada submit procesada, con dos outcomes nuevos:

| Outcome | Significado |
| --- | --- |
| DISPATCHED | La nueva canónica fue enviada al buzón. |
| PENDING | La nueva canónica está retenida sin envío; el actor la reintentará. |
| DUPLICATE | La identidad ya existía o se agregó como alias de una llamada activa. |
| REJECTED | Entrada inválida o identidad alterada; no sustituye una llamada válida. |
| CANCELLED | El manejador cerró o falló antes de terminar de procesar esa entrada. |

Los reintentos internos y confirmaciones posteriores producen awaitUpdate, no nuevos resultados de submit. Por tanto, un primer PENDING puede evolucionar a SERVED sin que el llamador reenvíe la solicitud. Repetir una identidad sin cambios sigue siendo seguro.

awaitUpdate, awaitResult y awaitTermination con espera positiva rechazan su uso desde el EDT. Las consultas inmutables y Duration.ZERO se pueden usar desde Swing.

## 7. Suscripción de eventos sin interferir con consumidores existentes

ExecutionEventMailbox es un canal adicional fiable en memoria, respaldado por una cola sin límite de capacidad. Los trabajadores publican los mismos objetos ExecutionEvent inmutables; no ejecutan callbacks ni I/O. El registro de suscripciones usa CopyOnWriteArrayList.

ManagerWorker se suscribe antes de iniciar su hilo y antes de emitir comandos propios. Al terminar se desuscribe. subscribeEvents no reproduce hechos anteriores al registro. unsubscribeEvents puede coincidir con un lote ya capturado por el publicador; ese último lote puede quedar en la cola retirada.

Los canales originales awaitEvent, awaitResult y awaitService de ElevatorWorker permanecen independientes y conservan sus mensajes. Esto permite usar el flujo original para logs/UI sin competir con el consumidor del manejador.

La cola agrega señales wake sin inventar eventos físicos. Las entradas y el cierre despiertan al actor; si no hay pendientes recibidas espera un evento/señal. Si las hay, utiliza espera de 100 ms para reintentar aunque una cabina pase de NEW a RUNNING sin emitir eventos. No sondea en un bucle ocupado.

## 8. Reintentos, reservas y equidad

Cada intento de despacho conserva las tres evaluaciones optimistas de T31 y su envío condicionado. Si no hay opción o sigue cambiando el estado, la llamada permanece RECEIVED. El actor reintenta ante eventos o por el temporizador; cada vuelta procesa como máximo 256 eventos, 16 canónicas pendientes y una entrada nueva. El cursor de reintento rota para que una llamada sin opción no bloquee permanentemente a las siguientes.

Una cabina con un Pickup propio DISPATCHED se excluye temporalmente para otras canónicas hasta recibir su admisión. Esa reserva evita ignorar trabajo propio que todavía no aparece en el snapshot de la agenda. Se libera al pasar a ASSIGNED; desde ahí el estimador ya puede considerar los pendientes admitidos. Los comandos ajenos todavía en el buzón conservan la limitación de visibilidad de T30.

COMMAND_REJECTED correlacionado devuelve la canónica a RECEIVED y excluye esa cabina para esa llamada durante esta época, evitando un bucle de rechazo semántico. Otro envío utiliza commandId nuevo. No se envía otro Pickup mientras exista un intento pendiente de admisión o servicio. Si todas las cabinas rechazan, la llamada sigue visible como pendiente; no desaparece ni se marca atendida.

Los eventos se correlacionan por época, cabina, commandId, contenido operativo, intento vigente y UUID canónico receptor. Se ignoran secuencias ya procesadas y eventos ajenos. Un evento tardío de un intento antiguo no cambia la asignación nueva.

## 9. Terminación, fallo y cierre

WORKER_TERMINATED llega después de los eventos previos de esa cabina en el mismo canal. Si la llamada ya fue SERVED, conserva su historial. Si el intento no fue servido, vuelve a RECEIVED y puede asignarse a otra cabina disponible, pues el trabajador anterior terminó.

WORKER_FAILED deja como UNCONFIRMED los intentos enviados/admitidos que no tengan servicio confirmado. Esto evita reasignar automáticamente un trabajo cuyo último punto de ejecución podría ser incierto. También queda UNCONFIRMED una finalización histórica que no aporta evidencia de parada compatible en la captura. Una repetición/alias no elude esa incertidumbre.

close del manejador no cierra las cabinas prestadas ni cancela físicamente comandos ya enviados. Las canónicas aún RECEIVED pasan a CANCELLED; las enviadas/admitidas sin confirmación final pasan a UNCONFIRMED, pues la cabina puede continuar. Las entradas todavía en su buzón reciben un Result CANCELLED con su identidad. No se fabrican servicios ni cancelaciones físicas.

La resolución operativa de UNCONFIRMED y la invalidación global de épocas mediante reset se conectarán en fase 6. Aquí quedan explícitas y consultables. Las colas e historiales son fiables solo en memoria; no hay persistencia tras una caída del proceso ni garantías distribuidas de ejecución única.

## 10. Cambios en las pruebas anteriores

ManagerWorkerScenarios conserva sus doce casos. Las expectativas incorporan PENDING cuando no hay cabinas y DUPLICATE para entradas equivalentes. La prueba concurrente exige un resultado por identidad y progreso, pero no exige un comando por alias. La prueba del EDT agrega awaitUpdate a las esperas prohibidas.

ElevatorSelectionScenarios conserva sus dieciocho casos. Si todas las cabinas se cierran antes del envío, ahora exige PENDING con registro retenido en vez de rechazo que debe reenviar el llamador. La selección, desempates, revalidación y envío condicionado se siguen comprobando.

## 11. Validación local

Compilación Java 17 con --release 17 y ejecución directa de:

| Grupo | Escenarios satisfactorios |
| --- | ---: |
| PendingConfirmationScenarios, T32 | 26 |
| ElevatorSelectionScenarios, T31 | 18 |
| PickupTimeEstimatorScenarios, T30 | 20 |
| ManagerWorkerScenarios, T29 | 12 |
| DirectionalAgendaScenarios, T25 | 20 |
| ConcurrentAdmissionScenarios, T26 | 21 |
| ExecutionEventScenarios, T27 | 20 |
| ParallelTimingScenarios, T28 | 8 |
| Total | 145 |

Los 26 casos nuevos cubren ciclo completo, alias antes/después de asignar, sentidos separados, UUID alterado, retransmisión histórica, nueva pulsación, llegada sin servicio, finalización prematura, piso/sentido incorrectos, correlación/época, idempotencia, rechazo y reintento, intento obsoleto, terminal normal/fallo, cierre y vistas inmutables. También contrastan canónica receptora distinta e identidad alias histórica frente a una nueva llamada.

Las pruebas con hilos reales verifican 24 identidades equivalentes retenidas con cabinas NEW y posteriormente servidas con un solo comando, independencia de los canales originales, rechazo receptor con reasignación automática y terminación normal antes del servicio. El rechazo receptor se inyecta deliberadamente con una colisión de ID para probar su manejo; en producción sigue siendo obligatorio compartir la secuencia correctamente.

No se ejecutó Maven en este entorno. La integración y la suite acumulada deben confirmarse en el proyecto de David.

## 12. Incorporación y validación en PowerShell

Desde la raíz `D:\Users\David\Desktop\workspace\java\elevator-manager`:

```powershell
git status --short
Expand-Archive -LiteralPath "$HOME\Downloads\T32-pendientes-y-confirmaciones.zip" -DestinationPath . -Force
mvn -Dtest=PendingConfirmationTest test
mvn "-Dtest=ElevatorWorkerTest,ElevatorMovementTest,ElevatorDwellTest,DirectionalAgendaTest,ConcurrentAdmissionTest,ExecutionEventTest,ParallelTimingTest,ManagerWorkerTest,PickupTimeEstimatorTest,ElevatorSelectionTest,PendingConfirmationTest" test
mvn clean verify
```

Ajustar la ruta de descarga. Aceptar los cuatro reemplazos completos indicados en la tabla: dos clases de producción y dos clases de escenarios. Se esperan 26 pruebas específicas, 177 en el grupo de regresión y 229 totales si se mantiene la base de 203 de T31. Confirmar las cifras reales y BUILD SUCCESS.

```powershell
New-Item -ItemType Directory -Force .\logs | Out-Null
mvn -Dtest=PendingConfirmationTest test 2>&1 | Tee-Object -FilePath .\logs\t32-pendientes-y-confirmaciones.log
java -Djava.awt.headless=true -cp "target\classes;target\test-classes" com.elevatormanager.dispatch.PendingConfirmationScenarios
```

## 13. Cierre y commit tras validar

Actualizar el estado de esta ficha con los resultados reales y marcar solo T32 como completada en docs/01-analisis-y-plan.md. Incluir el plan en el mismo commit:

```powershell
git diff --check
git add -- src/main/java/com/elevatormanager/dispatch/HallCallSnapshot.java src/main/java/com/elevatormanager/dispatch/HallRequestLedger.java src/main/java/com/elevatormanager/dispatch/ManagerWorker.java src/main/java/com/elevatormanager/simulation/ExecutionEventMailbox.java src/main/java/com/elevatormanager/simulation/ElevatorWorker.java src/test/java/com/elevatormanager/dispatch/PendingConfirmationScenarios.java src/test/java/com/elevatormanager/dispatch/PendingConfirmationTest.java src/test/java/com/elevatormanager/dispatch/ManagerWorkerScenarios.java src/test/java/com/elevatormanager/dispatch/ElevatorSelectionScenarios.java docs/implementacion/T32-pendientes-y-confirmaciones.md docs/comprobaciones/T32-validacion-local.md docs/01-analisis-y-plan.md
git diff --cached --check
git diff --cached --stat
git diff --cached
git commit -m "T32: coordinar pendientes duplicados y confirmaciones de servicio"
git status --short
git log -1 --oneline
```

Siguiente tarea después del cierre: T33 — Probar reglas de dirección y recogida en ruta.
