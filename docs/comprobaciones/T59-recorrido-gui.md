# T59 — Validación local y recorrido de animación

- **Estado actual:** T59 aceptada al solicitar David T60. Confirmados commit `1cd6913`, 18 pruebas específicas, 886 totales con BUILD SUCCESS y estado Git limpio. Se recibió una captura de tres cabinas en posiciones distintas; no acredita por sí sola fluidez, ambos sentidos y redimensionado.

> El guion siguiente conserva el alcance y los resultados esperados de la entrega T59. Las limitaciones sobre sincronización de controles se abordan en T60.
- **Base:** T58 aceptada en commit `712b24a`, 868 pruebas; repositorio limpio.

## Incorporación

Extraer T59-animacion-del-desplazamiento.zip en una carpeta temporal. Combinar el contenido de elevator-manager sobre el repositorio conservando .git. El ZIP contiene el proyecto completo, no es un parche. Se excluyen .git, target, logs y volcados de fallo JVM.

Desde la raíz:

```powershell
mvn "-Dtest=CabinAnimationTest" test
mvn clean verify
git diff --check
java -jar .\target\elevator-manager-0.1.0-SNAPSHOT.jar
```

Esperado: 18 pruebas específicas y 886 totales, cero fallos/errores/omitidas y BUILD SUCCESS. Son resultados esperados para el equipo local; no se ejecutó Maven en el entorno de preparación.

## Recorrido visual

1. Configurar N=2, P=6, movimiento=1500 ms, parada=2000 ms. Validar y observar las dos cabinas en piso 1.
2. En Cabinas, pulsar Start y marcar piso 6 para E1. Volver a Edificio: E1 debe mostrar posiciones intermedias entre pisos, mientras E2 permanece en 1. Observar varios tramos seguidos: la animación no debe volver al inicio del mismo tramo con cada captura.
3. Comparar en Cabinas: durante un traslado, Piso actual sigue siendo el piso dejado. El dibujo intermedio no debe adelantar la confirmación lógica ni apagar destinos. Al llegar, el modelo actualiza el piso y gobierna la parada.
4. Tras llegar E1 al piso 6, solicitar para ella piso 1 y para E2 piso 6. Observar bajada y subida simultáneas, cada cabina dentro de su hueco y sin modificar la dirección de la otra.
5. Durante una parada y cuando ya no haya destinos, comprobar posición estable. La imagen no debe seguir avanzando hacia pisos que no pertenecen al tramo publicado.
6. Redimensionar durante un traslado y alternar Edificio/Cabinas. Las coordenadas deben adaptarse sin reiniciar el presupuesto temporal. Si el EDT se retrasa, el dibujo puede saltar a la posición temporal vigente, pero no ejecutar fotogramas antiguos para ponerse al día.
7. En otra ejecución usar N=1, P=1: cabina estable, sin división por cero. En otra, N=20, P=100: conservar acceso por scroll a columnas y pisos extremos.
8. Cerrar la ventana y confirmar retorno al prompt. Conservar la salida completa de Maven y describir cualquier incidencia visual.

Los fotogramas usan una demora nominal de 16 ms; la publicación del conjunto sigue limitada a 100 ms y depende del planificador. No se garantiza que movimientos de 1 ms sean visibles ni que una captura demorada muestre todos los tramos ya terminados. Para este recorrido se usan 1500 ms para que las posiciones intermedias se puedan inspeccionar.

La sincronización completa durante pausa, Stop y retorno de Reset se acepta en T60, que es la siguiente tarjeta. Esta tarea no declara superadas esas comprobaciones gráficas.

## Commit sugerido, después de validar

El staging enumera únicamente archivos de T59 y el registro de aceptación T58.

```powershell
git diff --check
git add -- src/main/java/com/elevatormanager/simulation/SimulationController.java src/main/java/com/elevatormanager/ui/UiSnapshotPump.java src/main/java/com/elevatormanager/ui/CabinMotion.java src/main/java/com/elevatormanager/ui/BuildingPanel.java src/main/java/com/elevatormanager/ui/SystemControlPanel.java src/test/java/com/elevatormanager/ui/CabinAnimationScenarios.java src/test/java/com/elevatormanager/ui/CabinAnimationTest.java docs/01-analisis-y-plan.md docs/comprobaciones/T58-recorrido-gui.md docs/implementacion/T59-animacion-del-desplazamiento.md docs/implementacion/T59-codigo-completo.md docs/comprobaciones/T59-validacion-local.md docs/comprobaciones/T59-recorrido-gui.md
git diff --cached --check
git diff --cached --stat
git commit -m "T59: implementar animacion del desplazamiento"
git status --short
```

T59 queda pendiente de cierre hasta recibir validación local y aceptación. No se crea un commit en nombre de David ni se da por cerrada la fase 10.
