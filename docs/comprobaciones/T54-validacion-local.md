# T54 — Validación local

- **Fecha:** 28 de octubre de 2026, Guatemala.
- **Base:** T53 `463d302`, proyecto actualizado recibido de David.
- **Entorno:** Linux, OpenJDK 17.0.20, sin Maven ni escritorio.
- **Compilación:** módulo jdk.compiler, --release 17 -encoding UTF-8; todas las fuentes de producción y todos los escenarios sin dependencia de JUnit. Directorio de clases externo, sin reutilizar target.
- **T54:** 17 escenarios satisfactorios, con hilo real cuando corresponde y fuente monotónica controlada para presupuestos exactos.
- **Regresión:** 35 grupos ejecutables satisfactorios. Esto no equivale a ejecutar Maven/JUnit.
- **JUnit:** se añaden 17 adaptadores; 781 anotaciones @Test en el proyecto completo. No se compilaron ni ejecutaron los adaptadores JUnit aquí.

## Cobertura específica

1. Origen lógico cero y avance monotónico.
2. Exclusión del tiempo físico pausado.
3. Idempotencia de pausa/reanudación.
4. Cien ciclos sin pérdida de presupuesto.
5. Cruce del límite con signo de nanoTime.
6. Duraciones nulas, negativas, desbordadas y marcas futuras.
7. Duración cero o cumplida durante pausa.
8. Espera pausada sin vencimiento por tiempo físico.
9. Espera parcial con consumo exacto del resto.
10. Despertar de todos los consumidores.
11. Interrupción de espera pausada.
12. Interrupción de espera temporizada.
13. Reintento del presupuesto original tras interrupción.
14. Cierre durante esperas pausadas y temporizadas, con dos consumidores.
15. Rechazo de nuevas operaciones tras cierre idempotente.
16. Movimiento y parada de ElevatorState conservados al congelar el reloj.
17. Espera real con System.nanoTime sin vencimiento anticipado.

Las comprobaciones de presupuesto usan nanosegundos exactos de una fuente controlada; no requieren tolerancia numérica. Los hilos tienen hasta tres segundos para confirmar bloqueo o terminación; no es una tolerancia sobre tiempo simulado. El caso de reloj real exige al menos cinco milisegundos activos y no fija límite superior estrecho sujeto al planificador. JUnit aplica timeout de treinta segundos por prueba. Los fixtures interrumpen y esperan todos sus hilos incluso si falla una comprobación.

## Regresión independiente

| Grupo | Salida | Duración s |
| --- | ---: | ---: |
| DirectionPickupScenarios | 0 | 9.333 |
| ElevatorSelectionScenarios | 0 | 1.606 |
| ManagerWorkerScenarios | 0 | 1.237 |
| PendingConfirmationScenarios | 0 | 1.684 |
| PickupTimeEstimatorScenarios | 0 | 0.177 |
| ScalabilityScenarios | 0 | 4.776 |
| ReadFailureScenarios | 0 | 0.095 |
| RequestFileScenarios | 0 | 0.265 |
| LoggingScenarios | 0 | 0.387 |
| SessionLoggingScenarios | 0 | 2.011 |
| ModelRulesScenarios | 0 | 0.108 |
| CabinResetScenarios | 0 | 7.352 |
| ConcurrentAdmissionScenarios | 0 | 0.217 |
| DirectionalAgendaScenarios | 0 | 3.436 |
| ElevatorDwellScenarios | 0 | 0.687 |
| ElevatorMovementScenarios | 0 | 0.195 |
| ElevatorWorkerScenarios | 0 | 0.173 |
| ExecutionEventScenarios | 0 | 0.933 |
| GlobalResetScenarios | 0 | 7.842 |
| ImportReadErrorScenarios | 0 | 1.214 |
| LifecycleRaceScenarios | 0 | 3.065 |
| OrderlyShutdownScenarios | 0 | 1.591 |
| ParallelTimingScenarios | 0 | 4.686 |
| RequestImportScenarios | 0 | 1.719 |
| SimulationControllerScenarios | 0 | 1.54 |
| StopContinuationScenarios | 0 | 5.524 |
| PausableClockScenarios | 0 | 0.133 |
| CabinPanelsScenarios | 0 | 4.63 |
| ConfigurationFormScenarios | 0 | 1.252 |
| EdtSafetyScenarios | 0 | 3.17 |
| EventViewerScenarios | 0 | 1.998 |
| FileSelectionScenarios | 0 | 1.287 |
| GuiJourneyScenarios | 0 | 13.226 |
| HallCallsScenarios | 0 | 2.343 |
| SystemControlsScenarios | 0 | 4.73 |

El primer lanzamiento local de ReadFailureScenarios no tenía resources en el classpath y notificó que faltaba 01-minimo.txt. Se corrigió el comando para incluir src/main/resources y src/test/resources; el grupo y todos los restantes finalizaron correctamente sin cambiar el código del proyecto. Los seis grupos ya satisfactorios se conservaron. No se atribuye esa incidencia al parser ni al nuevo reloj.

## Revisión de entrega

El cambio de producción añade únicamente PausableClock; no modifica la semántica actual de los actores, Stop ni GUI. No se cambia pom.xml ni se añaden dependencias. El plan conserva T53 marcada como completada y registra su cierre confirmado. T54 permanece sin marcar hasta la validación local de David. Git diff --check correcto; se revisaron también espacios finales y saltos finales de las fuentes nuevas.

El ZIP contiene el proyecto completo con fuentes, pruebas, recursos y documentación. No contiene .git, target ni logs. Se verifica cada entrada contra sus bytes de origen. Se conserva la estructura Maven y el historial documental previo. La documentación antigua T53 refleja el estado de aquella entrega; el plan actual registra el cierre posterior comunicado por David.

## Validación en Windows

Extraer primero a una carpeta temporal. Copiar el contenido de elevator-manager sobre el repositorio local conservando su .git. Ejecutar desde la raíz del proyecto:

```powershell
mvn "-Dtest=PausableClockTest" test
mvn clean verify
git diff --check
```

Esperado: 17 pruebas específicas y 781 totales, cero fallos/errores/omisiones y BUILD SUCCESS. No hace falta buscar botones de pausa: la integración gráfica corresponde a T56. Como comprobación del comportamiento base, se puede iniciar el JAR y repetir Start, solicitud, Stop, continuación, Reset y cierre:

```powershell
java -jar .\target\elevator-manager-0.1.0-SNAPSHOT.jar
```

Tras confirmar las pruebas, marcar T54 como completada y registrar los resultados en esta acta y en el plan. La fase 9 continúa con T55; no se considera cerrada por esta entrega.

## Commit sugerido

```powershell
git status --short
git diff --check
git add -- src/main/java/com/elevatormanager/simulation/time/PausableClock.java src/test/java/com/elevatormanager/simulation/time/PausableClockScenarios.java src/test/java/com/elevatormanager/simulation/time/PausableClockTest.java docs/01-analisis-y-plan.md docs/implementacion/T54-control-temporal-pausable.md docs/implementacion/T54-codigo-completo.md docs/comprobaciones/T54-validacion-local.md
git diff --cached --stat
git diff --cached --check
git diff --cached
git commit -m "T54: implementar control temporal pausable"
git status --short
git log -1 --oneline
```
