# T65 — Validación local y aceptación pendiente

- **Fecha:** 2026-11-02, Guatemala.
- **Base aceptada:** T64 en `36af413`, 1,050 pruebas totales, BUILD SUCCESS y árbol limpio.
- **Estado:** implementada y verificada de forma independiente; Maven Windows y aceptación de David pendientes.

## Evidencia disponible

| Comprobación | Resultado |
| --- | --- |
| Compilación | 133 fuentes independientes, OpenJDK 17.0.20 real, release 17/UTF-8. |
| Específicas T65 | 64 escenarios correctos; mismos 64 adaptadores JUnit. |
| Regresión previa | 1,005 escenarios en 47 grupos correctos en sus ejecuciones finales. |
| Total independiente | 1,069; no se confunde con la suite Maven completa. |
| Correlación | ID/cabina/época exactos; ACK sin finalización, mensajes prematuros/repetidos e intentos antiguos controlados. |
| Estado | STATE solicitado/espontáneo, caché por generación/revisión, igualdad de revisión con restante distinto y replay histórico sin rollback. |
| Seguimiento real | Manejador consume texto, confirma admisión y exige parada compatible antes de servir una llamada. |
| Reset | ACK no termina el viaje; STATE correlacionado de N cabinas, limpieza del manejador y snapshots físicas antes de RESET. |
| Pausa/consulta | STATUS leído por el actor dentro de pausa sin avanzar reloj ni restante. |
| Controles | Canales con origen, START/STOP/PAUSE/RESUME/RESET/STATUS/SHUTDOWN e IDs compartidos. |
| Recursos | Consultas en carrera con cierre se resuelven/rechazan; join del consumidor de respuestas antes de CLOSED. |
| Demo | Actores reales desde clases y JAR auxiliar; final T65_INTEGRATION_OK, epoch=1, state=CLOSED, responsesJoined=true. |
| Integridad | 18 archivos Java completos en Markdown, 186 .java en ZIP, enlaces y diff --check comprobados. |

La regresión completa se ejecutó y los grupos afectados por los ajustes de arranque/importación/cierre y sincronización de pruebas GUI se repitieron. No se incrementaron timeouts, omitieron casos ni se usaron dependencias JUnit falsas. Los diagnósticos de error inyectados en las pruebas de logging pertenecen a sus casos esperados. Ver [resultados y traza](T65-resultados-locales.md).

## Validación en Windows

```powershell
Set-Location 'D:\Users\David\Desktop\workspace\java\elevator-manager'
mvn "-Dtest=ProtocolIntegrationTest" test
mvn clean verify
java -cp .\target\classes com.elevatormanager.diagnostics.ProtocolIntegrationDemo
git diff --check
git status --short
```

| Criterio | Esperado | Estado recibido |
| --- | --- | --- |
| ProtocolIntegrationTest | 64, cero fallos/errores/omitidas | Pendiente de David |
| Suite Maven completa | 1,114, cero fallos/errores/omitidas; BUILD SUCCESS | Pendiente de David |
| Demo | HELD/clockFrozen=true durante STATUS; epoch=1 y CLOSED al final | Pendiente de David |
| Revisión de T65 | Sin incidencias abiertas de esta tarjeta | Pendiente |
| Commit y Git limpio | Registrar hash real | Pendiente |

## Límites de esta evidencia

Maven no está instalado en el entorno de implementación; los adaptadores JUnit esperan ejecutarse con sus dependencias reales en Windows. El total 1,114 es esperado, no observado aquí. Las pruebas headless no constituyen un nuevo recorrido nativo de ventanas.

STATE es una proyección de consulta y no reemplaza el snapshot de animación/planificación. Los canales de producción incorporan origen y registro de IDs; se conservan APIs heredadas para compatibilidad con los escenarios previos. La comprobación de aceptación final del protocolo completo y el cierre de fase 11 corresponden a T66.
