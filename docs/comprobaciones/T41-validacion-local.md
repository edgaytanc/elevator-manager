# T41 — Validación local

- **Fecha:** 2026-10-28, Guatemala.
- **Base:** commit `f39eb3a`; 418 pruebas Maven reportadas en el cierre previo de T40.
- **Entorno:** OpenJDK 17.0.20, Linux, headless. Maven y dependencias JUnit no disponibles.
- **Resultado:** compilación de todos los fuentes de producción y escenarios independientes con objetivo Java 17; 389 escenarios satisfactorios en 21 grupos. Los 16 escenarios T41 también pasan tras el ajuste final de mensajes nulos del formatter.

## Compilación

Se utilizó el compilador del módulo jdk.compiler, sin reutilizar target ni clases del ZIP. Se compilaron todos los *Scenarios.java y ScalabilityEvaluation junto a producción. No se compilaron los adaptadores JUnit con dependencias reales; eso corresponde a Maven en el equipo de David.

## Regresión independiente

| Grupo | Escenarios | Salida | Duración s |
| --- | ---: | ---: | ---: |
| DirectionPickupScenarios | 20 | 0 | 9.543 |
| ElevatorSelectionScenarios | 18 | 0 | 1.784 |
| ManagerWorkerScenarios | 12 | 0 | 1.319 |
| PendingConfirmationScenarios | 26 | 0 | 1.780 |
| PickupTimeEstimatorScenarios | 20 | 0 | 0.360 |
| ScalabilityScenarios | 13 | 0 | 4.894 |
| LoggingScenarios | 16 | 0 | 0.617 |
| ModelRulesScenarios | 7 | 0 | 0.231 |
| CabinResetScenarios | 32 | 0 | 7.574 |
| ConcurrentAdmissionScenarios | 21 | 0 | 0.305 |
| DirectionalAgendaScenarios | 20 | 0 | 3.517 |
| ElevatorDwellScenarios | 15 | 0 | 0.851 |
| ElevatorMovementScenarios | 10 | 0 | 0.335 |
| ElevatorWorkerScenarios | 7 | 0 | 0.433 |
| ExecutionEventScenarios | 20 | 0 | 1.274 |
| GlobalResetScenarios | 33 | 0 | 8.553 |
| LifecycleRaceScenarios | 24 | 0 | 4.580 |
| OrderlyShutdownScenarios | 32 | 0 | 1.720 |
| ParallelTimingScenarios | 8 | 0 | 4.781 |
| SimulationControllerScenarios | 14 | 0 | 1.746 |
| StopContinuationScenarios | 21 | 0 | 6.208 |

Los 389 escenarios son 373 previos y 16 nuevos; no equivalen a la suite Maven completa. La modificación final solo admite mensajes nulos del formatter y añade su aserción; los 16 escenarios se recompilaron y repitieron después. No cambian las clases del motor.

## Evidencia de T41

- Los cuatro productores paralelos publican 100 mensajes por cabina, cada uno con 5,000 caracteres ñ. Los 400 registros quedan completos, únicos y con una copia idéntica en consola, sin fragmentos mezclados.
- Cada archivo conserva exactamente 100 registros de su cabina. La prueba usa directorios temporales y limpia archivos y bloqueos al finalizar.
- La consola y los archivos se vacían antes del cierre; la salida del llamador sigue abierta después.
- Consulta repetida no agrega handlers; un handler temporal del logger raíz no recibe publicaciones de cabina y permanece instalado.
- Tres sesiones consecutivas conservan registros anteriores por append y liberan los .lck. Dos aperturas del mismo directorio fallan de forma explícita; directorios distintos permanecen independientes.
- Una falla intencional al crear el segundo archivo libera la configuración parcial; después se abre el mismo directorio y se escribe una sola vez.
- Ruta con %u, %g y ñ se trata literalmente. Mensajes con saltos, parámetros, niveles y excepciones conservan representación legible.
- El controlador acepta el logging como recurso, arranca actores reales, confirma CLOSED, termina sus hilos y permite reabrir el directorio.
- LoggingDemo se ejecutó con dos hilos: diez mensajes en consola y cinco por archivo, sin bloqueo al finalizar.

## Límites y validación pendiente

No se ejecutó Maven/JUnit ni una ventana JFrame en este entorno. No se probaron bloqueos entre procesos distintos ni permisos de Windows; FileLock depende del sistema de archivos. T42 completará conexión de eventos y política de incidencias durante escritura. No se afirma que el motor ya publique sus movimientos en estos archivos.

```powershell
mvn -Dtest=LoggingTest test
mvn "-Dtest=LoggingTest,OrderlyShutdownTest,LifecycleRaceTest" test
mvn clean verify
java -cp .\target\classes com.elevatormanager.diagnostics.LoggingDemo
```

Se esperan 16 pruebas específicas, 72 seleccionadas y 434 acumuladas. Confirmar los valores reales y BUILD SUCCESS antes de marcar T41 cerrada. Commit sugerido: `T41: configurar logging por elevador`.
