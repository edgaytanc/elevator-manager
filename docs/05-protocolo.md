# 05 — Protocolo textual y archivo de solicitudes

- **Proyecto:** Elevator Manager.
- **Versión:** 1.0.0; contrato normativo T62 de mensajes V1. Contrato de archivo 1.0.0 en 07.
- **Fecha:** 2026-11-02, Guatemala.
- **Estado:** T62 aprobada en `5b310e5`, T63 validada en `6c8d7d3` y T64 validada en `36af413`. T65 integra seguimiento, STATE/STATUS y controles con pruebas independientes correctas; Maven local y aceptación pendientes. T66 pendiente.
- **Base:** [02 — Decisiones](02-decisiones.md), [03 — Arquitectura](03-arquitectura.md), [04 — Asignación](04-algoritmo-asignacion.md).

## 1. Alcance y transporte

El extra exige órdenes y argumentos enviados en texto. Se propone una línea por mensaje, UTF-8, sobre colas de cadenas en la misma JVM. No basta registrar el texto de una llamada directa entre objetos: la recepción debe parsear la cadena antes de actuar.

El manejador emite recogidas; el controlador emite destinos internos y controles globales dirigidos a cada cabina. Las respuestas regresan por un canal enrutado al propietario de la orden. Las llamadas externas hacia el manejador son objetos de dominio; la frontera textual obligatoria de este diseño es la comunicación con las cabinas.

Se separan mensajes de servicio y control. Controles prioritarios despiertan esperas temporales o de pausa. FIFO por emisor/canal, sin prometer orden total entre emisores o entre control y servicio. Cada cabina procesa secuencialmente los mensajes admitidos.

## 2. Reglas léxicas

- Palabras reservadas y enumeraciones ASCII en mayúsculas; UUID en hexadecimal minúscula. No se admiten traducciones ni cambios de caja.
- Separador entre tokens: uno o más espacios ASCII. No hay texto libre, comillas, tabs ni campos vacíos.
- Se puede eliminar un terminador LF o CRLF de la línea; no aceptar saltos internos.
- Enteros ASCII canónicos: `0` o `[1-9][0-9]*`, sin signo ni ceros iniciales. IDs de comando/secuencias: 1..9223372036854775807; épocas/revisiones/duraciones: 0..9223372036854775807. Piso/ID de cabina: 1..2147483647 y dentro de la configuración.
- Época inicial de la aplicación = 0; se admite una época inicial no negativa inyectada al crear una sesión. Cada reset global usa exactamente época actual + 1. El controlador asigna IDs de comando únicos y crecientes para toda la sesión, empezando en 1; no se reinician al hacer reset. Al agotar un contador se informa fallo local y no se permite wrap-around.
- Dirección: `UP` o `DOWN`. Fase: `IDLE`, `MOVING`, `DWELLING`, `HELD` o `TERMINATED`.
- `-` representa ausencia exclusivamente en campos que lo permiten.
- Cantidad de tokens exacta para cada comando; tokens adicionales son error.

## 3. Gramática de comandos

Los símbolos entre `<...>` son campos; las barras separan alternativas documentales, no se transmiten. Cada línea siguiente es una forma completa.

```text
V1 CMD <cmdId> EPOCH <epoch> ELEVATOR <id> PICKUP <requestId> <floor> <UP|DOWN>
V1 CMD <cmdId> EPOCH <epoch> ELEVATOR <id> DESTINATION <requestId> <floor>
V1 CMD <cmdId> EPOCH <epoch> ELEVATOR <id> RESET
V1 CMD <cmdId> EPOCH <epoch> ELEVATOR <id> STOP
V1 CMD <cmdId> EPOCH <epoch> ELEVATOR <id> START
V1 CMD <cmdId> EPOCH <epoch> ELEVATOR <id> PAUSE
V1 CMD <cmdId> EPOCH <epoch> ELEVATOR <id> RESUME
V1 CMD <cmdId> EPOCH <epoch> ELEVATOR <id> STATUS
V1 CMD <cmdId> EPOCH <epoch> ELEVATOR <id> SHUTDOWN
```

Todos los comandos están dirigidos a una cabina; no existe destinatario ALL. El controlador emite N mensajes y coordina sus confirmaciones. Los IDs de solicitud (`requestId` y `canonicalRequestId`) son UUID en forma canónica minúscula `xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx`, con 32 dígitos hexadecimales y cuatro guiones. Son independientes de los IDs de comando y conservan la identidad de `Request`, `HallRequest` y `CabinRequest`. Los números 100/101 de la propuesta anterior no representan el dominio actual.

| Comando | Validación y efecto |
| --- | --- |
| PICKUP | Solo servicio RUNNING/época actual. Piso/sentido posibles. Agrega recogida o informa duplicado funcional. |
| DESTINATION | Solo servicio RUNNING/época actual. Agrega destino interno a esa cabina. |
| RESET | Control autorizado en época superior; adopta la nueva época, invalida agenda previa y retorna a 1/UP. No aceptar salto de época por otro comando. |
| STOP | Detiene en frontera segura según D12. |
| START | Habilita avance desde RESET o STOPPED en la transición global correspondiente. |
| PAUSE | Congela fase restante; forma parte de la barrera PAUSING. |
| RESUME | Reanuda desde PAUSED con tiempo restante. |
| STATUS | Consulta consistente; no cambia estado ni solicita un destino. |
| SHUTDOWN | Finaliza cooperativamente; debe poder cerrar incluso si el receptor quedó en otra época. |

La autorización depende del controlador interno, no solo de un token recibido. No hay entradas de red no confiables ni autenticación exigida. El código no debe exponer un envío de controles arbitrarios desde el formulario de solicitudes.

## 4. Aceptación, errores y eventos

### 4.1 Respuestas inmediatas

```text
V1 ACK <cmdId> EPOCH <epoch> ELEVATOR <id> ACCEPTED
V1 ACK <cmdId> EPOCH <epoch> ELEVATOR <id> COALESCED <canonicalRequestId>
V1 ERROR <cmdId|0> EPOCH <epoch|0> ELEVATOR <id|0> <errorCode>
```

ACK confirma admisión, no desplazamiento ni finalización. `COALESCED` indica una solicitud funcional equivalente ya activa; se vincula al ID canónico y no se crea trabajo duplicado. Los campos 0 de ERROR se usan únicamente si no se pudo recuperar de manera segura el valor correspondiente del mensaje. La época 0 es válida: en ERROR solo cmdId=0 o elevatorId=0 señalan identidad desconocida; EPOCH=0 sin cmdId fiable no acredita correlación. Se recupera únicamente una cabecera completa y léxicamente válida; de otro modo los tres campos son 0. El diagnóstico interno registra causa y un prefijo limitado de la línea, sin ejecutarla parcialmente.

Errores definidos: `INVALID_FORMAT`, `UNSUPPORTED_VERSION`, `UNKNOWN_COMMAND`, `INVALID_ARGUMENT`, `INVALID_FLOOR`, `INVALID_DIRECTION`, `UNKNOWN_ELEVATOR`, `STALE_EPOCH`, `FUTURE_EPOCH`, `INVALID_STATE`, `ID_CONFLICT`, `WORKER_UNAVAILABLE`, `INTERNAL_ERROR`, `MESSAGE_TOO_LONG`, `UNAUTHORIZED_CONTROL`.

`UNKNOWN_ELEVATOR` lo produce el enrutador si la cabina no existe. Un timeout de supervisión es una incidencia local del emisor, no una respuesta fingida del receptor.

### 4.2 Eventos terminales de comando

```text
V1 EVENT <cmdId> EPOCH <epoch> ELEVATOR <id> COMPLETED
V1 EVENT <cmdId> EPOCH <epoch> ELEVATOR <id> CANCELLED <reason>
V1 EVENT <cmdId> EPOCH <epoch> ELEVATOR <id> FAILED <errorCode>
```

Razones de cancelación: `RESET`, `SHUTDOWN`, `SUPERSEDED`. Un comando aceptado tiene como máximo un resultado terminal lógico. Retransmitir puede repetir su notificación; el consumidor la deduplica por cmdId/época.

- PICKUP/DESTINATION: COMPLETED cuando empieza su servicio, no al emitir ACK ni al finalizar el tiempo de parada.
- RESET: COMPLETED únicamente tras piso 1/UP y descarte de pendientes viejos.
- STOP: COMPLETED al quedar detenida en frontera segura, sin iniciar servicio pendiente.
- PAUSE: COMPLETED al conservar posición y tiempo restante, sin avance posterior hasta un control que lo habilite.
- START/RESUME: COMPLETED al habilitar ejecución; no significa agenda vacía.
- SHUTDOWN: COMPLETED como último evento de dominio antes de terminar el trabajador. El controlador confirma además terminación real del hilo antes de CLOSED.
- STATUS: snapshot correlacionado y después COMPLETED.

Una orden coalescida recibe su resultado cuando termina/cancela la solicitud canónica, sin otro servicio físico. Mantener correlaciones suficientes para notificar a los emisores de ambos comandos.

### 4.3 Eventos de movimiento y estado

```text
V1 MOVE <seq> EPOCH <epoch> ELEVATOR <id> FROM <floor> TO <floor> DIRECTION <UP|DOWN>
V1 ARRIVED <seq> EPOCH <epoch> ELEVATOR <id> FLOOR <floor>
V1 DIRECTION <seq> EPOCH <epoch> ELEVATOR <id> FROM <UP|DOWN> TO <UP|DOWN>
V1 STOPPED_AT <seq> EPOCH <epoch> ELEVATOR <id> FLOOR <floor> SERVICE <UP|DOWN>
V1 STATE <cmdId|0> EPOCH <epoch> ELEVATOR <id> REV <revision> FLOOR <floor> NEXT <floor|-> DIRECTION <UP|DOWN> PHASE <phase> SUSPENDED <MOVING|DWELLING|-> HOLD <STOP|PAUSE|-> MODE <NORMAL|RESET_RETURN> REMAINING_MS <milliseconds> INTERNAL <floorList|-> EXTERNAL <callList|->
```

`seq` aumenta por cabina durante la sesión y permite ordenar eventos físicos. `STATE` usa cmdId de STATUS o 0 si es publicación espontánea. `floorList` es lista ascendente sin espacios, separada por comas, por ejemplo `3,7`. `callList` usa `piso:dirección`, orden por piso y UP antes de DOWN, por ejemplo `3:UP,5:DOWN`. Las listas reflejan pendientes, no solicitudes completadas. `REMAINING_MS` admite 0 y usa redondeo hacia arriba de la duración interna, sin introducir avance por el redondeo.

La snapshot interna usada por el algoritmo conserva más información de ruta/compromisos que este estado público compacto. Se comparte mediante publicación inmutable, no por acceso a la agenda mutable. La consulta pública no pretende reconstruir todo el planificador.

## 5. Épocas, idempotencia y orden

1. Tras comprobar identidad/retransmisión según sección 10, comando de servicio con época menor: STALE_EPOCH; con mayor: FUTURE_EPOCH. Nunca crear servicio de una época futura implícitamente.
2. Solo RESET autorizado adopta exactamente la siguiente época. Repetir el mismo cmdId y contenido devuelve la aceptación/resultado conocido sin volver a ejecutar el retorno.
3. Reset invalida trabajo previo antes de admitir nuevas órdenes. Sus cancelaciones usan la época original para correlación histórica, pero no modifican el estado global nuevo.
4. Mismo cmdId con contenido diferente: ID_CONFLICT, sin mutación. Durante una sesión conservar un registro de resultados de comandos suficiente para deduplicar las retransmisiones; si se introduce purga, habrá que definir una ventana de reintentos antes de purgar.
5. Si se reintenta por demora, usar el mismo cmdId. No generar otro ID para el mismo comando ante un resultado desconocido. Preferir STATUS y diagnóstico antes de reasignar una recogida que pudiera haberse aceptado.
6. El canal es local y no simula pérdidas por defecto. No prometer entrega exactamente una vez: combinar encolado, idempotencia y correlación.
7. Cierre supera reset; reset supera Stop/pausa. Si un control aceptado es sustituido antes de completar, emitir CANCELLED SUPERSEDED. Esto evita barreras esperando confirmaciones imposibles.
8. Confirmación global se calcula sobre las N cabinas esperadas, época y cmdIds emitidos, nunca por contar cualquier N respuestas.

## 6. Secuencia válida de recogida

Configuración: N ≥ 2, P ≥ 7, época 1, sistema RUNNING. Estado previo: cabina 2 inmóvil en piso 7/UP.

```text
V1 CMD 42 EPOCH 1 ELEVATOR 2 PICKUP 00000000-0000-0000-0000-000000000100 7 DOWN
V1 ACK 42 EPOCH 1 ELEVATOR 2 ACCEPTED
V1 DIRECTION 1 EPOCH 1 ELEVATOR 2 FROM UP TO DOWN
V1 STOPPED_AT 2 EPOCH 1 ELEVATOR 2 FLOOR 7 SERVICE DOWN
V1 EVENT 42 EPOCH 1 ELEVATOR 2 COMPLETED
```

El trabajador queda en DWELLING hasta agotar el tiempo de parada; la solicitud ya fue recogida. Después podría recibirse un destino interno como `V1 CMD 43 EPOCH 1 ELEVATOR 2 DESTINATION 00000000-0000-0000-0000-000000000101 4`. Estos ejemplos son referencias de formato, no trazas generadas por el programa.

## 7. Formato de archivo de solicitudes

**Actualización T43, 2026-10-28:** el contrato normativo completo del archivo está en [07 — Formato del archivo de solicitudes](07-formato-archivo-solicitudes.md). Se separa de la gramática de mensajes V1 de las secciones anteriores; esta actualización no implementa el protocolo textual entre actores.

```text
HALL <piso> <UP|DOWN>
CABIN <idElevador> <piso>
```

Se mantienen UTF-8, BOM opcional solo al inicio, LF/CRLF, espacios ASCII, comentarios de línea completa con #, ausencia de tabs y prohibición de comentarios al final de instrucciones. 07 precisa los límites, ceros iniciales, diagnósticos, números de línea, identidad, estados y ejemplos completos.

Se valida el archivo entero antes de sustituir el lote; un error, cancelación o cambio del contexto de importación conserva el anterior. Solo se confirma en RESET y misma sesión/configuración/época. Importar no mueve cabinas. Start presenta las entradas una sola vez en orden de archivo; ese orden no garantiza aceptación ni servicio físico en ese mismo orden.

El importador conserva líneas repetidas. La unificación de equivalentes activos sigue D18; no se promete una única parada para dos líneas iguales bajo cualquier planificación concurrente. Los UUID y la época se asignan al confirmar el lote; los comandos se crean al enviarlos por el flujo normal. El archivo no contiene IDs, épocas, controles ni horarios.

## 8. Seguridad operativa y pruebas

No usar reflexión ni interpretar comandos como código, rutas o shell. El parser mapea tokens a tipos conocidos y rechaza todo lo demás. No retener indefinidamente líneas gigantes o volcarlas completas a la GUI sin límites; si se fija un máximo práctico, documentarlo y probar su rechazo explícito.

Pruebas previstas: round-trip por cada comando, aridad, valores fuera de rango, sentido extremo, época antigua/futura, colisión de ID, retransmisión, duplicado funcional, ACK distinto de completado, control prioritario en pausa, cancelación por reset y archivo atómico. Referencias en [06](06-pruebas.md).

## 9. Historial

| Versión | Fecha | Cambio |
| --- | --- | --- |
| 0.1.0 | 2026-10-19 | Gramática V1, resultados, control de épocas y formato transaccional de archivos. |
| 0.1.1 | 2026-10-28 | T43: sección 7 delega el contrato de archivo completo a 07; mensajes V1 sin cambios. |
| 1.0.0 | 2026-11-02 | T62: UUID, época 0, léxico canónico, framing, estado suspendido, errores deterministas y escenarios de interoperabilidad. Reemplaza la propuesta aún no implementada. |

**Siguiente lectura:** [06 — Plan de pruebas](06-pruebas.md).


## 10. Reglas normativas de interoperabilidad T62

Las palabras «debe» y «rechaza» son obligatorias para un emisor/receptor V1. Esta sección resuelve los detalles abiertos de la propuesta de octubre; prevalece sobre ella y sobre ejemplos conceptuales antiguos del plan. No acredita implementación textual. Se usan N y P de la misma configuración inmutable de sesión en ambos extremos.

### 10.1 Framing y forma canónica

Un elemento de la cola contiene exactamente un mensaje completo. El codificador produce ASCII (subconjunto de UTF-8), un espacio U+0020 entre tokens, sin espacios exteriores ni terminador. El receptor admite varios espacios entre tokens y un único LF o CRLF al final; rechaza espacios exteriores, tabuladores, BOM, CR aislado, caracteres no ASCII, líneas vacías, comentarios y saltos internos. No se usa `trim()` para convertir una entrada inválida en válida. No se admiten concatenaciones de mensajes en un elemento. Al usar un stream futuro, el adaptador separará LF/CRLF antes de entregar cada elemento, sin cambiar la gramática.

Máximo: 1,048,576 bytes por elemento, incluido el terminador opcional; el límite exacto se admite si el mensaje es válido. El exceso produce MESSAGE_TOO_LONG antes de tokenizar, con cabecera 0/0/0, sin truncamiento ni ejecución. No se trocea STATE: si su serialización excede el límite, STATUS admitido termina FAILED MESSAGE_TOO_LONG sin publicar un estado parcial. Las publicaciones espontáneas imposibles se omiten con incidencia local. Este límite de transporte no limita N/P del modelo ni cambia el formato de importación. El diagnóstico conserva como máximo 256 caracteres de entrada escapados, sin volcar megabytes a la GUI.

El parser reconoce únicamente los nombres y posiciones de campos de las secciones 3 y 4. Los símbolos `<...>`, `|` y los nombres de tipos pertenecen al documento, nunca al cable. UUID usa `[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}`; no se exige una versión UUID particular. No se convierte un número en UUID. `-` solo vale en NEXT, SUSPENDED, HOLD, INTERNAL y EXTERNAL. La ausencia no equivale a lista vacía representada por un token vacío.

| Familia | Cantidad exacta de tokens |
| --- | --- |
| CMD PICKUP / DESTINATION / control | 11 / 10 / 8 |
| ACK ACCEPTED / COALESCED | 8 / 9 |
| ERROR | 8 |
| EVENT COMPLETED / CANCELLED / FAILED | 8 / 9 / 9 |
| MOVE / ARRIVED / DIRECTION / STOPPED_AT | 13 / 9 / 11 / 11 |
| STATE | 29 |

La aridad se cuenta separando por uno o más espacios ASCII. Los literales intermedios son obligatorios: `EPOCH`, `ELEVATOR`, `FROM`, `TO`, etc. Los tokens numéricos se validan por dígitos y rango antes de convertirlos. Una cabecera válida tiene exactamente el prefijo `V1 CMD <positiveLong> EPOCH <nonnegativeLong> ELEVATOR <positiveInt>`.

### 10.2 Identidad, versión y contenido operativo

La sesión es el contexto de canales en memoria, no un campo transmitido. No se reutilizan canales ni respuestas de una sesión anterior aunque su contador coincida. No hay negociación: V1 es la única versión; otro token de versión bien formado `V` seguido de dígitos produce UNSUPPORTED_VERSION. Un token de versión con otra forma es INVALID_FORMAT. Todo el retorno usa V1.

La clave de comando es `(sesión, cmdId)` y su contenido incluye época, destinatario, verbo, UUID y argumentos presentes. Comparar tokens normalizados, no espaciado ni terminador. Un emisor único de IDs compartido por controlador/manejador evita colisiones; no hace falta que la recepción vea números consecutivos o crecientes, porque los emisores son concurrentes. El enrutador conserva el destinatario junto a esa clave para detectar reutilización en otra cabina. Un ERROR por ID_CONFLICT no invalida el comando original.

El UUID de solicitud conserva su identidad en retransmisión; para un mismo UUID, tipo, época, cabina asignada, piso y dirección deben coincidir. Inconsistencia produce ID_CONFLICT. Una recogida puede reasignarse solo después de cancelación confirmada y con identidad nueva; un timeout no demuestra cancelación. Un UUID canónico en COALESCED debe identificar trabajo equivalente activo de esa cabina y época. Nuevas solicitudes después del servicio usan otro UUID.

Los campos transportados son operativos. `createdAt` del dominio no está en V1 ni se usa para asignar costo/orden: un codec debe modelar un DTO de protocolo sin inventar una fecha de emisión ni regenerar el UUID. El adaptador al dominio conserva metadatos de sesión cuando los tenga; no se promete round-trip de campos nunca transmitidos. T63 debe probar round-trip del DTO y todos sus campos, no equiparar `Request.equals` (identidad UUID) a igualdad de contenido.

### 10.3 Validación y errores deterministas

El receptor de comandos aplica el siguiente orden y devuelve un único ERROR en el primer paso que falla. El rechazo no modifica agenda, estado físico ni época.

1. Límite, framing y léxico permitido: MESSAGE_TOO_LONG o INVALID_FORMAT.
2. Versión: UNSUPPORTED_VERSION o INVALID_FORMAT según 10.2.
3. Familia CMD y cabecera completa: INVALID_FORMAT. La cola de órdenes no acepta ACK/EVENT/STATE como órdenes.
4. Verbo: UNKNOWN_COMMAND; luego aridad y etiquetas exactas: INVALID_FORMAT.
5. Tipos de argumentos: INVALID_ARGUMENT para número no canónico, desbordamiento o UUID inválido; INVALID_DIRECTION para sentido distinto de UP/DOWN.
6. Configuración: UNKNOWN_ELEVATOR si el destino no existe, luego INVALID_FLOOR si piso no pertenece a 1..P, luego INVALID_DIRECTION para UP en P o DOWN en 1. Para P=1 no hay PICKUP válido; DESTINATION a 1 sí puede admitirse en RUNNING.
7. Registro de comandos: ID_CONFLICT si misma clave con contenido diferente; si mismo contenido, reproducir ACK/resultado conocido sin ejecutar. Esta comprobación precede a la época/estado actual para permitir reintentar un comando histórico terminado. Los rechazos no reservan cmdId; los comandos admitidos sí.
8. Autoridad: controles solo por el canal privado del controlador; UNAUTHORIZED_CONTROL si otro emisor intenta usarlos. PICKUP solo por el manejador; DESTINATION solo por el controlador. Un productor con rol incorrecto recibe UNAUTHORIZED_CONTROL, también para servicio. El rol es contexto del canal, nunca un token que el mensaje se autoconcede. Los replays también verifican el rol original antes de responder.
9. Época: STALE_EPOCH si menor, FUTURE_EPOCH si mayor. RESET nuevo requiere exactamente actual+1: menor es STALE_EPOCH, igual es INVALID_STATE, mayor que siguiente es FUTURE_EPOCH. Solo SHUTDOWN autorizado puede usar una época distinta, manteniendo la época de su comando en ACK/terminal.
10. Disponibilidad y estado: WORKER_UNAVAILABLE para trabajador terminado/canal cerrado; INVALID_STATE para transición no habilitada. Un cierre ya admitido puede reproducirse desde el registro aunque el actor haya terminado. Excepciones inesperadas al admitir producen INTERNAL_ERROR y diagnóstico local; nunca se deja morir un trabajador por un mensaje inválido.

Para cabecera recuperable, ERROR copia los tres valores originales; no sustituye EPOCH por la época vigente. Sin cabecera completa, los tres son 0. La cabecera es recuperable incluso si el verbo o su cuerpo son inválidos. Una versión no soportada no tiene cabecera V1 recuperable y usa 0/0/0. Un destinatario inexistente conserva su ID positivo solicitado.

Si el error ocurre después de ACK, se emite EVENT FAILED, no ERROR retroactivo. Un error de parseo en un canal de respuestas se diagnostica y descarta sin responder ERROR a ERROR (evitar bucles). Para respuestas válidas, el consumidor verifica cabina, época e ID esperados antes de cambiar seguimiento. Los códigos de error y las razones de CANCELLED son conjuntos cerrados de la sección 4; una palabra ajena es INVALID_ARGUMENT al decodificar.

### 10.4 Controles, estado y prioridades

El controlador reserva la transición global antes de enviar controles. Los estados de esta tabla son los de `SimulationState` del coordinador, no los de `ElevatorPhase`. La precondición se comprueba al admitir bajo coordinación con reset/cierre; un ACK no autoriza una transición global que el coordinador no haya iniciado.

| Orden | Estado global que habilita su envío | Efecto y condición de finalización |
| --- | --- | --- |
| PICKUP / DESTINATION | RUNNING | Añadir o coalescer servicio de época vigente; completar al iniciar la parada compatible. |
| START | RESET / STOPPED | Habilitar trabajador; COMPLETED al quedar habilitado. El lote RESET se presenta por el controlador, no por el codec. |
| STOP | RUNNING / PAUSING / PAUSED | Detener despacho. Completar tramo adyacente iniciado; suspender parada iniciada con restante; no iniciar otra recogida. COMPLETED en frontera segura. |
| PAUSE | RUNNING | Congelar tiempo activo/tramo/parada, confirmar barrera. COMPLETED al quedar congelado; no crea ni elimina solicitudes. |
| RESUME | PAUSED | Habilitar tiempo activo y continuar fase restante; no confundir con START desde STOPPED. |
| RESET | RESET / RUNNING / STOPPING / STOPPED / PAUSING / PAUSED / RESETTING | Adoptar siguiente época y cancelar trabajo viejo; completar tramo iniciado coherentemente y retornar físicamente a 1/UP. Termina en RESET esperando Start. Un nuevo reset puede sustituir retorno previo. |
| STATUS | Cualquier estado hasta CLOSING mientras receptor vivo | Capturar una instantánea coherente; emitir STATE correlacionado y luego COMPLETED. |
| SHUTDOWN | Cualquier estado salvo CLOSED | Cancelar pendientes y terminar cooperativamente; COMPLETED antes de terminar hilo. CLOSED exige además join real de todos los hilos. |

Repetir START estando RUNNING, STOP estando STOPPED, PAUSE estando PAUSED o RESUME estando RUNNING con un cmdId nuevo produce INVALID_STATE. Repetir el mismo cmdId conserva idempotencia. STOP puede sustituir pausa; cierre sustituye cualquier control y RESET sustituye Stop/pausa. Para controles del mismo nivel prevalece el orden de admisión; RESET nuevo sustituye RESET viejo. El control sustituido que aún no terminó recibe CANCELLED SUPERSEDED. Un control ya completado no recibe otro terminal.

Al admitir RESET se cancelan comandos de servicio activos con CANCELLED RESET y su época original, incluidos aliases coalescidos; se invalida la entrada vieja en vuelo. Tras ACK del reset, el trabajador ya adoptó su época, pero falta el retorno. El controlador espera exactamente los cmdIds de las N cabinas y la confirmación del manejador antes de publicar RESET global. Durante RESETTING no se admite servicio, aunque una cabina haya llegado antes. SHUTDOWN cancela servicio con SHUTDOWN. Las cancelaciones no borran el registro de resultados.

No se espera respuesta manteniendo un bloqueo global. Colas separadas permiten despertar al trabajador pausado o detenido para controles. El canal de control conserva FIFO por productor; los controles prioritarios pueden adelantar servicio, por eso el emisor no infiere ejecución a partir del orden de encolado. Se mantiene un hilo por cabina y uno de manejador; no se crea un hilo por mensaje.

### 10.5 Estado público y eventos

STATE tiene los campos y orden exactos siguientes; no permite omisiones ni pares clave/valor adicionales:

```text
V1 STATE 90 EPOCH 0 ELEVATOR 1 REV 8 FLOOR 2 NEXT 3 DIRECTION UP PHASE HELD SUSPENDED MOVING HOLD PAUSE MODE NORMAL REMAINING_MS 450 INTERNAL 5 EXTERNAL 3:UP
```

REV empieza en 0 y no disminuye durante la sesión. Dos snapshots con la misma revisión pueden informar menor restante al avanzar el reloj activo sin transición discreta: no descartarlos solo por igualdad de REV. Dentro de cada canal se conserva FIFO; con época menor se ignora el estado para la sesión vigente; revisión menor de misma época se descarta. Una respuesta STATUS histórica se puede entregar al solicitante como histórica sin instalarla como estado actual. No comparar revisiones de distintas cabinas.

Si PHASE=HELD, SUSPENDED es MOVING o DWELLING y HOLD es STOP o PAUSE; HOLD=STOP nunca admite SUSPENDED=MOVING. Fuera de HELD ambos son `-`. NEXT se presenta exactamente si la fase efectiva es MOVING y es el piso adyacente según dirección; FLOOR conserva el último piso dejado hasta ARRIVED. En otros casos NEXT es `-`. MODE=RESET_RETURN y PHASE=TERMINATED exigen listas vacías. REMAINING_MS=0 en IDLE/TERMINATED; en MOVING/DWELLING/HELD pertenece a 0..duración configurada de la fase efectiva y es techo de nanosegundos restantes dividido por 1,000,000, sin suma que desborde. No se serializa nanoTime como fecha civil.

INTERNAL usa pisos únicos ascendentes separados por coma; EXTERNAL usa pares únicos `piso:UP`/`piso:DOWN` ordenados por piso, UP antes de DOWN. Sin espacios interiores ni comas finales; vacío es `-`. Validar rango y sentidos extremos de cada entrada. Estas listas muestran pendientes por función, no orden de servicio ni UUID. STATE es una proyección de consulta, no reemplaza `ElevatorSnapshot` de planificación/animación: no contiene frontera comprometida, reloj preciso ni duración total. La GUI conserva su publicación inmutable de alta precisión.

MOVE usa FROM/TO adyacentes coherentes con DIRECTION. ARRIVED indica llegada lógica; STOPPED_AT indica inicio del servicio en FLOOR con el sentido de servicio (también para destino interno), no fin de la espera. DIRECTION solo se emite si cambia el sentido. `seq` físico es único y creciente por cabina, empieza en 1, no se reinicia por reset y puede tener huecos porque otros eventos internos no se transmiten. No se interpreta REV como `seq` ni cmdId. ACK y terminales conservan la época del comando; eventos físicos/STATE espontáneo usan la vigente al capturar. Un lector de respuestas valida léxico, aridad, enumeraciones y coherencia con configuración, sin aplicar precondiciones de admisión como RUNNING o época vigente a eventos históricos válidos.

### 10.6 Retransmisión, coalescencia y resultados

Cada orden admitida produce un ACK antes de sus propios STATE/terminales. Para STATUS: ACK, STATE correlacionado, COMPLETED. No se exige orden global entre mensajes de distintas órdenes/cabinas. ACCEPTED y COALESCED son excluyentes. Una repetición de orden activa reproduce el mismo ACK; de orden terminal reproduce el ACK y el mismo terminal; STATUS reproduce además el STATE original, sin tomar una consulta nueva bajo el mismo ID. Una nueva consulta debe usar otro cmdId.

El receptor conserva el registro durante toda la sesión; no hay purga ni reintentos automáticos infinitos. Los consumidores deduplican terminales por cmdId/época/cabina y no reducen dos veces un contador de pendientes. ERROR es rechazo previo a admisión y no exige EVENT posterior. Un comando admitido debe acabar en COMPLETED, CANCELLED o FAILED si el proceso sigue operativo; un fallo fatal de JVM no permite garantizar una respuesta. El supervisor detecta timeout como fallo local, nunca inventa ACK/terminal del actor.

Una solicitud equivalente es DESTINATION de igual cabina/piso o PICKUP de igual cabina/piso/sentido, en la misma época y todavía activa. Distinto UUID/cmdId recibe COALESCED con UUID canónico. Su resultado sigue al del trabajo canónico y todos sus cmdIds reciben terminal propio. Si una solicitud ya empezó servicio, terminó lógicamente y no se coalesce retroactivamente. Repetir mismo cmdId reproduce su resultado aunque la cabina esté ahora en otro piso o época.

## 11. Ejemplos de conformidad T62

Son vectores documentales, no trazas ejecutadas. N=2, P=8, época 0, tiempo entre pisos/parada al menos 450 ms. Cada escenario es independiente salvo sus propias líneas. Las líneas se muestran en orden causal; no representan una garantía de ausencia de eventos adicionales.

### 11.1 Admisión y duplicado funcional

Cabina 1 RUNNING en piso 1/UP. No empieza el servicio a 5 entre estas admisiones.

```text
V1 CMD 10 EPOCH 0 ELEVATOR 1 DESTINATION 00000000-0000-0000-0000-000000000010 5
V1 ACK 10 EPOCH 0 ELEVATOR 1 ACCEPTED
V1 CMD 11 EPOCH 0 ELEVATOR 1 DESTINATION 00000000-0000-0000-0000-000000000011 5
V1 ACK 11 EPOCH 0 ELEVATOR 1 COALESCED 00000000-0000-0000-0000-000000000010
V1 EVENT 10 EPOCH 0 ELEVATOR 1 COMPLETED
V1 EVENT 11 EPOCH 0 ELEVATOR 1 COMPLETED
```

Los terminales suceden al iniciar servicio a 5, tras el movimiento omitido del ejemplo. Si se reenvía el primer CMD después de terminar, se reproducen ACK 10 ACCEPTED y EVENT 10 COMPLETED sin crear otra parada.

### 11.2 Reset con orden en vuelo y cancelación

La cabina aún tiene activa la orden 20 al admitir reset 21; el productor autorizado inicia transición global a época 1.

```text
V1 CMD 20 EPOCH 0 ELEVATOR 1 DESTINATION 00000000-0000-0000-0000-000000000020 8
V1 ACK 20 EPOCH 0 ELEVATOR 1 ACCEPTED
V1 CMD 21 EPOCH 1 ELEVATOR 1 RESET
V1 ACK 21 EPOCH 1 ELEVATOR 1 ACCEPTED
V1 EVENT 20 EPOCH 0 ELEVATOR 1 CANCELLED RESET
V1 CMD 22 EPOCH 0 ELEVATOR 1 PICKUP 00000000-0000-0000-0000-000000000022 3 UP
V1 ERROR 22 EPOCH 0 ELEVATOR 1 STALE_EPOCH
V1 EVENT 21 EPOCH 1 ELEVATOR 1 COMPLETED
```

El último evento exige retorno físico a 1/UP. La barrera global todavía espera las otras cabinas y el manejador. Retransmitir CMD 20 devuelve su ACK y CANCELLED históricos, sin reinstalar su solicitud en época 1.

### 11.3 Consulta en pausa

Cabina HELD/MOVING hacia 3, tiempo restante 450 ms y solicitudes pendientes que muestra STATE; coordinador PAUSED.

```text
V1 CMD 90 EPOCH 0 ELEVATOR 1 STATUS
V1 ACK 90 EPOCH 0 ELEVATOR 1 ACCEPTED
V1 STATE 90 EPOCH 0 ELEVATOR 1 REV 8 FLOOR 2 NEXT 3 DIRECTION UP PHASE HELD SUSPENDED MOVING HOLD PAUSE MODE NORMAL REMAINING_MS 450 INTERNAL 5 EXTERNAL 3:UP
V1 EVENT 90 EPOCH 0 ELEVATOR 1 COMPLETED
V1 CMD 91 EPOCH 0 ELEVATOR 1 RESUME
V1 ACK 91 EPOCH 0 ELEVATOR 1 ACCEPTED
V1 EVENT 91 EPOCH 0 ELEVATOR 1 COMPLETED
```

STATUS no reanuda el reloj. RESUME es del controlador; solo cuando toda la barrera esté lista vuelve el coordinador a RUNNING.

### 11.4 Rechazos completos y precedencia

Cada fila tiene registro vacío, RUNNING y época 0, excepto donde se indica. En la fila de conflicto ya se admitió `CMD 10 ... DESTINATION ...0010 5` de 11.1.

| Entrada completa | Respuesta completa esperada |
| --- | --- |
| `V2 CMD 1 EPOCH 0 ELEVATOR 1 STATUS` | `V1 ERROR 0 EPOCH 0 ELEVATOR 0 UNSUPPORTED_VERSION` |
| `V1 CMD 1 EPOCH 0 ELEVATOR 1 FLY` | `V1 ERROR 1 EPOCH 0 ELEVATOR 1 UNKNOWN_COMMAND` |
| `V1 CMD 2 EPOCH 0 ELEVATOR 1 RESET EXTRA` | `V1 ERROR 2 EPOCH 0 ELEVATOR 1 INVALID_FORMAT` |
| `V1 CMD 3 EPOCH 0 ELEVATOR 1 DESTINATION 123 2` | `V1 ERROR 3 EPOCH 0 ELEVATOR 1 INVALID_ARGUMENT` |
| `V1 CMD 4 EPOCH 0 ELEVATOR 1 DESTINATION 00000000-0000-0000-0000-000000000004 9` | `V1 ERROR 4 EPOCH 0 ELEVATOR 1 INVALID_FLOOR` |
| `V1 CMD 5 EPOCH 0 ELEVATOR 1 PICKUP 00000000-0000-0000-0000-000000000005 1 DOWN` | `V1 ERROR 5 EPOCH 0 ELEVATOR 1 INVALID_DIRECTION` |
| `V1 CMD 6 EPOCH 0 ELEVATOR 3 STATUS` | `V1 ERROR 6 EPOCH 0 ELEVATOR 3 UNKNOWN_ELEVATOR` |
| `V1 CMD 7 EPOCH 1 ELEVATOR 1 STATUS` | `V1 ERROR 7 EPOCH 1 ELEVATOR 1 FUTURE_EPOCH` |
| `V1 CMD 8 EPOCH 0 ELEVATOR 1 START` | `V1 ERROR 8 EPOCH 0 ELEVATOR 1 INVALID_STATE` |
| `V1 CMD 10 EPOCH 0 ELEVATOR 1 DESTINATION 00000000-0000-0000-0000-000000000010 6` | `V1 ERROR 10 EPOCH 0 ELEVATOR 1 ID_CONFLICT` |
| `V1 CMD 0 EPOCH 0 ELEVATOR 1 STATUS` | `V1 ERROR 0 EPOCH 0 ELEVATOR 0 INVALID_FORMAT` |
| `V1 CMD 9 EPOCH 0 ELEVATOR 1 DESTINATION 00000000-0000-0000-0000-000000000009 02` | `V1 ERROR 9 EPOCH 0 ELEVATOR 1 INVALID_ARGUMENT` |

No aplicar simultáneamente varios errores de filas distintas. Ejemplo de precedencia: cuerpo con piso 9 y destinatario 3 produce UNKNOWN_ELEVATOR, pues destino se verifica antes que rango de piso.

## 12. Entrega por tarjetas y aceptación

| Tarjeta | Resultado esperado |
| --- | --- |
| T62 | Este contrato revisable, ejemplos, trazabilidad y matriz de pruebas futuras. Sin cambio Java. |
| T63 | DTOs/codec de todas las familias aquí definidas, errores controlados y round-trip canónico. |
| T64 | Cadenas reales cruzando colas; parseo en la frontera del trabajador. |
| T65 | Adaptación de controles, ACK/terminales, snapshots y barreras de época sin perder correlaciones. |
| T66 | Demostración reproducible de integración, errores y reset con mensajes en vuelo. |

T62 satisface la definición documental cuando versión, framing, tipos, aridades, autoridad, identidad, estado y cada resultado permiten construir extremos compatibles sin leer Java. El extra 3 solo se acredita al integrar y demostrar T64–T66. El archivo HALL/CABIN de 07 no admite estas líneas V1 ni sirve como transporte textual entre actores.


## 13. Implementación del codec T63

`ProtocolMessage` contiene DTOs inmutables y sin fechas; `ProtocolCodec(config)` expone `encode`, `decode`, `decodeCommand` y `decodeResponse`. La emisión es canónica; la recepción admite los separadores/terminadores definidos en 10.1 y verifica configuración, aridades y coherencia del estado público. Las entradas inválidas retornan `ProtocolDecodeResult.Failure`, sin ejecutar órdenes ni detener un trabajador. `encode` informa `EncodingException` con código estructurado; no trunca mensajes demasiado largos.

El parser aplica los pasos 1–6 de 10.3. Los pasos 7–10 (identidad histórica, rol, época vigente y estado/disponibilidad) pertenecen a la admisión T64–T65. Decodificar RESET con época 0 puede ser sintácticamente válido y aun así ser rechazado al intentar admitirlo en la sesión; `decode` no confirma aceptación. El constructor de DTO valida forma independiente del edificio; el codec añade rangos y tiempos de la configuración. Pisos numéricos canónicos 0 producen INVALID_FLOOR; cabeceras CMD con ID/destinatario 0 son INVALID_FORMAT sin correlación recuperable.

`ProtocolCommands` convierte los tres `Command` actuales a DTO y de vuelta usando `Instant createdAt` explícito del llamador. Conserva UUID y contenido operativo; no inventa fechas de envío, no asigna IDs nuevos ni autoriza ejecución. Los otros controles tienen DTO propio y esperarán su adaptación al motor en T65. El método `Failure.asErrorMessage()` solo construye datos: el receptor de respuestas debe diagnosticar/descartar el fallo y nunca enviarlo automáticamente al otro extremo.

El tamaño se mide en bytes UTF-8 antes de tokenizar, sin construir una copia UTF-8 gigante; después se exige ASCII. Se retienen como máximo 29 tokens de framing para rechazar excesos y no crear un array proporcional al número de espacios. El escritor se detiene al exceder 1 MiB y la vista de diagnóstico se limita a 256 caracteres escapados. Las listas de STATE se copian defensivamente y se exige orden único, sin corregir silenciosamente la entrada.

Hay 76 escenarios nuevos equivalentes bajo JUnit y un main independiente. [Resultados T63](comprobaciones/T63-resultados-locales.md) distingue pruebas ejecutadas, regresión y Maven pendiente. Esta actualización documenta la implementación del contrato 1.0.0 sin cambiar la versión de cable V1. Los ejemplos documentales de 11 continúan como vectores normativos; no se presentan como una traza del motor.


## 14. Transporte real T64

El envío normal de PICKUP en ManagerWorker y DESTINATION en SimulationController llama al codec estricto y encola una cadena. ElevatorWorker mantiene un buzón de servicio de sobres que contienen únicamente String/Instant y otro prioritario de RESET con String/futuro. El futuro es correlación local de retorno, no una orden tipada escondida. Los métodos públicos antiguos submit/submitIfCurrent/requestReset serializan antes de encolar; no se almacena ni ejecuta el Command original.

El receptor usa decodeCommand en su hilo y solo reconstruye servicio/reset cuando ese resultado es válido. La fecha es contexto explícito del emisor, ausente de V1; la sobrecarga de texto sin fecha usa Instant.EPOCH estable. Se conserva el contexto de identidad del ledger previo sin inventar una fecha nueva en cada reintento. ProtocolReception publica contadores y nombre del hilo receptor sin retener entradas originales gigantes.

El codec incorpora encodeSyntax/decodeCommandSyntax para separar forma de configuración. Los métodos estrictos encode/decode/decodeCommand/decodeResponse conservan su validación completa y sus 76 pruebas. La ruta tipada compatible usa encodeSyntax para que un destino fuera del edificio llegue al receptor como antes y este produzca el rechazo. Si el parser estricto rechaza un mensaje estructuralmente correcto, decodeCommandSyntax se usa solo para reconstruir Result/ExecutionEvent de rechazo de clientes previos; ese objeto nunca entra en la agenda ni se mueve. Una entrada malformada sin Command representable solo produce ERROR textual y diagnóstico limitado.

ProtocolMailbox y awaitProtocolMessage ofrecen respuestas textuales producidas por el actor: ACK ACCEPTED/COALESCED, ERROR, MOVE, ARRIVED, DIRECTION, STOPPED_AT y terminales COMPLETED/CANCELLED/FAILED. La serialización se prepara con datos locales; la publicación a colas/suscriptores ocurre después de soltar el lock y sin callbacks/I/O. Cada suscripción tiene su propia cola; no roba eventos del manejador o la GUI. Desuscribir puede dejar un último frame de un lote ya capturado concurrentemente. Una cola tiene un consumidor lógico; ZERO permite sondeo y una espera positiva desde EDT se rechaza.

RESET se admite desde el buzón prioritario, no desde el de servicio. Recibe ACK al adoptarse y COMPLETED solo al volver a 1/UP; cancela trabajo activo con época original y rechaza entradas antiguas aún sin admisión. Al cerrar, el actor rechaza entradas no admitidas y cancela trabajo aceptado, incluido un reset todavía pendiente. Si falla inesperadamente, los trabajos activos reciben FAILED INTERNAL_ERROR. Repetir un comando servido de la época aún gestionada reproduce su ACK/terminal textual conocido sin duplicar el servicio físico; los eventos tipados conservan su semántica previa de replay.

Esta tarjeta preserva el ledger y los controles nativos: todavía no centraliza autoridad de productores ni idempotencia histórica entre épocas/destinatarios. El manejador sigue consumiendo ExecutionEvent para correlacionar admisión/servicio y las barreras de control conservan sus futuros; T65 debe integrar las respuestas de texto, STATE/STATUS, controles restantes y descarte de mensajes obsoletos conforme al contrato. STOP/START/PAUSE/RESUME/STATUS/SHUTDOWN enviados como texto a la cabina se rechazan con INVALID_STATE en T64; la GUI sigue ofreciendo esos controles por su coordinación existente. No se proclama completado todo el protocolo ni T66.

El diagnóstico TextTransportDemo ejecuta actores reales y muestra las respuestas emitidas por el receptor, no solo una llamada directa formateada como log. La línea T64_ORDER es la representación canónica de la orden reportada por el manejador; T64_WIRE son cadenas realmente recibidas del canal de la cabina. Las pruebas alteran el cuerpo antes de enviarlo y verifican el piso atendido, la reconstrucción de objetos y el hilo que procesó la cadena.


## 15. Integración de respuestas y generaciones T65

Esta sección actualiza el alcance pendiente de 14 sin cambiar la gramática ni V1. El manejador de producción se suscribe a ProtocolMailbox, decodifica respuestas y aplica HallRequestLedger.onProtocol. onEvent permanece para clientes/escenarios tipados previos, pero no conduce el seguimiento de producción. El par cabina/ID debe corresponder al intento actualmente asignado, además de la época: un ACK de un intento descartado no puede asignar uno nuevo que reutilice la misma entrada lógica.

ACK lleva la llamada a ASSIGNED y no a SERVED. MOVE/ARRIVED/DIRECTION/STOPPED_AT mantienen evidencia secuenciada por cabina; COMPLETED solo sirve una llamada después de ACK y parada del mismo piso/sentido. Repeticiones/terminales ajenos se ignoran. ERROR vuelve a pendiente con rechazo de esa cabina; FAILED conserva UNCONFIRMED sin reasignación automática; una cancelación confirmada por cierre permite la política de reintento ya existente.

El controlador registra cada control antes de encolarlo y recibe sus respuestas a través de un consumidor de texto por sesión. ProtocolReplyTracker exige coincidencia exacta de ID, cabina y época; ACK y STATE no completan un ticket por sí solos. STATUS y RESET requieren su STATE correlacionado antes de COMPLETED. Una prueba final de reset debe indicar piso 1, UP, IDLE, modo NORMAL y listas vacías. La finalización global añade la limpieza del manejador y verifica las snapshots físicas; no cuenta N mensajes cualesquiera como N cabinas.

adoptEpoch invalida solo la caché de estado público vigente, sin borrar las correlaciones históricas pendientes. Una respuesta antigua puede resolver su propio ticket, pero no instalar una snapshot en la época nueva ni activar una transición global sustituida. Revisiones menores se descartan; revisión igual con restante distinto se admite. STATE espontáneo usa ID 0; un STATE positivo no solicitado o anterior al ACK no se instala. Un replay de STATUS terminado devuelve su captura original y no hace retroceder la caché actual.

Los canales managerChannel/controllerChannel son capacidades internas por objeto, cuyo origen no se transmite como un token de confianza. El primero admite PICKUP; el segundo DESTINATION y controles. La composición del controlador comparte ProtocolIdentities entre sus cabinas: un ID de comando aceptado no se reutiliza con otro contenido/cabina/época y se conserva hasta terminar la sesión. El actor comprueba rol, conflicto y replay antes de época/estado. Los adaptadores tipados y de texto heredados se conservan para compatibilidad de escenarios anteriores; la producción utiliza los canales con origen. El ledger del dominio sigue administrando las identidades UUID de las solicitudes.

START, STOP, PAUSE, RESUME, STATUS y SHUTDOWN se procesan en el buzón de controles del actor; RESET sigue siendo prioritario y admite sustitución por una generación posterior sin alterar el tramo físico en curso. Controles sustituidos reciben CANCELLED SUPERSEDED; cerrar cancela trabajo/control pendientes y SHUTDOWN produce su terminal antes de la salida real. El cierre nativo queda como salvaguarda si ya no puede asignarse otro ID, sin fabricar una confirmación textual.

La pausa sigue coordinada por la barrera N+1 y un reloj compartido. Un wake de control permite que el actor lea STATUS dentro del checkpoint, fuera del lock de barrera y sin reanudar el reloj ni admitir servicio. PAUSE completa solo tras la captura congelada; RESUME se confirma tras liberar la barrera. El consumidor de respuestas no participa en la pausa porque debe seguir recibiendo las confirmaciones; no mueve elevadores ni altera el tiempo activo.

queryStatus requiere actores iniciados y es válida en RUNNING, STOPPED, PAUSED y RESET posterior al retorno. Antes del primer Start o después del cierre falla localmente sin dejar futuros pendientes. protocolState ofrece la última proyección recibida de la generación vigente. Ninguna de estas APIs reemplaza ElevatorSnapshot ni el reloj de alta precisión de la animación.

No se añaden hilos por mensaje. Un solo ProtocolResponsePump por sesión lee respuestas, y su join termina antes de CLOSED; las finalizaciones de futuros ocurren fuera de monitores y fuera de los trabajadores de cabina. Las colas de suscripción anteriores no se comparten entre consumidores. Los cambios de ids por controles se reflejan en los escenarios de importación: preparar un archivo no consume IDs; START emite uno por cabina antes de enviar el lote.

[Resultados T65](comprobaciones/T65-resultados-locales.md) contiene pruebas de correlación adversarial, estado viejo, pausa, cierre y reset. La demostración de aceptación final del protocolo completo queda en T66; no se atribuye a T65 el cierre de esa tarjeta ni de toda la fase 11.
