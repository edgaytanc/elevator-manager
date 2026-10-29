# T52 — Validación local

**Confirmación posterior del usuario:** 32 pruebas específicas, 176 de interfaz y 754 totales satisfactorias en Maven; commit `6eec38e`, árbol limpio. Registro nativo con Reset completo de ambas cabinas y terminación de actores. La captura confirma estado/visor; no mide fluidez. El informe de abajo conserva la evidencia local original.

- **Fecha:** 2026-10-28, Guatemala.
- **Base:** T51, commit `ff29a67`, 722 pruebas Maven satisfactorias y capturas confirmadas por David.
- **Entorno:** Linux, OpenJDK 17.0.20, headless; Maven no instalado.
- **Resultado:** 33 grupos independientes con salida 0, 709 escenarios (677 anteriores y 32 nuevos).

## Compilación y alcance

Se utilizó JavaCompiler del módulo jdk.compiler con `--release 17 -encoding UTF-8`, desde las fuentes actuales a un directorio externo nuevo para la versión final. Se excluyeron adaptadores *Test.java que requieren JUnit. src/main/resources estuvo en el classpath. No se utilizaron binarios de target ni del equipo de David.

La regresión se repitió tras finalizar la caché y el bloqueo por época dibujada: un Start posterior a Reset no debe rehabilitar destinos de una vista que todavía conserva una época anterior. Los resultados de la tabla corresponden a las clases finales. Las repeticiones no cuentan como escenarios nuevos.

Los 709 escenarios independientes no equivalen a una ejecución de las 754 pruebas JUnit esperadas. JUnit/Maven, empaquetado Maven y revisión de JFrame nativo permanecen pendientes en Windows. El POM no cambia.

| Grupo | Salida | Duración s |
| --- | ---: | ---: |
| DirectionPickupScenarios | 0 | 9.301 |
| ElevatorSelectionScenarios | 0 | 1.54 |
| ManagerWorkerScenarios | 0 | 1.211 |
| PendingConfirmationScenarios | 0 | 1.656 |
| PickupTimeEstimatorScenarios | 0 | 0.21 |
| ScalabilityScenarios | 0 | 4.5 |
| ReadFailureScenarios | 0 | 0.103 |
| RequestFileScenarios | 0 | 0.266 |
| LoggingScenarios | 0 | 0.38 |
| SessionLoggingScenarios | 0 | 1.944 |
| ModelRulesScenarios | 0 | 0.112 |
| CabinResetScenarios | 0 | 7.332 |
| ConcurrentAdmissionScenarios | 0 | 0.183 |
| DirectionalAgendaScenarios | 0 | 3.422 |
| ElevatorDwellScenarios | 0 | 0.668 |
| ElevatorMovementScenarios | 0 | 0.189 |
| ElevatorWorkerScenarios | 0 | 0.2 |
| ExecutionEventScenarios | 0 | 0.942 |
| GlobalResetScenarios | 0 | 7.842 |
| ImportReadErrorScenarios | 0 | 1.195 |
| LifecycleRaceScenarios | 0 | 3.004 |
| OrderlyShutdownScenarios | 0 | 1.558 |
| ParallelTimingScenarios | 0 | 4.673 |
| RequestImportScenarios | 0 | 1.727 |
| SimulationControllerScenarios | 0 | 1.557 |
| StopContinuationScenarios | 0 | 5.52 |
| CabinPanelsScenarios | 0 | 4.453 |
| ConfigurationFormScenarios | 0 | 1.238 |
| EdtSafetyScenarios | 0 | 3.168 |
| EventViewerScenarios | 0 | 2.002 |
| FileSelectionScenarios | 0 | 1.221 |
| HallCallsScenarios | 0 | 2.122 |
| SystemControlsScenarios | 0 | 4.725 |

## Evidencia de T52

Los 32 escenarios cubren compuerta inicial/frecuencia/ticks perdidos/wrap de nanoTime, captura fuera de EDT, coalescencia con 10000 solicitudes de captura mientras una está retenida, intervalo mínimo, sustitución de publicación tras Reset, colecciones inmutables, exclusión de historial servido, conjuntos de época/sesión anterior, recuperación de fallo, descarte de publicación tardía, cierre real y repetido del pump, snapshot directo validado, caché de tres paneles con 30 cabinas accesibles, navegación, reconstrucción tras expulsión, documentos sin cambios, auditoría con ocho cabinas simultáneamente MOVING, importación grande y cierre durante lectura, recurso lento, preparación retenida, cierre durante captura, Stop/Reset con callbacks tardíos, fin del capturador, guardas de captura/EDT, callbacks de WindowCloseHandler y Reset durante importación.

El monitor RepaintManager se instala/restaura en EDT y registra invalidaciones/dirty regions invocadas fuera del EDT. Un control positivo con un JLabel del fixture demuestra que detecta una mutación deliberada; después se separa esa evidencia de la auditoría de producción. Los recorridos instrumentados del sistema terminaron sin violaciones. Un DocumentListener también confirma que repetir cien veces un snapshot sin cambios no reescribe documentos de cabina.

Se confirmó movimiento simultáneo de las ocho cabinas antes de esperar pulsos Swing. La importación utiliza 10000 instrucciones en 900000 bytes, con apertura retenida y posterior delegación al RequestFileReader real, dentro del contrato. Se comprueba el hilo de lectura y el lote real completo. La lectura se prueba en RESET; el movimiento se verifica en otro escenario RUNNING. No se fabrica una importación válida mientras el estado la prohíbe.

Se retienen preparación, lectura, captura y cierre de recursos mediante latches para comprobar que el EDT continúa despachando. Los latches se liberan y se esperan cierres reales de motores, capturadores y pools. No se usa un sleep en EDT. Los futuros de cierre no se dan por terminados hasta liberar el recurso o capturador retenido.

## Pulsos observados

Timer Swing de 20 ms; el hilo de prueba espera los pulsos con un plazo de cinco segundos. El mayor intervalo es diagnóstico local, no una garantía de latencia. Incluye el primer intervalo desde la preparación del temporizador.

| Observación | Pulsos esperados | Mayor intervalo ms |
| --- | ---: | ---: |
| 1 | 4 | 21.392 |
| 2 | 8 | 20.418 |
| 3 | 6 | 20.529 |
| 4 | 3 | 20.450 |
| 5 | 5 | 20.371 |
| 6 | 5 | 20.426 |
| 7 | 5 | 20.490 |
| 8 | 4 | 20.318 |
| 9 | 4 | 20.440 |

## Render y paquete

Se inspeccionó la composición final a 1180×820 con sesión/logging reales: controles, cabina visible en movimiento, destino 8 encendido, recogida 4/Subir y registros recientes. Se dejó avanzar el ciclo de captura/refresco antes de inspeccionarlo; la publicación es periódica y puede tener retraso de uno o más ticks. El render no acredita arrastre, foco, DPI o cierre por X en una ventana nativa.

Se verificó git diff --check y se comparó el Markdown de código completo con los nueve Java nuevos/modificados. Se verificaron CRC y bytes de todas las entradas del ZIP. Incluye todas las fuentes Java, recursos anteriores, nueva fixture de importación, escenarios y Markdown; excluye .git, target y logs generados.

## Pendiente en Windows

```powershell
mvn "-Dtest=EdtSafetyTest" test
mvn "-Dtest=EdtSafetyTest,EventViewerTest,CabinPanelsTest,HallCallsTest,SystemControlsTest,ConfigurationFormTest" test
mvn clean verify
java -jar .\target\elevator-manager-0.1.0-SNAPSHOT.jar
```

Se esperan 32 específicas, 176 combinadas y 754 acumuladas sin fallos, errores u omisiones y BUILD SUCCESS. Seguir el [recorrido nativo T52](../implementacion/T52-actualizacion-segura-edt.md). T52 queda pendiente de confirmación del usuario; T53 y el cierre de fase 8 todavía no se declaran completados.

Commit sugerido: `T52: limitar refrescos y verificar actualizacion segura en EDT`.
