# 07 — Formato del archivo de solicitudes

- **Proyecto:** Elevator Manager.
- **Versión del contrato de archivo:** 1.0.0.
- **Fecha:** 2026-10-28, Guatemala.
- **Tarjeta:** T43; base T42 validada en `ae5a1bf`, 458 pruebas Maven satisfactorias.
- **Estado:** contrato documental definido para implementar en T44–T46; aún no hay parser ni importación operativa.
- **Referencias:** [decisiones D17–D23](02-decisiones.md), [arquitectura](03-arquitectura.md), [resumen previo en 05](05-protocolo.md#7-formato-de-archivo-de-solicitudes).

## 1. Alcance y autoridad

El archivo describe llamadas externas y destinos internos de un lote, sin horarios ni pasajeros. Esta especificación completa la sección 7 de 05 y es la referencia normativa para el importador. La gramática de mensajes V1 del protocolo entre actores conserva su contrato separado; no se incorpora al archivo.

La versión 1.0.0 identifica este documento. El archivo no lleva cabecera de versión, configuración del edificio, UUID, época, fechas, IDs de comando ni tiempos de espera. Su nombre y ruta no tienen significado operativo. Se recomienda extensión .txt, pero la validez depende del contenido y no de la extensión. No se interpreta como CSV, JSON, código Java ni órdenes de shell.

## 2. Codificación, líneas y límites

| Elemento | Regla normativa |
| --- | --- |
| Codificación | UTF-8 estricto. Un error de decodificación rechaza el archivo; no sustituir bytes por caracteres de reemplazo ni intentar otra codificación. |
| BOM | Se permite un único BOM UTF-8 EF BB BF en el byte 0. Se retira antes de analizar la primera línea. U+FEFF en cualquier otra posición, incluso en un comentario, es error. |
| Fin de línea | LF o CRLF; se permite mezclarlos. Un CR aislado y otros separadores de línea, incluidos U+0085, U+2028 y U+2029, se rechazan. |
| Última línea | Puede terminar sin salto de línea. Un salto final no crea una línea vacía artificial adicional. |
| Línea vacía | Una línea sin caracteres o formada solo por espacios ASCII se ignora. |
| Espacio | Solo U+0020 separa tokens o indenta instrucciones/comentarios. Se permiten espacios exteriores y varios entre tokens. |
| Comentario | Después de quitar espacios ASCII iniciales, si comienza por # se ignora como instrucción. El texto puede incluir tildes y otros caracteres Unicode válidos. |
| Caracteres prohibidos | Tabs y controles C0/C1 o DEL, excepto los terminadores permitidos, se rechazan también en comentarios y líneas aparentemente vacías. Los separadores de línea indicados arriba y el BOM fuera del inicio también se rechazan. |
| Instrucción | Una por línea, con tres tokens exactos. Fuera de comentarios solo se admite texto ASCII imprimible y los espacios indicados. No hay comentarios al final de una instrucción. |
| Tamaño máximo | 1,048,576 bytes (1 MiB), incluidos BOM y terminadores. Exactamente el límite es válido; un byte más se rechaza. |
| Líneas máximas | 10,000 líneas físicas, incluidos comentarios y líneas vacías. La línea 10,001 se rechaza. |
| Longitud por línea | Hasta 4,096 puntos de código Unicode, excluidos el BOM inicial y el terminador. No contar bytes ni unidades UTF-16 como caracteres. |

Los límites son decisiones operativas de este contrato, no exigencias del enunciado académico. T44 deberá comprobarlos sin leer ilimitadamente ni truncar datos. Una línea de comentario también está sujeta a los límites y a la validación de caracteres; ignorarla no elimina esas comprobaciones.

Las líneas se numeran desde 1 sobre el archivo físico. Un BOM no añade una línea; CRLF cuenta como un solo terminador. Un archivo de cero bytes o solo BOM tiene cero líneas de contenido; un archivo con un único LF tiene una línea vacía. Todos ellos se rechazan como lote vacío.

## 3. Gramática

Cada alternativa siguiente representa una línea completa de instrucción; los campos entre ángulos son explicativos y no se escriben literalmente:

```text
HALL <piso> <UP|DOWN>
CABIN <idElevador> <piso>
```

Gramática léxica, después de retirar el BOM inicial y separar terminadores:

```text
SP          = U+0020
SEP         = SP, { SP }
DIGIT       = "0" | "1" | "2" | "3" | "4" | "5" | "6" | "7" | "8" | "9"
UINT        = DIGIT, { DIGIT }
DIRECTION   = "UP" | "DOWN"
INSTRUCTION = "HALL", SEP, UINT, SEP, DIRECTION
            | "CABIN", SEP, UINT, SEP, UINT
LINE        = { SP }, [ INSTRUCTION, { SP } | COMMENT ]
COMMENT     = "#", { carácter permitido de comentario }
```

La validación semántica posterior exige enteros positivos y límites del edificio. Solo son válidas las mayúsculas exactas HALL, CABIN, UP y DOWN. No se corrigen minúsculas ni traducciones. No hay comillas, escapes, separadores por coma, signos +/-, puntos decimales, notación exponencial ni cifras Unicode en los argumentos numéricos.

Se permiten ceros iniciales: 003 representa 3. La identidad funcional usa el valor numérico, no su escritura. El token 0 es un entero léxicamente correcto, pero falla el rango positivo de piso o cabina. El valor matemático debe caber en int positivo, hasta 2,147,483,647, antes de contrastarlo con la configuración. Un número mayor se rechaza sin desbordamiento; no se envuelve ni satura.

## 4. Significado y validación de dominio

N es la cantidad configurada de elevadores y P la cantidad configurada de pisos. Los tiempos y el edificio se obtienen de SimulationConfig; no se modifican desde el archivo.

| Instrucción | Significado | Restricciones |
| --- | --- | --- |
| HALL piso UP | Llamada externa compartida para subir. El manejador elige la cabina. | 1 ≤ piso < P. |
| HALL piso DOWN | Llamada externa compartida para bajar. El manejador elige la cabina. | 1 < piso ≤ P. |
| CABIN id piso | Destino interno de la cabina indicada. | 1 ≤ id ≤ N y 1 ≤ piso ≤ P. |

Con P = 1 no existe ninguna llamada HALL válida; CABIN 1 1 es válido si N ≥ 1. Con más cabinas, CABIN id 1 también es válido para cada ID configurado. Un destino interno en el piso actual es una solicitud válida; el motor decidirá la parada conforme a su estado.

Cada instrucción es independiente. Un HALL seguido de CABIN no describe a una misma persona, no exige que se asigne esa cabina y no pospone el destino interno hasta una recogida. No se introducen dependencias implícitas entre líneas.

START, STOP, RESET, PAUSE, RESUME, WAIT, SLEEP, STATUS, SHUTDOWN y mensajes V1 son instrucciones desconocidas en este formato. Cualquier cambio futuro de gramática deberá versionarse en la documentación y validarse expresamente.

## 5. Lectura, identidad y preparación

T44 leerá y validará el archivo completo contra una configuración capturada, produciendo descriptores inmutables en orden, con su número de línea original. El parser no debe crear actores, alterar agendas, elegir cabina para HALL, consumir IDs de comando ni mover elevadores.

T45 confirmará el lote únicamente después de revalidar la misma sesión, configuración, época y estado RESET. Al confirmar se asignará un UUID nuevo por instrucción y la época vigente; createdAt será un mismo Instant capturado para esa confirmación de lote. Los UUID no se escriben en el archivo y se conservan para ese lote confirmado. Un intento fallido no reemplaza el lote anterior ni publica solicitudes de la lectura rechazada.

El mismo texto importado válidamente otra vez genera un lote nuevo con nuevas identidades, sustituyendo al preparado anteriormente. No equivale a retransmitir órdenes previas. Los IDs de comandos se generan por el flujo normal de la sesión cuando se envían órdenes a cabinas; en HALL eso ocurre cuando el manejador despacha, no al leer el archivo.

## 6. Orden y duplicados

Las instrucciones se conservan en su orden físico, omitiendo comentarios y líneas vacías sin perder sus números de línea. Start presenta las entradas una vez al flujo de admisión del controlador en ese orden. No se ordenan por piso, tipo, cabina ni dirección, y no se agregan retardos entre líneas.

Ese orden describe presentación/envío. Los buzones y actores son independientes: no garantiza orden de aceptación en las cabinas, orden de asignación, orden de servicio ni un resultado temporal idéntico entre ejecuciones. La selección por ETA y los tiempos concurrentes siguen rigiendo el motor.

El importador no elimina ni rechaza instrucciones repetidas. Cada línea válida conserva su entrada y UUID; la deduplicación operativa D18 ocurre durante la admisión de solicitudes activas:

| Tipo | Clave funcional activa |
| --- | --- |
| HALL | (época, piso, dirección) |
| CABIN | (época, cabina, piso) |

La primera solicitud activa puede ser la canónica y las siguientes quedar vinculadas a ella según el dominio existente. HALL 3 UP y HALL 3 DOWN son diferentes; CABIN 1 5 y CABIN 2 5 también. HALL 3 UP y CABIN 1 3 no son duplicados entre sí.

Si una solicitud equivalente ya terminó o se canceló antes de admitir otra, D18 permite trabajo nuevo. Por ello dos líneas iguales no garantizan exactamente una parada para cualquier planificación concurrente. Conservar repetidos en el lote y unificar trabajo activo son reglas distintas.

## 7. Atomicidad y estados

| Situación | Resultado exigido en T44–T46 |
| --- | --- |
| Archivo válido y no vacío, confirmación aún en RESET de la misma sesión/época/configuración | Sustituir íntegramente el lote preparado; permanecer RESET sin mover cabinas. |
| Cualquier línea inválida, incluso la última | Rechazar el archivo completo; conservar el lote anterior y todas las agendas. |
| Archivo vacío o solo comentarios/espacios | EMPTY_BATCH; conservar el lote anterior. No significa vaciarlo. |
| Error de lectura, codificación o límites | Rechazar sin cargar parcialmente ni sustituir el lote anterior. |
| Cancelar el selector | Cancelación sin cambios; no es un error sintáctico ni una orden de reset. |
| Start o reset mientras se lee | Revalidar al confirmar; si cambió estado o época, rechazar el resultado tardío sin aplicarlo. |
| Nueva sesión o configuración mientras se lee | Rechazar el resultado de la sesión/configuración anterior aunque coincida el número de época. |
| Estado RUNNING, STOPPING, STOPPED, RESETTING, PAUSING, PAUSED, CLOSING o CLOSED | No confirmar importación; únicamente RESET la autoriza. |
| Start desde RESET con lote confirmado | Presentar el lote una sola vez al flujo normal de la sesión. Start repetido no vuelve a cargarlo. |
| Stop y posterior Start | Continuar pendientes, sin volver a presentar el lote ya consumido. |
| Reset global | Invalidar lote preparado y trabajo de la época anterior según el contrato de ciclo de vida. |

La atomicidad es de la validación y sustitución del lote preparado. No significa ejecución física simultánea o transaccional de todos sus servicios. Un fallo posterior al iniciar se informa mediante el seguimiento normal; no deshace movimientos ya ocurridos ni reejecuta automáticamente entradas.

## 8. Diagnósticos para T44

Cada error de línea debe incluir código estable, número físico desde 1, causa legible y la forma esperada. Para límites de archivo, lectura o decodificación sin posición fiable se informa alcance de archivo, sin inventar una línea. Si se conoce un desplazamiento de byte puede añadirse como dato auxiliar.

En lectura correcta, se revisan todas las líneas y se conserva al menos el diagnóstico principal de cada línea inválida en orden ascendente. Para una línea, la precedencia es: caracteres/BOM/longitud, instrucción conocida, aridad, argumentos de izquierda a derecha y restricciones del dominio. Un fallo general de tamaño, decodificación o terminación de líneas puede detener el análisis; nunca autoriza una carga parcial. No añadir EMPTY_BATCH si ya existen errores que explican la invalidez.

| Código | Uso |
| --- | --- |
| READ_ERROR | No se puede abrir o completar la lectura. |
| INVALID_UTF8 | Codificación inválida; no reparar automáticamente. |
| FILE_TOO_LARGE | Más de 1 MiB de bytes leídos. |
| TOO_MANY_LINES | Más de 10,000 líneas físicas. |
| LINE_TOO_LONG | Más de 4,096 puntos de código en una línea. |
| INVALID_BOM | BOM repetido o fuera del comienzo. |
| INVALID_LINE_ENDING | CR aislado u otro separador no admitido. |
| INVALID_CHARACTER | Tab, control o carácter no ASCII en una instrucción. |
| UNKNOWN_INSTRUCTION | Primer token distinto de HALL o CABIN. |
| INVALID_ARITY | Cantidad distinta de tres tokens. |
| INVALID_INTEGER | Argumento numérico que no coincide con dígitos ASCII sin signo. |
| INTEGER_OUT_OF_RANGE | Valor numérico mayor que int positivo. |
| FLOOR_OUT_OF_RANGE | Piso 0 o fuera de 1..P; reutiliza el código del modelo. |
| ELEVATOR_OUT_OF_RANGE | ID 0 o fuera de 1..N; reutiliza el código del modelo. |
| INVALID_DIRECTION | Dirección distinta de UP o DOWN. |
| HALL_UP_AT_TOP | Subida desde el último piso; código del modelo. |
| HALL_DOWN_AT_BOTTOM | Bajada desde el primer piso; código del modelo. |
| EMPTY_BATCH | No hay instrucciones y tampoco otros errores que expliquen el rechazo. |

La cancelación y el rechazo por estado o contexto obsoleto son resultados de la importación del controlador; no errores del parser de una línea. Sus tipos concretos se implementarán en T45. Las excepciones de lectura conservarán su causa original; la vista no debe mostrar un archivo entero ni líneas gigantes para explicar un fallo.

Ejemplo de diagnóstico con N = 2 y P = 8:

```text
Línea 4 — HALL_UP_AT_TOP: no se puede subir desde el piso 8.
Formato esperado: HALL <piso> UP, con 1 <= piso < 8.
Resultado del archivo: rechazado; lote preparado anterior conservado.
```

## 9. Ejemplos válidos completos

Los bloques siguientes representan el contenido íntegro de cada archivo, sin etiquetas de línea insertadas. Todos usan LF por representación documental; CRLF también es válido.

### 9.1 Lote mínimo — N = 1, P = 4

```text
CABIN 1 4
```

### 9.2 Dos cabinas y sentidos mixtos — N = 2, P = 8

```text
# Lote mixto; importar no inicia los movimientos.
HALL 3 UP
CABIN 1 8

HALL 8 DOWN
CABIN 2 1
```

Se preparan cuatro instrucciones de las líneas 2, 3, 5 y 6, conservando ese orden. La llamada externa del piso 3 no obliga a usar la cabina 1.

### 9.3 Repetidos y ceros iniciales — N = 2, P = 8

```text
# Se conservan las seis instrucciones; el motor unifica solo trabajo activo.
HALL 3 UP
HALL 003 UP
HALL 3 DOWN
CABIN 1 5
CABIN 1 005
CABIN 2 5
```

Las líneas 2 y 3 tienen la misma clave HALL; las líneas 5 y 6 tienen la misma clave CABIN. La línea 4 tiene otro sentido y la línea 7 otra cabina. No se reducen seis entradas a cuatro durante el parseo.

### 9.4 Edificio de un piso — N = 1, P = 1

```text
# Un destino interno en el piso actual es válido.
CABIN 1 1
```

### 9.5 Espacios ASCII exteriores y múltiples — N = 2, P = 8

```text
  # Comentario con acentos: dirección y petición.
   HALL   3   UP
  CABIN  2  4
```

La indentación es deliberada en este ejemplo; también se permiten espacios ASCII al final de cada instrucción. No representan tabs ni espacios Unicode.

## 10. Ejemplos inválidos

Para N = 2, P = 8, cada fila es una línea candidata completa. Las notaciones TAB, NBSP y BOM entre corchetes describen caracteres reales que se usarán en las pruebas de T44; no son sustituciones permitidas en archivos.

| Entrada | Diagnóstico principal esperado |
| --- | --- |
| `HALL 8 UP` | HALL_UP_AT_TOP |
| `HALL 1 DOWN` | HALL_DOWN_AT_BOTTOM |
| `HALL 0 UP` | FLOOR_OUT_OF_RANGE |
| `CABIN 3 4` | ELEVATOR_OUT_OF_RANGE |
| `CABIN 0 4` | ELEVATOR_OUT_OF_RANGE |
| `CABIN 1 9` | FLOOR_OUT_OF_RANGE |
| `CABIN 1 0` | FLOOR_OUT_OF_RANGE |
| `HALL 3 ARRIBA` | INVALID_DIRECTION |
| `HALL 3 up` | INVALID_DIRECTION |
| `hall 3 UP` | UNKNOWN_INSTRUCTION |
| `HALL 3` | INVALID_ARITY |
| `CABIN 1 4 EXTRA` | INVALID_ARITY |
| `HALL 3 UP # comentario` | INVALID_ARITY |
| `HALL +3 UP` | INVALID_INTEGER |
| `CABIN 1 -4` | INVALID_INTEGER |
| `CABIN 1 4.0` | INVALID_INTEGER |
| `CABIN 1 2147483648` | INTEGER_OUT_OF_RANGE |
| `HALL ٣ UP` | INVALID_CHARACTER; el dígito es Unicode, no ASCII. |
| `HALL[TAB]3 UP` | INVALID_CHARACTER |
| `HALL[NBSP]3 UP` | INVALID_CHARACTER |
| `# comentario[TAB]` | INVALID_CHARACTER, también en comentarios. |
| ` [BOM]HALL 3 UP` | INVALID_BOM; está después de un espacio. |
| `RESET` | UNKNOWN_INSTRUCTION; el control no pertenece al archivo. |
| `V1 CMD 1 EPOCH 0 ELEVATOR 1 RESET` | UNKNOWN_INSTRUCTION |
| `HALL,3,UP` | UNKNOWN_INSTRUCTION |
| `HALL 3 UP; CABIN 1 4` | INVALID_ARITY |

Archivo completo inválido por su última línea, N = 2 y P = 8:

```text
# Ninguna de sus entradas se publica si falla la línea final.
HALL 3 UP
CABIN 1 8
HALL 8 UP
```

El error está en la línea física 4. Las dos instrucciones anteriores no se cargan ni sustituyen parte del lote anterior. Un archivo de solo comentarios y líneas vacías se rechaza con EMPTY_BATCH aunque su UTF-8 sea válido.

## 11. Frontera de las siguientes tarjetas

- T44: parser, límites, diagnósticos, descriptores y validación completa sin ejecución.
- T45: lectura fuera del EDT, doble comprobación de RESET/contexto, sustitución atómica, identidades y presentación del lote una vez al iniciar.
- T46: archivos de ejemplo descargables y pruebas de cancelación, inexistencia, vacío y permisos, además de la matriz de bordes de este contrato.

T43 no modifica fuentes Java ni añade tests ejecutables. Los ejemplos en este documento son especificación; todavía no demuestran una importación funcionando.
