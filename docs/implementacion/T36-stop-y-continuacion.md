# T36 — Implementar Stop y continuación con Start

- **Proyecto:** Elevator Manager.
- **Fecha:** 2026-10-27, Guatemala.
- **Fase:** 6 — Implementar ciclo de vida y reset.
- **Base confirmada:** T35, commit `9a5f7f4`, 14 pruebas específicas y 276 acumuladas satisfactorias; árbol limpio informado por David.
- **Estado:** cerrada funcionalmente; Maven validado por David y commit `1f9b0c0`.

## 1. Comportamiento implementado

Stop solicita detener despacho y cabinas en paralelo. El controlador publica STOPPING y rechaza nuevas solicitudes manuales y Start. Solo publica STOPPED después de confirmar al manejador y a todas las cabinas. El futuro de stop() confirma esa barrera completa, no únicamente el envío del control.

Una cabina MOVING termina únicamente el tramo adyacente iniciado. Conserva el destino comprometido, dirección y pendientes. No inicia otro tramo ni abre una nueva parada al llegar, incluso si acaba de alcanzar el destino solicitado. El servicio pendiente empezará tras Start.

Una parada DWELLING pasa a HELD con motivo STOP y fase suspendida DWELLING. Conserva exactamente el restante capturado mediante reloj monotónico. El tiempo de detención no consume la parada. Una parada cuyo restante era cero se reanuda con cero, sin reiniciar su duración.

El manejador deja terminar la operación que ya estaba ejecutando y confirma después de abandonarla. Conserva registro canónico, inbox, seguimiento y eventos pendientes. Los comandos enviados antes de esa confirmación pueden llegar a un inbox de cabina ya detenida: permanecen encolados y no se admiten ni ejecutan hasta continuar.

Start desde STOPPED habilita los mismos trabajadores; no reconstruye cabinas, manejador, agendas, registros de deduplicación ni generador de comandos. Mantiene la época. Una identidad ya atendida no vuelve a servirse al continuar. Los snapshots pueden reflejar brevemente la fase retenida mientras el actor procesa la continuación; RUNNING habilita el trabajo, no afirma que todas las cabinas ya hayan dado un paso físico.

## 2. Acciones por estado

| Estado | Stop | Start | Solicitud manual |
| --- | --- | --- | --- |
| RESET | UNCHANGED; no inicia hilos ni altera RESET. | Arranque inicial de T35. | Rechazada. |
| RUNNING | Solicita barrera y pasa a STOPPING. | UNCHANGED; no duplica trabajo. | Validada y enrutada. |
| STOPPING | Se une a la misma operación; espera su confirmación. | Rechazado. | Rechazada. |
| STOPPED | UNCHANGED. | Continúa trabajadores existentes y pasa a RUNNING. | Rechazada. |
| CLOSING/CLOSED | Rechazado. | Rechazado. | Rechazada. |

Los estados de pausa y reset operativo siguen reservados para sus tareas. T36 no implementa esos controles ni modifica la época.

## 3. Contratos y sincronización

- `SimulationController.stop()` devuelve CompletableFuture<Result>. APPLIED/STOPPED confirma la barrera. El hilo coordinador sigue libre durante el tramo restante; close() puede interrumpir la operación.
- `ElevatorWorker.requestStop()` confirma después de publicar el snapshot y los eventos de llegada y detención. El control es prioritario respecto a inbox y servicio.
- `ManagerWorker.requestStop()` confirma fuera de un despacho y sin consumir nuevas entradas hasta la continuación. Las copias de futuros evitan que el consumidor complete o cancele la barrera privada del actor.
- `resume()` en ambos actores habilita la continuación. El controlador los llama solo desde STOPPED. Los métodos no llaman Thread.start de nuevo.
- Las esperas de los actores usan Condition y mailbox; no existe sondeo periódico de una cabina detenida. El temporizador de reintentos del manejador conserva su función previa.
- No se ejecutan callbacks de futuros bajo los bloqueos locales de los trabajadores. El controlador no espera confirmaciones sosteniendo un bloqueo global ni bloquea el EDT.

Los canales de bajo nivel conservan órdenes ya enviadas mientras están detenidos; eso no autoriza admisión manual desde la GUI. La frontera pública de aplicación sigue siendo SimulationController, que rechaza entradas fuera de RUNNING.

`calls()` puede conservar una proyección anterior hasta que el manejador vuelva a consumir sus eventos tras Start. Los eventos de servicio ya producidos permanecen fiables en el mailbox; una vista retrasada no implica que haya que reenviar la llamada.

## 4. Modelo y eventos

ElevatorState agrega holdDwellForStop y resumeAfterStop. La suspensión usa HELD/DWELLING y HoldReason.STOP ya definidos en ElevatorSnapshot. Nunca se publica HELD/MOVING por Stop. El próximo tramo y las solicitudes se conservan según las reglas existentes.

ExecutionEvent incorpora CONTROL_STOPPED y CONTROL_RESUMED para distinguir controles de STOP_STARTED y STOP_ENDED, que representan paradas de servicio. La secuencia local continúa creciendo en el mismo actor. CONTROL_RESUMED se emite cuando la cabina procesa efectivamente la continuación; varios Start/Stop consecutivos sin oportunidad de avance no obligan a fabricar un evento por cada pulsación.

## 5. Fallos y cierre mínimo

Si falla una confirmación de Stop, el futuro falla, se conserva la incidencia y se solicita cierre. No se publica STOPPED sin todos los participantes. Si close() llega durante STOPPING, el resultado del Stop pendiente se completa como REJECTED por cierre, sin quedar indefinidamente pendiente. No es un reset ni una continuación fallida silenciosa.

Un timeout elegido por el consumidor para get() no cancela la barrera real ni fuerza un estado. La duración válida de un tramo puede ser mayor que un timeout de prueba. No esperar futuros ni terminación en EDT. Los callbacks que actualicen Swing deben volver al EDT.

Se conserva el cierre mínimo de T35 para liberar actores durante pruebas, incluido el manejador esperando su condición. La integración del cierre con GUI, handlers y supervisión global corresponde a T39.

## 6. Pruebas añadidas

StopContinuationScenarios y StopContinuationTest ejercitan los mismos 21 casos:

1. Restante exacto de parada con reloj explícito.
2. Stop no congela un tramo físico.
3. Parada ya vencida no reinicia su duración al continuar.
4. Stop en RESET no inicia trabajadores.
5. Barrera de cuatro cabinas inmóviles y manejador.
6. Movimiento detenido en el siguiente piso con compromiso conservado.
7. Llegada durante STOPPING sin servicio nuevo.
8. Parada congelada, snapshot estable y consumo posterior del restante.
9. Stop repetido idempotente y operación compartida.
10. Start rechazado en STOPPING.
11. Solicitudes rechazadas en STOPPING.
12. Solicitudes rechazadas en STOPPED.
13. Inbox de cabina conservado hasta continuar.
14. Inbox de manejador conservado sin despachar estando detenido.
15. Despacho en vuelo cubierto por la barrera antes de confirmar.
16. Lote mixto con tres cabinas; todos los UUID terminan una sola vez.
17. Diez ciclos sin duplicar trabajo completado.
18. EDT responde mientras se detiene un tramo.
19. Cierre durante STOPPING completa el futuro pendiente.
20. Cierre desde STOPPED despierta todos los actores.
21. 4,000 ciclos rápidos: 2,000 en reposo y 2,000 con parada abierta.

Se usan latches, eventos, futuros y límites de espera. Las ventanas sin actividad usan un poll de eventos con timeout, no sleeps para deducir estados. El generador bloqueado del caso de despacho en vuelo es una herramienta controlada de prueba; los generadores de producción deben seguir siendo rápidos.

## 7. Carrera detectada y corrección

Durante la implementación se observaron dos esperas incorrectas al encadenar controles: una nueva barrera podía reemplazar a la anterior mientras el manejador seguía esperando la condición vieja; además, el wake de Stop podía consumirse durante el drenaje de eventos y el actor volver a dormir en mailbox.await() sin comprobar el control.

Se corrigieron revalidando la identidad de la barrera al despertar y comprobando Stop/cierre bajo el lock antes de volver a esperar en el mailbox. Si el control llega después de esa comprobación, su wake queda en la cola y despierta al actor. La prueba intensiva del caso 21 cubre ciclos rápidos; también se ejecutó una comprobación adicional de 10,000 ciclos (5,000 en reposo y 5,000 con parada abierta). Estos resultados se registran en la ficha de validación, sin afirmar ausencia de toda carrera posible.

## 8. Archivos completos de T36

| Ruta | Cambio |
| --- | --- |
| src/main/java/com/elevatormanager/model/ElevatorState.java | Congelación y restauración de parada. |
| src/main/java/com/elevatormanager/simulation/ElevatorWorker.java | Control prioritario, confirmación y continuación. |
| src/main/java/com/elevatormanager/simulation/ExecutionEvent.java | Dos eventos de control. |
| src/main/java/com/elevatormanager/dispatch/ManagerWorker.java | Barrera de despacho y conservación de pendientes. |
| src/main/java/com/elevatormanager/simulation/SimulationController.java | Stop global y Start desde STOPPED. |
| src/test/java/com/elevatormanager/simulation/StopContinuationScenarios.java | 21 escenarios ejecutables sin JUnit. |
| src/test/java/com/elevatormanager/simulation/StopContinuationTest.java | 21 adaptadores JUnit. |
| docs/implementacion/T36-stop-y-continuacion.md | Esta ficha. |
| docs/implementacion/T36-codigo-completo.md | Las siete clases completas para lectura. |
| docs/comprobaciones/T36-validacion-local.md | Resultados reales y límites de validación. |

Se actualiza el plan y se incorpora la confirmación del cierre de T35 en sus dos fichas. No se cambian POM, dependencias ni ventana de T11. El ZIP conserva las rutas desde la raíz Maven y contiene todo el proyecto limpio, sin .git, target o logs temporales.

## 9. Incorporación y validación PowerShell

Desde el proyecto limpio de T35, revisar estado y extraer. Ajustar la ruta de descarga si fuera necesario.

```powershell
Set-Location 'D:\Users\David\Desktop\workspace\java\elevator-manager'
git status --short
Expand-Archive -LiteralPath "$HOME\Downloads\T36-stop-y-continuacion.zip" -DestinationPath . -Force
mvn -Dtest=StopContinuationTest test
mvn "-Dtest=SimulationControllerTest,StopContinuationTest,ManagerWorkerTest,PendingConfirmationTest,ExecutionEventTest,ElevatorDwellTest,DirectionalAgendaTest" test
mvn clean verify
```

Se esperan 21 pruebas específicas, 128 en el grupo de regresión indicado y 297 acumuladas sobre la base confirmada de 276 de T35. Confirmar los números reales y BUILD SUCCESS. Para los escenarios independientes, después de compilar:

```powershell
java -Djava.awt.headless=true -cp "target\classes;target\test-classes" com.elevatormanager.simulation.StopContinuationScenarios
```

Salida esperada: `T36: 21 escenarios satisfactorios.` La GUI operativa llegará en fase 9; la validación de T36 se realiza sobre el controlador y los actores reales.

## 10. Commit sugerido y cierre

Después de pasar Maven, marcar T36 completada en el plan y actualizar el estado de esta ficha. Preparar los archivos explícitos:

```powershell
git diff --check
git add -- src/main/java/com/elevatormanager/model/ElevatorState.java src/main/java/com/elevatormanager/simulation/ElevatorWorker.java src/main/java/com/elevatormanager/simulation/ExecutionEvent.java src/main/java/com/elevatormanager/dispatch/ManagerWorker.java src/main/java/com/elevatormanager/simulation/SimulationController.java src/test/java/com/elevatormanager/simulation/StopContinuationScenarios.java src/test/java/com/elevatormanager/simulation/StopContinuationTest.java docs/implementacion/T36-stop-y-continuacion.md docs/implementacion/T36-codigo-completo.md docs/comprobaciones/T36-validacion-local.md docs/01-analisis-y-plan.md docs/implementacion/T35-controlador-ciclo-de-vida.md docs/comprobaciones/T35-validacion-local.md
git diff --cached --check
git diff --cached --stat
git commit -m "T36: implementar Stop y continuacion con Start"
git status --short
```

**Siguiente tarea:** T37 — Implementar reset prioritario por cabina. T37–T40 permanecen abiertas; la fase 6 no está cerrada.

## 11. Cierre confirmado de T36

David confirmó el 2026-10-27: 21 pruebas específicas, 128 del grupo seleccionado y 297 acumuladas, sin fallos, errores u omisiones; BUILD SUCCESS. Commit `1f9b0c0` y árbol limpio. Se detectó una línea vacía al final de T36-codigo-completo.md; su normalización documental no cambia la validación funcional. El estado pendiente de las instrucciones anteriores corresponde a la entrega original.
