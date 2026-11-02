# T61 — Aceptación de animación y cierre de fase 10

- **Fecha:** 2 de noviembre de 2026, Guatemala.
- **Estado:** pendiente de resultados locales T61 y cierre nativo de fase 10.
- **T61:** implementada y comprobada headless; no equivale todavía a aceptación en Windows.

## Evidencias disponibles

| Tarjeta | Evidencia aportada por David | Estado |
| --- | --- | --- |
| T58 | 12 pruebas específicas, 868 totales; commit 712b24a, Git limpio | Aceptada al solicitar T59 |
| T59 | 18 específicas, 886 totales, BUILD SUCCESS; commit 1cd6913 y Git limpio; captura RUNNING con tres cabinas | Aceptada al solicitar T60 |
| T60 | 24 específicas, 910 totales, BUILD SUCCESS; commit 6883d66 y Git limpio; captura PAUSED con posiciones intermedias y controles coherentes | Aceptada al solicitar T61 |
| T61 | Escenarios y mediciones headless entregados con el ZIP | Pendiente de validación local |

Las capturas estáticas acreditan los estados y la presentación visibles. No se atribuye a ellas una demostración de fluidez, congelación sostenida ni retorno físico completo.

## Pendientes para el cierre

| Comprobación | Estado |
| --- | --- |
| VisualQualityTest: 16 pruebas correctas | Pendiente de David |
| Maven completo: 926 pruebas, BUILD SUCCESS | Pendiente de David |
| P=1, varias cabinas y pisos legibles | Pendiente de confirmación nativa |
| Etiquetas e IDs accesibles al desplazar y redimensionar | Pendiente de confirmación nativa |
| Subida/bajada fluida y respuesta de controles/pestañas | Pendiente de confirmación nativa |
| Pausa/reanudación sin contar pared pausada | Pendiente de confirmación nativa |
| Stop adyacente y Start con pendientes | Pendiente de confirmación nativa |
| Reset físico hasta piso 1/UP, incluido desde pausa | Pendiente de confirmación nativa |
| Cerrar y X desde pausa, retorno al prompt | Pendiente de confirmación nativa |
| Revisión Git, commit T61 y árbol limpio | Pendiente de David |

Seguir T61-recorrido-gui.md y conservar las líneas de mediciones emitidas. No hace falta atribuir al escritorio los números de Linux. Una incidencia debe quedar registrada antes de decidir el cierre.

Tras confirmar estos puntos, actualizar el acta y el plan, marcar T61 completada y declarar cerrada la fase 10. La siguiente tarjeta será T62 — Especificar gramática y semántica del protocolo, inicio de fase 11. No se implementa ni se da por iniciada T62 en esta entrega.
