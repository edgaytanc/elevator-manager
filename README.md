# Elevator Manager

Simulador de elevadores en Java 17, Swing y Maven. **Etapa actual: fase 8, T50 — paneles y botones internos de cabina.** Fases 1–7 y T47–T49 cerradas; base validada T49, commit `2b17a64`, 650 pruebas satisfactorias.

El motor ya dispone de trabajadores independientes por elevador, manejador con hilo propio, solicitudes con herencia real, Stop/continuación, Reset, cierre asíncrono, logs e importación validada en RESET. El formulario de entrada captura N, P y ambos tiempos en milisegundos. T48 conecta preparación de sesión, Start, Stop/continuación, Reset, carga de archivos en RESET y cierre con confirmación real.

## Entorno

- Objetivo de lenguaje y bytecode: Java 17, establecido en pom.xml.
- Equipo de David: Windows, PowerShell, VS Code, JDK 27 y Maven 3.8.6.
- Entrada: `com.elevatormanager.app.ElevatorManager`.
- Swing, concurrencia y logging pertenecen al JDK; JUnit 5 solo se utiliza en pruebas.
- `JAVA_HOME` debe apuntar a la raíz del JDK, sin `bin`; no se fijan rutas personales en el POM.

## Incorporación y construcción

El ZIP contiene el proyecto fuente completo con rutas relativas a la raíz Maven, sin .git, target ni logs generados. Extraer sobre la raíz de elevator-manager, conservando el repositorio local. Los archivos pom.xml, README.md, src y docs deben quedar al mismo nivel.

```powershell
mvn "-Dtest=CabinPanelsTest,HallCallsTest,SystemControlsTest,ConfigurationFormTest" test
mvn clean verify
java -jar .\target\elevator-manager-0.1.0-SNAPSHOT.jar
```

T50 agrega 32 pruebas. Se esperan 32 específicas de CabinPanelsTest, 104 junto a llamadas/controles/formulario y 682 acumuladas. Maven y la revisión visual en Windows permanecen pendientes para esta entrega; el informe local distingue los escenarios independientes de la suite JUnit.

## Formulario actual

1. Completar cantidad de elevadores N y pisos P: enteros mayores que cero.
2. Completar movimiento y espera en un piso: enteros positivos en ms, hasta `SimulationConfig.MAX_TIME_MILLIS`.
3. Pulsar **Validar configuración** o Enter desde un campo. Todos los errores se muestran debajo de sus campos; una entrada inválida no entrega configuración.
4. Corregir y confirmar. Se prepara una sesión fuera del EDT; sus parámetros quedan bloqueados durante toda la sesión, incluso después de Reset.
5. En RESET se puede cargar un archivo. Cargar prepara el lote sin mover cabinas; Start lo distribuye.
6. Start inicia o continúa desde STOPPED. Stop conserva trabajo; Reset limpia y retorna físicamente al piso 1.
7. Cerrar con **Cerrar** o X; espera actores, lectura y logs antes de devolver el prompt.

Validar prepara la sesión, pero solo Start habilita la ejecución normal. Reset puede iniciar los trabajadores necesarios para confirmar limpieza y retorno sin admitir trabajo nuevo. Las llamadas externas ya se ingresan en el panel compartido: seleccionar piso y pulsar Subir/Bajar mientras RUNNING. Cada pestaña de elevador permite solicitar destinos internos y consultar piso, dirección, fase y pendientes. También se puede generar movimiento importando un lote y pulsando Start.

## Llamadas externas

El selector está limitado a 1..P; al escribir un piso, confirmar con Enter. Subir está deshabilitado en P y Bajar en 1; con P=1 ambos están deshabilitados. Los botones solo admiten llamadas en RUNNING. Cada llamada pasa por el manejador: la interfaz no elige cabina ni modifica agendas. Repetir una llamada activa utiliza la deduplicación existente. El mensaje de envío no confirma atención; la atención real se registra en logs. Las recogidas asignadas se muestran en la pestaña de su cabina hasta la atención; el visor de eventos corresponde a T51.

## Cabinas y destinos internos

Se crea una pestaña por elevador. Cada una muestra piso actual/siguiente, dirección y estado. Los botones de destino se habilitan en RUNNING y usan páginas de hasta 20 pisos; el resumen enumera todos los destinos pendientes, aunque su página no esté visible. Amarillo y el indicador ● significan destino admitido y pendiente. Enviar no equivale a admisión ni atención: las luces se actualizan desde snapshots. Stop conserva pendientes y luces; Reset cancela las solicitudes y las limpia. Las llamadas externas se muestran por piso y sentido en la cabina elegida por el manejador.

## Logs de la sesión

Cada configuración confirmada abre un directorio `logs/sesion-<UUID>/` con `elevador1.log` ... `elevadorN.log` y `manejador.log`. La creación, lectura y liberación se realizan fuera del EDT. El área de texto del panel muestra el resultado de la última acción; el visor de eventos corresponde a T51.

## Documentación

- [Plan y estado de tareas](docs/01-analisis-y-plan.md).
- [Implementación, incorporación y prueba visual T50](docs/implementacion/T50-paneles-y-botones-cabina.md).
- [Código completo T50](docs/implementacion/T50-codigo-completo.md).
- [Validación local T50](docs/comprobaciones/T50-validacion-local.md).
- [Implementación, incorporación y prueba visual T49](docs/implementacion/T49-llamadas-externas-compartidas.md).
- [Código completo T49](docs/implementacion/T49-codigo-completo.md).
- [Validación local T49](docs/comprobaciones/T49-validacion-local.md).
- [Implementación, incorporación y prueba visual T48](docs/implementacion/T48-panel-controles-sistema.md).
- [Código completo T48](docs/implementacion/T48-codigo-completo.md).
- [Validación local T48](docs/comprobaciones/T48-validacion-local.md).
- [Implementación, incorporación y prueba visual T47](docs/implementacion/T47-formulario-configuracion.md).
- [Código completo T47](docs/implementacion/T47-codigo-completo.md).
- [Validación local T47](docs/comprobaciones/T47-validacion-local.md).
- [Formato de archivo de solicitudes](docs/07-formato-archivo-solicitudes.md).
- [Catálogo de ejemplos](src/main/resources/examples/requests/README.md).

Los informes anteriores registran el estado de su entrega original; el plan y esta portada reflejan el avance actual.
