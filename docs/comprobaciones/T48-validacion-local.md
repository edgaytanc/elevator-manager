# T48 — Validación local

> Cierre funcional confirmado al iniciar T49: 28 pruebas específicas, 44 combinadas con T47 y 622 acumuladas, BUILD SUCCESS, commit `0bae607`, registros de Stop/continuación, dos retornos Reset 1/UP y cierre con retorno al prompt. El informe siguiente conserva su estado original previo al cierre.

- **Fecha:** 2026-10-28, Guatemala.
- **Base:** T47, commit `5f30a29`, 594 pruebas Maven y capturas confirmadas por David.
- **Entorno:** Linux, OpenJDK 17.0.20, headless; Maven no instalado.
- **Resultado:** compilación de producción y escenarios con objetivo Java 17; 29 grupos independientes con salida 0, 577 escenarios (549 previos y 28 nuevos de T48).

## Compilación y alcance

Se utilizó JavaCompiler del módulo jdk.compiler con `--release 17 -encoding UTF-8`, excluyendo adaptadores *Test.java porque sus dependencias JUnit requieren Maven. Los recursos de src/main/resources estuvieron en el classpath. No se utilizaron binarios de target ni del ZIP adjunto.

Después de la regresión completa se ajustó el orden de acciones dentro de los escenarios de Stop/Reset/cierre: botón y comprobación se ejecutan en la misma entrega al EDT, evitando depender de que una segunda entrega gane a un callback ya encolado. Se recompilaron todos los fuentes en un directorio externo nuevo y se repitieron los 28 escenarios T48 con salida 0; los 16 de T47 también se comprobaron en ese directorio. No se modificó producción después de la regresión completa. Las repeticiones no se contabilizan como pruebas adicionales.

Los 577 escenarios independientes no equivalen a las 622 pruebas JUnit esperadas. Los adaptadores JUnit, construcción Maven y JAR Maven no se ejecutaron aquí.

| Grupo | Salida | Duración s |
| --- | ---: | ---: |
| DirectionPickupScenarios | 0 | 9.304 |
| ElevatorSelectionScenarios | 0 | 1.541 |
| ManagerWorkerScenarios | 0 | 1.235 |
| PendingConfirmationScenarios | 0 | 1.659 |
| PickupTimeEstimatorScenarios | 0 | 0.186 |
| ScalabilityScenarios | 0 | 4.598 |
| ReadFailureScenarios | 0 | 0.122 |
| RequestFileScenarios | 0 | 0.298 |
| LoggingScenarios | 0 | 0.362 |
| SessionLoggingScenarios | 0 | 2.007 |
| ModelRulesScenarios | 0 | 0.109 |
| CabinResetScenarios | 0 | 7.339 |
| ConcurrentAdmissionScenarios | 0 | 0.254 |
| DirectionalAgendaScenarios | 0 | 3.444 |
| ElevatorDwellScenarios | 0 | 0.675 |
| ElevatorMovementScenarios | 0 | 0.192 |
| ElevatorWorkerScenarios | 0 | 0.191 |
| ExecutionEventScenarios | 0 | 0.94 |
| GlobalResetScenarios | 0 | 7.835 |
| ImportReadErrorScenarios | 0 | 1.185 |
| LifecycleRaceScenarios | 0 | 2.917 |
| OrderlyShutdownScenarios | 0 | 1.554 |
| ParallelTimingScenarios | 0 | 4.654 |
| RequestImportScenarios | 0 | 1.703 |
| SimulationControllerScenarios | 0 | 1.555 |
| StopContinuationScenarios | 0 | 5.535 |
| ConfigurationFormScenarios | 0 | 1.25 |
| FileSelectionScenarios | 0 | 1.21 |
| SystemControlsScenarios | 0 | 4.553 |

## Evidencia nueva

Los 28 escenarios T48 cubren controles iniciales, rechazo de configuración inválida, preparación fuera de EDT y bloqueo de parámetros, controles RESET, Start/Stop/continuación con misma sesión, indicadores inmediatos, retorno físico, carga sin movimiento, bloqueo de importación en RUNNING/STOPPED, cancelación, lote inválido atómico con línea, limpieza por Reset, cierre sin sesión y durante preparación/movimiento/Stop/Reset, reintento tras fallo de preparación, confirmación repetida, logging real/liberación del lock, acceso fuera del EDT, disponibilidad del EDT durante preparación, fallo de cierre visible, Reset sustituyendo Stop, lectura fallida/reintento, ejecución del lote por Start, error real al abrir directorio de logs y ruta única de cierre.

Las sesiones, trabajadores, parser y archivos de importación son reales. Se inyecta selección de Path para no abrir diálogos nativos. Los escenarios usan EDT real y esperan futuros fuera de ese hilo. La preparación tardía se retiene mediante latches y el cierre debe esperar y liberar su sesión; se verifica awaitTermination. Los directorios temporales se eliminan y los paneles detienen su Timer al cerrar. El caso de cierre fallido usa un recurso de prueba que lanza IOException; no se interpreta como éxito de liberación.

El test de logs abre el mismo factory de producción sobre un directorio temporal, comprueba registros no vacíos y reapertura del lock tras cerrar. El error de apertura utiliza un archivo real donde se esperaba un directorio; se verifica que no se publica controlador ni se habilita Start.

## Comprobación visual y paquete

Se renderizó SystemControlPanel sin JFrame a una imagen de 820×650 y se inspeccionó: configuración, indicador de estado, botones y resultado de acción visibles sin solapamiento. Los controles iniciales están deshabilitados excepto Cerrar. No se verificaron decoración nativa, foco, escala Windows, selector JFileChooser ni X de JFrame en este entorno.

`git diff --check` terminó sin errores. Se verificó el CRC del ZIP y se compararon sus entradas con los bytes de los fuentes. Incluye todos los .java y recursos, nuevos escenarios y Markdown; excluye .git, target y logs generados.

## Pendiente en Windows

```powershell
mvn "-Dtest=SystemControlsTest" test
mvn "-Dtest=SystemControlsTest,ConfigurationFormTest" test
mvn clean verify
java -jar .\target\elevator-manager-0.1.0-SNAPSHOT.jar
```

Se esperan 28 específicas, 44 junto a T47 y 622 acumuladas, sin fallos, errores u omisiones y BUILD SUCCESS. Seguir el [recorrido visual](../implementacion/T48-panel-controles-sistema.md). T48 sigue pendiente de confirmación de David; no se cierra la fase 8.

Commit sugerido: `T48: integrar controles del sistema y sesion asincrona`.
