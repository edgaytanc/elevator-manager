# T55 — Coordinar pausa global y reanudación

- **Fecha:** 29 de octubre de 2026, Guatemala.
- **Base confirmada:** T54, commit `4e34f89`, 17/781 pruebas Maven satisfactorias y árbol limpio.
- **Entrega:** proyecto completo, fuentes Java, pruebas y documentación Markdown.
- **Estado:** cerrada por David el 29 de octubre de 2026; 29/810 pruebas Maven satisfactorias, commit `dd9dc82` y árbol limpio.
- **Nota histórica:** esta acta describe T55; T56 sustituye la política provisional de Stop/Reset durante pausa.

## Resultado

El controlador ofrece pause y resume asíncronos. PAUSED significa que el manejador y TODAS las cabinas llegaron a sus puntos seguros, publicaron su captura y reconocieron la misma generación de pausa. No significa solo que se cambió una etiqueta en la GUI.

| Paso | Coordinador de sesión | Actores |
| --- | --- | --- |
| Solicitar pausa | RUNNING a PAUSING; congela el reloj común; despierta buzones | Terminan la iteración local en vuelo |
| Reconocer | Espera asíncronamente N+1 reconocimientos | Publican captura congelada; reconocen y esperan en Condition |
| Completar pausa | Publica PAUSED después de todos los ACK | Sin despacho, admisión local ni transiciones de servicio |
| Reanudar | PAUSED a RUNNING; libera una compuerta común y reactiva el reloj | Restituyen la captura normal y continúan el tramo/parada original |
| Cerrar | Resuelve pausa pendiente y cierra la compuerta antes de los actores | Salen sin reanudar manualmente; cierre confirma terminación real |

## Diseño temporal

PauseCoordinator tiene un PausableClock de T54 y N+1 participantes: manejador 0, cabinas 1..N. La sesión inyecta exactamente la misma instancia a todos. La marca lógica inicia cerca de cero y pertenece a la sesión; no se compara con nanoTime directo, con otra sesión ni con fechas civiles. Se usa para inicios de tramo/parada, snapshots, eventos, reintentos de despacho y observación del estimador ETA. Los plazos de Future, join, cierre, lectura y refresco Swing siguen siendo de tiempo real.

La pausa detiene el reloj desde PAUSING. Cada actor acaba la iteración local ya iniciada antes de reconocer; durante PAUSING aún pueden publicarse esas últimas admisiones o eventos. La garantía de quietud completa comienza con PAUSED. Los eventos ya en cola no se eliminan y pueden ser procesados por el manejador después de Resume; no se promete una transacción global del historial de eventos.

ElevatorState conserva sus marcas originales y su fase privada. El actor publica una proyección HELD/PAUSE cuando estaba MOVING o DWELLING, incluyendo fase suspendida, siguiente piso, destino comprometido, pendientes, duración y restante. En reposo publica IDLE con la marca congelada. Las capturas pausadas no se recalculan con tiempo físico. Tras Resume el actor publica la fase normal y continúa con las mismas marcas: no inicia otro tramo ni reinicia la parada. El progreso visual se obtiene del snapshot, sin inventar llegadas.

El manejador usa el mismo reloj activo al llamar al ranking. Esto evita descontar el intervalo pausado como edad de la captura o anticipar artificialmente una llegada. Los constructores existentes de trabajadores aislados se mantienen: usan nanoTime directo y permiten ejecutar los escenarios previos sin cambiar sus contratos. Los nuevos constructores con barrera se usan para la composición de una sesión.

## Concurrencia y barreras

El checkpoint está fuera del lock de cada actor. Sus callbacks publican snapshots bajo el lock local, pero fuera del lock de la barrera. Las futuras se completan fuera de los locks. El coordinador no espera get, join, I/O ni condiciones para publicar PAUSED: registra la continuación de la barrera en su ejecutor serializado. Una petición desde EDT solo agenda el control y devuelve Future.

Cada pausa efectiva inicia una generación y borra los ACK anteriores. Se valida N+1 sin inferir reconocimiento de los estados Thread.State. Un despertar espurio vuelve a verificar generación y condición. Al solicitar pausa se despierta la espera local de tramo/parada/reposo y el buzón del manejador. La cabina revalida la petición después de adquirir su lock para cerrar la carrera con el checkpoint; el manejador revalida antes de volver a dormir para no perder una señal consumida al drenar eventos.

Pausa repetida durante PAUSING observa la operación vigente. En PAUSED devuelve UNCHANGED; Resume repetido en RUNNING también. Cancelar el futuro público no cancela la barrera privada. Reanudar antes de PAUSED se rechaza. El estado RUNNING se publica al liberar el control; los actores pueden tardar en ser planificados por el sistema operativo, conservando sus identidades y pendientes.

La terminación de un participante durante una barrera pendiente la hace fallar sin esperar a otro actor ausente. El controlador inicia el cierre ante fallo de barrera. El cierre intencional cancela una pausa en curso como REJECTED y no se registra como fallo del motor. La compuerta despierta a todos y permanece cerrada a nuevos checkpoints; el reloj sigue legible para los eventos finales. Una barrera con un participante terminado no permite reanudar.

## Alcance de T55 y siguiente tarea

Los botones Pausar/Reanudar y los registros explícitos de esas transiciones se implementan en T56. En esta entrega, Start no sirve como Resume y Stop/Reset se rechazan durante PAUSING/PAUSED. Tras Resume siguen funcionando según sus contratos anteriores. El cierre sí funciona en cualquier etapa y libera hilos pausados. La matriz ampliada de aceptación de la fase 9 corresponde a T57.

No se ha creado ningún commit local para simular el hash de David. El ZIP contiene las fuentes completas y conserva su repositorio Git al copiarlo encima; no incluye .git, target ni logs. Se corrige la línea vacía al EOF del documento T54 y se registra su cierre confirmado sin reescribir el commit anterior.

## Validación

Se añaden 29 pruebas: 12 del coordinador y 17 de sesión. Las pruebas usan actores reales y un caso de captura retenida con latches para demostrar que la barrera no se completa antes del último reconocimiento. Se comprueban tramos y paradas parciales, conservación de identidad, llamadas, varias cabinas, cancelaciones, EDT, carreras con cierre, independencia entre sesiones y veinte ciclos rápidos sobre los mismos Thread.

Consultar [el acta](../comprobaciones/T55-validacion-local.md) para resultados, tolerancias y comandos Maven. El documento [T55-codigo-completo.md](T55-codigo-completo.md) reproduce íntegras todas las fuentes Java añadidas o modificadas; el ZIP contiene además las demás fuentes previas. David confirmó la validación local y el cierre de T55. Las prioridades y controles se integran en T56.
