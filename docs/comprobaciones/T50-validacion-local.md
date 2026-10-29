# T50 — Validación local

> Cierre completo confirmado al iniciar T51: 32 pruebas específicas, 104 combinadas y 682 acumuladas, BUILD SUCCESS, commit `1d3378b` y árbol limpio. Se aportaron registros de servicio/Reset completo/cierre y capturas de RESET y atención en piso 4 con destino 8 aún encendido. El informe siguiente conserva el estado previo al cierre.

- **Fecha:** 2026-10-28, Guatemala.
- **Base:** T49, commit `2b17a64`, 650 pruebas Maven satisfactorias confirmadas por David, llamadas servidas y árbol limpio.
- **Entorno:** Linux, OpenJDK 17.0.20, headless; Maven no instalado.
- **Resultado:** 31 grupos independientes con salida 0, 637 escenarios (605 previos y 32 nuevos).

## Compilación y alcance

Se utilizó JavaCompiler del módulo jdk.compiler con `--release 17 -encoding UTF-8`, sobre las fuentes actuales y un directorio externo nuevo para la versión final. Se excluyeron adaptadores *Test.java que requieren JUnit. Los recursos de src/main/resources estuvieron en el classpath. No se utilizaron binarios de target ni del equipo de David.

La primera inspección visual detectó que JTextArea con ajuste de líneas podía reclamar demasiado alto preferido y dejar sin espacio el cuerpo del panel. Se acotó el feedback mediante JScrollPane y se alinearon navegación/listas. Se recompilaron todas las fuentes y se repitió la regresión completa con las clases finales. Se inspeccionaron un render de cabina y uno de la composición completa. Las repeticiones no se cuentan como escenarios nuevos.

Los 637 escenarios independientes no equivalen a una ejecución de las 682 pruebas JUnit esperadas. Los adaptadores JUnit, construcción Maven, empaquetado Maven y ventana nativa siguen pendientes en Windows. El POM y sus dependencias se conservan.

| Grupo | Salida | Duración s |
| --- | ---: | ---: |
| DirectionPickupScenarios | 0 | 9.352 |
| ElevatorSelectionScenarios | 0 | 1.546 |
| ManagerWorkerScenarios | 0 | 1.247 |
| PendingConfirmationScenarios | 0 | 1.658 |
| PickupTimeEstimatorScenarios | 0 | 0.207 |
| ScalabilityScenarios | 0 | 4.583 |
| ReadFailureScenarios | 0 | 0.11 |
| RequestFileScenarios | 0 | 0.288 |
| LoggingScenarios | 0 | 0.375 |
| SessionLoggingScenarios | 0 | 1.949 |
| ModelRulesScenarios | 0 | 0.122 |
| CabinResetScenarios | 0 | 7.359 |
| ConcurrentAdmissionScenarios | 0 | 0.243 |
| DirectionalAgendaScenarios | 0 | 3.444 |
| ElevatorDwellScenarios | 0 | 0.689 |
| ElevatorMovementScenarios | 0 | 0.212 |
| ElevatorWorkerScenarios | 0 | 0.217 |
| ExecutionEventScenarios | 0 | 0.951 |
| GlobalResetScenarios | 0 | 7.911 |
| ImportReadErrorScenarios | 0 | 1.22 |
| LifecycleRaceScenarios | 0 | 3.111 |
| OrderlyShutdownScenarios | 0 | 1.682 |
| ParallelTimingScenarios | 0 | 4.693 |
| RequestImportScenarios | 0 | 1.761 |
| SimulationControllerScenarios | 0 | 1.575 |
| StopContinuationScenarios | 0 | 5.576 |
| CabinPanelsScenarios | 0 | 4.537 |
| ConfigurationFormScenarios | 0 | 1.236 |
| FileSelectionScenarios | 0 | 1.216 |
| HallCallsScenarios | 0 | 2.339 |
| SystemControlsScenarios | 0 | 4.702 |

## Evidencia nueva

Los 32 escenarios cubren N paneles, P botones, paginación parcial y P máximo sin O(P) componentes, datos iniciales, bloqueo en RESET, enrutamiento a la cabina elegida, piso anterior/siguiente durante movimiento, ausencia de luz por mero envío, encendido tras admisión, permanencia ante recibo ENQUEUED, apagado al servir, conservación de otros destinos, Stop/continuación, Reset y snapshot viejo, cierre, recogidas externas con sentido, permanencia al asignar, retirada tras SERVED, sentidos opuestos, alias visibles una sola vez, edificio de un piso, deduplicación interna, rechazo de revisión antigua/snapshot ajeno, callbacks tardíos de Reset/Stop, orden inverso de recibos, bloqueo inmediato desde controles de sistema, composición manual interna/externa, contrato EDT y luces fuera de página.

Los componentes Swing y el EDT son reales. SimulationController, CabinRequest, HallRequest, manejador y trabajadores también son reales; no se fabricaron resultados de admisión para probar luces. Los pendientes se esperan en snapshots; el servicio interno se observa al comenzar DWELLING en el piso destino y el servicio externo mediante SERVED. La fase de parada observada tiene duración positiva en los escenarios para permitir verificar el cambio visual al atenderse.

Los callbacks diferidos son Runnable reales derivados de submit, retenidos por una cola de prueba y aplicados en EDT después del cambio de estado/época. No modifican luces y no restauran mensajes anteriores. Los fixtures esperan closeAsync y awaitTermination fuera del EDT. Los paneles de cabina no crean hilos/timers; la composición del sistema detiene su Timer al cerrar.

La prueba P=Integer.MAX_VALUE solo accede a la página final y comprueba un máximo de veinte botones; no envía un viaje hasta ese piso. Los cálculos de página incluyen la última página parcial sin overflow.

## Revisión visual y paquete

Se inspeccionó CabinPanel final a 520×620 con piso 1/siguiente 2, movimiento, destino 8 encendido y recogida piso 4/Subir. Se inspeccionó también SystemControlPanel a 1100×740 con dos pestañas, formulario, llamadas compartidas y controles. No hay solapamiento visible; los botones, listas y feedback tienen espacio. El render de composición utiliza una sesión de prueba real e inyecta su apertura para evitar destinos de logging permanentes; no crea un JFrame.

No se verificaron decoración nativa, foco, escala de Windows, desplazamiento mediante ratón ni cierre de JFrame por X en este entorno. Estos renders no sustituyen la lista visual de la ficha de implementación.

`git diff --check` terminó sin errores. Se comparó el Markdown de código completo con los seis Java nuevos/modificados. Se verificaron CRC y bytes del ZIP; incluye todas las fuentes Java, recursos previos, nuevos escenarios y documentación. Excluye .git, target y registros generados.

## Pendiente en Windows

```powershell
mvn "-Dtest=CabinPanelsTest" test
mvn "-Dtest=CabinPanelsTest,HallCallsTest,SystemControlsTest,ConfigurationFormTest" test
mvn clean verify
java -jar .\target\elevator-manager-0.1.0-SNAPSHOT.jar
```

Se esperan 32 específicas, 104 combinadas y 682 acumuladas, sin fallos, errores u omisiones y BUILD SUCCESS. Seguir el [recorrido visual T50](../implementacion/T50-paneles-y-botones-cabina.md). T50 permanece pendiente de resultados de David; no se cierra fase 8.

Commit sugerido: `T50: crear paneles y destinos internos por cabina`.
