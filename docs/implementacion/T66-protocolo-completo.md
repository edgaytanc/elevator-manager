# T66 — Probar y demostrar el protocolo completo

- **Fecha:** 2026-11-02, Guatemala.
- **Base:** T65 aceptada, commit `0648e6d`, 64 específicas/1,114 totales, BUILD SUCCESS y árbol limpio.
- **Estado:** implementada; validación local y aceptación de fase 11 pendientes.

## Objetivo y alcance

Acreditar el protocolo en su frontera real, incluyendo comandos rechazados, identidad, eventos físicos, generaciones y controles integrados. La entrega conserva motor, gramática V1, POM, herencia Request/HallRequest/CabinRequest y propiedad de los hilos. No se abre red ni se añade una consola textual a la GUI.

## Archivos Java

| Archivo | Responsabilidad |
| --- | --- |
| ProtocolAcceptanceDemo | 22 escenarios ejecutables con actores reales, verificaciones y salida TX/RX; sin JUnit. |
| ProtocolAcceptanceTest | 22 adaptadores con timeout y traza de diagnóstico en caso de fallo. |

El código íntegro de ambos archivos se incluye en [T66-codigo-completo.md](T66-codigo-completo.md). Todos los .java anteriores también están en el ZIP.

## Comprobaciones

1. Rechazos: UUID, piso, sentido extremo, tokens adicionales, versión, tamaño, comando desconocido, cabina inexistente, destino dirigido al actor incorrecto y canal sin autoridad. Se comprueba ausencia de ACK/efecto y recuperación con una orden válida.
2. Identidad: reenvío activo admitido una sola vez físicamente; reenvío terminal con ACK/resultado histórico; cambio de contenido rechazado; dos UUID equivalentes con COALESCED, un servicio y terminal por ID.
3. Reset: servicio en movimiento cancelado con su época original, retorno físico por MOVE/ARRIVED y ACK/STATE/COMPLETED correlacionados; inbox antiguo nunca admitido y mensaje tardío rechazados; época futura sin efecto.
4. Estado: STATUS repetido conserva la respuesta original después de mover la cabina; el receptor de respuestas no instala un snapshot histórico sobre la revisión vigente.
5. Integración: dos cabinas con hilos independientes, viaje corto durante largo y registro de IDs compartido; manejador real con objetos reconstruidos y confirmación SERVED en el sentido correcto; controlador con pausa/STATUS/reanudar/Stop/Start/reset global/reinicio/cierre.

## Cómo se evita una evidencia engañosa

La demo envía String mediante los canales reales y decodifica el buzón del receptor. Las respuestas no se construyen a mano. El caso del manejador contrasta el comando despachado con un objeto diferente reconstruido en la cabina. En los reintentos, una marca de lectura obliga a observar mensajes posteriores al primer terminal, en lugar de reutilizar la primera respuesta almacenada.

STOP mantiene encoladas las órdenes de los casos ACTIVE_REPLAY/COALESCED_SERVICE/QUEUED_BEFORE_RESET hasta la liberación o el reset. No se depende de una espera corta para garantizar esa condición. Cada Rig usa SHUTDOWN textual y join en finally, con cierre nativo de respaldo si falla el diagnóstico. Los marcadores de éxito se emiten después del cierre.

La traza contiene STATE espontáneos ID 0, revisiones, restantes y orden de publicación reales. Puede cambiar el entrelazado entre cabinas y el tiempo restante. Los resultados, identidades y requisitos comprobados deben mantenerse.

## Validación y cierre

Ver [resultados](../comprobaciones/T66-resultados-locales.md), [traza](../comprobaciones/T66-traza-local.md), [comandos Windows](../comprobaciones/T66-validacion-local.md) y [aceptación fase 11](../comprobaciones/T66-aceptacion-fase-11.md). Maven esperado: 22 específicas y 1,136 totales. Las pruebas de renderizado/EDT no sustituyen una nueva aceptación visual nativa.

T67–T72 pertenecen a la siguiente fase y permanecen pendientes.
