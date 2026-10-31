# T56 — Integrar controles y prioridades

- **Fecha:** 29 de octubre de 2026, Guatemala.
- **Base confirmada:** T55, commit `dd9dc82`, 29/810 pruebas Maven satisfactorias y árbol limpio.
- **Estado:** incorporada por David en `7dfb8e5`, 28/838 pruebas Maven satisfactorias y árbol limpio. El registro de escritorio confirma tres ciclos Pausar/Reanudar y cierre normal; Stop/Reset/cierre durante pausa, X y geometría siguen pendientes de confirmación nativa.

## Comportamiento

La consola gráfica incorpora Pausar y Reanudar. Pausar solicita PAUSING y muestra el progreso de N+1 reconocimientos; PAUSED solo aparece después de la barrera global. Reanudar libera el tiempo restante y los mismos actores de T55. Start sigue sirviendo para el arranque y la continuación después de Stop. No sustituye a Reanudar.

| Estado | Start | Pausar | Reanudar | Stop | Reset | Cargar archivo | Cerrar |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Sin sesión | No | No | No | No | No | No | Sí |
| RESET | Sí | No | No | No | Sí | Sí | Sí |
| RUNNING | No | Sí | No | Sí | Sí | No | Sí |
| PAUSING | No | No | No | Sí | Sí | No | Sí |
| PAUSED | No | No | Sí | Sí | Sí | No | Sí |
| STOPPING | No | No | No | No | Sí | No | Sí |
| STOPPED | Sí | No | No | No | Sí | No | Sí |
| RESETTING | No | No | No | No | No | No | Sí |
| CLOSING/CLOSED | No | No | No | No | No | No | No |

La tabla resume las condiciones por estado. Apertura, carga o control pendiente añaden restricciones: se evita duplicar el control desde los botones, pero Stop/Reset pueden sustituir una pausa o reanudación pendiente y Reset puede sustituir Stop. Las solicitudes de piso/cabina solo se admiten en RUNNING; las luces pendientes siguen visibles durante pausa. La barra conserva todos los controles en un scroll horizontal si falta espacio; no necesita una segunda fila recortada. CabinPanel muestra En pausa para HELD/PAUSE y Detenida para HELD/STOP.

## Prioridades

Cierre > Reset > Stop > pausa/reanudación. Esta prioridad resuelve controles en vuelo: no revierte una operación ya aplicada antes de recibir otra. El controlador serializa los controles y conserva las barreras privadas aunque se cancele un Future público.

- **Stop desde pausa:** publica STOPPING, solicita Stop al manejador y a todas las cabinas, y solo después libera la compuerta. La cabina termina el tramo adyacente pendiente, no abre servicio y conserva los destinos; se confirma STOPPED. Una parada suspendida pasa a HELD/STOP con su resto. Se continúa mediante Start.
- **Reset desde pausa:** adopta una nueva época, encola reset en los N+1 actores y libera la compuerta después. Cancela el trabajo previo, completa el tramo comprometido si existía y retorna físicamente a 1/UP. Solo entonces confirma RESET y habilita carga/Start.
- **Cierre desde pausa o reset:** cancela futuros de control vigentes, cierra la compuerta antes de cerrar actores y espera su terminación fuera del EDT. No necesita Reanudar ni reintenta trabajo cancelado.

`PauseCoordinator.releaseForControl` permite liberar una barrera incompleta por un control de mayor prioridad. No usa el método Resume, que sigue exigiendo todos los ACK. Completa la barrera sustituida excepcionalmente fuera del lock; el controlador resuelve la pausa pendiente como REJECTED y descarta la confirmación tardía por estado/identidad de operación. Las sustituciones esperadas no se consideran fallos del motor.

Se conserva la validación y el comportamiento de Stop/Reset de las fases anteriores: no se abre una ventana de servicio normal al soltar los actores pausados. Las banderas/buzones prioritarios ya están puestos antes de reactivar el reloj. Reanudar durante STOPPING/RESETTING se rechaza; una nueva pausa tampoco congela esos controles.

## EDT y resultados tardíos

Los botones solo solicitan futuros. No hay get, join, lectura, flush ni espera de barrera en EDT. La representación transitoria Pausando se establece al pulsar; no anuncia reconocimiento anticipado. El Timer de 100 ms y las publicaciones de T52 siguen refrescando estado y snapshots. Una captura antigua puede tardar un refresco en desaparecer; no controla la física.

Cada acción incrementa operationId. Stop, Reset o cierre invalidan la respuesta gráfica de una pausa/reanudación anterior. La llegada tardía de ese resultado no restaura la etiqueta PAUSED ni habilita Reanudar después de Stop/Reset. La GUI consulta siempre el estado actual. Botón Cerrar y X conservan el mismo WindowCloseHandler; se añade un guion nativo para comprobar ambos durante pausa.

## Registros

Se utiliza TraceMailbox.managerState, cola existente sin I/O en el productor. Los eventos llegan a manejador.log, consola y copia reciente del visor con sesión, época y secuencia. Los archivos por cabina conservan los eventos de ejecución física.

Eventos nuevos de control: SIMULATION_PAUSING, SIMULATION_PAUSED, SIMULATION_RESUMED, PAUSE_SUPERSEDED_BY_STOP, PAUSE_SUPERSEDED_BY_RESET y PAUSE_SUPERSEDED_BY_CLOSE. También se registran estados globales RUNNING, STOPPING, STOPPED, RESETTING, RESET y CLOSING con prefijo SIMULATION_. Una pausa repetida ya confirmada no genera otra transición; una pausa sustituida antes de reconocer no publica PAUSED después. RESETTING usa la nueva época.

CLOSING se registra mientras el consumidor está abierto. CLOSED se publica solo después de liberar los recursos, incluido el logger, por lo que no se intenta escribir a un canal ya sellado para registrar ese estado final. Los eventos terminales de los actores se drenan antes de cerrar el consumidor.

## Pruebas y entrega

Se añaden 28 pruebas: 14 de prioridades/trazas y 14 de controles Swing. Se actualizan el escenario y adaptador de la política provisional T55, conservando sus 17 pruebas. Se esperan 838 pruebas JUnit totales. Los tests nuevos ejercitan motor y logs reales con directorios temporales, y botones reales en EDT; no abren JFrame ni usan Robot. La aceptación nativa se documenta aparte.

El ZIP contiene el proyecto completo y todas las fuentes Java. [T56-codigo-completo.md](T56-codigo-completo.md) reproduce íntegras las fuentes Java añadidas/modificadas. [T56-validacion-local.md](../comprobaciones/T56-validacion-local.md) contiene evidencia, comandos Maven y commit sugerido; [T56-recorrido-gui.md](../comprobaciones/T56-recorrido-gui.md) permite comprobar el escritorio. No se cambia pom.xml ni se añaden dependencias. T57 permanece pendiente para ampliar la matriz de pausa y cerrar la fase 9 tras aceptación.
