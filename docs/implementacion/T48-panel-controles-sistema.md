# T48 — Crear panel de controles del sistema

> Cierre funcional confirmado al iniciar T49: 28 pruebas específicas, 44 combinadas con T47 y 622 acumuladas, BUILD SUCCESS, commit `0bae607`, registros de Stop/continuación, dos retornos Reset 1/UP y cierre con retorno al prompt. El informe siguiente conserva su estado original previo al cierre.

- **Fecha:** 2026-10-28, Guatemala.
- **Fase:** 8 — Construir la interfaz gráfica obligatoria.
- **Base:** T47 validada en `5f30a29`; 16 pruebas específicas, 594 acumuladas, BUILD SUCCESS y capturas de errores/configuración válida.
- **Estado:** implementada con comprobaciones locales; pendiente de Maven y revisión de ventana nativa en Windows.

## Resultado

La entrada ElevatorManager compone SystemControlPanel. Confirmar los cuatro valores válidos crea una sesión real con SessionLog en un preparador dedicado fuera del EDT. La ventana permanece disponible durante apertura/lectura/retorno/cierre. No se agregan workers artificiales para simular actividad: se utilizan SimulationController, ManagerWorker y ElevatorWorker existentes.

Los parámetros se bloquean desde que se solicita preparar hasta el cierre de esa sesión. Reset conserva N/P/tiempos; para configurar otra sesión se vuelve a abrir la aplicación. Si falla la apertura, se desbloquean los campos y se permite reintentar sin publicar una sesión parcial.

El rango numérico de T16/T47 no cambia. El rango matemático aceptado no garantiza memoria o recursos para cantidades extremas de cabinas; la preparación puede fallar y no se debe utilizar una cantidad extrema como prueba de carga gráfica.

## Controles por estado

| Estado | Start | Stop | Reset | Cargar archivo | Cerrar |
| --- | --- | --- | --- | --- | --- |
| Sin configurar | No | No | No | No | Sí |
| Preparando sesión | No | No | No | No | Sí; espera apertura tardía y la libera |
| RESET | Sí | No | Sí | Sí | Sí |
| RUNNING | No | Sí | Sí | No | Sí |
| STOPPING | No | No | Sí; sustituye Stop | No | Sí |
| STOPPED | Sí; continúa | No | Sí | No | Sí |
| RESETTING | No | No | No | No | Sí |
| PAUSING / PAUSED | No | No | No | No | Sí; controles de pausa pendientes de fase 9 |
| CLOSING del motor | No | No | No | No | Sí, hasta solicitar cierre de ventana |
| Cierre de ventana solicitado | No | No | No | No | No; espera confirmación |
| Cierre confirmado | No | No | No | No | Ventana dispuesta |

Hay bloqueos adicionales durante el envío de controles y la lectura: Start/carga no se repiten mientras existe una acción pendiente. Reset puede invalidar una lectura y el cierre siempre tiene prioridad. Las comprobaciones gráficas son preventivas; el controlador vuelve a validar su estado serializadamente.

Al pulsar Stop/Reset se muestra inmediatamente Deteniendo/Reseteando, sin anunciar finalización hasta recibir confirmación. Un Timer Swing coalescente de 100 ms consulta únicamente estado y tamaño del lote preparados, sin consumir eventos ni recorrer snapshots. El resultado de un futuro no restaura su estado histórico: se consulta el estado actual. Los tokens de operación/importación impiden que un callback anterior reemplace el mensaje de Reset o cierre.

## Importación

RequestImportHandler realiza selección nativa en EDT y delega lectura/validación al ejecutor del controlador. Cancelar conserva el lote; un archivo inválido muestra hasta ocho diagnósticos con línea, mantiene el lote previo y permite corregir. El texto se limita a 4000 caracteres: es el último resultado de control, no un historial de eventos. Los logs completos permanecen en disco; el visor de eventos es T51.

Cargar un lote no inicia cabinas. Start desde RESET consume el lote una sola vez por la ruta existente. Tras Stop no se admite importar: se debe completar Reset primero.

## Logs y cierre

Se abren `logs/sesion-<UUID>/elevadorN.log` y `manejador.log` por sesión, con exclusión y ownership de T41/T42. SessionLog se transfiere al controlador al completar su construcción; si falla la construcción, el abridor libera el logging. Si el directorio no se puede abrir, se informa el error y no se habilita Start.

Botón Cerrar y X usan el mismo WindowCloseHandler. SystemControlPanel.closeAsync espera incluso la apertura en curso, cierra la sesión resultante y espera que termine el preparador. Las esperas ocurren fuera del EDT. Una apertura fallida no impide cerrar una ventana sin sesión. No se dispone la ventana ante un cierre no confirmado; se muestra el error y se permite solicitarlo de nuevo, sin habilitar trabajo. Un fallo terminal de recursos puede seguir impidiendo confirmar el cierre, según el contrato T39; no se falsea CLOSED.

MainWindow conserva su constructor con proveedor de cierre externo: se obtiene en EDT después de liberar la sesión creada por esta ventana. El constructor por defecto utiliza una confirmación inmediata para ese recurso adicional.

## Archivos

- `ui/ConfigurationPanel.java`: callback de confirmación y bloqueo de edición, conservando constructor y pruebas T47.
- `ui/SystemControlPanel.java`: sesión, controles, resultados, estados y cierre asíncrono.
- `ui/MainWindow.java`: composición y ruta única de cierre.
- `ui/SystemControlsScenarios.java` y `ui/SystemControlsTest.java`: 28 escenarios y adaptadores JUnit.
- README, plan, cierre histórico T47 y tres Markdown de T48.

No se modifican el controlador, motor, parser, reglas del modelo ni POM. T49/T50/T51/T52/T53 permanecen como tarjetas separadas. El cuidado del EDT de este panel no sustituye la validación global y los refrescos de cabinas de T52.

## Incorporación y comandos en Windows

Extraer el proyecto fuente completo sobre la raíz Maven; no anidar otra carpeta elevator-manager. El ZIP excluye .git, target y logs. Conservar cambios personales antes de extraer.

```powershell
Set-Location 'D:\Users\David\Desktop\workspace\java\elevator-manager'
git status --short
Expand-Archive -LiteralPath "$HOME\Downloads\T48-panel-controles-sistema.zip" -DestinationPath . -Force
mvn "-Dtest=SystemControlsTest" test
mvn "-Dtest=SystemControlsTest,ConfigurationFormTest" test
mvn clean verify
java -jar .\target\elevator-manager-0.1.0-SNAPSHOT.jar
```

Se esperan 28 específicas, 44 junto a T47 y 622 acumuladas sin fallos, errores u omisiones, con BUILD SUCCESS. No se ejecutó Maven en el entorno local de esta entrega.

## Recorrido visual

1. Abrir el JAR: solo Cerrar está habilitado. Valores inválidos muestran errores y no preparan sesión.
2. Usar N=2, P=8, movimiento=1000 ms, espera=300 ms y validar. Observar Preparando y después RESET. Los parámetros quedan bloqueados.
3. Cargar `src/main/resources/examples/requests/03-sentidos-mixtos.txt`: seis solicitudes preparadas, estado RESET; no se inicia movimiento.
4. Abrir y cancelar el selector: siguen las seis solicitudes. Seleccionar `08-error-ultima-linea.txt`: mostrar error con línea y conservar el lote.
5. Pulsar Start: RUNNING; Stop/Reset/Cerrar habilitados, cargar deshabilitado. Los eventos completos de cabinas aparecen en consola y logs; los paneles por cabina se implementarán en T50.
6. Durante un viaje pulsar Stop: Deteniendo, luego STOPPED. Start continúa con los mismos trabajadores y pendientes. Reset permanece disponible para sustituir una detención.
7. Durante otro viaje pulsar Reset: Reseteando hasta confirmar retorno al piso 1. Después RESET, lote vacío y carga habilitada. No debe admitir Start/carga durante el retorno.
8. Volver a cargar y ejecutar. Cerrar durante movimiento y comprobar retorno al prompt; repetir en otra ejecución con X y durante Reset.
9. Reducir la ventana y comprobar acceso por desplazamiento al formulario. Verificar que se puede mover la ventana mientras se lee o retorna al piso 1.
10. Comprobar archivos en `logs/sesion-<UUID>/`; no se exige editar parámetros en código ni usar diagnósticos para operar estos controles.

Las pruebas locales usan EDT y componentes Swing reales, pero inyectan la selección de archivo y no crean JFrame. La revisión anterior del diálogo nativo y los botones/X en Windows sigue pendiente para T48.

## Commit sugerido

Después de confirmar Maven y el recorrido visual:

```powershell
git diff --check
git add -- README.md docs/01-analisis-y-plan.md docs/implementacion/T47-formulario-configuracion.md docs/comprobaciones/T47-validacion-local.md docs/implementacion/T48-panel-controles-sistema.md docs/implementacion/T48-codigo-completo.md docs/comprobaciones/T48-validacion-local.md src/main/java/com/elevatormanager/ui/ConfigurationPanel.java src/main/java/com/elevatormanager/ui/SystemControlPanel.java src/main/java/com/elevatormanager/ui/MainWindow.java src/test/java/com/elevatormanager/ui/SystemControlsScenarios.java src/test/java/com/elevatormanager/ui/SystemControlsTest.java
git diff --cached --check
git commit -m "T48: integrar controles del sistema y sesion asincrona"
git status --short
```

T48 se cerrará con los resultados de David. La siguiente tarjeta será T49 — Crear llamadas externas compartidas por piso.
