# T30 — Implementar estimación de tiempo de recogida

- **Proyecto:** Elevator Manager.
- **Fecha:** 2026-10-26.
- **Versión:** 0.1.0-SNAPSHOT; objetivo Java 17.
- **Fase:** 5 — Implementar el Elevator Manager y la asignación.
- **Base:** T29 validada por David con 165 pruebas, BUILD SUCCESS y commit `426665b`. El cierre documental posterior fue confirmado por David; su hash no se ha comunicado.
- **Estado:** implementación realizada; 101 tests locales satisfactorios. Integración en el proyecto de David, pendiente de validación Maven y commit.

## 1. Objetivo y alcance

Proyectar el tiempo hasta que una cabina pueda recoger a una persona en el piso y sentido solicitados, considerando piso lógico, tramo restante, dirección, frontera comprometida, paradas pendientes, espera activa y regreso necesario.

El resultado es una estimación bajo agenda estable y admisión inmediata, no una reserva ni una confirmación de servicio. T31 conectará estos costos a la selección y desempate del manejador. T29 conserva por ahora su reparto circular. La deduplicación global y el seguimiento de confirmaciones corresponden a T32.

## 2. Archivos entregados

| Ruta | Acción | Responsabilidad |
| --- | --- | --- |
| src/main/java/com/elevatormanager/dispatch/PickupTimeEstimator.java | Nuevo | Proyección pura y desglose de tiempo. |
| src/main/java/com/elevatormanager/simulation/PlanningSnapshot.java | Nuevo | Captura inmutable de agenda y contexto de planificación. |
| src/main/java/com/elevatormanager/simulation/ElevatorWorker.java | Reemplazar completo | Publicar atómicamente estado, orden de admisión y barrido completado. |
| src/test/java/com/elevatormanager/dispatch/PickupTimeEstimatorScenarios.java | Nuevo | Veinte escenarios, incluidos sesenta contrastes con el modelo físico. |
| src/test/java/com/elevatormanager/dispatch/PickupTimeEstimatorTest.java | Nuevo | Veinte pruebas JUnit que ejecutan los escenarios. |
| docs/implementacion/T30-estimacion-tiempo-recogida.md | Nuevo | Contrato, decisiones, validación y cierre. |
| docs/comprobaciones/T30-validacion-local.md | Nuevo | Evidencia local de ejecución. |

El ZIP conserva rutas desde la raíz Maven y contiene versiones completas de todos los archivos de T30. Se incorpora sobre el proyecto existente; no incluye otro pom.xml ni reemplaza el proyecto. Se mantiene la herencia Request/HallRequest/CabinRequest y el hilo independiente de cada trabajador.

## 3. Por qué se agrega PlanningSnapshot

ElevatorSnapshot conserva por separado destinos internos y llamadas externas. No contiene el orden conjunto de admisión ni el completedSweep del actor. Ambos afectan DirectionalAgenda.selectTarget: un empate se resuelve por admisión, y al completar un barrido se compromete el extremo del regreso cuando hay pendientes detrás.

Reconstruir ese orden a partir de createdAt, concatenar tipos o ignorar el barrido produciría rutas diferentes del motor. PlanningSnapshot contiene el ElevatorSnapshot original, admissionOrder y completedSweep, capturados juntos por ElevatorWorker. Valida que los pendientes coincidan en identidad y contenido y copia la lista para mantenerla inmutable.

ElevatorWorker publica una AtomicReference de esa captura. snapshot() conserva su firma y devuelve su componente ElevatorSnapshot; planningSnapshot() permite obtener la captura completa en una sola lectura. No se cambian los componentes ni constructores del record ElevatorSnapshot. Sus consumidores y pruebas anteriores siguen siendo compatibles. Eventos, Result, Service, buzón, movimiento, deduplicación y ciclo de vida conservan sus contratos.

## 4. API del estimador

PickupTimeEstimator es una clase sin estado compartido mutable. Cada invocación crea su propia proyección y no envía comandos, no altera cabinas, no duerme ni lee el reloj por su cuenta.

| Método | Referencia temporal |
| --- | --- |
| estimate(PlanningSnapshot, HallRequest) | Desde referenceNanos de la captura. |
| estimate(PlanningSnapshot, HallRequest, long observedAtNanos) | Desde un instante posterior del mismo nanoTime de JVM. |

Estimate contiene status, pickupNanos opcional, observedAtNanos, lista inmutable de steps y detail. Sus estados son ESTIMATED, UNAVAILABLE e INVALID_REQUEST. Un resultado sin tiempo no debe compararse como si tuviera costo cero.

Step contiene tipo, piso de origen/destino, sentido y duración en nanosegundos. Los tipos son REMAINING_LEG, MOVEMENT, WAIT y PICKUP. La suma de duraciones coincide con pickupNanos; PICKUP tiene duración cero y señala el instante de servicio proyectado.

Se usa BigInteger para acumular y comparar tiempos exactos. Configuraciones válidas con muchos pisos pueden superar Long.MAX_VALUE nanosegundos. No se satura el costo ni se convierte a Duration mediante un long que podría desbordarse. T31 podrá comparar los valores mediante compareTo.

## 5. Reglas de proyección

1. Validar edificio, época y contenido de una identidad pendiente. Una cabina HELD, TERMINATED o en RESET_RETURN no produce costo.
2. Incorporar hipotéticamente la llamada al final de admisión. Si ya existe una llamada funcionalmente equivalente pendiente, usar su canónica y su posición original sin agregar otra parada.
3. Si la cabina está MOVING, terminar primero el tramo adyacente con su tiempo restante. El piso que acaba de dejar no se atiende inmediatamente.
4. Si está DWELLING y la llamada coincide con piso y sentido de la parada todavía abierta, la recogida proyectada es inmediata. Si no coincide, sumar la espera restante.
5. Servir destinos internos y llamadas compatibles al pasar. Varios pedidos compatibles en una parada comparten una sola espera.
6. Mantener la frontera comprometida: una llamada externa no fuerza inversión ni extensión anticipada. Considerar el cambio de sentido y el retorno cuando hagan falta.
7. Al terminar un barrido, seleccionar el próximo objetivo con DirectionalAgenda y el completedSweep capturado. Los destinos internos amplían la frontera igual que ElevatorState.beginJourney.
8. Detener el cómputo al comenzar el servicio de la llamada objetivo. No sumar la duración de la parada posterior a recoger.

La proyección salta entre paradas y fronteras: no itera por cada piso. Depende del número de pendientes, no del tamaño numérico del edificio. Tiene un límite de transiciones proporcional a la agenda para detectar capturas incoherentes. Estas producen una excepción en lugar de un tiempo inventado.

## 6. Ejemplos calculables

Configuración común: 100 ms entre pisos y 50 ms por parada.

| Escenario | Tiempo proyectado |
| --- | ---: |
| Libre en 1; llamada en 4 DOWN | 300 ms |
| Sale de 1 hacia 5; quedan 60 ms del primer tramo; llamada en 3 UP | 160 ms |
| Caso anterior con destino interno y llamada UP en piso 2 | 210 ms: ambos pedidos comparten una parada |
| Sale de 1 hacia destino interno 5; quedan 60 ms; llamada en 3 DOWN | 610 ms: 360 hasta 5 + 50 de parada + 200 de retorno |
| Cabina cercana en 2 comprometida hacia 5; quedan 60 ms; llamada en 3 DOWN | 510 ms |
| Cabina lejana libre en 1 para esa misma llamada | 200 ms |
| Llamada compatible con parada abierta en su propio piso | 0 ms |

Así, cercanía física y tiempo de recogida son medidas distintas. El estimador permite justificar esa diferencia sin prometer optimalidad global.

## 7. Tiempo, disponibilidad y límites de la estimación

La sobrecarga con observedAtNanos descuenta la edad solamente del restante de la fase capturada, limitado a cero. Si el tramo ya debería haber vencido, el costo restante es cero pero no se declara una llegada real ni se recorren fases futuras usando ese retraso. Solo los eventos del actor confirman ejecución. Se rechaza una observación anterior a la captura o fuera del intervalo representable de nanoTime; se admite su wrap normal mediante diferencias.

No se incluye latencia de buzones, carga del planificador, nuevas entradas posteriores, cambios de época, cierre o reset durante la proyección. Las agendas ya admitidas son visibles; los comandos todavía en el buzón no lo son. No se consulta el historial de solicitudes ya atendidas: el llamador debe usar una identidad de solicitud nueva o resolver el historial en T32.

La captura no contiene Lifecycle del trabajador. T31 deberá comprobar también ElevatorWorker.lifecycle(), la época y el estado vigente antes de enviar. Una cabina cerrada puede conservar un último snapshot de fase física NORMAL; ese snapshot por sí solo no autoriza despacho. Revalidar después de estimar y procesar las confirmaciones sigue siendo necesario.

## 8. Validación realizada

Compilación --release 17 de las clases recuperadas y nuevas, seguida de ejecución directa en JVM:

| Grupo | Escenarios satisfactorios |
| --- | ---: |
| PickupTimeEstimatorScenarios, T30 | 20 |
| ManagerWorkerScenarios, T29 | 12 |
| DirectionalAgendaScenarios, T25 | 20 |
| ConcurrentAdmissionScenarios, T26 | 21 |
| ExecutionEventScenarios, T27 | 20 |
| ParallelTimingScenarios, T28 | 8 |
| Total | 101 |

Dentro de un escenario de T30 se comparan sesenta agendas con un oráculo que usa ElevatorState real y avanza un piso por transición, frente a los saltos del estimador. No son sesenta pruebas JUnit adicionales. También se comprueban capturas publicadas por un trabajador real, inmutabilidad, desglose del costo, edad y wrap del reloj, estados no disponibles, UUID incompatible y un edificio de Integer.MAX_VALUE pisos con tiempos que superan long.

Los ejemplos deterministas usan un reloj virtual, sin sleeps ni tolerancias de planificador. La regresión de T28 conserva sus hilos reales y márgenes de tiempo. No se ejecutó Maven aquí; la suite completa se confirma en el repositorio de David.

## 9. Incorporación y validación en PowerShell

Desde `D:\Users\David\Desktop\workspace\java\elevator-manager`, con los cambios anteriores confirmados:

```powershell
git status --short
Expand-Archive -LiteralPath "$HOME\Downloads\T30-estimacion-tiempo-recogida.zip" -DestinationPath . -Force
mvn -Dtest=PickupTimeEstimatorTest test
mvn "-Dtest=ElevatorWorkerTest,ElevatorMovementTest,ElevatorDwellTest,DirectionalAgendaTest,ConcurrentAdmissionTest,ExecutionEventTest,ParallelTimingTest,ManagerWorkerTest,PickupTimeEstimatorTest" test
mvn clean verify
```

Ajustar la ruta de descarga si corresponde. Aceptar la sustitución de ElevatorWorker.java por la versión completa incluida. La primera prueba debe informar 20 casos sin fallos ni errores. Si se mantiene la base de 165 pruebas de T29, se esperan 185 en la suite completa; confirmar la cifra con la salida real de Surefire y BUILD SUCCESS.

```powershell
New-Item -ItemType Directory -Force .\logs | Out-Null
mvn -Dtest=PickupTimeEstimatorTest test 2>&1 | Tee-Object -FilePath .\logs\t30-estimacion-tiempo-recogida.log
```

También se pueden ejecutar los escenarios después de compilar:

```powershell
java -Djava.awt.headless=true -cp "target\classes;target\test-classes" com.elevatormanager.dispatch.PickupTimeEstimatorScenarios
```

## 10. Cierre y commit posterior a validación

Tras confirmar Maven, actualizar el estado de esta ficha con los resultados reales y marcar únicamente T30 como completada en docs/01-analisis-y-plan.md. Revisar las diferencias y crear el commit:

```powershell
git diff --check
git add -- src/main/java/com/elevatormanager/dispatch/PickupTimeEstimator.java src/main/java/com/elevatormanager/simulation/PlanningSnapshot.java src/main/java/com/elevatormanager/simulation/ElevatorWorker.java src/test/java/com/elevatormanager/dispatch/PickupTimeEstimatorScenarios.java src/test/java/com/elevatormanager/dispatch/PickupTimeEstimatorTest.java docs/implementacion/T30-estimacion-tiempo-recogida.md docs/comprobaciones/T30-validacion-local.md docs/01-analisis-y-plan.md
git diff --cached --check
git diff --cached --stat
git diff --cached
git commit -m "T30: implementar estimacion de tiempo de recogida"
git status --short
git log -1 --oneline
```

El archivo del plan se añade junto con la implementación para evitar dejar su cierre fuera del commit, como ocurrió inicialmente en T29. El siguiente paso después de validar y confirmar T30 es T31 — Implementar selección y desempate.
