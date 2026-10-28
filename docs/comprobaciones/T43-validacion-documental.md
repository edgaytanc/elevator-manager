# T43 — Validación documental

- **Fecha:** 2026-10-28, Guatemala.
- **Base:** T42, commit confirmado `ae5a1bf`, 458 pruebas Maven satisfactorias.
- **Alcance:** documentación; no cambia producción, POM ni pruebas Java.

## Comprobaciones realizadas

| Comprobación | Resultado |
| --- | --- |
| Sintaxis y dominio de los cinco bloques válidos completos | 1, 4, 6, 1 y 2 instrucciones válidas respectivamente, para las configuraciones indicadas. |
| Tabla de ejemplos inválidos | 26 entradas contrastadas con sus códigos esperados y precedencia de diagnóstico. |
| Archivo completo con error al final | Línea física 4; las anteriores son válidas, pero el contrato exige rechazo íntegro. |
| Orden y repetidos | Seis entradas en el bloque de repetidos; las claves equivalentes se identifican por valor numérico y no se eliminan al parsear. |
| Coherencia del dominio | Rangos y sentidos contrastados con SimulationConfig, Request, CabinRequest, HallRequest y códigos de validación existentes. |
| Referencias locales | Los destinos Markdown de los diez documentos nuevos o actualizados existen. |
| Fuentes y construcción | Todos los .java y pom.xml son idénticos byte a byte a la entrega T42; no hay clases añadidas ni eliminadas. |
| Espacios y empaquetado | Comprobación de diferencias sin errores; ZIP íntegro y con proyecto completo. |

Para revisar los ejemplos se utilizó una comprobación temporal independiente de su sintaxis, valores y códigos esperados. No forma parte del proyecto, no es un parser Java y no demuestra lectura ni importación implementadas. Las condiciones de UTF-8 malformado, permisos, tamaños exactos y carreras de confirmación quedan especificadas como pruebas futuras de T44–T46; no se presentan como casos ejecutados aquí.

## Decisiones conservadas y precisadas

Se conservan UTF-8, BOM opcional al comienzo, LF/CRLF, espacios ASCII, comentarios completos con #, prohibición de tabs/comentarios finales, HALL/CABIN, importación en RESET y sustitución atómica. Se precisan ceros iniciales, límites de recursos, números de línea, códigos de error, creación de identidades y diferencia entre orden de presentación y orden de servicio.

La validación completa del archivo no elimina duplicados funcionales del lote ni garantiza una única parada bajo toda planificación concurrente. El motor conserva la regla D18 sobre solicitudes activas. El archivo no incluye tiempos, controles, pasajeros ni mensajes V1. No se implementa todavía la importación y no cambia el comportamiento observable de la aplicación.

## Evidencia previa y límites

David confirmó T42 con 24 pruebas específicas, 458 acumuladas, BUILD SUCCESS, EventLoggingDemo correcto y commit ae5a1bf. Esa evidencia se registra en las fichas correspondientes. T43 no ejecutó Maven de nuevo ni agregó pruebas: la cifra 458 describe la base validada, no un resultado local nuevo.

No se afirma que los ejemplos puedan importarse todavía. Los archivos de muestra descargables y las pruebas de lectura se completarán en T46; este contrato los incluye solo como contenido Markdown. El ZIP contiene todos los fuentes Java actuales para conservar el formato de entrega acordado.

## Cierre de la tarjeta

Contrato adoptado al solicitar T44 y contrastado posteriormente con su parser Java real. No se ha comunicado hash individual de T43; no se atribuye uno. Ver [ficha T43](../implementacion/T43-formato-archivo-solicitudes.md) y [contrato 07](../07-formato-archivo-solicitudes.md). Commit sugerido: `T43: definir formato del archivo de solicitudes`. La siguiente tarjeta es T44.
