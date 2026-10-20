# 05 — Protocolo textual y archivo de solicitudes

- **Proyecto:** Elevator Manager.
- **Versión:** 0.1.0; versión de mensajes V1.
- **Fecha:** 2026-10-19, Guatemala.
- **Estado:** contrato propuesto; no existe emisor/receptor implementado todavía.
- **Base:** [02 — Decisiones](02-decisiones.md), [03 — Arquitectura](03-arquitectura.md), [04 — Asignación](04-algoritmo-asignacion.md).

## 1. Alcance y transporte

El extra exige órdenes y argumentos enviados en texto. Se propone una línea por mensaje, UTF-8, sobre colas de cadenas en la misma JVM. No basta registrar el texto de una llamada directa entre objetos: la recepción debe parsear la cadena antes de actuar.

El manejador emite recogidas; el controlador emite destinos internos y controles globales dirigidos a cada cabina. Las respuestas regresan por un canal enrutado al propietario de la orden. Las llamadas externas hacia el manejador son objetos de dominio; la frontera textual obligatoria de este diseño es la comunicación con las cabinas.

Se separan mensajes de servicio y control. Controles prioritarios despiertan esperas temporales o de pausa. FIFO por emisor/canal, sin prometer orden total entre emisores o entre control y servicio. Cada cabina procesa secuencialmente los mensajes admitidos.

## 2. Reglas léxicas

- Tokens ASCII en mayúsculas; no se admiten traducciones ni minúsculas en el formato persistido/transmitido.
- Separador entre tokens: uno o más espacios ASCII. No hay texto libre, comillas, tabs ni campos vacíos.
- Se puede eliminar un terminador LF o CRLF de la línea; no aceptar saltos internos.
- Enteros decimales sin signo, dentro de `long` positivo para IDs, épocas y revisiones; piso/ID de cabina deben caber en `int` positivo y validarse contra configuración.
- Época inicial = 1. El controlador asigna IDs de comando únicos y crecientes para toda la sesión; no se reinician al hacer reset.
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

Todos los comandos están dirigidos a una cabina; no existe destinatario ALL. El controlador emite N mensajes y coordina sus confirmaciones. Los IDs de solicitud son independientes de los IDs de comando y permiten rastrear el servicio, incluso si se retransmite una orden.

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

ACK confirma admisión, no desplazamiento ni finalización. `COALESCED` indica una solicitud funcional equivalente ya activa; se vincula al ID canónico y no se crea trabajo duplicado. Los campos 0 de ERROR se usan únicamente si no se pudo recuperar de manera segura el valor correspondiente del mensaje. El diagnóstico interno registra además la línea y causa, sin ejecutarla parcialmente.

Errores definidos: `INVALID_FORMAT`, `UNSUPPORTED_VERSION`, `UNKNOWN_COMMAND`, `INVALID_ARGUMENT`, `INVALID_FLOOR`, `INVALID_DIRECTION`, `UNKNOWN_ELEVATOR`, `STALE_EPOCH`, `FUTURE_EPOCH`, `INVALID_STATE`, `ID_CONFLICT`, `WORKER_UNAVAILABLE`, `INTERNAL_ERROR`.

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
V1 STATE <cmdId|0> EPOCH <epoch> ELEVATOR <id> REV <revision> FLOOR <floor> NEXT <floor|-> DIRECTION <UP|DOWN> PHASE <phase> REMAINING_MS <milliseconds> INTERNAL <floorList|-> EXTERNAL <callList|->
```

`seq` aumenta por cabina durante la sesión y permite ordenar eventos físicos. `STATE` usa cmdId de STATUS o 0 si es publicación espontánea. `floorList` es lista ascendente sin espacios, separada por comas, por ejemplo `3,7`. `callList` usa `piso:dirección`, orden por piso y UP antes de DOWN, por ejemplo `3:UP,5:DOWN`. Las listas reflejan pendientes, no solicitudes completadas. `REMAINING_MS` admite 0 y usa redondeo hacia arriba de la duración interna, sin introducir avance por el redondeo.

La snapshot interna usada por el algoritmo conserva más información de ruta/compromisos que este estado público compacto. Se comparte mediante publicación inmutable, no por acceso a la agenda mutable. La consulta pública no pretende reconstruir todo el planificador.

## 5. Épocas, idempotencia y orden

1. Comando de servicio con época menor: STALE_EPOCH; con mayor: FUTURE_EPOCH. Nunca crear servicio de una época futura implícitamente.
2. Solo RESET autorizado adopta época superior. Repetir el mismo cmdId y contenido devuelve la aceptación/resultado conocido sin volver a ejecutar el retorno.
3. Reset invalida trabajo previo antes de admitir nuevas órdenes. Sus cancelaciones usan la época original para correlación histórica, pero no modifican el estado global nuevo.
4. Mismo cmdId con contenido diferente: ID_CONFLICT, sin mutación. Durante una sesión conservar un registro de resultados de comandos suficiente para deduplicar las retransmisiones; si se introduce purga, habrá que definir una ventana de reintentos antes de purgar.
5. Si se reintenta por demora, usar el mismo cmdId. No generar otro ID para el mismo comando ante un resultado desconocido. Preferir STATUS y diagnóstico antes de reasignar una recogida que pudiera haberse aceptado.
6. El canal es local y no simula pérdidas por defecto. No prometer entrega exactamente una vez: combinar encolado, idempotencia y correlación.
7. Cierre supera reset; reset supera Stop/pausa. Si un control aceptado es sustituido antes de completar, emitir CANCELLED SUPERSEDED. Esto evita barreras esperando confirmaciones imposibles.
8. Confirmación global se calcula sobre las N cabinas esperadas, época y cmdIds emitidos, nunca por contar cualquier N respuestas.

## 6. Secuencia válida de recogida

Configuración: N ≥ 2, P ≥ 7, época 1, sistema RUNNING. Estado previo: cabina 2 inmóvil en piso 7/UP.

```text
V1 CMD 42 EPOCH 1 ELEVATOR 2 PICKUP 100 7 DOWN
V1 ACK 42 EPOCH 1 ELEVATOR 2 ACCEPTED
V1 DIRECTION 1 EPOCH 1 ELEVATOR 2 FROM UP TO DOWN
V1 STOPPED_AT 2 EPOCH 1 ELEVATOR 2 FLOOR 7 SERVICE DOWN
V1 EVENT 42 EPOCH 1 ELEVATOR 2 COMPLETED
```

El trabajador queda en DWELLING hasta agotar el tiempo de parada; la solicitud ya fue recogida. Después podría recibirse un destino interno como `V1 CMD 43 EPOCH 1 ELEVATOR 2 DESTINATION 101 4`. Estos ejemplos son referencias de formato, no trazas generadas por el programa.

## 7. Formato de archivo de solicitudes

### 7.1 Gramática

```text
HALL <floor> <UP|DOWN>
CABIN <elevatorId> <floor>
```

UTF-8, opcional BOM únicamente al inicio del archivo, LF/CRLF, espacios exteriores permitidos y uno o más espacios ASCII entre tokens. Líneas vacías y líneas cuyo primer carácter no blanco sea `#` se ignoran; comentarios al final de una instrucción no se admiten. No se admiten tabs ni comandos de control en el archivo. Los enteros y sus límites siguen las reglas anteriores.

El importador crea IDs de solicitudes cuando confirma el lote. Los comandos de transporte se crean al enviarlas tras Start. Orden de líneas conserva orden de admisión; no asegura orden de recogida con ejecución paralela.

### 7.2 Ejemplo válido

Para N = 2, P = 8:

```text
# Lote de demostración; no empieza a moverse al importar.
HALL 3 UP
HALL 7 DOWN
CABIN 1 8
CABIN 2 4
```

### 7.3 Validación y atomicidad

Verificar codificación, aridad, tokens, pisos 1..P, ID 1..N y sentido posible. Un archivo sin instrucciones válidas se rechaza como lote vacío, conservando el anterior. Una línea inválida rechaza todo el lote e informa número de línea, causa y ejemplo de formato. No truncar silenciosamente.

Solo se confirma en RESET y misma época de inicio de lectura. Cancelar selector, error I/O o cambio de estado no cambia el lote preparado. Una nueva importación válida sustituye el lote anterior. Al iniciar, las llamadas repetidas activas se unifican según D18.

## 8. Seguridad operativa y pruebas

No usar reflexión ni interpretar comandos como código, rutas o shell. El parser mapea tokens a tipos conocidos y rechaza todo lo demás. No retener indefinidamente líneas gigantes o volcarlas completas a la GUI sin límites; si se fija un máximo práctico, documentarlo y probar su rechazo explícito.

Pruebas previstas: round-trip por cada comando, aridad, valores fuera de rango, sentido extremo, época antigua/futura, colisión de ID, retransmisión, duplicado funcional, ACK distinto de completado, control prioritario en pausa, cancelación por reset y archivo atómico. Referencias en [06](06-pruebas.md).

## 9. Historial

| Versión | Fecha | Cambio |
| --- | --- | --- |
| 0.1.0 | 2026-10-19 | Gramática V1, resultados, control de épocas y formato transaccional de archivos. |

**Siguiente lectura:** [06 — Plan de pruebas](06-pruebas.md).
