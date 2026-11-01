# T60 — Validación y recorrido gráfico de controles

- **Estado:** pendiente de resultados de David.
- **Base:** T59 aceptada en `1cd6913`, 886 pruebas totales y Git limpio.

## Incorporación y pruebas

Extraer T60-sincronizacion-de-animacion-y-controles.zip en una carpeta temporal. Combinar el contenido de elevator-manager con el repositorio local conservando .git. El ZIP contiene el proyecto completo y excluye .git, target, logs y volcados JVM.

```powershell
mvn "-Dtest=AnimationSynchronizationTest" test
mvn clean verify
git diff --check
java -jar .\target\elevator-manager-0.1.0-SNAPSHOT.jar
```

Esperado: 24 pruebas específicas y 910 totales, cero fallos/errores/omitidas y BUILD SUCCESS. Son resultados esperados en el equipo de David; no se ejecutó Maven en el entorno de preparación.

## Preparación visual

Configurar N=3, P=8, movimiento=2500 ms, parada=3000 ms. Iniciar y enviar E1 hacia piso 8. En Edificio observar una posición intermedia. Usar tiempos visibles para distinguir resto de tramo de una duración reiniciada. La automatización verifica presupuestos exactos; los clics manuales no sustituyen esas medidas.

| Caso | Acción | Resultado esperado |
| --- | --- | --- |
| Pausa entre pisos | Pulsar Pausar y esperar PAUSED; dejar pasar varios segundos | Posición estable, incluidos cambios de pestaña y redimensionado |
| Reanudar | Pulsar Reanudar | Continúa desde la posición pausada y no salta al extremo por el tiempo de pared acumulado |
| Ciclos | Repetir tres pausas en distintas posiciones | Sin reiniciar el tramo ni perder destinos |
| Parada | Pausar durante la espera en un piso y luego reanudar | Cabina fija mientras está pausada; consume solo la espera restante |
| Reposo | Pausar con todas las cabinas sin trabajo | No crea movimientos o solicitudes |
| Stop en movimiento | Pulsar Stop entre pisos | Completa solo el tramo adyacente y queda en STOPPED; destino pendiente conservado |
| Stop desde pausa | Pausar entre pisos y pulsar Stop | Continúa el resto del tramo sin Resume manual y queda en el adyacente |
| Continuación | Desde STOPPED pulsar Start | Continúa desde el piso alcanzado y atiende la agenda pendiente |
| Reset desde altura | Con una o más cabinas arriba, pulsar Reset | Sin colocación inmediata en piso 1; se observa el retorno por pisos hasta RESET |
| Reset desde pausa | Pausar entre pisos y pulsar Reset | Termina el tramo iniciado y retorna físicamente sin Resume manual |
| Prioridad | Pulsar Stop y luego Reset mientras STOPPING, si la ventana lo permite | Prevalece Reset; el resultado atrasado de Stop no cambia el estado ni la época |
| Cerrar desde pausa | En una ejecución pulsar Cerrar; en otra usar X | Ventana desaparece y vuelve el prompt sin reanudar manualmente |
| Cerrar tras Resume | Pausar, reanudar y cerrar enseguida | Sin retroceder por una captura HELD atrasada ni continuar dibujando durante el cierre |

Comparar Edificio con Piso actual/Siguiente en Cabinas: el primero puede ser fraccional; el piso lógico solo cambia con la llegada del trabajador. El final de Reset debe mostrar todas las cabinas en piso 1, dirección UP y sin pendientes. Comprobar que se pueden cargar archivos otra vez en RESET.

Las capturas de fase se obtienen asíncronamente. Si el EDT se retrasa, se presenta el estado vigente al recuperarse, sin reproducir fotogramas perdidos ni inventar tramos. Una imagen fija no prueba congelación o fluidez; registrar lo observado durante las esperas y controles. Conservar la salida completa si aparece un fallo o una incidencia visual.

## Commit sugerido tras validar

```powershell
git diff --check
git add -- src/main/java/com/elevatormanager/simulation/time/PausableClock.java src/main/java/com/elevatormanager/simulation/time/PauseCoordinator.java src/main/java/com/elevatormanager/simulation/SimulationController.java src/main/java/com/elevatormanager/ui/CabinMotion.java src/main/java/com/elevatormanager/ui/BuildingPanel.java src/main/java/com/elevatormanager/ui/UiSnapshotPump.java src/main/java/com/elevatormanager/ui/SystemControlPanel.java src/test/java/com/elevatormanager/simulation/time/ClockViewScenarios.java src/test/java/com/elevatormanager/ui/AnimationSynchronizationScenarios.java src/test/java/com/elevatormanager/ui/AnimationSynchronizationTest.java docs/01-analisis-y-plan.md docs/comprobaciones/T59-recorrido-gui.md docs/implementacion/T60-sincronizacion-de-animacion-y-controles.md docs/implementacion/T60-codigo-completo.md docs/comprobaciones/T60-validacion-local.md docs/comprobaciones/T60-recorrido-gui.md
git diff --cached --check
git diff --cached --stat
git commit -m "T60: sincronizar animacion con pausa stop y reset"
git status --short
```

T60 queda pendiente de aceptación local. La siguiente tarjeta es T61, claridad y rendimiento visual; aún no se da por cerrada la fase 10. La entrega no crea un commit en nombre de David.
