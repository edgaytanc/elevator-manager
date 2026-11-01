# T60 — Sincronizar animación, pausa, Stop y reset

- **Fecha:** 1 de noviembre de 2026, Guatemala.
- **Base aceptada:** T59, commit `1cd6913`; 18 pruebas específicas y 886 totales correctas, BUILD SUCCESS y árbol limpio. Se recibió una captura con tres cabinas en posiciones distintas. David solicita T60.
- **Estado:** implementada y comprobada en Linux headless; pendiente de validación Maven y aceptación nativa.

## Comportamiento

| Control | Animación |
| --- | --- |
| Pausar | Se congela en el tiempo activo real del reloj compartido, incluso conservando un snapshot MOVING anterior |
| Reanudar | Continúa consumiendo solo el resto; una captura HELD atrasada no obliga a esperar el siguiente Frame para volver a avanzar |
| Stop | Completa el tramo adyacente en curso y queda estable en ese piso; no simula servicio ni borra destinos |
| Stop desde pausa | El reloj se libera por el control prioritario y el tramo suspendido termina sin Resume manual |
| Start desde STOPPED | Conserva la posición alcanzada y continúa la agenda aceptada por el motor |
| Reset | Descarta capturas de épocas anteriores, conserva el tramo físico iniciado y representa el retorno real a piso 1 |
| Reset desde pausa | Libera el reloj, termina el tramo en curso y retorna según los snapshots RESET_RETURN |
| Cerrar/X | Se congela la representación durante la liberación de recursos y se rechazan nuevas capturas en el panel |

La animación continúa siendo una proyección: no mueve el modelo, no vacía solicitudes, no confirma llegadas ni crea rutas de retorno propias. Se preservan las prioridades T56, Java 17/Maven/Swing, la herencia Request/HallRequest/CabinRequest y los actores independientes ya aceptados. No se crean nuevos hilos o temporizadores de producción.

## Referencia temporal publicada

PausableClock publica un record View mediante una referencia volatile. Contiene tiempo activo acumulado, origen monotónico de la fuente y marca paused. La referencia se reemplaza bajo el lock existente al pausar/reanudar, sin modificar vistas ya publicadas. View.at calcula el mismo dominio temporal que referenceNanos; al estar pausada devuelve siempre el valor acumulado, independientemente de la pared transcurrida. El cierre del reloj conserva una vista final congelada.

PauseCoordinator.timeView y SimulationController.timeView solo devuelven esa referencia. BuildingPanel.useTimeView enlaza el proveedor de la sesión. La pintura lee una View por raster y la utiliza para todas las cabinas; esa lectura no adquiere los locks del reloj, la barrera o los elevadores. La fuente de presentación de producción es System.nanoTime, el mismo origen utilizado por el reloj de la sesión. La inyección de pruebas usa referencias equivalentes del mismo origen virtual.

Una captura MOVING previa a la pausa se proyecta hasta la marca activa congelada y permanece allí. Cuando llega el snapshot HELD de pausa, su progreso coincide con esa marca y no suma pared. Después de reanudar, una View vigente no pausada permite extrapolar un HELD cuyo tramo suspendido sea MOVING y cuyo motivo sea PAUSE. No se habilita avance para una parada HELD de Stop. Así se evitan tanto el salto por tiempo pausado como la detención artificial a la espera de una nueva captura.

El overload original CabinMotion.position(snapshot, active) conserva la semántica T59: un HELD solo muestra su progreso capturado. La continuación de HELD solo se autoriza mediante la referencia vigente utilizada por la integración T60.

## Capturas y controles

UiSnapshotPump mantiene su trabajador único y su gate normal de 100 ms. Cada captura en vuelo conserva un token privado de generación. invalidate reemplaza ese token y limpia la publicación/error visibles. Un resultado o error de una generación invalidada no puede volver a publicarse.

SystemControlPanel invalida las capturas al enviar Start, Pausar, Reanudar, Stop o Reset. Tras recibir el resultado vigente del control pide requestFresh, que ignora el gate para esa captura puntual. Si ya hay una captura en vuelo, se conserva una única solicitud fresca pendiente; se ejecuta al terminar la anterior, sin paralelizar capturas ni crear una cola por tick. El cierre elimina esa solicitud pendiente y no permite nuevas publicaciones.

La captura normal conserva la comprobación de sesión/estado/época y añade la comprobación de que todos los snapshots pertenecen a la época del conjunto. El reset publica primero la nueva época del controlador y los actores la adoptan después: esos conjuntos mixtos se descartan fuera del EDT.

El edificio aplica el último Frame disponible cada tick de 16 ms, en lugar de esperar al gate de 100 ms del visor. Render no hace trabajo cuando recibe el mismo objeto. Captura global, panel de cabinas y visor siguen limitados por su gate; solo se adelanta la aplicación de datos ya obtenidos. Un EDT ocupado mantiene coalescencia y no ejecuta fotogramas atrasados para recuperar tiempo.

La barrera del motor sigue siendo la única que confirma PAUSED; el dibujo no anuncia ese estado por su cuenta. La observación de cambios de fase y tramo sigue dependiendo de la entrega de un snapshot. Si el primer MOVING aún no se había mostrado, una captura fresca puede corregir la posición inicialmente conocida; no se reconstruyen movimientos que ocurrieron mientras el EDT estuvo bloqueado.

## Stop y retorno por reset

Stop mantiene el reloj activo mientras el trabajador termina el tramo adyacente. Su confirmación publica IDLE o una parada HELD según las reglas existentes: ambos se representan en currentFloor sin extrapolar otro tramo. Las solicitudes pendientes y su continuación no pertenecen a la vista.

Reset no llama bind ni vuelve a dibujar todas las cabinas en 1 al cambiar la época. La vista conserva la posición del tramo anterior mientras espera un conjunto vigente. El trabajador adopta la época nueva, termina el tramo físico ya iniciado y publica movimientos descendentes RESET_RETURN. La misma interpolación muestra esos tramos. El piso 1 solo se representa al alcanzarse por el modelo o como extremo de su último tramo de retorno. La dirección UP final procede del snapshot de reset confirmado.

## Cierre

freezeAnimation conserva la marca activa y si permitía continuar un HELD de pausa atrasado. Esta última condición es necesaria para no retroceder al cerrar inmediatamente después de Reanudar. El panel marca su animación cerrada: no acepta Frames posteriores y deja de solicitar repintados periódicos. Un reenlace explícito limpia ese estado.

SystemControlPanel llama freezeAnimation al iniciar el cierre; Cerrar y X siguen el mismo WindowCloseHandler existente. Se conservan la espera asíncrona al controlador/capturador, el cierre del Timer original y el tratamiento de una apertura tardía. No se requiere Resume manual para liberar actores pausados.

## Archivos Java

| Ruta relativa a com/elevatormanager | Cambio |
| --- | --- |
| simulation/time/PausableClock.java | View inmutable/volatile y publicación de anclajes |
| simulation/time/PauseCoordinator.java | Acceso sin locks a la View |
| simulation/SimulationController.java | Referencia temporal pública de solo lectura |
| ui/CabinMotion.java | Continuación autorizada de HELD de pausa |
| ui/BuildingPanel.java | Reloj vigente, congelación de cierre y bloqueo de capturas posteriores |
| ui/UiSnapshotPump.java | Invalidación, captura fresca única y rechazo de épocas mixtas |
| ui/SystemControlPanel.java | Enlace temporal, invalidación por controles y aplicación por tick |
| simulation/time/ClockViewScenarios.java (test) | Cuatro escenarios del reloj |
| ui/AnimationSynchronizationScenarios.java (test) | Veinte escenarios de sincronización |
| ui/AnimationSynchronizationTest.java (test) | Veinticuatro adaptadores JUnit |

Producción parte de src/main/java y pruebas de src/test/java. Los diez archivos completos se entregan en T60-codigo-completo.md y como .java en el proyecto íntegro del ZIP.

## Aceptación automatizada

Se comprueban cuatro casos del reloj: dominio activo, ciclos exactos con pared excluida y vistas antiguas inmutables, cierre idempotente congelado y lectura de View mientras otro hilo mantiene el lock del reloj bloqueado en su fuente.

Los veinte casos de interfaz cubren captura MOVING anterior a pausa, HELD atrasado, reanudación, ciclos repetidos, Stop adyacente y desde pausa, Start sin reinicio, continuidad de época de reset, descenso intermedio, reset desde HELD, cierre inmediato tras reanudar, captura inválida en vuelo, captura urgente sin solapamientos, error obsoleto, cierre con captura urgente pendiente, pausa/reanudación real, parada/reposo reales, Stop desde pausa real, retorno físico real y recorrido con botones Swing reales.

Los casos virtuales no usan sleeps para comprobar porcentajes. Los sleeps breves de los casos reales son estímulos fuera del EDT y de los locks del motor. Se exige cierre de los recursos y hay límites temporales de espera. Maven/JUnit y escritorio Windows se validan localmente según T60-recorrido-gui.md. T61 permanece pendiente y fase 10 sigue abierta.
