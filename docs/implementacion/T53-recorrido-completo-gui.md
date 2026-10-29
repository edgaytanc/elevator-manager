# T53 — Validar el recorrido completo desde GUI

- **Base incorporada por David:** T52, commit `6eec38e`, 754 pruebas Maven satisfactorias y árbol limpio.
- **Entrega:** pruebas integradas, archivo de recorrido y comprobación manual reproducible.
- **Estado:** automatización implementada; Maven y recorrido visual de esta entrega pendientes en Windows.

## Criterio

El usuario debe poder configurar, iniciar, llamar desde un piso, elegir destino de cabina, detener, continuar, resetear e importar mediante controles gráficos. Después de abrir la aplicación no necesita editar código ni introducir órdenes en consola. El arranque del JAR y la verificación de compilación quedan fuera de las acciones del simulador.

No se modifica el comportamiento de producción: T47–T52 ya conectan las operaciones necesarias. T53 comprueba su composición en una sesión completa y añade material para la aceptación nativa.

## Recorrido automatizado

GuiJourneyScenarios crea el SystemControlPanel real, incluidos formulario, llamadas, cabinas, visor, temporizador y UiSnapshotPump. La apertura utiliza SimulationController y SessionLog reales, con archivos temporales por prueba. La copia de consola se dirige a un sumidero local del fixture; se comprueba el contenido de los archivos sin inundar la salida de pruebas. Todos los recursos se cierran antes de borrar el directorio temporal.

Las acciones de negocio usan JButton.doClick, campos, Enter y selectores de Swing en el EDT. No se invocan start/stop/reset/submit/importRequests directamente desde las pruebas nuevas. El controlador se consulta para corroborar admisión, atención, identidad, época y terminación. Las esperas de comprobación están fuera del EDT, con condiciones y plazo máximo. No se fuerzan llamadas a updateView: se ejercita el temporizador de producción.

El selector JFileChooser se sustituye por un proveedor de ruta/cancelación; a partir de esa frontera actúan RequestImportHandler, el lector y el validador reales. El cierre usa el botón conectado a WindowCloseHandler y espera al motor, al logger y al capturador. No se crea un JFrame en estas pruebas; tampoco se utiliza Robot. Esto prueba integración de componentes, no la interacción física con el escritorio.

## Escenarios

| Prueba | Resultado exigido |
| --- | --- |
| Recorrido con dos cabinas | Configurar, Start, llamada atendida, destino encendido, Stop, continuación, atención/apagado, Reset, importación de cuatro líneas, Start y cierre |
| Recorrido con una cabina | Misma secuencia con lote de tres líneas y manejador único |
| Corrección de configuración | Entrada inválida no crea sesión; corregir y pulsar Enter prepara una sesión válida |
| Edificio de un piso | Sin llamadas externas imposibles; destino al mismo piso se atiende y Reset conserva 1/UP |
| Cargas cancelada, inválida e inexistente | Diagnóstico visible y conservación íntegra del lote válido anterior |
| Estados globales | Carga bloqueada en RUNNING/STOPPED; solicitudes bloqueadas en RESET/STOPPED; Reset restituye carga |
| Reset con trabajo admitido | Recogida visible en cabina asignada; limpieza de agendas y luces de ambas cabinas, retorno 1/UP; continuar no revive trabajo |
| Navegación y paginación | Cuatro cabinas y 25 pisos; expulsión de caché y reconstrucción de destino 25 pendiente mediante controles |
| Visor y archivos | Desactivar seguimiento y limpiar vista no borra registros; nuevos eventos siguen apareciendo |
| Cierre durante movimiento | Botón Cerrar termina actores/capturador y bloquea los controles, sin fallo de sesión |

Los recorridos principales comprueban que Stop/continuación conservan la misma sesión y el destino pendiente. El lote importado se prepara sin movimiento, se consume en Start y no se redistribuye al continuar. Se verifican llamadas SERVED, agendas vacías y fase IDLE al completar el lote. Cada archivo de elevador contiene RESET_COMPLETED y WORKER_TERMINATED; el del manejador contiene MANAGER_TERMINATED.

## Archivo de demostración

`src/main/resources/examples/requests/23-recorrido-gui.txt` está pensado para N=2, P=8. Contiene CABIN 1 8, CABIN 2 6, HALL 3 UP y HALL 7 DOWN. Se usa ese mismo archivo del proyecto en el recorrido automatizado de dos cabinas. En Windows se elige desde el diálogo Cargar archivo; no hay que escribir instrucciones ni modificar archivos durante el uso.

## Validación y cierre

Ejecutar GuiJourneyTest, el grupo completo de interfaz y mvn clean verify. Se esperan 10, 186 y 764 pruebas respectivamente. El informe [T53-validacion-local.md](../comprobaciones/T53-validacion-local.md) distingue las comprobaciones locales de Maven. El guion [T53-recorrido-gui.md](../comprobaciones/T53-recorrido-gui.md) permite registrar la aceptación nativa.

T53 y la fase 8 permanecen pendientes de los resultados del usuario. Esta entrega no inicia T54 ni cambia la semántica de Stop por una pausa temporal.
