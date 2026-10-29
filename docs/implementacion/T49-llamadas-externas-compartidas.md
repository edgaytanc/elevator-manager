# T49 — Crear llamadas externas compartidas por piso

> Cierre funcional confirmado al iniciar T50: 28 pruebas específicas, 72 combinadas y 650 acumuladas, BUILD SUCCESS, commit `2b17a64` y árbol limpio. Los registros acreditan llamadas servidas, Stop/continuación y cierre durante Reset; no un retorno global completo antes del cierre. El informe siguiente conserva su estado original.

- **Fecha:** 2026-10-28, Guatemala.
- **Fase:** 8 — Construir la interfaz gráfica obligatoria.
- **Base:** T48 validada en `0bae607`, 28 pruebas específicas, 44 combinadas y 622 acumuladas; ejecución registrada con ambas cabinas, Stop/continuación, Reset y cierre.
- **Estado:** implementada con escenarios locales; pendiente de Maven y revisión nativa en Windows.

## Resultado

HallCallPanel implementa una estación compartida del edificio con selector de piso 1..P y botones Subir/Bajar. Cumple el alcance de permitir seleccionar piso y sentido sin asociar la llamada a una cabina concreta. Se usan dos botones independientemente de N y P, evitando crear P filas o N copias de botones externos. MainWindow amplía su tamaño mínimo para alojar formulario, llamadas y controles. El formulario conserva su desplazamiento.

SystemControlPanel vincula la sesión cuando termina la preparación asíncrona de T48 y comparte su Timer Swing de 100 ms. HallCallPanel no crea hilos, temporizadores, controladores ni lectores. No consume eventos ni recorre snapshots o llamadas del manejador en el refresco: solo consulta estado/época y actualiza los controles.

## Admisión y extremos

| Situación | Subir | Bajar |
| --- | --- | --- |
| Sin sesión / antes de Start / STOPPED | Deshabilitado | Deshabilitado |
| RUNNING, piso 1 y P>1 | Habilitado | Deshabilitado |
| RUNNING, piso intermedio | Habilitado | Habilitado |
| RUNNING, piso P y P>1 | Deshabilitado | Habilitado |
| P=1 | Deshabilitado | Deshabilitado |
| Stop/Reset/cierre solicitado, transición o control pendiente | Deshabilitado | Deshabilitado |

El selector puede prepararse en estados estables sin ejecución, pero enviar requiere RUNNING. Las flechas del spinner respetan 1..P. Al escribir, confirmar el piso con Enter para actualizar sus botones; el manejador de pulsación también valida el texto actual antes de enviar. Letras, decimales, notación científica, vacío, overflow int, cero y pisos fuera de rango no producen llamada ni reutilizan silenciosamente el piso anterior. Los extremos escritos que aún no estaban confirmados también se rechazan y actualizan la deshabilitación correspondiente. El NumberFormatter del editor valida texto entero antes de convertir: Enter no puede truncar un decimal ni aceptar un desbordamiento. La política PERSIST conserva un texto inválido para mostrar el error al intentar llamar, en lugar de restaurar silenciosamente el piso anterior.

## Recorrido de una llamada

1. El EDT toma piso, dirección y época actual y crea HallRequest con UUID nuevo e Instant.now.
2. SimulationController.submit valida estado, época y reglas polimórficas de Request/HallRequest en su coordinador.
3. El controlador envía al manejador, que aplica su deduplicación por piso/sentido y elige cabina con el algoritmo existente.
4. La respuesta se publica en EDT. ENQUEUED indica envío, no asignación confirmada ni servicio físico.

La interfaz no construye Command.Pickup, no pide un ID de cabina ni escribe en las agendas. Cada pulsación tiene identidad propia; la deduplicación canónica continúa siendo responsabilidad del manejador. Sentidos opuestos en el mismo piso conservan identidades canónicas distintas.

El texto del panel describe el resultado del último envío. No es una luz de llamada ni un visor de servicio: que muestre atención pendiente de confirmación no significa que una llamada ya atendida siga activa en el motor. La atención real se confirma por los eventos y logs existentes; los paneles de cabina y su seguimiento corresponden a T50/T51.

## EDT y respuestas tardías

Los componentes se construyen, vinculan y refrescan desde EDT. El envío no hace get/join ni espera una cabina. Cada mensaje se condiciona a identidad de sesión, época, RUNNING, disponibilidad gráfica y secuencia de envío. Cambiar estado, Reset, cierre o sesión invalida mensajes anteriores; si dos respuestas llegan en orden inverso solo la última pulsación actualiza el texto. Esto no cancela llamadas admitidas ni elimina trabajo del manejador.

Los bloqueos visuales de T48 se aplican inmediatamente al solicitar Stop/Reset/cierre, antes de que el controlador publique su nuevo estado. Además, el controlador vuelve a validar la admisión, de modo que un clic que compita con Stop o Reset no autoriza trabajo fuera del estado vigente.

## Archivos

- `ui/HallCallPanel.java`: estación compartida, validación y callback seguro.
- `ui/SystemControlPanel.java`: composición, vinculación y refresco compartido.
- `ui/MainWindow.java`: ajuste del tamaño mínimo a 820×760.
- `ui/HallCallsScenarios.java` y `ui/HallCallsTest.java`: 28 escenarios y adaptadores JUnit.
- README, plan, constancia de cierre T48 y tres Markdown de T49.

No cambian el motor, parser, configuración, algoritmos, protocolos, POM ni herencia de solicitudes. T50–T53 permanecen pendientes.

## Incorporación y pruebas en Windows

Extraer el ZIP fuente completo sobre la raíz Maven del repositorio. Incluye todas las fuentes previas; excluye .git, target y registros generados. Mantener los cambios personales antes de extraer y evitar otra carpeta elevator-manager anidada.

```powershell
Set-Location 'D:\Users\David\Desktop\workspace\java\elevator-manager'
git status --short
Expand-Archive -LiteralPath "$HOME\Downloads\T49-llamadas-externas-compartidas.zip" -DestinationPath . -Force
mvn "-Dtest=HallCallsTest" test
mvn "-Dtest=HallCallsTest,SystemControlsTest,ConfigurationFormTest" test
mvn clean verify
java -jar .\target\elevator-manager-0.1.0-SNAPSHOT.jar
```

Se esperan 28 específicas, 72 combinadas y 650 acumuladas, sin fallos, errores u omisiones, con BUILD SUCCESS. Maven no está instalado en el entorno de esta entrega; no se atribuye una ejecución Maven local.

## Recorrido visual

1. Abrir: llamadas deshabilitadas, sin selector de cabina. Configurar N=2, P=8, movimiento=1000 ms y espera=300 ms.
2. Validar: queda RESET y todavía no admite llamadas. Pulsar Start: RUNNING y habilitación por piso.
3. Seleccionar piso 1: solo Subir. Seleccionar 8: solo Bajar. Seleccionar 3: ambos.
4. En 3 pulsar Subir y Bajar; comprobar envíos al manejador y movimiento/servicio por consola o logs. La cabina la decide el manejador.
5. Seleccionar 6 y pulsar Subir dos veces durante la misma llamada activa. El motor debe mantener una canónica; no se exige una luz gráfica de llamada en T49.
6. Escribir abc, 2.5 y 99 desde un piso intermedio e intentar llamar: error visible y ninguna llamada usando el piso anterior. Corregir con un piso válido y Enter.
7. Detener: llamadas bloqueadas de inmediato. En STOPPED siguen bloqueadas. Start habilita de nuevo según piso.
8. Reset: llamadas bloqueadas hasta completar retorno; en RESET aún requieren Start. Volver a iniciar y realizar otra llamada para comprobar época actual.
9. Cerrar durante un viaje y comprobar retorno al prompt; repetir con X. No deben aparecer mensajes tardíos que rehabiliten controles.
10. Abrir otra ejecución con N=1 y P=1: ambos sentidos siempre deshabilitados. Comprobar ventana a escala habitual de Windows y acceso al formulario.

Las pruebas usan componentes y EDT reales, pero no una ventana nativa; la revisión visual anterior sigue pendiente de David. Los registros de T48 se conservan como evidencia previa, sin presentarlos como ejecución de T49.

## Commit sugerido

Después de confirmar Maven y la revisión visual:

```powershell
git diff --check
git add -- README.md docs/01-analisis-y-plan.md docs/implementacion/T48-panel-controles-sistema.md docs/comprobaciones/T48-validacion-local.md docs/implementacion/T49-llamadas-externas-compartidas.md docs/implementacion/T49-codigo-completo.md docs/comprobaciones/T49-validacion-local.md src/main/java/com/elevatormanager/ui/HallCallPanel.java src/main/java/com/elevatormanager/ui/SystemControlPanel.java src/main/java/com/elevatormanager/ui/MainWindow.java src/test/java/com/elevatormanager/ui/HallCallsScenarios.java src/test/java/com/elevatormanager/ui/HallCallsTest.java
git diff --cached --check
git commit -m "T49: crear llamadas externas compartidas por piso"
git status --short
```

T49 se cerrará con los resultados del usuario. La siguiente tarjeta es T50 — Crear paneles y botones internos de cabina.
