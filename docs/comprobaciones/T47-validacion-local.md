# T47 — Validación local

> Cierre confirmado al iniciar T48: 16 pruebas específicas y 594 acumuladas, BUILD SUCCESS, commit `5f30a29`, árbol limpio y capturas de validación inválida/válida. T47 queda cerrada. El informe siguiente conserva el estado de su entrega original.

- **Fecha:** 2026-10-28, Guatemala.
- **Base:** T46, commit `84f6261` del proyecto adjunto; 578 pruebas Maven reportadas anteriormente. David confirmó cerradas las fases 1–7.
- **Entorno:** Linux, OpenJDK 17.0.20, headless; Maven no instalado.
- **Resultado:** fuentes de producción y escenarios independientes compilados desde src con objetivo Java 17; 28 grupos con salida 0, 549 escenarios (533 anteriores y 16 nuevos).

## Ejecución

Se utilizó JavaCompiler del módulo jdk.compiler porque el entorno dispone del módulo pero no del ejecutable javac. Se compilaron los fuentes actuales a un directorio limpio externo al proyecto con `--release 17 -encoding UTF-8`; se excluyeron los adaptadores *Test.java que requieren JUnit. El classpath de ejecución agregó src/main/resources, necesario para los ejemplos. No se utilizaron clases de target ni binarios del ZIP original.

Los 549 escenarios independientes no equivalen a una ejecución de las 594 pruebas JUnit esperadas. La suite Maven, empaquetado Maven y ejecución del JAR Maven se deben confirmar en el equipo de David. No se instalaron dependencias alternativas ni se cambiaron las versiones del POM.

| Grupo | Salida | Duración s |
| --- | ---: | ---: |
| DirectionPickupScenarios | 0 | 9.301 |
| ElevatorSelectionScenarios | 0 | 1.552 |
| ManagerWorkerScenarios | 0 | 1.23 |
| PendingConfirmationScenarios | 0 | 1.678 |
| PickupTimeEstimatorScenarios | 0 | 0.21 |
| ScalabilityScenarios | 0 | 4.598 |
| ReadFailureScenarios | 0 | 0.105 |
| RequestFileScenarios | 0 | 0.305 |
| LoggingScenarios | 0 | 0.415 |
| SessionLoggingScenarios | 0 | 2.029 |
| ModelRulesScenarios | 0 | 0.135 |
| CabinResetScenarios | 0 | 7.365 |
| ConcurrentAdmissionScenarios | 0 | 0.283 |
| DirectionalAgendaScenarios | 0 | 3.473 |
| ElevatorDwellScenarios | 0 | 0.683 |
| ElevatorMovementScenarios | 0 | 0.197 |
| ElevatorWorkerScenarios | 0 | 0.197 |
| ExecutionEventScenarios | 0 | 0.957 |
| GlobalResetScenarios | 0 | 7.913 |
| ImportReadErrorScenarios | 0 | 1.229 |
| LifecycleRaceScenarios | 0 | 2.939 |
| OrderlyShutdownScenarios | 0 | 1.612 |
| ParallelTimingScenarios | 0 | 4.683 |
| RequestImportScenarios | 0 | 1.727 |
| SimulationControllerScenarios | 0 | 1.641 |
| StopContinuationScenarios | 0 | 5.393 |
| ConfigurationFormScenarios | 0 | 1.322 |
| FileSelectionScenarios | 0 | 1.236 |

## Cobertura nueva

ConfigurationFormScenarios ejecutó 16 escenarios: campos vacíos, cuatro errores simultáneos, configuración válida exacta, mínimos, máximos sin crear trabajadores, overflow int, overflow long, límite seguro de nanos, decimales/notación científica, espacios, corrección, invalidación tras edición, Enter en los cuatro campos, errores nulos con mapa inmutable, coincidencia con fromText y rechazo de acceso fuera de EDT.

Los controles JTextField/JButton/JLabel son reales y se utilizan en el EDT real mediante invokeAndWait. Las pruebas verifican tanto configuración publicada como diagnósticos por campo; no se infiere éxito por ausencia de excepción. Las 40 combinaciones de la prueba de coincidencia se cuentan como un escenario, no como 40 pruebas adicionales.

Se renderizó ConfigurationPanel con cuatro errores a BufferedImage de 900×440 y se inspeccionó visualmente: labels, unidades ms, campos, diagnósticos y botón visibles sin solapamiento. Este render no sustituye la revisión del JFrame nativo, su foco, escala de Windows, scroll o cierre mediante X. No se ejecutó una ventana nativa en este entorno.

`git diff --check` terminó sin errores. El ZIP se comprobó mediante CRC y comparación de bytes de cada entrada con el fuente; todos los .java y recursos versionados están incluidos, junto con los archivos nuevos de T47. Se excluyen .git, target y logs generados.

## Pendiente en Windows

```powershell
mvn "-Dtest=ConfigurationFormTest" test
mvn clean verify
java -jar .\target\elevator-manager-0.1.0-SNAPSHOT.jar
```

Se esperan 16 pruebas específicas y 594 acumuladas, sin fallos, errores u omisiones, con BUILD SUCCESS. Seguir la [revisión visual T47](../implementacion/T47-formulario-configuracion.md). T47 sigue pendiente de confirmación; la fase 8 queda iniciada. Commit sugerido: `T47: crear formulario de configuracion con validacion por campo`.
