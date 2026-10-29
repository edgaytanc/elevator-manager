# T49 — Validación local

> Cierre funcional confirmado al iniciar T50: 28 pruebas específicas, 72 combinadas y 650 acumuladas, BUILD SUCCESS, commit `2b17a64` y árbol limpio. Los registros acreditan llamadas servidas, Stop/continuación y cierre durante Reset; no un retorno global completo antes del cierre. El informe siguiente conserva su estado original.

- **Fecha:** 2026-10-28, Guatemala.
- **Base:** T48, commit `0bae607`, 622 pruebas Maven y registros de ejecución confirmados por David.
- **Entorno:** Linux, OpenJDK 17.0.20, headless; Maven no instalado.
- **Resultado:** producción y escenarios compilados con objetivo Java 17; 30 grupos independientes con salida 0, 605 escenarios (577 previos y 28 nuevos de T49).

## Compilación y alcance

Se utilizó JavaCompiler del módulo jdk.compiler con `--release 17 -encoding UTF-8` sobre los fuentes actuales y un directorio externo de clases creado para T49. Se excluyeron los adaptadores *Test.java que requieren JUnit. src/main/resources estuvo en el classpath. No se utilizaron binarios del target del usuario ni del ZIP adjunto.

Se repitió la regresión después de reforzar el NumberFormatter: la confirmación del editor debe rechazar decimales sin truncarlos a un piso entero. Los escenarios prueban la confirmación del editor además del clic y verifican habilitación al cambiar desde un extremo a un piso interior. Las repeticiones no se cuentan como escenarios nuevos.

Los 605 escenarios independientes no equivalen a una ejecución de las 650 pruebas JUnit esperadas. Los adaptadores JUnit, construcción Maven y JAR Maven permanecen pendientes en el equipo de David. El POM y las versiones de dependencias no se cambiaron.

| Grupo | Salida | Duración s |
| --- | ---: | ---: |
| DirectionPickupScenarios | 0 | 9.337 |
| ElevatorSelectionScenarios | 0 | 1.54 |
| ManagerWorkerScenarios | 0 | 1.209 |
| PendingConfirmationScenarios | 0 | 1.745 |
| PickupTimeEstimatorScenarios | 0 | 0.228 |
| ScalabilityScenarios | 0 | 4.666 |
| ReadFailureScenarios | 0 | 0.142 |
| RequestFileScenarios | 0 | 0.332 |
| LoggingScenarios | 0 | 0.511 |
| SessionLoggingScenarios | 0 | 2.001 |
| ModelRulesScenarios | 0 | 0.139 |
| CabinResetScenarios | 0 | 7.34 |
| ConcurrentAdmissionScenarios | 0 | 0.21 |
| DirectionalAgendaScenarios | 0 | 3.436 |
| ElevatorDwellScenarios | 0 | 0.736 |
| ElevatorMovementScenarios | 0 | 0.196 |
| ElevatorWorkerScenarios | 0 | 0.186 |
| ExecutionEventScenarios | 0 | 0.981 |
| GlobalResetScenarios | 0 | 7.88 |
| ImportReadErrorScenarios | 0 | 1.195 |
| LifecycleRaceScenarios | 0 | 2.982 |
| OrderlyShutdownScenarios | 0 | 1.59 |
| ParallelTimingScenarios | 0 | 4.665 |
| RequestImportScenarios | 0 | 1.723 |
| SimulationControllerScenarios | 0 | 1.544 |
| StopContinuationScenarios | 0 | 5.522 |
| ConfigurationFormScenarios | 0 | 1.246 |
| FileSelectionScenarios | 0 | 1.199 |
| HallCallsScenarios | 0 | 2.111 |
| SystemControlsScenarios | 0 | 4.639 |

## Evidencia nueva

Los 28 escenarios cubren panel sin sesión, RESET, extremos inferior/superior, piso interior, edificio de un piso, límites del spinner, envío y servicio UP/DOWN mediante manejador real, sentidos opuestos independientes, deduplicación de llamadas activas, recibo ENQUEUED sin afirmar servicio, Stop/continuación, callbacks tardíos tras Reset/Stop/cierre, respuestas en orden inverso, cambio de sesión, texto inválido sin reutilizar piso previo, rechazo decimal/overflow/notación científica, fuera de rango, confirmación de texto válido, extremos escritos, acceso fuera del EDT, edificio con P=Integer.MAX_VALUE sin componentes proporcionales a P, bloqueo inmediato por T48, recorrido compuesto sin archivo y bloqueo al cerrar.

Los controles Swing se usan en EDT real. SimulationController, manejador, cabinas y HallRequest son reales; las pruebas no sustituyen el envío ni fabrican resultados de admisión. Para atención se espera estado SERVED del registro del manejador y se verifica que la asignación de comando procede de ese manejador. Para repetidos se comprueban dos UUID de pulsación asociados a una única identidad canónica activa. Las llamadas opuestas conservan dos canónicas.

Los callbacks diferidos usan una cola que conserva Runnable reales procedentes del futuro de submit. Se aplican explícitamente en EDT tras cambiar estado, época o sesión, y se verifica que no reemplazan el mensaje vigente ni habilitan botones. No se bloquea el EDT esperando trabajo. El escenario de edificio grande no envía una llamada que requiera recorrer miles de millones de pisos; solo valida el tamaño constante del panel y el extremo superior.

La composición completa de T48 se prueba con sesión preparada, Start gráfico, llamada gráfica, atención real y cierre confirmado; no requiere importar un archivo. Todos los controladores esperan closeAsync y awaitTermination fuera del EDT. HallCallPanel no crea timers ni hilos adicionales; los paneles de sistema detienen su Timer en el cierre.

## Revisión visual y paquete

Se renderizó HallCallPanel en RUNNING a 760×160 y se inspeccionó: título compartido, selector y botones sin solapamiento; piso 1 muestra Subir habilitado y Bajar deshabilitado. El render se repitió con el formateador final. No sustituye JFrame, foco/Enter nativos, escala de Windows ni cierre por X; la revisión visual completa queda pendiente.

`git diff --check` terminó sin errores. El documento de código completo se comparó con los cinco Java nuevos/modificados y conserva su texto exacto. Se verificaron CRC y bytes de las entradas del ZIP. Incluye todas las fuentes Java, recursos previos, nuevos escenarios y Markdown; excluye .git, target y registros generados.

## Pendiente en Windows

```powershell
mvn "-Dtest=HallCallsTest" test
mvn "-Dtest=HallCallsTest,SystemControlsTest,ConfigurationFormTest" test
mvn clean verify
java -jar .\target\elevator-manager-0.1.0-SNAPSHOT.jar
```

Se esperan 28 específicas, 72 combinadas y 650 acumuladas, sin fallos, errores u omisiones y BUILD SUCCESS. Seguir el [recorrido visual T49](../implementacion/T49-llamadas-externas-compartidas.md). T49 continúa pendiente de confirmación del usuario; la fase 8 sigue en curso.

Commit sugerido: `T49: crear llamadas externas compartidas por piso`.
