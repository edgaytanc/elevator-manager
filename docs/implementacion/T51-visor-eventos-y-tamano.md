# T51 — Agregar visor de eventos y adaptación de tamaño

> Cierre confirmado al iniciar T52: 40 específicas, 144 combinadas y 722 acumuladas, BUILD SUCCESS, commit `ff29a67`, árbol limpio y capturas del visor en RESET/RUNNING con 112 registros. El informe siguiente conserva el estado original de entrega.

- **Fecha:** 2026-10-28, Guatemala.
- **Fase:** 8 — Construir la interfaz gráfica obligatoria.
- **Base:** T50 validada en `1d3378b`, 682 pruebas, ejecución y capturas de luces confirmadas.
- **Estado:** implementada y comprobada localmente; pendiente de Maven y ventana nativa en Windows.

## Resultado y registros

El visor inferior muestra trazas reales del manejador y las cabinas con secuencia visual, fecha, nivel y actor. SessionLog conserva el envío del LogRecord original a sus handlers y luego agrega una copia textual limitada a RecentEventBuffer. Los archivos conservan mensajes y stack traces completos; el visor recibe un resumen de errores sin retener Throwable ni snapshots. Los fallos reales de sesión se indican en el pie del visor.

SimulationController expone la copia reciente por Optional. No cambia la admisión, protocolo ni algoritmo; no se consume ExecutionEventMailbox/awaitEvent para alimentar Swing. Una sesión construida explícitamente sin logging admite el panel con un mensaje de canal ausente; la entrada normal de T48 siempre abre SessionLog.

| Límite | Valor |
| --- | ---: |
| Registros en búfer reciente | 500 |
| Caracteres por mensaje visual | 1200 |
| Suma de caracteres de mensaje retenidos | 300000 |
| Actor / nivel por registro | 64 / 32 caracteres |
| Registros leídos por refresco | 200 |
| Registros mostrados | 300 |
| Caracteres del documento mostrado | 120000 |

Son límites de la copia visual; la retención fiable del motor/logging conserva sus contratos previos. La metainformación también está acotada por registro/cantidad; no se presenta este presupuesto como límite de toda la aplicación. Los caracteres se cuentan como unidades UTF-16 de String/Document.

El búfer descarta primero los más antiguos al superar cantidad o texto. after(cursor, limit) devuelve una copia inmutable y cuenta los registros ya no recuperables antes de ese cursor. Leer no retira entradas: dos lectores pueden inspeccionar lo mismo. Los saltos de línea se normalizan en la copia para que un registro no fabrique miles de líneas visuales. La secuencia del visor expresa orden de consumo por SessionLog; no implica un instante físico global entre actores.

## Visor

EventViewerPanel comparte el Timer Swing de T48 (100 ms, coalescente). Consulta como máximo 200 registros y actualiza el documento en EDT; no lee archivos, espera futuros ni crea un hilo adicional. Los campos históricos conservan su época. Reset no limpia el historial; vincular otra sesión sí lo separa y reinicia el cursor.

- **Seguir últimos:** lleva la vista al final. Desactivarlo no pausa la recepción ni el logging; mantiene selección/posición cuando los registros correspondientes siguen retenidos.
- **Limpiar visor:** limpia el documento y avanza al último registro ya publicado en el búfer. No borra archivos ni vacía la copia compartida. Eventos que el consumidor publique después pueden aparecer, aunque se originaran antes en una cola pendiente.
- **Contadores:** informan registros mostrados, retirados por límites visuales y no recuperables porque el búfer ya los recortó. El visor es una ventana reciente, no un archivo de auditoría completo.

Los archivos siguen siendo la referencia completa bajo operación correcta. Ante error real de I/O se conserva la señal de fallo de T42; no se promete un archivo íntegro cuando falló su destino. Una lectura visual no modifica las barreras flush/cierre. El cierre de ventana puede disponer el visor antes de mostrar todo el último lote; el cierre del logging sí drena sus registros a archivos según el contrato existente.

## Adaptación de tamaño

Los controles del sistema se fijan arriba, con el resultado de la última acción en un área de alto acotado. Un divisor vertical reparte espacio entre zona de trabajo y visor; dentro, otro divisor conserva configuración/llamadas y cabinas. La zona de trabajo tiene scroll vertical/horizontal para alcanzar contenido que no cabe, además de los scrolls de cada panel.

MainWindow toma el área útil informada por GraphicsEnvironment para la nueva ventana y centra un tamaño de hasta 1180×860 dentro de ella. El mínimo es 720×480 o el área disponible si fuera menor; ya no exige 1060×760. No se declara una ventana redimensionable a cualquier tamaño manteniendo todo simultáneamente visible: a tamaños pequeños se utiliza desplazamiento y los divisores.

CabinDashboardPanel mantiene pestañas desplazables y añade Cabina/Ver cabina para acceder directamente a cualquiera de las N, incluso fuera del área inicial. La selección no envía solicitudes ni cambia el estado del elevador. Se valida ID 1..N al pulsar Ver cabina.

## Archivos

- `logging/RecentEventBuffer.java`: retención acotada y cursores no destructivos.
- `logging/SessionLog.java`: copia textual después de enviar el registro original.
- `simulation/SimulationController.java`: acceso opcional al historial reciente.
- `ui/EventViewerPanel.java`: vista limitada, seguimiento, limpieza y contadores.
- `ui/SystemControlPanel.java`: composición y refresco compartido.
- `ui/CabinDashboardPanel.java`: navegación directa de cabinas.
- `ui/MainWindow.java`: geometría ajustada al escritorio.
- `ui/EventViewerScenarios.java` y `ui/EventViewerTest.java`: 40 escenarios y adaptadores JUnit.
- README, plan, cierre histórico T50 y tres Markdown T51.

T52/T53 permanecen pendientes: no se da por completada la validación global de EDT bajo carga ni el recorrido completo por haber conectado este visor.

## Incorporación y comandos en Windows

Extraer el ZIP fuente completo sobre la raíz Maven, sin anidar otra carpeta elevator-manager. Excluye .git, target y logs generados; conservar el repositorio local y cambios personales.

```powershell
Set-Location 'D:\Users\David\Desktop\workspace\java\elevator-manager'
git status --short
Expand-Archive -LiteralPath "$HOME\Downloads\T51-visor-eventos-y-tamano.zip" -DestinationPath . -Force
mvn "-Dtest=EventViewerTest" test
mvn "-Dtest=EventViewerTest,CabinPanelsTest,HallCallsTest,SystemControlsTest,ConfigurationFormTest" test
mvn clean verify
java -jar .\target\elevator-manager-0.1.0-SNAPSHOT.jar
```

Se esperan 40 específicas, 144 combinadas y 722 acumuladas sin fallos, errores u omisiones y BUILD SUCCESS. Maven no está instalado en este entorno.

## Recorrido visual

1. Abrir y configurar N=2/P=8 con movimiento=500 ms y espera=300 ms. Start, destinos internos y llamadas externas deben producir eventos de Manejador y Elevador, sin comandos de consola adicionales durante el uso.
2. Comprobar desplazamiento vertical/horizontal del visor y alternar Seguir últimos. Desactivarlo no debe detener cabinas ni archivos.
3. Pulsar Limpiar visor: desaparecen los eventos ya mostrados. Realizar otra solicitud: aparecen eventos nuevos. Comprobar que los .log de la sesión conservan los anteriores.
4. Realizar Stop/continuación y Reset: el historial conserva trazas con sus épocas; los controles siguen habilitándose como antes.
5. Reducir la ventana, mover divisores y usar los scrolls para alcanzar formulario/llamadas/cabinas fuera de vista. Los controles principales deben continuar accesibles arriba. Verificar también escala de pantalla y barra de tareas habituales.
6. En otra ejecución, configurar N=25/P=3, o una cantidad adecuada a los recursos del equipo. Usar Cabina=25 y Ver cabina; comprobar sus datos y botones. La navegación no debe enviar trabajo por sí sola.
7. Generar suficientes eventos para observar recorte del historial y sus contadores. No es necesario forzar el desbordamiento manualmente: las pruebas específicas comprueban 1200 registros completos en archivo y los límites de ambas capas.
8. Cerrar con botón y X durante movimiento y revisar retorno al prompt. Los archivos deben cerrar correctamente; no se exige que el visor muestre cada evento de terminación antes de desaparecer.

Los renders headless y pruebas de geometría no sustituyen esta revisión de ventana nativa.

## Commit sugerido

Después de validar Maven y recorrido visual:

```powershell
git diff --check
git add -- README.md docs/01-analisis-y-plan.md docs/implementacion/T50-paneles-y-botones-cabina.md docs/comprobaciones/T50-validacion-local.md docs/implementacion/T51-visor-eventos-y-tamano.md docs/implementacion/T51-codigo-completo.md docs/comprobaciones/T51-validacion-local.md src/main/java/com/elevatormanager/logging/RecentEventBuffer.java src/main/java/com/elevatormanager/logging/SessionLog.java src/main/java/com/elevatormanager/simulation/SimulationController.java src/main/java/com/elevatormanager/ui/EventViewerPanel.java src/main/java/com/elevatormanager/ui/SystemControlPanel.java src/main/java/com/elevatormanager/ui/CabinDashboardPanel.java src/main/java/com/elevatormanager/ui/MainWindow.java src/test/java/com/elevatormanager/ui/EventViewerScenarios.java src/test/java/com/elevatormanager/ui/EventViewerTest.java
git diff --cached --check
git commit -m "T51: agregar visor de eventos acotado y adaptar ventana"
git status --short
```

T51 se cerrará con resultados de David. La siguiente tarjeta es T52 — Garantizar actualización segura en EDT.
