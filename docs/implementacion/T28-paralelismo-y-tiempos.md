# T28 — Comprobar paralelismo y tiempos

- **Proyecto:** Elevator Manager.
- **Versión:** 0.1.0-SNAPSHOT; fuentes compatibles con Java 17.
- **Fecha:** 2026-10-23.
- **Fase:** 4 — Construir el motor concurrente de elevadores.
- **Etiqueta:** CALIDAD.
- **Base confirmada:** `7f926ca` — T27: incorporar eventos de ejecución; árbol limpio.
- **Estado:** pruebas implementadas; ocho escenarios nuevos y 93 escenarios de regresión ejecutados localmente. implementada integración y validación Maven en el proyecto de David.

## 1. Objetivo y alcance

Ejecutar al menos dos cabinas con recorridos simultáneos, comprobar sus trazas y verificar tiempos con tolerancia al planificador del sistema operativo.

Las pruebas usan ElevatorWorker real, dos hilos de cabina independientes y el flujo ExecutionEvent incorporado en T27. Comprueban solapamiento de intervalos registrados por los propios trabajadores y tiempos mínimos de desplazamiento/parada. No se modifica ninguna clase de producción ni se agregan dependencias Maven.

Esta tarea comprueba concurrencia y temporización funcional en una JVM. No afirma ejecución física de instrucciones en dos núcleos, garantías de tiempo real ni ausencia de toda posible carrera en cualquier ejecución futura.

## 2. Archivos entregados

| Ruta desde la raíz Maven | Uso |
| --- | --- |
| src/test/java/com/elevatormanager/simulation/ParallelTimingScenarios.java | Ocho escenarios ejecutables con hilos reales y clase main. |
| src/test/java/com/elevatormanager/simulation/ParallelTimingTest.java | Ocho métodos JUnit que ejecutan los mismos escenarios. |
| docs/comprobaciones/T28-traza-local.txt | Salida auténtica de la ejecución local directa, incluida como evidencia de referencia. |
| docs/implementacion/T28-paralelismo-y-tiempos.md | Diseño de la comprobación, resultados y procedimiento de integración/cierre. |

El ZIP contiene exactamente estas cuatro rutas, sin carpeta contenedora, archivos compilados, configuración alternativa ni versiones antiguas de producción. Extraer en la raíz de elevator-manager.

## 3. Diseño de las comprobaciones

Cada escenario crea dos trabajadores con una misma SimulationConfig, IDs 1 y 2 y época 0. Cada trabajador conserva su propio estado, buzón, hilo y cola de eventos. El fixture cierra ambos y espera su terminación real; también verifica que no registraron fallos.

Cuando se requieren solicitudes simultáneas, dos productores usan CountDownLatch: primero notifican que están listos y después se liberan con una misma barrera. No se exige que el sistema operativo ejecute ambas instrucciones de envío en el mismo instante.

El hilo de prueba consume las colas con un plazo acotado. Puede leer primero toda la traza de A y luego la de B: esas lecturas no son la medición. Los tiempos observados son referenceNanos capturados por cada actor, con System.nanoTime en la misma JVM. No se usa Thread.sleep ni sondeo activo para esperar avances.

Para cada traza verificada se comprueba:

- Secuencia local consecutiva, revisión que no retrocede y tiempos monotónicos.
- Pisos dentro del edificio y cambio del piso lógico únicamente en ARRIVED.
- Cada DEPARTED indica el piso adyacente según su sentido y conserva el piso lógico de salida.
- Cada ARRIVED corresponde a una salida anterior.
- Las paradas abren alineadas y terminan en el mismo piso.
- Los tramos y paradas terminados respetan sus intervalos temporales.

Una cola puede contener un intervalo abierto al cerrar el fixture: cerrar no inventa una llegada ni un fin de parada. Los escenarios que verifican una duración completa esperan explícitamente ARRIVED o STOP_ENDED antes de evaluarla.

## 4. Solapamiento y tolerancias

Para un recorrido, el intervalo observado comienza con su primera DEPARTED y termina en su última ARRIVED. Los intervalos de ambas cabinas deben tener intersección de duración estrictamente positiva:

```text
fin_interseccion = min(fin_A, fin_B)
inicio_interseccion = max(inicio_A, inicio_B)
solapamiento = fin_interseccion - inicio_interseccion > 0
```

El cálculo utiliza diferencias respecto de un origen común, no fechas civiles ni valores absolutos de nanoTime entre procesos diferentes. Una duración total corta por sí sola no demuestra concurrencia: se exige la intersección de los intervalos reales.

Para cada tramo y parada completa se acepta:

```text
duracion_configurada <= duracion_observada <= duracion_configurada + 2000 ms
```

La tolerancia es asimétrica: el planificador puede retrasar un despertar, pero no autoriza anticipar la llegada o terminar antes la parada. Los 2000 ms de margen superior son una cota funcional amplia para ejecución en Windows, máquinas virtuales o equipos compartidos; no constituyen un objetivo de rendimiento.

En los tiempos acumulados se suman las duraciones nominales y se permite ese margen por cada transición temporizada involucrada: tres tramos para el recorrido de 1 a 4, o dos tramos y una parada para el recorrido de 1 a 3 con atención en piso 2. No se usa igualdad exacta de milisegundos ni se modifica la configuración de producción para hacer pasar las pruebas.

La espera de un evento tiene un plazo global de ocho segundos por operación until; los productores también tienen esperas acotadas. Si un equipo saturado supera una cota, la prueba falla con trazas y valores observados. Debe investigarse la causa antes de aumentar el margen.

## 5. Escenarios de aceptación

| Escenario | Comprobación |
| --- | --- |
| simultaneousJourneysOverlap | Dos cabinas viajan de 1 a 4; sus intervalos se solapan, cada una registra tres llegadas y consume al menos tres tiempos de movimiento. |
| oppositeJourneysOverlap | Se posiciona A en piso 4; después A baja a 1 mientras B sube a 4. Los recorridos opuestos se solapan y mantienen sus sentidos. |
| anotherCabinMovesDuringDwell | A abre una parada de 1000 ms en piso 1. B sale después y completa un viaje a piso 2 antes de terminar esa parada. |
| shortJourneyFinishesDuringLongJourney | A viaja a 5 y B a 2; los viajes se solapan y B completa antes del destino final de A. |
| intermediateStopAddsItsFullDuration | A atiende piso 2 y continúa a 3; se verifica el tiempo completo de parada entre tramos y el mínimo acumulado de 520 ms. |
| mailboxWakeupsDoNotShortenOrRestartLeg | Durante un tramo se envían 32 retransmisiones; hay un solo tramo, una sola finalización y su duración permanece dentro del intervalo permitido. |
| closingOneCabinDoesNotStopTheOther | A termina por cierre local; después B recibe y atiende un recorrido, incluida su parada completa. |
| singleFloorStopsOverlapWithoutMovement | En un edificio de un piso ambas cabinas atienden paradas solapadas, con duración completa y sin salidas ni llegadas ficticias. |

Las trazas representan el solapamiento de actividad de cabinas. No se reclama orden global para sequence: cada cabina tiene su propia secuencia.

## 6. Evidencia local obtenida

Entorno: OpenJDK 17.0.20, Linux; compilación con --release 17. Ejecución directa de ParallelTimingScenarios contra la producción confirmada hasta T27.

Resultados de la ejecución incluida en docs/comprobaciones/T28-traza-local.txt:

| Observación | Resultado |
| --- | ---: |
| Solapamiento de recorridos de 1 a 4 | 360,579 ms |
| Recorrido A de tres tramos; mínimo 360 ms | 360,591 ms |
| Recorrido B de tres tramos; mínimo 360 ms | 360,583 ms |
| Solapamiento de recorridos en sentidos opuestos | 300,362 ms |
| Solapamiento de viaje corto y viaje largo | 180,226 ms |
| Parada antes de continuar; mínimo 160 ms | 160,196 ms |
| Dos tramos más parada intermedia; mínimo 520 ms | 520,541 ms |
| Solapamiento de paradas en edificio de un piso | 250,169 ms |

Los números se muestran redondeados a tres decimales; las aserciones utilizan nanosegundos enteros sin redondear. Son evidencia local, no resultados medidos en el equipo de David ni valores que deban repetirse exactamente.

El archivo contiene líneas T28_CHECK con mediciones y T28_TRACE con eventos agrupados por escenario y cabina. t_ms es el tiempo relativo al origen común del escenario. Cada evento incluye cabina, secuencia, tipo, piso lógico, siguiente piso, dirección, fase y commandId cuando corresponde. Los grupos por cabina no alteran el instante registrado ni intentan representar un orden global de lectura.

La última línea debe indicar: T28: 8 escenarios satisfactorios.

## 7. Regresión local

| Grupo ejecutado | Escenarios satisfactorios |
| --- | ---: |
| ParallelTimingScenarios, T28 | 8 |
| ExecutionEventScenarios, T27 | 20 |
| ConcurrentAdmissionScenarios, T26 | 21 |
| DirectionalAgendaScenarios, T25 | 20 |
| ElevatorDwellScenarios, T24 | 15 |
| ElevatorMovementScenarios, T23 | 10 |
| ElevatorWorkerScenarios, T22 | 7 |
| Total | 101 |

No se ejecutó Maven en este entorno. Las 52 pruebas restantes de modelo/protocolo deben confirmarse junto con estas en la regresión del proyecto de David.

## 8. Validación en PowerShell

Desde D:\Users\David\Desktop\workspace\java\elevator-manager, después de extraer el ZIP:

```powershell
mvn "-Dtest=ElevatorWorkerTest,ElevatorMovementTest,ElevatorDwellTest,DirectionalAgendaTest,ConcurrentAdmissionTest,ExecutionEventTest,ParallelTimingTest" test
mvn clean verify
```

Esperado si no se agregaron otras pruebas: 101 pruebas específicas y 153 en la regresión completa; cero fallos, errores y omisiones, con BUILD SUCCESS. ParallelTimingTest aporta ocho pruebas nuevas.

Para ejecutar únicamente T28 y conservar una evidencia propia del entorno Windows:

```powershell
New-Item -ItemType Directory -Force .\logs | Out-Null
mvn -Dtest=ParallelTimingTest test 2>&1 | Tee-Object -FilePath .\logs\t28-paralelismo-y-tiempos.log
```

Este comando debe finalizar con ocho pruebas y BUILD SUCCESS. logs permanece como salida local conforme al .gitignore existente. No sobrescribe la evidencia local de referencia entregada en docs/comprobaciones.

Después de compilar también puede ejecutarse la clase de escenarios sin JUnit, en una JVM separada:

```powershell
java -cp "target\classes;target\test-classes" com.elevatormanager.simulation.ParallelTimingScenarios
```

## 9. Cierre de tarea y fase

Después de ambos BUILD SUCCESS:

1. Marcar T28 como [x] en docs/01-analisis-y-plan.md.
2. Actualizar el estado de este documento a implementada y validada, registrando los resultados Maven reales y su fecha.
3. Confirmar que T22–T28 están marcadas como terminadas. Esto permite cerrar la fase 4; no completa tareas de la fase 5.
4. Revisar y crear el commit de T28:

```powershell
git status --short
git add -- src/test/java/com/elevatormanager/simulation/ParallelTimingScenarios.java src/test/java/com/elevatormanager/simulation/ParallelTimingTest.java docs/comprobaciones/T28-traza-local.txt docs/implementacion/T28-paralelismo-y-tiempos.md docs/01-analisis-y-plan.md
git diff --cached --check
git diff --cached
git commit -m "T28: comprobar paralelismo y tiempos"
git status --short
git log -1 --oneline
```

El ZIP no sobrescribe el plan general, cuya versión vigente se mantiene en el repositorio de David. Tampoco contiene modificaciones de producción: las pruebas confirman el motor integrado hasta T27.
