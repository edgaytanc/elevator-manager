# T34 — Evaluar eficiencia y escalabilidad

- **Proyecto:** Elevator Manager.
- **Fecha:** 2026-10-27.
- **Versión:** 0.1.0-SNAPSHOT; objetivo Java 17.
- **Fase:** 5 — Implementar el Elevator Manager y la asignación.
- **Etiqueta:** CALIDAD.
- **Base confirmada:** T33, commit `c1c5f5d`, 249 pruebas sin fallos ni errores, BUILD SUCCESS y árbol limpio.
- **Estado:** evaluación preparada y ejecutada localmente. Integración y validación Maven completadas, T34 cerrado en el proyecto de David.

## 1. Objetivo y alcance

Ejecutar las mismas cargas con 1, 2 y una N mayor configurable, medir espera y movimientos y explicar las decisiones del algoritmo. La evaluación debe comprobar ejecución completa y coherencia de las mediciones, sin exigir mejora monótona ni prometer optimalidad global.

Se utiliza la producción validada hasta T32/T33: ManagerWorker con hilo propio, selección ETA/ID, registro global de pendientes, eventos y trabajadores independientes por cabina. T34 agrega herramientas de evaluación y pruebas en src/test; no requiere cambios en producción ni nuevas dependencias Maven.

## 2. Archivos completos de la entrega

| Ruta | Contenido |
| --- | --- |
| src/test/java/com/elevatormanager/dispatch/ScalabilityEvaluation.java | Evaluador configurable, métricas por eventos e informe Markdown. |
| src/test/java/com/elevatormanager/dispatch/ScalabilityScenarios.java | Trece escenarios sobre mediciones y ejecución real de cargas. |
| src/test/java/com/elevatormanager/dispatch/ScalabilityTest.java | Trece pruebas JUnit de los mismos escenarios. |
| docs/implementacion/T34-eficiencia-y-escalabilidad.md | Método, decisiones, parámetros y cierre. |
| docs/comprobaciones/T34-validacion-local.md | Evidencia de compilación y regresión. |
| docs/comprobaciones/T34-resultados-locales.md | Informe real: resumen, 27 ejecuciones, decisiones iniciales y 162 observaciones por llamada. |

El ZIP conserva estas seis rutas desde la raíz Maven. Se incorpora sobre el proyecto completo de T33. El evaluador está en test-classes y no pasa a ser una función de la interfaz ni reemplaza la clase de entrada ElevatorManager.

## 3. Diseño de la comparación

Se usan ocho pisos y tres cargas de seis llamadas externas únicas:

| Carga | Orden de llamadas piso/sentido |
| --- | --- |
| subida | 2/UP, 3/UP, 4/UP, 5/UP, 6/UP, 7/UP |
| mixta | 7/DOWN, 2/UP, 5/DOWN, 3/UP, 6/DOWN, 4/UP |
| opuestos | 3/UP, 3/DOWN, 5/UP, 5/DOWN, 7/DOWN, 2/UP |

La lista, su orden, UUID y createdAt de referencia se conservan entre configuraciones. Cada ejecución crea cabinas nuevas en piso 1, dirección UP y agendas vacías, además de un manejador nuevo y una secuencia de comandos compartida. Las llamadas se envían en ráfaga, sin esperar su servicio entre entradas. Esto reproduce la misma carga lógica, aunque el planificador de hilos pueda producir decisiones y tiempos diferentes.

La matriz predeterminada es 1, 2 y 4 cabinas, con tres repeticiones por carga: 27 ejecuciones y 162 observaciones. El orden de N rota entre repeticiones: [1,2,4], [2,4,1], [4,1,2]. Con tres repeticiones cada configuración ocupa una vez cada posición, evitando ejecutar siempre primero la de una cabina. No se aplica calentamiento JIT controlado.

Los duplicados funcionales se rechazan al construir una carga de evaluación. T32 ya prueba su agrupación; aquí contar alias como atenciones independientes distorsionaría las mediciones. En cambio, UP y DOWN del mismo piso sí son llamadas diferentes.

## 4. Parámetros configurables

| Propiedad Java | Valor predeterminado | Regla |
| --- | ---: | --- |
| t34.n | 4 | N >= 3 para que la tercera configuración sea distinta de 1 y 2. |
| t34.repetitions | 3 | Entero positivo. |
| t34.movementMillis | 25 | Tiempo válido de SimulationConfig. |
| t34.dwellMillis | 10 | Tiempo válido de SimulationConfig. |
| t34.timeoutSeconds | 15 | Plazo positivo representable para completar cada carga. |

Los tiempos se mantienen iguales al comparar cantidades de cabinas. El plazo de observación no es un costo estimado; sirve para detectar una carga sin progreso. Los tests ejercitan también cinco cabinas, además de la N configurable, y las métricas incluyen IDs sin servicio con valor cero. No hay índices ni bucles limitados a dos cabinas.

## 5. Definición de las métricas

| Métrica | Definición |
| --- | --- |
| Espera individual | referenceNanos de COMMAND_COMPLETED compatible menos nanoTime capturado justo antes de submit de esa llamada. |
| Espera media | Promedio de las esperas individuales del grupo, convertido a milisegundos sin acumular en un long susceptible a desbordamiento. |
| p95 | Muestra ordenada en posición ceil(0.95*n), contando desde uno. Se agrupan las muestras de todas las repeticiones; no se promedian percentiles. |
| Máxima | Mayor espera individual del grupo. |
| Movimientos | Cantidad de ARRIVED que completan un tramo adyacente; se suman todas las cabinas. |
| Paradas | Cantidad de STOP_STARTED; se suman todas las cabinas. |
| Tiempo total de carga | Desde el inicio de los envíos hasta el último evento físico necesario para terminar todos los servicios y cerrar sus paradas. |
| Reparto | Llamadas servidas y movimientos por cabina, incluyendo ceros. |
| Mejora de espera | 100*(media N=1 - media N)/media N=1 de la misma carga. Un valor negativo indicaría empeoramiento. |

Los movimientos, paradas, tiempo total y cabinas activas del resumen son promedios por ejecución. Las medias, p95 y máximos de espera proceden de las muestras individuales combinadas. Con tres repeticiones hay 18 muestras por grupo y el p95 por rango más próximo coincide con el máximo; no es una estimación robusta de cola poblacional.

Se usa un origen nanoTime de cada ejecución. Los tiempos crudos de envío, servicio y espera quedan en nanosegundos relativos dentro del informe. La espera incluye despacho, reintentos y planificación del SO; no incluye el tiempo de la parada posterior a recoger. El tiempo total sí incluye el fin de esa última parada. La creación y el cierre de hilos quedan fuera de las métricas.

## 6. Validación del medidor y de la ejecución

El colector recibe una suscripción adicional de ExecutionEvent antes del arranque. Mide con referenceNanos del actor, sin usar el instante en que el consumidor extrae el evento. Se verifican:

- Secuencias consecutivas y tiempo monotónico por cabina.
- Salidas y llegadas adyacentes correlacionadas, sin solapar movimiento y parada.
- Duraciones mínimas de movimiento y parada según la configuración.
- Finalización única de una llamada conocida, en DWELLING, piso/sentido compatibles y UUID canónico correcto.
- Ausencia de trabajadores fallidos o terminados durante la medición.
- Cierre físico de todas las paradas antes de terminar la carga.
- Estado global SERVED, una asignación y una canónica por cada llamada única.
- Un resultado inicial por entrada y ningún resultado extra por reintentos internos.
- Terminación sin fallos de todos los actores después de medir.

Los resultados iniciales reales del manejador se conservan por llamada. DISPATCHED incluye la explicación ETA/ID y el destinatario inicial. Si una entrada fue PENDING, su cabina final aparece en las observaciones; no se inventa una decisión inicial de costo que el resultado no informó.

Un fixture de reloj explícito comprueba una espera de 25.000.200 ns, un movimiento, una parada y 35.000.400 ns de tiempo total. También se comprueba rechazo de finalizaciones duplicadas o de sentido incompatible y la fórmula del percentil. Estos casos validan la medición separadamente de la velocidad del entorno.

## 7. Resultados locales y lectura

La ejecución de referencia está íntegra en docs/comprobaciones/T34-resultados-locales.md. Se realizó en Linux con OpenJDK 17.0.20, movimiento de 25 ms, parada de 10 ms y tres repeticiones.

| Carga | Media con 1 cabina ms | Media con 2 ms | Media con 4 ms | Movimientos medios con 1 | Movimientos medios con 4 |
| --- | ---: | ---: | ---: | ---: | ---: |
| subida | 122.116 | 99.134 | 92.871 | 6.000 | 18.000 |
| mixta | 139.031 | 104.795 | 92.206 | 8.000 | 17.000 |
| opuestos | 156.328 | 109.885 | 83.781 | 12.000 | 16.000 |

En esta ejecución, cuatro cabinas redujeron la espera media frente a una en 23.95%, 33.68% y 46.41%, respectivamente. También aumentaron los movimientos agregados: en subida de 6 a 18; en la carga mixta de 8 a 17; en opuestos de 12 a 16. Dos cabinas en opuestos registraron 11.333 movimientos medios, mostrando que desplazamiento y espera no tienen una relación monótona simple.

Los datos muestran un intercambio entre paralelismo, espera y desplazamiento agregado para estas cargas. No demuestran que cuatro cabinas sean siempre mejores ni que esos porcentajes se reproduzcan en otro equipo. El informe conserva cada ejecución para distinguir resultados estables de variaciones del planificador.

## 8. Decisiones del algoritmo y escalabilidad

La política de T31 minimiza la ETA estimada de la siguiente recogida, considerando agenda, sentido y fase. Empata por ID ascendente. T32 reserva temporalmente cabinas con admisión pendiente y agrupa llamadas equivalentes. Esas reglas siguen vigentes durante la evaluación.

Elegir ETA mínima busca reducir espera local. Varias cabinas pueden recorrer segmentos similares para atender llamadas distintas, aumentando movimientos agregados respecto a una sola cabina que agrupa recogidas en un barrido. El desempate puede concentrar trabajo en IDs menores; no garantiza reparto uniforme.

La implementación evalúa candidatas en función de N y del tamaño de sus agendas y ordena las opciones por costo/ID. El trabajo de estimación y clasificación crece al aumentar cabinas y pendientes. Las suscripciones y el seguimiento también agregan trabajo al manejador. Las mediciones muestran funcionamiento con 1, 2, 4 y 5 cabinas bajo cargas pequeñas; no establecen capacidad máxima, complejidad temporal medida, consumo de CPU/RAM ni escalabilidad lineal.

Los estados de partida son equivalentes y el orden de configuraciones rota, pero no hay control completo de calentamiento JIT, afinidad de CPU, pausas de GC o ruido del SO. Los tiempos son escalados para una comprobación reproducible del simulador. No se mide saturación ni tráfico sostenido y no se realiza prueba estadística de significancia.

## 9. Pruebas y regresión realizadas

| Grupo | Escenarios satisfactorios |
| --- | ---: |
| ScalabilityScenarios, T34 | 13 |
| DirectionPickupScenarios, T33 | 20 |
| PendingConfirmationScenarios, T32 | 26 |
| ElevatorSelectionScenarios, T31 | 18 |
| PickupTimeEstimatorScenarios, T30 | 20 |
| ManagerWorkerScenarios, T29 | 12 |
| DirectionalAgendaScenarios, T25 | 20 |
| ConcurrentAdmissionScenarios, T26 | 21 |
| ExecutionEventScenarios, T27 | 20 |
| ParallelTimingScenarios, T28 | 8 |
| Total | 178 |

Se compilaron las clases con --release 17 y todos esos main terminaron con código 0. La matriz de resultados de 27 ejecuciones se ejecutó aparte y no se suma como 27 pruebas JUnit nuevas. Maven y la suite acumulada deben confirmarse en el equipo de David.

## 10. Incorporación y validación en PowerShell

Desde `D:\Users\David\Desktop\workspace\java\elevator-manager`:

```powershell
git status --short
Expand-Archive -LiteralPath "$HOME\Downloads\T34-eficiencia-y-escalabilidad.zip" -DestinationPath . -Force
mvn -Dtest=ScalabilityTest test
mvn "-Dtest=ElevatorWorkerTest,ElevatorMovementTest,ElevatorDwellTest,DirectionalAgendaTest,ConcurrentAdmissionTest,ExecutionEventTest,ParallelTimingTest,ManagerWorkerTest,PickupTimeEstimatorTest,ElevatorSelectionTest,PendingConfirmationTest,DirectionPickupTest,ScalabilityTest" test
mvn clean verify
```

Ajustar la ruta del ZIP. Se esperan 13 pruebas específicas, 210 del grupo de regresión y 262 totales si se conserva la base de 249 de T33. Confirmar las cifras reales y BUILD SUCCESS.

Para generar el informe de la misma matriz en el equipo local, después de compilar:

```powershell
New-Item -ItemType Directory -Force .\logs | Out-Null
java -Djava.awt.headless=true "-Dt34.n=4" "-Dt34.repetitions=3" "-Dt34.movementMillis=25" "-Dt34.dwellMillis=10" "-Dt34.timeoutSeconds=15" -cp "target\classes;target\test-classes" com.elevatormanager.dispatch.ScalabilityEvaluation | Set-Content -Encoding UTF8 .\logs\t34-resultados.md
if ($LASTEXITCODE -ne 0) { throw "Fallo la evaluacion T34." }
Get-Content .\logs\t34-resultados.md -TotalCount 25
```

Ejemplo de N mayor y más repeticiones:

```powershell
java -Djava.awt.headless=true "-Dt34.n=6" "-Dt34.repetitions=5" -cp "target\classes;target\test-classes" com.elevatormanager.dispatch.ScalabilityEvaluation | Set-Content -Encoding UTF8 .\logs\t34-resultados-n6.md
if ($LASTEXITCODE -ne 0) { throw "Fallo la evaluacion T34 con N=6." }
```

Para repetir los trece escenarios independientes de JUnit:

```powershell
java -Djava.awt.headless=true -cp "target\classes;target\test-classes" com.elevatormanager.dispatch.ScalabilityScenarios
```

## 11. Cierre de T34 y de la fase 5

Después de validar Maven y revisar el informe del equipo de David, actualizar el estado de esta ficha y marcar T34 como completada en docs/01-analisis-y-plan.md. Con T29–T33 ya cerradas, esto permite registrar el cierre de la fase 5. No se adelanta la fase 6: el controlador global de ciclo de vida, Stop y Reset aún corresponden a las siguientes tareas.

```powershell
git diff --check
git add -- src/test/java/com/elevatormanager/dispatch/ScalabilityEvaluation.java src/test/java/com/elevatormanager/dispatch/ScalabilityScenarios.java src/test/java/com/elevatormanager/dispatch/ScalabilityTest.java docs/implementacion/T34-eficiencia-y-escalabilidad.md docs/comprobaciones/T34-validacion-local.md docs/comprobaciones/T34-resultados-locales.md docs/01-analisis-y-plan.md
git diff --cached --check
git diff --cached --stat
git diff --cached
git commit -m "T34: evaluar eficiencia y escalabilidad del manejador"
git status --short
git log -1 --oneline
```

Siguiente tarea después del cierre: T35 — Implementar el controlador del ciclo de vida, inicio de fase 6.
