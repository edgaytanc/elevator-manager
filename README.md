# Elevator Manager

Simulador de elevadores en Java 17, Swing y Maven. **Etapa actual: fase 8, T47 — formulario de configuración.** David confirmó el cierre de las fases 1–7; la base adjunta es T46, commit `84f6261`.

El motor ya dispone de trabajadores independientes por elevador, manejador con hilo propio, solicitudes con herencia real, Stop/continuación, Reset, cierre asíncrono, logs e importación validada en RESET. El formulario de entrada captura N, P y ambos tiempos en milisegundos. Sus controles completos de simulación se conectarán desde T48.

## Entorno

- Objetivo de lenguaje y bytecode: Java 17, establecido en pom.xml.
- Equipo de David: Windows, PowerShell, VS Code, JDK 27 y Maven 3.8.6.
- Entrada: `com.elevatormanager.app.ElevatorManager`.
- Swing, concurrencia y logging pertenecen al JDK; JUnit 5 solo se utiliza en pruebas.
- `JAVA_HOME` debe apuntar a la raíz del JDK, sin `bin`; no se fijan rutas personales en el POM.

## Incorporación y construcción

El ZIP contiene el proyecto fuente completo con rutas relativas a la raíz Maven, sin .git, target ni logs generados. Extraer sobre la raíz de elevator-manager, conservando el repositorio local. Los archivos pom.xml, README.md, src y docs deben quedar al mismo nivel.

```powershell
mvn "-Dtest=ConfigurationFormTest" test
mvn clean verify
java -jar .\target\elevator-manager-0.1.0-SNAPSHOT.jar
```

T47 agrega 16 pruebas. Se esperan 594 pruebas acumuladas partiendo de las 578 reportadas para T46. Maven y la revisión visual en Windows permanecen pendientes para esta entrega; el informe local distingue los escenarios independientes de la suite JUnit.

## Formulario actual

1. Completar cantidad de elevadores N y pisos P: enteros mayores que cero.
2. Completar movimiento y espera en un piso: enteros positivos en ms, hasta `SimulationConfig.MAX_TIME_MILLIS`.
3. Pulsar **Validar configuración** o Enter desde un campo. Todos los errores se muestran debajo de sus campos; una entrada inválida no entrega configuración.
4. Corregir y confirmar. Cualquier edición invalida la confirmación anterior.
5. Cerrar con **Cerrar** o X; la aplicación debe devolver el prompt.

Validar no crea sesión ni mueve cabinas. Start/Stop/Reset e importación desde esta ventana corresponden a T48; las capacidades del motor y los diagnósticos previos permanecen disponibles.

## Documentación

- [Plan y estado de tareas](docs/01-analisis-y-plan.md).
- [Implementación, incorporación y prueba visual T47](docs/implementacion/T47-formulario-configuracion.md).
- [Código completo T47](docs/implementacion/T47-codigo-completo.md).
- [Validación local T47](docs/comprobaciones/T47-validacion-local.md).
- [Formato de archivo de solicitudes](docs/07-formato-archivo-solicitudes.md).
- [Catálogo de ejemplos](src/main/resources/examples/requests/README.md).

Los informes anteriores registran el estado de su entrega original; el plan y esta portada reflejan el avance actual.
