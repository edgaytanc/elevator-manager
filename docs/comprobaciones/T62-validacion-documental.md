# T62 — Validación documental

- **Fecha:** 2026-11-02, Guatemala.
- **Entorno de revisión:** Linux sin sesión gráfica utilizada. Java runtime 17 presente; `mvn` y `javac` no disponibles en PATH. No se ejecutó Maven, compilación ni GUI; no son necesarias para una modificación exclusivamente documental.
- **Base:** ZIP actualizado de David, HEAD `dbea15a`.

## Comprobaciones realizadas

| Comprobación | Resultado |
| --- | --- |
| Alcance T62 y dependencias | Fase 11, especificación documental; implementación de codec T63 y transporte T64–T65 pendientes. |
| Fuentes y POM frente al ZIP recibido | 201 archivos idénticos byte a byte, incluidos 169 Java. Sin fuentes añadidos/eliminados/modificados. |
| Identidad y época frente a modelos actuales | Request usa UUID; controller/estado admiten época 0; IDs de comando positivos y sin reinicio por reset. Contrato corregido. |
| Gramática y ejemplos concretos | 27 líneas V1 en bloques documentales comprobadas por aridad, cabecera numérica, UUID y estructura de STATE cuando aplican. Inspección estática, no ejecución de codec. |
| Aridad de STATE | 29 tokens, incluidos SUSPENDED/HOLD/MODE, coherentes con el ejemplo de pausa. |
| Errores y resultados | Revisión de tablas y precedencia, rechazo antes de mutación, ACK frente a terminal, coalescencia y reintento histórico. |
| Controles y transporte | Revisión de roles, prioridad, barreras, retorno físico y separación del archivo HALL/CABIN. |
| Markdown | Bloques equilibrados y enlaces locales de los documentos modificados/nuevos comprobados sin interpretar anclas como archivos. |
| Git | `git diff --check` satisfactorio; cambios únicamente en documentación. |
| Entrega | ZIP íntegro con raíz Maven, código completo y documentación; excluye .git/target/registros generados. |

El script de inspección temporal se ejecutó fuera del repositorio: compara bytes con el ZIP original y cuenta/verifica ejemplos. No se incorpora como prueba Java ni como parser del protocolo. Los 12 rechazos tabulados se revisaron contra la precedencia de 10.3, sin presentarlos como mensajes procesados por el programa.

## Criterio de aceptación y límites

T62 entrega versión, framing, separadores, tipos/rangos, nueve órdenes, UUID/correlación, época, errores y finalización; los ejemplos permiten construir un emisor/receptor sin consultar Java. Queda pendiente la revisión del contrato por David antes de marcar la tarjeta terminada.

Las 926 pruebas y BUILD SUCCESS corresponden a la validación T61 comunicada en el contexto previo, no a una nueva ejecución aquí. No hay un parser V1 ejecutable, tráfico textual entre actores ni evidencias del extra 3 en esta tarjeta. La matriz PR01–PR16 del plan de pruebas debe convertirse en pruebas reales durante T63–T66. Los ejemplos no son trazas generadas. No se acreditan nuevas mediciones nativas de Windows.
