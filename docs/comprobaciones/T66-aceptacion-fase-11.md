# T66 — Aceptación de fase 11, protocolo textual

- **Base:** fases 1–10 aceptadas; T62–T65 cerradas.
- **Último commit recibido:** `0648e6d`, T65; 1,114 pruebas correctas y Git limpio.
- **Estado del cierre:** T66 implementada; pendiente de validación y aceptación local.

| Criterio | Evidencia disponible | Pendiente |
| --- | --- | --- |
| Gramática, argumentos, correlación y generaciones | docs/05-protocolo.md; T62 aceptada | Ninguno de T62 |
| Codec y parser estricto | T63, 76 específicas; commit 6c8d7d3 | Regresión T66 |
| Frontera String real en colas | T64, 48 específicas; commit 36af413 | Regresión T66 |
| Respuestas, estado, reset y barreras | T65, 64 específicas; commit 0648e6d | Regresión T66 |
| Errores y recuperación del actor | Demo T66, 10 rechazos sintácticos/semánticos/de autoridad + FUTURE_FRAME | Maven/demo Windows |
| Duplicados y alias | ACTIVE_REPLAY, COMPLETED_REPLAY, CONFLICTING_REPLAY, COALESCED_SERVICE | Maven/demo Windows |
| Reset con mensajes en vuelo | RESET_IN_FLIGHT, QUEUED_BEFORE_RESET, LATE_OLD_FRAME y regresión T65 | Maven/demo Windows |
| Estado histórico sin rollback | STATUS_REPLAY y regresión adversarial T65 | Maven/demo Windows |
| Manejador, paralelismo y controles | MANAGER_ROUND_TRIP, PARALLEL_CABINS, CONTROLLER_EXTRAS | Maven/demo Windows |
| Extras anteriores y recursos | Regresión de pausa, animación, renderizado, EDT, importación, logs y cierre | Maven completo y recorrido GUI |
| Entrega reproducible | ZIP completo, código íntegro, documentación y traza real | Commit T66 y Git limpio |

Para cerrar fase 11: 22 específicas, 1,136 totales, cero fallos/errores/omitidas y BUILD SUCCESS; demo completa correcta desde clases/JAR; recorrido nativo conforme; revisión de Git y commit real. Hasta recibirlo se conserva T66 en revisión.

Este cierre solo acredita fase 11 y sus regresiones. La aceptación funcional global, estrés, tolerancias y trazabilidad completa de fases 12–13 todavía están planificadas en T67 y posteriores.
