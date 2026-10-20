# Elevator Manager

Simulador de elevadores en Java. **Etapa actual: preparación del entorno y construcción (T07/T08).** La aplicación entregada en esta etapa únicamente verifica la entrada del JAR desde consola; todavía no contiene interfaz gráfica, motor, herencia de solicitudes ni trabajadores del simulador. Esos requisitos se implementarán según el plan, sin introducir herencia o hilos artificiales para una comprobación de empaquetado.

## 1. Entorno reportado

- Windows 11, PowerShell y VS Code.
- JDK Oracle 27, con `java`, `javac` y `jar` funcionando.
- Maven 3.8.6, usando `C:\Program Files\Java\jdk-27`.
- Git 2.56.0.windows.2; repositorio local inicializado en rama `main`, sin primer commit al preparar esta entrega.
- Extension Pack for Java instalado según confirmación del usuario.

`JAVA_HOME` apunta a la raíz del JDK, sin `bin`. Las rutas de tu equipo no se fijan en el POM ni en la configuración compartida de VS Code.

## 2. Compilación y dependencias seleccionadas

| Componente | Versión/decisión |
| --- | --- |
| Nivel de lenguaje, APIs y bytecode | Java 17 mediante `maven.compiler.release=17`. |
| JDK de desarrollo del usuario | 27, pendiente de comprobar la construcción de este POM en su equipo. |
| Maven Clean Plugin | 3.4.1 |
| Maven Resources Plugin | 3.3.1 |
| Maven Compiler Plugin | 3.14.1 |
| Maven Surefire Plugin | 3.5.4 |
| Maven JAR Plugin | 3.4.2 |
| JUnit Jupiter | 5.13.4, alcance exclusivo de pruebas. |
| Versión de la aplicación | 0.1.0-SNAPSHOT, desarrollo; no equivale a la versión final prevista en el plan. |

Las versiones están fijadas para esta preparación; no se presentan como las últimas disponibles. Swing, concurrencia y logging pertenecen al JDK y no necesitan dependencias adicionales. Java 27 ejecuta las herramientas de desarrollo; el objetivo Java 17 del POM evita compilar accidentalmente el proyecto para Java 27.

No se incluyen pruebas vacías ni una prueba artificial para obtener una marca verde. JUnit está declarado; la primera ejecución de tests reales se comprobará cuando existan reglas del dominio. Un aviso `No tests to run` es esperable por ahora y no demuestra que el plan de pruebas se haya ejecutado.

## 3. Incorporar esta entrega

El ZIP contiene rutas relativas a la raíz del repositorio. Extraerlo en:

```text
D:\Users\David\Desktop\workspace\java\elevator-manager
```

`pom.xml`, `README.md`, los archivos de configuración y `src/` deben quedar al mismo nivel que la carpeta `docs/` existente. Esta entrega no incluye `.git/` ni reemplaza los documentos 01..06. Si el explorador propone una subcarpeta con el nombre del ZIP, entrar en ella y copiar su contenido a la raíz correcta.

Abrir **la carpeta raíz** con VS Code y aceptar la importación Maven si se solicita. No usar la opción de crear otro proyecto Java: esta carpeta ya será el proyecto Maven.

## 4. Comprobar construcción y ejecución

Desde la raíz, ejecutar primero:

```powershell
mvn clean verify
```

La primera ejecución requiere acceso a Maven Central para plugins y dependencias. Esperar `BUILD SUCCESS`. Si falla, conservar el error y detener los pasos siguientes; no omitir pruebas ni cambiar versiones a ciegas.

Después:

```powershell
java -jar .\target\elevator-manager-0.1.0-SNAPSHOT.jar
```

Salida esperada, con la versión real de Java de tu equipo:

```text
Elevator Manager - entorno preparado.
Versión de Java: 27
Etapa actual: T08 - configuración de Maven.
```

Todavía no debe abrirse una ventana. Para inspeccionar el nivel del bytecode:

```powershell
javap -verbose .\target\classes\com\elevatormanager\app\ElevatorManager.class |
    Select-String "major version"
```

Resultado esperado: `major version: 61`, correspondiente a Java 17. La comprobación definitiva de funcionamiento del producto sobre JDK 17 se realizará también durante la entrega; `--release` no sustituye las pruebas funcionales.

## 5. Primer commit

Solo después de construir y ejecutar correctamente:

```powershell
git status --short
git check-ignore target
git add .gitignore .gitattributes .editorconfig .vscode pom.xml README.md src docs
git diff --cached --stat
git status
```

Revisar que solo aparezcan fuentes, configuración y documentación previstos; `target/` no debe estar preparado para commit. Después:

```powershell
git commit -m "Configura base Maven y documentacion inicial"
```

Si Git solicita identidad, configurar nombre y correo reales antes de repetir el commit. No se asigna una identidad inventada ni se cambia la configuración global automáticamente. No se crea ni publica un remoto en esta etapa.

## 6. Estado de tareas

| Tarea | Evidencia actual | Qué falta para cerrarla |
| --- | --- | --- |
| T05 | Java, javac y jar reportados funcionales por el usuario. | Completada como preparación del JDK. |
| T06 | `mvn -version` funciona con JDK correcto. | `mvn clean verify` de este proyecto en el equipo del usuario. |
| T07 | VS Code/extensión y repositorio `main` inicializado. | Importación del proyecto y primer commit comprobados. |
| T08 | POM y entrada mínima preparados. | Construcción Maven, bytecode objetivo y JAR verificados en el equipo del usuario. |
| T09 | No realizada. | Comprobar Swing, hilo y logging temporalmente en la siguiente tarea. |
| T10–T11 | No completadas; `app/ElevatorManager` se anticipa solo para probar el empaquetado. | Estructura completa, composición de dependencias y ventana mínima. |

Los documentos 02..06 siguen siendo propuestas de diseño; esta configuración no implica aprobarlos ni declarar implementado el simulador. Tras verificar T08, actualizar las versiones concretas de herramientas en 02-decisiones.md durante la siguiente revisión documental, conservando su identidad e historial.

## 7. Advertencias conocidas

Maven 3.8.6 mostró en el equipo del usuario una advertencia de acceso nativo de Jansi 2.4.0 al ejecutarse sobre JDK 27. No impidió `mvn -version`. Esta entrega no modifica `MAVEN_OPTS` ni autoriza acceso nativo global para ocultarla. Si la construcción falla, distinguir la advertencia del mensaje real de error.

## 8. Validación de esta entrega

Se comprobaron estáticamente el XML del POM, los JSON, las versiones fijadas y la coincidencia entre paquete/clase y entrada configurada del JAR. El entorno de preparación dispone únicamente de un runtime Java 17, sin Maven ni las herramientas `javac`, `jar` y `javap`; por ello no se ejecutaron compilación, empaquetado ni pruebas de la aplicación. La construcción Maven y la ejecución sobre el JDK 27 del usuario están pendientes de los comandos de la sección 4. No se reportan como realizadas.

## 9. Referencias técnicas

- [Maven Compiler Plugin 3.14.1](https://maven.apache.org/plugins-archives/maven-compiler-plugin-3.14.1/plugin-info.html).
- [Maven Surefire Plugin 3.5.4](https://maven.apache.org/surefire-archives/surefire-3.5.4/maven-surefire-plugin/plugin-info.html).
- [Manifiesto del JAR](https://maven.apache.org/plugins/maven-jar-plugin/examples/manifest-customization.html).
- [JUnit 5.13.4](https://docs.junit.org/5.13.4/release-notes/).

## 10. Historial

| Versión documental | Fecha | Cambio |
| --- | --- | --- |
| 0.1.0 | 2026-10-20, Guatemala | Preparación de T07/T08, configuración Maven y guía de comprobación/primer commit. |
