# T64 — Sustituir la frontera manejador–cabina por mensajes de texto

- **Fecha:** 2 de noviembre de 2026, Guatemala.
- **Fase:** 11 — Protocolo textual, extra 3.
- **Base aceptada:** T63, commit `6c8d7d3`, 76 específicas/1,002 totales, BUILD SUCCESS, demo correcta y árbol limpio.
- **Estado:** cerrada por validación de David en `36af413`: 48 específicas, 1,050 totales, BUILD SUCCESS, demo real correcta y Git limpio. El resto conserva la descripción de la entrega original T64.

## Resultado

Las recogidas normales del manejador y destinos internos del controlador ahora cruzan la frontera como String V1. ElevatorWorker conserva únicamente texto y fecha Instant contextual en su buzón, decodifica en su propio hilo y reconstruye el Command antes de admitirlo en la agenda. También RESET atraviesa un buzón prioritario con texto y futuro de respuesta. El Command original del emisor no cruza ni se ejecuta desde el buzón.

Las firmas submit(Command), submitIfCurrent(Command, snapshot) y requestReset(Command.Reset) se conservan como adaptadores que serializan antes de encolar. La ruta de producción del manejador/controlador usa explícitamente submitTextIfCurrent/submitText. Se mantiene atómica la comparación de snapshot y el encolado para no despachar usando una estimación obsoleta.

ProtocolMailbox permite observar ACK, errores, MOVE/ARRIVED/DIRECTION/STOPPED_AT y terminales como cadenas generadas por el actor. El envío de respuestas ocurre después de liberar el lock, sin I/O ni callbacks. Cada suscriptor tiene su propia cola; los Result/Service/ExecutionEvent existentes siguen disponibles y no pierden mensajes por la observación textual.

Las pruebas modifican el piso dentro de la cadena y comprueban el piso atendido; comparan que el Command recibido sea otra instancia con el mismo contenido; y verifican el nombre del hilo receptor. Esta evidencia distingue transporte real de un log que simplemente imprime una llamada directa.

## API y responsabilidades

| API | Significado |
| --- | --- |
| ElevatorWorker.submitText(text[, createdAt]) | Encola servicio; true no significa ACK/admisión. Sin fecha explícita usa Instant.EPOCH estable. |
| submitTextIfCurrent(text, createdAt, snapshot) | Compara snapshot/enfila bajo lock local; ENQUEUED, STALE o UNAVAILABLE. |
| requestResetText(text) | Buzón prioritario; el actor exige RESET válido y el futuro confirma retorno físico a 1/UP. |
| awaitProtocolMessage(timeout) | Canal textual propio; consumidor lógico independiente de Result/Service/Event. |
| subscribeProtocol / unsubscribeProtocol | Cola de observación adicional por suscriptor, sin callbacks. |
| protocolReception / controller.protocolReceptions | Contadores y nombre del hilo que realmente leyó los frames. |
| ProtocolCodec.encodeSyntax/decodeCommandSyntax | Solo forma/gramática, no autorización ni configuración. La ruta estricta se mantiene. |

El contexto Instant conserva metadatos del dominio: no proporciona piso, sentido, UUID, época o destinatario, que se leen del texto. El parser estricto valida antes de ejecutar. La decodificación solo de sintaxis se usa para reconstruir rechazos de clientes tipados anteriores cuando la forma es correcta pero el edificio no; ese DTO nunca se admite. Los mensajes malformados sin Command representable producen ERROR textual y no fabrican una solicitud.

Las entradas de servicio no pueden activar RESET y las entradas del buzón de reset no pueden introducir servicio. Los controles adicionales todavía no integrados retornan INVALID_STATE, sin ACK ficticio. Los controles de la GUI siguen usando la coordinación ya validada de Stop/Start, pausa/reanudación y cierre. No se añaden hilos de transporte, sockets ni dependencias.

## Respuestas y ciclo de vida

ACK significa admisión/coalescencia, no llegada. El servicio emite COMPLETED al comenzar una parada compatible. Reset cancela trabajo admitido de su época original, rechaza entradas antiguas aún sin admitir y solo completa al regresar físicamente a 1/UP. Cerrar rechaza entradas no admitidas y cancela las aceptadas, incluido un reset pendiente; un fallo inesperado informa FAILED INTERNAL_ERROR para trabajo activo. La terminación real continúa comprobándose con join.

El actor conserva sus canales y ledger previos. El manejador todavía usa ExecutionEvent para el seguimiento y el controlador usa sus futuros para las barreras. T65 debe integrar las respuestas/STATE y controles restantes, autoridad de productores, idempotencia histórica y descarte de generaciones obsoletas. T64 no declara completadas T65/T66 ni acredita todas las reglas del protocolo completo.

## Archivos de código

| Archivo | Cambio |
| --- | --- |
| protocol/ProtocolCodec.java | Separación explícita entre sintaxis y validación de configuración; API estricta conservada. |
| simulation/ProtocolMailbox.java | Cola de String para respuestas y suscripciones; protege el EDT de esperas positivas. |
| simulation/ElevatorWorker.java | Buzones textuales, parser en actor, reconstrucción, respuestas/cancelaciones y compatibilidad. |
| dispatch/ManagerWorker.java | Serializa PICKUP antes de envío condicional real. |
| simulation/SimulationController.java | Serializa DESTINATION y ofrece observación textual agregada. |
| diagnostics/TextTransportDemo.java | Demostración con manejador y cabinas reales. |
| simulation/TextTransportScenarios.java, en test | 48 escenarios de transporte real independientes de JUnit. |
| simulation/TextTransportTest.java, en test | Los mismos 48 escenarios adaptados a JUnit. |

Las rutas Java de la tabla son relativas a `src/main/java/com/elevatormanager`, salvo los dos archivos de pruebas en `src/test/java/com/elevatormanager`. Los ocho archivos completos aparecen en [T64 — Código completo](T64-codigo-completo.md). El ZIP incluye además el proyecto completo, todos sus .java, recursos, POM y documentación Markdown; excluye .git, target y logs generados.

## Verificación ejecutada

Compilación real Java 17 con release 17/UTF-8 de 128 fuentes independientes. Pasaron 48 escenarios específicos y 957 previos en 46 grupos: 1,005 en total. Se comprobaron manejador, costos/selección, concurrencia, prioridades, reset, importación, logging, pausa, controles/EDT y animación. Cierre/reset/eventos se repitieron después del último ajuste de terminal de cierre, sin sumar sus casos dos veces.

TextTransportDemo ejecutó el flujo real desde clases y JAR auxiliar, incluyendo recuperación de UNKNOWN_COMMAND y servicio confirmado SERVED. [Resultados completos](../comprobaciones/T64-resultados-locales.md). No se ejecutó Maven aquí: se esperan 48 específicas y 1,050 totales, pendientes de Windows. [Guía de aceptación](../comprobaciones/T64-validacion-local.md).

## Incorporación y validación en Windows

Comprobar que no hay cambios personales que se sobrescribirían. El ZIP tiene pom.xml en su raíz y se extrae sobre el proyecto; .git local se conserva.

```powershell
Set-Location 'D:\Users\David\Desktop\workspace\java\elevator-manager'
git status --short
Expand-Archive -LiteralPath "$HOME\Downloads\T64-frontera-textual-manejador-cabina.zip" -DestinationPath . -Force
git diff --check
git diff --stat
git status --short
mvn "-Dtest=TextTransportTest" test
mvn clean verify
java -cp .\target\classes com.elevatormanager.diagnostics.TextTransportDemo
```

Esperado: 48 específicas y 1,050 totales, sin fallos/errores/omitidas, BUILD SUCCESS y última línea T64_TRANSPORT_OK. T64_ORDER representa la orden reportada por el manejador; las líneas T64_WIRE son las respuestas reales leídas del canal. La demo confirma hilo receptor, reconstrucción, ACK separado de COMPLETED y recuperación tras FLY. No se necesitan archivos de entrada ni una nueva configuración de dependencias.

## Commit sugerido tras validar

Actualizar el plan con los resultados reales y marcar T64 terminada después de confirmar la aceptación.

```powershell
git diff --check
git add -- src docs
git diff --cached --check
git diff --cached --stat
git commit -m "T64: sustituir frontera manejador cabina por mensajes de texto"
git status --short
```

No se realiza el commit automáticamente. La siguiente tarea será T65 — Integrar respuestas, estado y reset.


## Cierre recibido antes de T65

David confirmó TextTransportTest con 48 casos y `mvn clean verify` con 1,050, todos sin fallos/errores/omitidas y BUILD SUCCESS. La demo recibió en elevator-worker-1, reconstruyó el Command, confirmó SERVED y recuperó UNKNOWN_COMMAND manteniendo el actor vivo. Commit `36af413`, 18 archivos, 4,881 inserciones/71 eliminaciones y árbol limpio. Esto sustituye los pendientes de validación de la entrega original.
