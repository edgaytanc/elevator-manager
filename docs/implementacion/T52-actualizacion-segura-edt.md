# T52 — Garantizar actualización segura en EDT

- **Fecha:** 2026-10-28, Guatemala.
- **Fase:** 8 — Construir la interfaz gráfica obligatoria.
- **Base:** T51 validada en `ff29a67`, 722 pruebas, ejecución y capturas confirmadas.
- **Estado:** implementada con pruebas locales; pendiente de Maven y recorrido nativo bajo carga en Windows.

## Hallazgo y cambio

El EDT ya recibía los callbacks de T48–T51 correctamente, pero cada updateView recorría el historial completo de llamadas y renderizaba todas las cabinas. Las acciones podían repetir ese trabajo además del Timer. T52 mueve la captura/agrupación global a UiSnapshotPump, limita refrescos y evita reconstruir widgets que no están visibles o no cambian.

| Trabajo | Hilo / política |
| --- | --- |
| Crear/modificar Swing | EDT; guardas de entrada en paneles y manejadores. |
| Abrir sesión y logs | Preparador de T48, fuera del EDT. |
| Leer y validar archivo | Ejecutor I/O del controlador; límite de archivo de T43 intacto. |
| Capturar snapshots y agrupar llamadas activas | Trabajador ui-snapshot-capture. |
| Dibujar cabina visible y visor | EDT, cadencia monotónica de 100 ms. |
| Bloquear controles por Stop/Reset/cierre | EDT, inmediato; no espera al siguiente refresco de datos. |
| Esperar actores, recursos y capturador | Cierre asíncrono fuera del EDT. |

## Publicación acotada

UiSnapshotPump permite una sola captura en curso. Mientras está ocupada, las solicitudes se descartan; no se encolan miles de trabajos atrasados. Mantiene una sola referencia al último Frame, que reemplaza la anterior. Frame contiene UUID de sesión, época, snapshots inmutables y listas inmutables de llamadas activas agrupadas por cabina. No incorpora llamadas históricas SERVED/CANCELLED.

La captura verifica la época antes y después; un reset concurrente descarta el conjunto. La vista comprueba además sesión y época al aplicarlo. Los snapshots de distintas cabinas no representan un instante físico global atómico. El pump no publica Runnable por cada evento al EDT: el Timer toma la última copia disponible.

RefreshGate usa System.nanoTime y un intervalo de 100000000 ns para la captura y el refresco periódico. No utiliza sleeps en EDT ni intenta recuperar todos los ticks omitidos. La cadencia no es una garantía de tiempo real ni de latencia máxima: una captura costosa puede terminar después y solo entonces se utiliza su publicación. Los controles siempre vuelven a comprobar el estado del controlador.

Si no hay conjunto vigente, la cabina visible puede mostrar su snapshot local mediante SimulationController.snapshot(id), una lectura O(1) del valor ya publicado. Esa ruta no recorre N cabinas ni el historial global en EDT. Durante Reset la representación puede esperar actualización local, sin restaurar pendientes de la época anterior.

## Cabinas y documentos

Se mantienen N pestañas ligeras y se crean paneles completos al inspeccionarlos, con una caché de hasta tres. Solo la pestaña visible recibe render periódico. Al volver a una cabina que salió de caché se reconstruye desde su agenda/snapshot actual; no se pierden solicitudes, movimientos ni luces del motor. La página de navegación de un panel recreado puede volver a la primera página; el resumen conserva los pendientes reales.

Seleccionar una pestaña sincroniza el selector Cabina. El acceso directo sigue disponible. La cantidad de pestañas y actores continúa dependiendo de N; la caché no elimina los límites de recursos del equipo para cantidades extremas.

CabinPanel evita reescribir textos/listas si conserva la misma instantánea, estado, disponibilidad y llamadas. El bloqueo inmediato también comprueba que la época dibujada siga vigente antes de rehabilitar destinos y solo actualiza los botones de los paneles retenidos; no recaptura ni ordena datos globales. Los callbacks manuales conservan sus comprobaciones de secuencia, época y RUNNING. Las luces siguen derivándose de los pendientes, no del recibo de envío.

El visor mantiene sus lotes y presupuestos de T51. Su actualización periódica también pasa por la compuerta de frecuencia. No se cambian la retención fiable del motor, el protocolo, la herencia de solicitudes ni el contrato de importación.

## Cierre y errores de captura

Cerrar marca el pump como cerrado, invalida su publicación y solicita terminar el ejecutor. Su futuro solo confirma cuando el trabajador ha terminado realmente. Una captura que ignore temporalmente interrupción no publica después del cierre; la ventana espera sin bloquear EDT. SystemControlPanel espera tanto el cierre del motor como el del pump, incluso si uno falla, y conserva la espera del preparador.

Una captura fallida conserva la última publicación y expone el error a la vista, que lo informa desde EDT. Un intento posterior puede recuperarse. No se dispone la ventana antes de terminar el capturador. La preparación tardía durante cierre no llega a crear un pump nuevo.

## Pruebas y alcance de la evidencia

Se añaden 32 escenarios y adaptadores JUnit. Se utiliza un RepaintManager de prueba que detecta invalidación/repaint fuera del EDT; un control positivo modifica deliberadamente un JLabel del fixture fuera del EDT para verificar que el monitor detecta una violación. Esa violación no pertenece al código de producción y se separa de las auditorías siguientes.

Durante los recorridos de producción instrumentados se comprueban preparación, ocho cabinas MOVING simultáneas, cambios de selección, Stop/Reset, importación grande y cierres. Un DocumentListener verifica que repetir el mismo snapshot no reescribe textos. Las comprobaciones cubren los caminos ejecutados; no constituyen una prueba formal sobre cualquier extensión futura o plataforma.

EdtImportFixture retiene la entrada del lector y después llama a RequestFileReader real. Usa 10000 instrucciones válidas en 900000 bytes, dentro de MAX_FILE_BYTES/MAX_LINES. La retención permite comprobar pulsos mientras la lectura está pendiente, sin depender de la velocidad del disco. También se espera la importación real completa después de liberarla. La importación se realiza en RESET; el movimiento concurrente se prueba en un escenario RUNNING separado, respetando T45.

Los pulsos son Timer Swing de 20 ms, esperados desde el hilo de prueba con plazo de cinco segundos. Se imprime el mayor intervalo observado como diagnóstico; no se exige una latencia arbitraria de pocos milisegundos ni se promete ese valor en otros equipos. Los bloqueos de prueba se liberan y los ejecutores/controladores esperan terminación.

## Archivos

- `ui/RefreshGate.java`, `ui/UiSnapshotPump.java`: cadencia y publicación fuera del EDT.
- `simulation/SimulationController.java`: snapshot directo de una cabina.
- `ui/CabinDashboardPanel.java`, `ui/CabinPanel.java`: caché, render visible y omisión de escrituras repetidas.
- `ui/SystemControlPanel.java`: compuerta, creación/cierre del pump y bloqueo inmediato.
- `simulation/EdtImportFixture.java` (test): lector retenido que delega al lector real.
- `ui/EdtSafetyScenarios.java`, `ui/EdtSafetyTest.java`: 32 escenarios y adaptadores.
- README, plan, cierre histórico T51 y tres Markdown T52.

## Incorporación y validación en Windows

Extraer el proyecto fuente completo sobre la raíz Maven, sin carpeta elevator-manager anidada; el ZIP excluye .git, target y logs generados. Conservar cambios personales antes de extraer.

```powershell
Set-Location 'D:\Users\David\Desktop\workspace\java\elevator-manager'
git status --short
Expand-Archive -LiteralPath "$HOME\Downloads\T52-actualizacion-segura-edt.zip" -DestinationPath . -Force
mvn "-Dtest=EdtSafetyTest" test
mvn "-Dtest=EdtSafetyTest,EventViewerTest,CabinPanelsTest,HallCallsTest,SystemControlsTest,ConfigurationFormTest" test
mvn clean verify
java -jar .\target\elevator-manager-0.1.0-SNAPSHOT.jar
```

Se esperan 32 específicas, 176 combinadas y 754 acumuladas, sin fallos, errores u omisiones y BUILD SUCCESS. Maven no está instalado en el entorno local de esta entrega.

## Recorrido nativo

1. Configurar N=8/P=8, movimiento=1000 ms y espera=300 ms. Iniciar y solicitar destinos en varias cabinas, cambiando entre pestañas y Ver cabina.
2. Mientras se mueven, arrastrar/redimensionar la ventana, mover divisores, usar scroll y revisar visor. Los controles deben responder; no se espera refresco de cada evento individual.
3. Solicitar Stop y Reset: el bloqueo de admisión debe ser inmediato y los estados finales solo aparecer tras las confirmaciones reales.
4. En RESET, cargar un archivo de mayor tamaño dentro del contrato y comprobar respuesta. Las pruebas específicas ya generan y validan el caso de 10000 líneas; no es obligatorio crearlo manualmente.
5. Cerrar durante movimiento o lectura y comprobar retorno al prompt. Repetir con X. El cierre debe esperar recursos y capturador sin congelar la ventana.
6. Comprobar que las luces y pendientes al volver a una cabina reflejan su estado real aunque el panel haya sido recreado. Las cabinas siguen trabajando mientras no están visibles.

Las pruebas headless usan EDT real pero no acreditan arrastre, foco, DPI ni decoración de JFrame en Windows. T53 validará el recorrido completo de la GUI; no se declara cerrada la fase 8 en esta tarjeta.

## Commit sugerido

Después de confirmar Maven y revisión nativa:

```powershell
git diff --check
git add -- README.md docs/01-analisis-y-plan.md docs/implementacion/T51-visor-eventos-y-tamano.md docs/comprobaciones/T51-validacion-local.md docs/implementacion/T52-actualizacion-segura-edt.md docs/implementacion/T52-codigo-completo.md docs/comprobaciones/T52-validacion-local.md src/main/java/com/elevatormanager/ui/RefreshGate.java src/main/java/com/elevatormanager/ui/UiSnapshotPump.java src/main/java/com/elevatormanager/simulation/SimulationController.java src/main/java/com/elevatormanager/ui/CabinPanel.java src/main/java/com/elevatormanager/ui/CabinDashboardPanel.java src/main/java/com/elevatormanager/ui/SystemControlPanel.java src/test/java/com/elevatormanager/simulation/EdtImportFixture.java src/test/java/com/elevatormanager/ui/EdtSafetyScenarios.java src/test/java/com/elevatormanager/ui/EdtSafetyTest.java
git diff --cached --check
git commit -m "T52: limitar refrescos y verificar actualizacion segura en EDT"
git status --short
```

T52 se cerrará con los resultados de David. La siguiente tarjeta es T53 — Validar el recorrido completo desde GUI.
