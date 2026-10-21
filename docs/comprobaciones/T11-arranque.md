# T11 — Crear la entrada ElevatorManager

- **Proyecto:** Elevator Manager.
- **Fase:** 2 — Crear carpetas y diseñar la arquitectura.
- **Fecha:** 2026-10-20, Guatemala.
- **Estado:** Completada y validada en el equipo de desarrollo.
- **Precondición:** T10 terminada por el usuario.

## Objetivo y aceptación

Preparar main, arranque gráfico en EDT y composición inicial. La ventana debe abrirse desde el IDE y desde el JAR construido, y cerrarse correctamente por su botón y por la X.

## Archivos

| Ruta | Cambio |
| --- | --- |
| src/main/java/com/elevatormanager/app/ElevatorManager.java | Sustituye la entrada de consola de T08 por el arranque Swing. |
| src/main/java/com/elevatormanager/ui/MainWindow.java | Añade una ventana mínima con mensaje y botón Cerrar. |
| docs/comprobaciones/T11-arranque.md | Documenta alcance, validación y cierre. |

El POM existente ya configura Main-Class como com.elevatormanager.app.ElevatorManager y genera elevator-manager-0.1.0-SNAPSHOT.jar. Esta entrega no modifica ese POM, el diagnóstico de T09 ni los package-info de T10.

## Responsabilidades y concurrencia

ElevatorManager compone la ventana y programa su creación con SwingUtilities.invokeLater. MainWindow contiene su JFrame y exige EDT al construirse y mostrarse. La ventana no conoce clases del dominio ni crea trabajadores.

El registro VENTANA_ABIERTA; EDT=true confirma que se invocó setVisible desde el EDT. Se utiliza java.util.logging con su salida a consola; los archivos por elevador se implementarán en las tarjetas de logging. La comprobación de T09 conserva su registro independiente.

Esta tarjeta establece la composición disponible: entrada → ventana. No introduce dependencias ficticias para controladores o servicios todavía no implementados. La herencia de dominio Request → HallRequest/CabinRequest se diseñará en T13 e implementará en T18. Los trabajadores del manejador y de las cabinas se incorporarán en las tarjetas previstas. La ventana requiere una sesión gráfica de escritorio.

Cerrar usa dispose, por botón o X. En esta etapa no existen trabajadores del simulador que detener; cuando se incorporen, el cierre deberá coordinarse fuera del EDT antes de liberar recursos y confirmar su terminación.

## Aplicación

Extraer el ZIP temporalmente y copiar sus carpetas src y docs sobre la raíz del proyecto existente. Reemplazar únicamente ElevatorManager.java; MainWindow.java y este documento son nuevos. No crear un segundo proyecto ni un nivel adicional de carpeta.

## Validación desde terminal y JAR

Cerrar ventanas de comprobación anteriores. Desde PowerShell:

```powershell
Set-Location 'D:\Users\David\Desktop\workspace\java\elevator-manager'
mvn clean verify
java -jar .\target\elevator-manager-0.1.0-SNAPSHOT.jar
```

Esperar BUILD SUCCESS. Debe abrirse una ventana titulada Elevator Manager, con el mensaje T11 y botón Cerrar. La terminal debe mostrar INICIO_T11 con la versión real de Java y VENTANA_ABIERTA; EDT=true. La fecha y formato del logger dependen del equipo.

Mover y redimensionar la ventana; debe responder. Pulsar Cerrar y comprobar que el proceso termina y PowerShell recupera su prompt. Ejecutar otra vez java -jar y cerrar con la X: también debe terminar sin excepciones. No debe aparecer el mensaje Etapa actual: T08 de la entrada antigua.

## Validación desde VS Code

Abrir src/main/java/com/elevatormanager/app/ElevatorManager.java y ejecutar Run Java sobre main. Comprobar la misma ventana y EDT=true en la consola seleccionada por VS Code. Cerrarla y verificar que la ejecución termina. La compilación Maven por sí sola no acredita esta comprobación.

## Comprobación de cambios

```powershell
git diff --check
git diff -- src/main/java/com/elevatormanager/app/ElevatorManager.java
git status --short
```

Deben figurar la entrada modificada, la ventana nueva y este documento. No deben aparecer modificaciones en pom.xml ni EnvironmentCheck.java causadas por esta entrega. Tampoco deben versionarse target o logs.

## Cierre

Registrar aquí los resultados observados y cambiar el estado a Completada y validada en el equipo de desarrollo solo cuando pasen todas las comprobaciones. Después:

```powershell
git add -- src/main/java/com/elevatormanager/app/ElevatorManager.java src/main/java/com/elevatormanager/ui/MainWindow.java docs/comprobaciones/T11-arranque.md
git diff --cached --stat
git diff --cached --check
```

Revisar que solo se prepararon los cambios previstos. Si son correctos:

```powershell
git commit -m "T11: iniciar ventana Swing desde ElevatorManager"
git status --short
```

Entonces mover T11 a Terminada. Próxima tarjeta: T12 — Diseñar componentes y contratos.

## Verificación de la entrega

Se comprobaron estáticamente rutas, paquetes, contenido del ZIP y compatibilidad del nombre de entrada con el POM recuperado. No se ejecutaron javac, Maven ni la ventana en este entorno, que no dispone de JDK; esas verificaciones quedan pendientes en el equipo de desarrollo. No se añaden pruebas automáticas que repliquen el montaje gráfico.
