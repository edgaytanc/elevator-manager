# T57 — Validación local

- **Fecha:** 31 de octubre de 2026, Guatemala.
- **Base:** T56 `7dfb8e5`; David confirmó 28/838 pruebas Maven, BUILD SUCCESS, staged diff sin observaciones y árbol limpio.
- **Entorno:** Linux, OpenJDK 17.0.20; sin Maven ni escritorio.
- **Compilación:** módulo jdk.compiler con --release 17 -encoding UTF-8; todas las fuentes de producción y todos los escenarios independientes. Directorio externo de clases, sin reutilizar target.
- **Adaptadores JUnit:** no se compilaron/ejecutaron aquí, por no disponer de sus dependencias. Se añadieron 18 @Test; total esperado 856.
- **Ejecución específica:** los 18 escenarios de PausePhaseScenarios terminaron correctamente.
- **Regresión final:** 40 grupos ejecutables satisfactorios, incluidos los 18 casos de T57.

## Alcance y oráculos

La matriz se desarrolla en T57-pausa-en-distintas-fases.md. Incluye pausas en movimiento ascendente/descendente, parada UP/DOWN, reposo antes/después del trabajo, fases mixtas de tres cabinas, cuatro pausas dentro de la misma fase, trabajo de sentidos opuestos, duración mínima de 1 ms, edificio de un piso y dieciséis cabinas libres. Las snapshots completas y el ledger deben permanecer exactamente iguales en PAUSED, incluyendo sus listas/identidades, piso, siguiente piso, dirección, frontera, tiempo restante y progreso derivado.

Se exigen todos los ACK antes de observar PAUSED. Al restaurar fase, el presupuesto no aumenta y la posición/frontera se conserva. Se verifica no terminar antes del resto y no excederlo en más de 250 ms tanto en tiempo activo como en pared tras Resume. Los casos medios/tardíos han consumido más de 250 ms, de modo que reiniciar una duración completa no puede confundirse con la tolerancia. Los casos tempranos complementan ese límite con el chequeo directo de presupuesto no creciente.

Las esperas tienen plazo de diez segundos y timeout JUnit de treinta por caso. Los sleeps son estímulos de prueba fuera de EDT/locks del motor, no implementación de pausa. Se exige cierre real y ausencia de fallo del motor al salir de cada fixture. La prueba de hilos en reposo corre sin sesiones paralelas de otras pruebas, como en la configuración Maven actual. No mide CPU ni usa Thread.State como sustituto de la barrera.

Los resultados precisos están en T57-resultados-locales.md: catorce mediciones de nanosegundos reales, mostradas también como tabla de milisegundos. No se presentan como resultados Maven ni nativos de Windows. La primera ejecución específica fue seguida por una mejora en el anclaje del tiempo de pared de los casos repetidos: empieza antes de enviar la solicitud para incluir la entrega inicial. Se recompilaron las fuentes y la regresión final utiliza ese código definitivo. No hubo cambios de producción.

## Regresión independiente

| Grupo | Salida | Duración s |
| --- | ---: | ---: |
| DirectionPickupScenarios | 0 | 9.318 |
| ElevatorSelectionScenarios | 0 | 1.531 |
| ManagerWorkerScenarios | 0 | 1.221 |
| PendingConfirmationScenarios | 0 | 1.637 |
| PickupTimeEstimatorScenarios | 0 | 0.166 |
| ScalabilityScenarios | 0 | 4.571 |
| ReadFailureScenarios | 0 | 0.111 |
| RequestFileScenarios | 0 | 0.269 |
| LoggingScenarios | 0 | 0.377 |
| SessionLoggingScenarios | 0 | 1.949 |
| ModelRulesScenarios | 0 | 0.118 |
| CabinResetScenarios | 0 | 7.339 |
| ConcurrentAdmissionScenarios | 0 | 0.225 |
| DirectionalAgendaScenarios | 0 | 3.439 |
| ElevatorDwellScenarios | 0 | 0.697 |
| ElevatorMovementScenarios | 0 | 0.197 |
| ElevatorWorkerScenarios | 0 | 0.193 |
| ExecutionEventScenarios | 0 | 0.936 |
| GlobalPauseScenarios | 0 | 6.36 |
| GlobalResetScenarios | 0 | 7.784 |
| ImportReadErrorScenarios | 0 | 1.187 |
| LifecycleRaceScenarios | 0 | 3.04 |
| OrderlyShutdownScenarios | 0 | 1.533 |
| ParallelTimingScenarios | 0 | 4.661 |
| PausePhaseScenarios | 0 | 24.666 |
| PausePriorityScenarios | 0 | 3.802 |
| RequestImportScenarios | 0 | 1.737 |
| SimulationControllerScenarios | 0 | 1.554 |
| StopContinuationScenarios | 0 | 5.34 |
| PausableClockScenarios | 0 | 0.112 |
| PauseCoordinatorScenarios | 0 | 0.086 |
| CabinPanelsScenarios | 0 | 4.571 |
| ConfigurationFormScenarios | 0 | 1.246 |
| EdtSafetyScenarios | 0 | 3.192 |
| EventViewerScenarios | 0 | 2.026 |
| FileSelectionScenarios | 0 | 1.209 |
| GuiJourneyScenarios | 0 | 12.54 |
| HallCallsScenarios | 0 | 2.446 |
| PauseControlsScenarios | 0 | 6.021 |
| SystemControlsScenarios | 0 | 4.703 |

Todos los resultados son de ejecutables Java independientes, no informes Surefire. Se incluyen las pruebas GUI headless de fases anteriores, las prioridades T56 y los casos temporales nuevos. Headless no demuestra foco, ratón, cierre físico mediante X ni geometría de la ventana.

## Revisión y estado de aceptación

Se compararon todas las fuentes de src/main/java con la base T56 de esta entrega: ninguna cambió, ni se añadió/eliminó producción. Se añaden únicamente PausePhaseScenarios.java y PausePhaseTest.java. No se modifica pom.xml ni se incorporan dependencias. Código completo en T57-codigo-completo.md; proyecto íntegro en el ZIP. Las fuentes/documentos nuevos no contienen espacios finales ni líneas vacías extra al EOF; git diff --check correcto para archivos rastreados.

Se registra la evidencia de T56 confirmada por David sin inventar ejecución nativa de Stop/Reset/cierre durante pausa o X. El plan y T57-aceptacion-fase-9.md conservan esos pendientes y la validación Maven de T57. T56/T57 y el cierre formal de fase 9 no se dan por aceptados nativamente en esta entrega; no se inicia T58.

El ZIP se comprueba por CRC y mediante comparación byte a byte de cada entrada con el proyecto. Se excluyen .git, target y logs; se preservan las fuentes, pruebas, recursos, configuración y documentación completa. No se ejecuta un commit ni se intenta recrear el hash de David.

## Validación en Windows

Extraer primero el ZIP en una carpeta temporal y copiar el contenido elevator-manager sobre el repositorio conservando .git. Ejecutar desde la raíz:

```powershell
mvn "-Dtest=PausePhaseTest" test
mvn clean verify
git diff --check
```

Esperado: 18 específicas, 856 totales, cero fallos/errores/omitidas y BUILD SUCCESS. La consola mostrará T57_TIMING. Si aparece un fallo temporal, conservar salida y entorno para distinguir latencia de un reinicio del presupuesto; no ocultarlo aumentando la tolerancia o repitiendo hasta verde.

Completar T57-aceptacion-fase-9.md con las evidencias pendientes y las observaciones nativas. Cuando la aceptación esté confirmada, actualizar el plan, marcar T56/T57 completadas y registrar el cierre de fase 9.

## Commit sugerido

```powershell
git status --short
git diff --check
git add -- src/test/java/com/elevatormanager/simulation/PausePhaseScenarios.java src/test/java/com/elevatormanager/simulation/PausePhaseTest.java docs/01-analisis-y-plan.md docs/implementacion/T56-controles-y-prioridades.md docs/comprobaciones/T56-validacion-local.md docs/comprobaciones/T56-recorrido-gui.md docs/implementacion/T57-pausa-en-distintas-fases.md docs/implementacion/T57-codigo-completo.md docs/comprobaciones/T57-validacion-local.md docs/comprobaciones/T57-resultados-locales.md docs/comprobaciones/T57-aceptacion-fase-9.md
git diff --cached --stat
git diff --cached --check
git diff --cached
git commit -m "T57: probar pausa en distintas fases"
git status --short
git log -1 --oneline
```
