# T55 — Validación local

- **Fecha:** 29 de octubre de 2026, Guatemala.
- **Base confirmada por David:** T54 `4e34f89`; 17 específicas y 781 totales, BUILD SUCCESS y árbol limpio.
- **Entorno de esta entrega:** Linux, OpenJDK 17.0.20, sin Maven ni escritorio.
- **Compilación:** módulo jdk.compiler, --release 17 -encoding UTF-8. Todas las fuentes de producción y todos los escenarios independientes, sin reutilizar target. Adaptadores JUnit excluidos por no disponer de sus dependencias.
- **T55:** 12 escenarios de barrera y 17 de sesión, todos satisfactorios.
- **Regresión completa independiente:** 37 grupos satisfactorios, con recursos del proyecto en el classpath.
- **JUnit esperado:** 29 pruebas específicas y 810 anotaciones @Test totales. No se ejecutó Maven/JUnit aquí.

## Escenarios de barrera

| Caso | Criterio |
| --- | --- |
| Participantes inválidos | Rechazo de cantidades inferiores a manejador + una cabina |
| Barrera parcial | Dos de tres ACK no completan el futuro ni permiten Resume |
| Reloj congelado antes de ACK | El tiempo no avanza desde PAUSING |
| Captura retenida con latches | No reconocer hasta que termine la publicación local |
| Cancelación pública | Cancelar una copia no cancela la barrera privada |
| Pausa repetida | No duplica la generación |
| Doce generaciones | Los ACK anteriores no completan una nueva pausa |
| Cierre parcial | Cancela sin esperar al actor ausente y libera al presente |
| Actor ya terminado | Rechaza iniciar una pausa inválida |
| Fallo de participante | Resuelve error inmediatamente sin esperar los demás ACK |
| ID fuera de rango | Rechazo explícito |
| Checkpoint activo | No genera capturas ni pausas artificiales |

## Escenarios de sesión

1. Rechazo de pausa/reanudación fuera de RUNNING/PAUSED.
2. Barrera en reposo con una cabina.
3. Barrera en reposo con seis cabinas.
4. Tramo parcial: piso anterior, siguiente piso, destino, tiempo restante y luz conservados; no hay llegadas durante pausa; mismo comando atendido tras Resume.
5. Parada parcial: fase DWELLING suspendida y presupuesto restante conservado.
6. Dos cabinas en movimiento con una misma referencia temporal congelada.
7. Controles idempotentes y Start distinto de Resume.
8. Veinte ciclos rápidos sobre los mismos objetos Thread, sesión y época.
9. Conservación de ledger, solicitudes internas y llamada externa; atención posterior sin perder identidades.
10. Rechazo de solicitudes manuales e importación durante pausa.
11. Política T55: Stop/Reset pausados rechazados; después de Resume vuelven a funcionar.
12. Peticiones desde EDT sin esperar en ese hilo.
13. Cancelación de futuro público sin dejar la sesión en PAUSING.
14. Veinticuatro solicitudes concurrentes comparten una generación.
15. Cierre de sesión pausada sin Resume manual ni fallo de motor.
16. Carrera pausa/cierre con ambos futuros resueltos.
17. Sesiones con dominios de pausa independientes.

## Tiempos y límites

El reloj de producción se usa con hilos reales. Las pruebas de movimiento y parada esperan una fracción del presupuesto y luego pausan durante 850 ms, más que su duración completa de 800 ms. Se exige igualdad exacta de los snapshots durante pausa. Al reanudar se exige que el restante publicado no aumente y que ARRIVED/STOP_ENDED consuma por lo menos el restante capturado en tiempo activo. No se impone un límite superior estrecho de latencia del sistema operativo ni se cuenta el tiempo pausado como movimiento. El tiempo restante y progress publicados son estables; no se reinician las marcas del modelo.

Los tests permiten hasta ocho segundos para operaciones/eventos y tres para observar bloqueo/terminación de hilos de barrera. Esos límites son de infraestructura, no tolerancias de tiempo simulado. Los adaptadores JUnit tienen timeout de treinta segundos por prueba. Los fixtures cierran controladores y compuertas e interrumpen/esperan sus hilos en finally/try-with-resources.

La prueba retenida con latches demuestra determinísticamente el reconocimiento parcial sin depender de capturar PAUSING por sondeo rápido. Los escenarios de integración comprueban que PAUSED tiene N+1 reconocimientos. El escenario de carrera con cierre no presupone qué operación ganará: ambas deben resolverse y el cierre real debe finalizar sin error.

## Regresión independiente

| Grupo | Salida | Duración s |
| --- | ---: | ---: |
| DirectionPickupScenarios | 0 | 9.303 |
| ElevatorSelectionScenarios | 0 | 1.556 |
| ManagerWorkerScenarios | 0 | 1.199 |
| PendingConfirmationScenarios | 0 | 1.667 |
| PickupTimeEstimatorScenarios | 0 | 0.167 |
| ScalabilityScenarios | 0 | 4.494 |
| ReadFailureScenarios | 0 | 0.106 |
| RequestFileScenarios | 0 | 0.241 |
| LoggingScenarios | 0 | 0.354 |
| SessionLoggingScenarios | 0 | 1.94 |
| ModelRulesScenarios | 0 | 0.113 |
| CabinResetScenarios | 0 | 7.327 |
| ConcurrentAdmissionScenarios | 0 | 0.209 |
| DirectionalAgendaScenarios | 0 | 3.441 |
| ElevatorDwellScenarios | 0 | 0.688 |
| ElevatorMovementScenarios | 0 | 0.196 |
| ElevatorWorkerScenarios | 0 | 0.165 |
| ExecutionEventScenarios | 0 | 0.935 |
| GlobalPauseScenarios | 0 | 6.327 |
| GlobalResetScenarios | 0 | 7.781 |
| ImportReadErrorScenarios | 0 | 1.196 |
| LifecycleRaceScenarios | 0 | 2.955 |
| OrderlyShutdownScenarios | 0 | 1.548 |
| ParallelTimingScenarios | 0 | 4.627 |
| RequestImportScenarios | 0 | 1.705 |
| SimulationControllerScenarios | 0 | 1.522 |
| StopContinuationScenarios | 0 | 5.497 |
| PausableClockScenarios | 0 | 0.105 |
| PauseCoordinatorScenarios | 0 | 0.087 |
| CabinPanelsScenarios | 0 | 4.537 |
| ConfigurationFormScenarios | 0 | 1.252 |
| EdtSafetyScenarios | 0 | 3.156 |
| EventViewerScenarios | 0 | 1.956 |
| FileSelectionScenarios | 0 | 1.191 |
| GuiJourneyScenarios | 0 | 13.138 |
| HallCallsScenarios | 0 | 2.257 |
| SystemControlsScenarios | 0 | 4.681 |

Estos resultados son de ejecutables Java independientes, no un informe Surefire. Incluyen los escenarios de T54 y los recorridos GUI headless de T53. No acreditan pulsaciones físicas ni nuevos botones de pausa en escritorio: los controles visuales corresponden a T56.

## Revisión de cambios

Diez fuentes Java nuevas/modificadas, todas completas en T55-codigo-completo.md y en sus rutas Maven. El cambio no requiere dependencias nuevas ni modifica pom.xml. Se conserva la herencia de Request, los hilos del manejador y de cada cabina, los canales fiables y el planificador existente. Los eventos de ejecución y sus snapshots comparten el reloj activo en sesiones coordinadas; sus marcas no se comparan con nanoTime directo ni con las de otra sesión.

Se corrige la línea vacía extra del EOF de T54-codigo-completo.md y se registra el cierre T54 confirmado por David. No se modifica la configuración de finales de línea del repositorio ni la traza histórica T28. Las fuentes y documentos T55 se revisan sin espacios finales ni líneas vacías extra al EOF; git diff --check sin errores en archivos rastreados.

El ZIP se compara entrada por entrada contra los bytes del proyecto y se comprueba su CRC. Contiene el proyecto completo: fuentes, pruebas, recursos y documentación, sin .git, target ni logs. No se realiza ningún commit ni se modifica el repositorio local de David desde este entorno.

## Validación pendiente en Windows

Extraer el ZIP a una carpeta temporal y copiar el contenido de elevator-manager sobre el repositorio local conservando su .git. Desde la raíz:

```powershell
mvn "-Dtest=PauseCoordinatorTest,GlobalPauseTest" test
mvn clean verify
git diff --check
```

Esperado: 29 específicas y 810 totales, sin fallos, errores ni omitidas; BUILD SUCCESS. T55 no añade botones Swing: la coordinación se comprueba mediante el controlador y los actores reales de estas pruebas. Si se inicia el JAR, continúan disponibles los controles de la fase 8.

Después de confirmar los resultados, marcar T55 completada en el plan y registrar el resultado de Maven en esta acta. Mantener T56 y T57 pendientes.

## Commit sugerido

```powershell
git status --short
git diff --check
git add -- src/main/java/com/elevatormanager/dispatch/ManagerWorker.java src/main/java/com/elevatormanager/dispatch/PickupTimeEstimator.java src/main/java/com/elevatormanager/simulation/ElevatorWorker.java src/main/java/com/elevatormanager/simulation/ExecutionEvent.java src/main/java/com/elevatormanager/simulation/SimulationController.java src/main/java/com/elevatormanager/simulation/time/PauseCoordinator.java src/test/java/com/elevatormanager/simulation/GlobalPauseScenarios.java src/test/java/com/elevatormanager/simulation/GlobalPauseTest.java src/test/java/com/elevatormanager/simulation/time/PauseCoordinatorScenarios.java src/test/java/com/elevatormanager/simulation/time/PauseCoordinatorTest.java docs/01-analisis-y-plan.md docs/comprobaciones/T54-validacion-local.md docs/implementacion/T54-control-temporal-pausable.md docs/implementacion/T54-codigo-completo.md docs/comprobaciones/T55-validacion-local.md docs/implementacion/T55-pausa-global-y-reanudacion.md docs/implementacion/T55-codigo-completo.md
git diff --cached --stat
git diff --cached --check
git diff --cached
git commit -m "T55: coordinar pausa global y reanudacion"
git status --short
git log -1 --oneline
```


## Cierre confirmado por David — 29 de octubre de 2026

PauseCoordinatorTest y GlobalPauseTest: 29/0/0/0; mvn clean verify: 810/0/0/0. Ambos BUILD SUCCESS. Staged diff sin observaciones; commit `dd9dc82` (T55: coordinar pausa global y reanudacion); git status --short sin salida. Los mensajes ERROR_LOGGING pertenecen a pruebas de fallos inyectados. T55 queda cerrada; las instrucciones y la política descritas arriba corresponden a la entrega original. T56 habilita controles y prioridades durante pausa.
