# 06 — Plan de pruebas y aceptación

- **Proyecto:** Elevator Manager.
- **Versión:** 0.1.0.
- **Fecha:** 2026-10-19, Guatemala.
- **Estado:** plan previo al código; todos los casos están pendientes de ejecución.
- **Base:** [plan Kanban](01-analisis-y-plan.md), [decisiones](02-decisiones.md), [arquitectura](03-arquitectura.md), [asignación](04-algoritmo-asignacion.md), [protocolo](05-protocolo.md).

## 1. Propósito y alcance de esta versión

Definir antes de implementar qué significa funcionar correctamente y qué evidencia se necesitará. Este documento se crea ahora como especificación de pruebas; durante el desarrollo incorporará resultados, incidencias y regresiones. Solo al cierre podrá afirmar cuáles pasaron.

Incluye requisitos base y tres extras. No fija una nota académica, porcentaje de cobertura o límite de rendimiento que el enunciado no establezca. Los objetivos técnicos de tiempo y carga de este plan son parámetros iniciales propuestos, sujetos a calibración registrada.

## 2. Trazabilidad de requisitos

| ID | Obligación del enunciado o alcance acordado | Diseño | Pruebas |
| --- | --- | --- | --- |
| R01 | Configurar N, P y tiempos en ms. | 02 D06–D07; 03 entrada. | P01–P03 |
| R02 | Estado inicial piso 1/arriba por cabina. | 02 D08/D11. | P01, P22 |
| R03 | Piso lógico conserva último piso dejado. | 03 estado por cabina. | P04, P05 |
| R04 | Comandos propios, internas, externas y reset. | 03 flujos; 05 comandos. | P06–P10, P20–P23 |
| R05 | Tiempos comunes de movimiento/parada. | 02 D06/D31. | P04, P11, P34 |
| R06 | Log de movimiento, llegada, parada, reset e inversión con ID. | 02 D30; 03 logs. | P30, P31 |
| R07 | Terminal y archivo independiente por cabina. | 02 D30. | P30, P31 |
| R08 | Externas compartidas y gestionadas por manejador. | 02 D17/D19. | P06, P07, P12 |
| R09 | Funcionar con 1 o N cabinas. | 03 trabajadores; 04 selección. | P01, P12, P32 |
| R10 | No invertir prematuramente por una llamada. | 04 barridos/frontera. | P13, P14, P16 |
| R11 | Recoger en ruta con sentido compatible. | 04 servicio. | P09, P14, P15 |
| R12 | Asignar según proximidad/carga/rapidez. | 04 ETA/desempates. | P12, P13, P17 |
| R13 | Manejador y cabinas paralelos independientes. | 03 hilos. | P18, P32, P33 |
| R14 | Consola GUI con Start/Stop/Reset y solicitudes. | 02 transiciones; 03 UI. | P06, P19–P23, P29 |
| R15 | Cargar solicitudes solo en reset. | 02 D22–D23; 05 archivo. | P24–P28 |
| R16 | Mostrar piso, destinos encendidos y externas asignadas. | 03 snapshots/UI. | P07–P10, P29 |
| R17 | Herencia con lógica, paquetes, Threads y logger. | 03 estructura/herencia. | P35, P36 |
| R18 | Entrada llamada ElevatorManager. | 02 D04; 03 construcción. | P01, P36 |
| X01 | Extra: pausa y reanudación. | 02 D13; 03 tiempo. | P37–P39 |
| X02 | Extra: simulación gráfica animada. | 03 presentación. | P40, P41 |
| X03 | Extra: comandos y argumentos transmitidos como texto. | 05 completo. | P42–P47 |

Reglas adicionales de importación atómica, época, cierre y duplicados son decisiones para concretar el enunciado, no citas de requisitos literales. Sus pruebas verifican coherencia del producto propuesto.

## 3. Niveles y herramientas

| Nivel | Qué verifica | Herramienta prevista |
| --- | --- | --- |
| Unidad | Configuración, subtipos, reglas de ruta, ETA, codec y parser. | JUnit; reloj controlado; sin ventanas ni espera real. |
| Integración | Manejador, varias cabinas, canales, controles, archivos y logs. | JUnit, eventos observables y límites de espera. |
| Sistema | Flujos completos, visualización y ejecución fuera del IDE. | Guion manual y pruebas del controlador. |
| Concurrencia | Solapamiento, carreras, prioridad y liberación de recursos. | Barreras/latches, hilos identificados, trazas de época/correlación. |
| Entrega | Construcción limpia, JAR, documentación y ejemplos. | Maven y ejecución independiente. |

Las pruebas no deben repetir línea por línea la implementación. Usar entradas y resultados observables. Una prueba de herencia debe ejecutar comportamiento de ambos subtipos a través de referencias base, además de revisar la relación de clases.

## 4. Datos y mediciones

### 4.1 Configuraciones propuestas

| Nombre | Parámetros | Uso |
| --- | --- | --- |
| MIN | N = 1, P = 1, M = 100 ms, S = 200 ms. | Límite mínimo; sin llamadas externas posibles. |
| BASE | N = 2, P = 8, M = 1000 ms, S = 2000 ms. | Demostración visible y cálculos E01..E09. |
| FAST | N = 3, P = 8, M = 20 ms, S = 40 ms. | Integración con hilos reales; evitar igualdad temporal exacta. |
| LOAD | N = 5, P = 20, M = 10 ms, S = 20 ms. | Lote finito de 200 solicitudes válidas con semilla fija. |

En LOAD contar solicitudes canónicas después de deduplicación, no exigir 200 servicios físicos si hay duplicados. Estos tamaños son pruebas propuestas, no límites del programa.

### 4.2 Tiempo y reproducibilidad

En pruebas unitarias, avanzar un reloj controlado y comprobar duraciones exactas del modelo. Para tiempos reales visibles en BASE usar como tolerancia superior inicial por tramo/parada `máximo(100 ms, 15 % de la duración)`, registrando si la carga del equipo exige recalibración. El tiempo real no debe ser menor que la duración configurada salvo resolución/medición explícita; no usar el margen superior para permitir saltarse tiempo.

Esperar finalización de integraciones con un límite derivado del recorrido proyectado más margen. Un timeout es fallo a investigar, no prueba de que una solicitud quedó correctamente cancelada. Los tests de FAST/LOAD se centran en orden, integridad y terminación; no se aplica a ellos un margen de 100 ms como medida de precisión de una fase de 10 ms.

Guardar JDK/Maven usados, sistema operativo, commit, configuración, semilla, prueba y fecha. Pruebas con snapshots iniciales artificiales de pisos altos son pruebas unitarias del planificador; no afirmar que el programa inicializa cabinas fuera del piso 1.

## 5. Casos funcionales y algoritmo

Todos los estados de resultado de esta sección son **Pendiente**. E01..E09 remiten a los cálculos teóricos de 04.

| ID | Preparación y pasos | Resultado esperado |
| --- | --- | --- |
| P01 | Iniciar con MIN y luego con BASE desde clase/JAR. | N correcto; todas las cabinas en 1/UP; estado RESET; no se mueve antes de Start. |
| P02 | Probar N/P cero, negativos, texto, vacío y desbordamiento; tiempos cero/negativos. | Errores claros; no construir sesión parcial; aceptar límites mínimos válidos. |
| P03 | Intentar UP en P, DOWN en 1, piso 0/P+1 y cabina 0/N+1. | Rechazo en entrada y dominio; ningún comando inválido llega a ejecutarse. |
| P04 | Cabina de 1 a 3 con M conocido; observar mitad del primer tramo. | Piso lógico 1 hasta ARRIVED 2; siguiente piso/progreso coherentes. |
| P05 | Movimiento de 3 a 1; inspeccionar tramo 3→2. | Piso lógico 3 mientras viaja; llega a 2 y luego a 1, sin saltos. |
| P06 | RUNNING: llamar desde piso 3/UP sin elegir cabina. | Manejador selecciona; una sola cabina acepta la llamada canónica. |
| P07 | Seleccionar destino interno 8 en cabina 1. | Solo cabina 1 lo incorpora; indicador encendido hasta servicio. |
| P08 | Repetir destino y externa mientras están pendientes; repetir después de completarlas. | Duplicados activos se unifican; después se aceptan solicitudes nuevas equivalentes. |
| P09 | Programar externas UP y DOWN en piso intermedio de recorrido UP comprometido. | Servicio UP no completa DOWN; DOWN permanece visible y termina en retorno compatible. |
| P10 | Seleccionar piso dejado mientras la cabina ya está en tránsito; comparar con selección estando inmóvil. | En tránsito programa regreso; inmóvil puede abrir servicio sin movimiento. |
| P11 | Dos cabinas con paradas de distintas solicitudes. | Ambas usan M/S comunes; cada parada atendida consume S antes del siguiente movimiento, salvo pausa/Stop que conserva restante. |
| P12 | Ejecutar E01 y E08; repetir selección con N = 1. | ETA/desempate calculados; con una cabina siempre se procesa por manejador. |
| P13 | Ejecutar E02. | Elegir B aunque A esté más cerca por distancia; no invertir A antes de 8. |
| P14 | Ejecutar E03 y E09. | Recoger compatible intermedia; mantener frontera y llamada contraria pendiente. |
| P15 | Ejecutar E05 y E07. | Servicio compatible abierto con ETA 0; cabina libre cambia sentido de servicio al llegar al origen contrario. |
| P16 | Ejecutar E06 y repetir caso simétrico al bajar. | Conservar tramo/destino comprometidos y no invertir anticipadamente. |
| P17 | Ejecutar E04 y dos asignaciones consecutivas antes del ACK de la primera. | ETA usa restante sin doble conteo; segunda estimación incluye trabajo ya enviado. |
| P18 | Dos cabinas con viajes suficientemente largos y observación por eventos/barrera. | Hay intervalo de ejecución simultánea; no suma secuencial artificial de los viajes. |

## 6. Ciclo de vida, importación y GUI

| ID | Preparación y pasos | Resultado esperado |
| --- | --- | --- |
| P19 | Pulsar Start repetidamente; Stop en movimiento; Start cuando STOPPED. | Un solo conjunto de trabajadores; acaba tramo iniciado sin nueva parada; luego continúa pendientes. |
| P20 | Stop durante DWELLING; esperar tiempo de pared y volver a Start. | Conserva duración restante de esa parada; no termina por el tiempo detenido. |
| P21 | Reset durante tránsito con trabajo pendiente, incluyendo mensajes enviados sin ACK. | Nueva época; completa tramo restante y retorna físicamente a 1; sin recogidas antiguas ni comandos resucitados. |
| P22 | Reset desde STOPPED y desde RESET con lote preparado. | Retorno avanza aunque estuviera detenido; cancela lote; finaliza solo con todas en 1/UP. |
| P23 | Cerrar con movimiento, pausa, reset o cola pendiente; repetir cierre. | Trabajadores y ejecutores terminan; handlers cerrados; no declarar CLOSED antes de ello. |
| P24 | Importar lote válido en RESET; observar sin Start y luego iniciar. | No movimiento al importar; admisión en orden una sola vez al iniciar. |
| P25 | Lote válido A preparado; intentar B con error en última línea. | Rechazo completo de B con línea/causa; A intacto. |
| P26 | Importar fuera de RESET o cambiar estado mientras se lee el archivo. | No confirmar importación tardía; ni agenda ni lote se alteran. |
| P27 | Cancelar selector; archivo sin permisos, inexistente, UTF-8 inválido, vacío o solo comentarios. | Mensaje de causa; no cambiar lote preparado ni dejar recursos abiertos. |
| P28 | Archivo con BOM inicial, CRLF, espacios; probar tabs y comentario al final de orden. | Aceptar formas permitidas en 05; rechazar las prohibidas explícitamente. Nueva importación válida sustituye A. |
| P29 | Usar todas las entradas y controles gráficos; redimensionar y mostrar N/P mayores. | Estado, piso, internas y externas correctos; controles habilitados según estado; GUI responde y permite inspección. |

## 7. Logs, arquitectura y entrega

| ID | Preparación y pasos | Resultado esperado |
| --- | --- | --- |
| P30 | Ejecutar viaje UP, DOWN, llegada, servicio, inversión y reset de dos cabinas. | Cinco categorías obligatorias legibles con ID en terminal y archivo propio; sin mezcla ni duplicación. |
| P31 | Repetir controles y sesiones; forzar ruta de log no escribible. | Handlers no se acumulan; error de registro visible; no afirmar evidencia guardada si falló. |
| P32 | Ejecutar LOAD con semilla fija hasta vaciar agenda. | Todas las canónicas válidas se completan o tienen cancelación explícita; sin pérdida ni errores concurrentes. |
| P33 | Intercalar admisiones, Stop/Start/reset y eventos viejos usando barreras reproducibles. | Invariantes de época y recursos preservadas; sin interbloqueos ni crecimiento de hilos por controles. |
| P34 | Medir tramos/paradas reales de BASE y reloj controlado. | Duraciones y tolerancias documentadas; no avanzar antes de agotar duración lógica. |
| P35 | Invocar validación/descripcion de HallRequest/CabinRequest a través de Request; revisar paquetes y trabajadores. | Herencia funcional, paquetes coherentes, manejador independiente y N trabajadores reales. |
| P36 | Construcción limpia, ejecución del JAR en otra carpeta y cierre. | `ElevatorManager` es entrada; recursos/rutas relativas funcionan; documentación coincide con uso real. |

## 8. Extras

| ID | Preparación y pasos | Resultado esperado |
| --- | --- | --- |
| P37 | Pausar a mitad de tramo; observar piso/progreso; reanudar. | Tras PAUSED no avanza; mantiene tiempo restante y no reinicia ni salta el tramo. |
| P38 | Pausar durante parada e IDLE; repetir ciclos de pausa/reanudación. | Preserva restante/pedidos, no crea hilos ni consume CPU por sondeo activo. |
| P39 | Reset/cerrar durante PAUSING o PAUSED; Stop desde PAUSED. | Controles prioritarios despiertan esperas; reset retorna; cierre termina; Stop conserva estado para Start. |
| P40 | Ver cabinas animadas en desplazamientos simultáneos y P = 1. | Posición coherente y IDs distinguibles; hay movimiento gráfico real, no solo etiquetas. |
| P41 | Animación con Stop, pausa y reset. | Stop acaba tramo; pausa congela; reset dibuja retorno físico sin saltos por tiempo pausado. |
| P42 | Codificar/parsear cada comando válido y sus respuestas/eventos de 05. | Round-trip conserva campos; mensajes efectivamente viajan en texto y se parsean. |
| P43 | Versión desconocida, comando inválido, aridad, números fuera de rango, tabs y cabina inexistente. | ERROR controlado, sin mutación ni muerte del trabajador; correlación recuperable o 0. |
| P44 | Reenviar mismo cmdId/contenido; mismo cmdId con otros argumentos; orden funcional duplicada con otro cmdId. | Repetición idempotente; ID_CONFLICT; COALESCED vinculado y sin doble servicio. |
| P45 | Servicio de época anterior/futura y reset autorizado de nueva época. | Rechazo STALE/FUTURE; solo reset adopta generación; eventos viejos no alteran estado nuevo. |
| P46 | Observar ACK de recogida antes de la parada; forzar control que la cancela antes de servicio. | ACK no equivale a recogida; un único resultado terminal lógico, con CANCELLED si no se atendió. |
| P47 | Llenar cola de servicio y enviar PAUSE/RESET/SHUTDOWN; sustituir un control en curso. | Control prioritario reacciona; control sustituido se cancela y ninguna barrera espera una respuesta imposible. |

## 9. Ejecución progresiva y registro de resultados

| Momento del proyecto | Qué ejecutar | Qué completar en este documento |
| --- | --- | --- |
| Modelo y arquitectura | P01–P03/P35 en cuanto existan tipos. | Rutas reales de tests, configuración del entorno. |
| Motor y manejador | P04–P18; P32–P34 al integrar. | Resultados, duración, defectos y regresiones. |
| Controles, archivos y GUI | P19–P31. | Guiones manuales, trazas y observaciones. |
| Extras | P37–P47. | Evidencia separada para cada extra. |
| Entrega | P36 y regresión completa pertinente. | Matriz final y vínculo a 08-evidencias.md cuando exista. |

Formato por ejecución:

| Campo | Contenido a registrar |
| --- | --- |
| Identificador | Pxx y número de ejecución. |
| Contexto | Commit, JDK/Maven, SO, configuración, semilla. |
| Preparación | Estado inicial y datos realmente usados. |
| Observación | Eventos/estados/duración obtenidos. |
| Resultado | Pendiente, Pasa, Falla o Bloqueada. |
| Evidencia | Ruta relativa a captura, log o informe de prueba real. |
| Incidencia | Desviación y corrección; regresión que la valida. |

No registrar un resultado esperado como observado. No inventar capturas o archivos de Surefire antes de ejecutar pruebas. `mvn clean verify` será el comando previsto tras configurar el proyecto, no una comprobación ya realizada.

## 10. Criterios de entrada y salida

**Para iniciar desarrollo:** requisitos vinculados, semántica de controles definida, arquitectura/propiedad del estado conocidas, reglas de asignación y protocolo suficientemente concretas. Las versiones exactas de herramientas se cierran al preparar el entorno.

**Para cerrar versión:** requisitos R01..R18 y X01..X03 demostrados, defectos que impidan el alcance resueltos, construcción limpia y JAR fuera del IDE funcionando, cierre sin trabajadores huérfanos y documentación actualizada a la realidad. Un caso bloqueado no cuenta como aprobado; justificar cualquier ajuste de alcance explícitamente.

## 11. Historial

| Versión | Fecha | Cambio |
| --- | --- | --- |
| 0.1.0 | 2026-10-19 | Plan de pruebas previo a implementación: 18 requisitos base, tres extras y 47 casos pendientes. |


## Matriz documental T43 para T44–T46 — 2026-10-28

Esta ampliación especifica pruebas futuras; no declara un parser implementado ni casos nuevos ejecutados. La referencia normativa es [07](07-formato-archivo-solicitudes.md).

| Grupo futuro | Casos exigidos | Resultado |
| --- | --- | --- |
| Codificación | UTF-8 con/sin BOM; BOM duplicado o interior; secuencias malformadas. | Aceptar únicamente lo permitido, sin reparar bytes. |
| Líneas | LF, CRLF, mezcla, sin salto final, CR aislado, numeración con comentarios/vacías. | Reglas de 07 y línea física exacta. |
| Léxico | Espacios múltiples/exteriores; tabs en instrucción/comentario; NBSP, dígitos Unicode, minúsculas. | Separadores ASCII y tokens exactos; error explícito. |
| Gramática | Aridad exacta, comentarios finales, controles/V1, números con signos/decimales, ceros iniciales. | Tres tokens; ceros iniciales válidos, sin corregir formatos inválidos. |
| Dominio | Pisos/IDs cero o fuera del edificio; desbordamiento de int; sentidos imposibles; P = 1. | Reutilizar códigos del modelo cuando correspondan. |
| Límites | 1 MiB y un byte más; 10,000/10,001 líneas; 4,096/4,097 puntos de código. | Aceptar el límite exacto si el resto es válido y rechazar el exceso sin truncar. |
| Lote vacío | Cero bytes, BOM solo, espacios, comentarios, líneas vacías. | EMPTY_BATCH, sin reemplazar el anterior. |
| Orden/duplicados | Mixtos, repetidos numéricamente iguales, sentido o cabina diferentes. | Conservar entradas y líneas; deduplicación solo de trabajo activo. |
| Atomicidad | Error en primera/última línea, varios errores y lote anterior preparado. | Ninguna carga parcial; diagnósticos ordenados; conservar anterior. |
| Integración T45 | RESET, Start/reset durante lectura, nueva sesión/configuración, Stop/Start posterior. | Doble comprobación y consumo único sin mover al importar. |
| Lectura T46 | Cancelación, inexistencia, vacío y permisos. | Resultado documentado, causa de lectura preservada y aplicación utilizable. |

El límite de longitud cuenta puntos de código: incluir comentarios con caracteres suplementarios para evitar confundirlos con unidades UTF-16. Probar ceros iniciales y valor numérico por separado de la longitud de línea. Los casos de límites requieren archivos construidos exactamente en bytes/líneas, no solo nombres de ejemplo.


## Avance T44 — Parser implementado, 2026-10-28

RequestFileScenarios contiene 48 escenarios ejecutables y RequestFileTest los mismos 48 adaptadores JUnit. Se comprobaron localmente léxico, UTF-8, BOM, terminadores, límites exactos, descriptores, diagnóstico completo, ausencia de lote parcial, streams, interrupción y concurrencia; la matriz de dominio se contrasta con los modelos existentes. Se verifican también los cinco archivos válidos y las 26 entradas inválidas de 07 mediante el diagnóstico Java real. Evidencia y límites en [validación T44](comprobaciones/T44-validacion-local.md). Maven no está disponible localmente; se esperan 506 pruebas acumuladas al ejecutarlo en el equipo de David.

Las carreras de importación/confirmación, sustitución de un lote en el controlador y consumo único al iniciar siguen en T45. La colección de archivos de muestra, cancelación de selector y pruebas específicas de permisos siguen en T46. Las comprobaciones de T44 no acreditan esos comportamientos todavía.


## Avance T45 — Integración RESET, 2026-10-28

RequestImportScenarios y RequestImportTest agregan 40 escenarios de preparación sin movimiento, confirmación, reemplazo, rechazo fuera de RESET, envío único, continuidad Stop/Start, épocas, cancelación, respuestas diferidas, lectura más reciente, cierre y recursos. Lectores de prueba retenidos con CountDownLatch fuerzan las carreras sin inferir estados por dormir. Se compara identidad de hilos y se mantiene vivo un lector que ignora interrupción para verificar que CLOSED espera su salida real.

T44 ya tiene 48 pruebas específicas y 506 acumuladas confirmadas por David en `9ae324d`. T45 espera 546 acumuladas y mantiene pendiente su ejecución Maven. La regresión independiente y las muestras están en [validación T45](comprobaciones/T45-validacion-local.md). Los resultados de importación son de preparación, y los informes de Start solo de envío; el servicio se prueba por ExecutionEvent/canónicas y seguimiento del manejador.
