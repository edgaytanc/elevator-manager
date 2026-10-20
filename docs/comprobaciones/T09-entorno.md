# T09 — Comprobación de Swing, hilos y logging

- **Proyecto:** Elevator Manager.
- **Versión:** 0.1.0.
- **Fecha:** 2026-10-20, Guatemala.
- **Estado:** diagnóstico preparado; compilación y modo consola verificados en preparación; GUI y cierre visual pendientes en Windows del usuario.
- **Tarea:** T09, fase 1 del plan Kanban.

## 1. Punto de partida confirmado

El usuario construyó y ejecutó el proyecto con Maven 3.8.6/JDK 27: `BUILD SUCCESS`, JAR funcional y bytecode `major version: 61` (Java 17). El repositorio quedó con commit inicial `b93b390` y árbol limpio. Por tanto T05, T06, T07 y T08 cuentan con las comprobaciones correspondientes de preparación.

T09 verifica que el equipo puede abrir Swing, ejecutar trabajo de fondo sin congelar el EDT y escribir registros a consola y archivo. Todavía no prueba elevadores, decisiones de asignación ni herencia de solicitudes.

## 2. Archivos de esta entrega

```text
src/main/java/com/elevatormanager/diagnostics/EnvironmentCheck.java
docs/comprobaciones/T09-entorno.md
```

Extraer el ZIP en la raíz existente `elevator-manager`. No cambia `ElevatorManager.java`, `pom.xml`, `.git/` ni los documentos 01..06. La subcarpeta de comprobaciones guarda guías técnicas auxiliares; no sustituye el futuro manual ni las evidencias finales.

El diagnóstico se conserva aislado y puede retirarse en la limpieza final si se documenta. No introduce lógica temporal en el motor ni reemplaza el diseño de trabajadores de cada cabina.

## 3. Funcionamiento esperado

- `main` configura un logger con `FileHandler` y `ConsoleHandler`, ambos UTF-8; desactiva handlers del padre para evitar duplicaciones.
- Swing crea componentes en el EDT mediante `SwingUtilities.invokeLater`.
- Un hilo real llamado `t09-worker` ejecuta cinco pasos de aproximadamente un segundo. La espera temporal solo ocurre en ese hilo.
- El trabajador comunica avances a la GUI mediante tareas en el EDT; nunca modifica directamente sus componentes.
- Un temporizador Swing actualiza el contador de pulsos del EDT cada 250 ms aproximadamente. Su avance y el botón de respuesta permiten comprobar que la ventana sigue atendiendo eventos mientras el trabajador espera.
- El archivo generado es `logs/t09-entorno.log`, relativo al directorio desde el que se ejecutó el comando. Cada ejecución lo reemplaza; ejecutar una instancia a la vez.
- Cerrar interrumpe cooperativamente el trabajador, espera su terminación fuera del EDT, cierra handlers y dispone la ventana. No utiliza `Thread.stop()` ni termina la JVM con `System.exit()`.

`Thread.sleep` se utiliza únicamente para esta comprobación breve; el motor definitivo requerirá las esperas controlables de pausa/reset especificadas en la arquitectura.

## 4. Construcción y ejecución gráfica

Desde la raíz del proyecto, ejecutar:

```powershell
mvn clean verify
```

Esperar `BUILD SUCCESS`. Después:

```powershell
java -cp .\target\classes com.elevatormanager.diagnostics.EnvironmentCheck
```

Se usa classpath para seleccionar esta entrada auxiliar. `java -jar` sigue ejecutando el `ElevatorManager` mínimo de T08; no cambiar el manifiesto para esta tarea.

### Primera ejecución: terminar normalmente

1. Confirmar que se abre la ventana titulada **T09 - Comprobación del entorno**.
2. Mientras avanzan los cinco pasos, pulsar varias veces **Probar respuesta de la ventana**.
3. Confirmar que el contador del botón y los pulsos del EDT avanzan antes de terminar el trabajo de fondo. La ventana debe responder al movimiento del ratón y al arrastre.
4. Esperar el mensaje **Comprobación completada: 5/5**.
5. Cerrar con la X y confirmar que la terminal recupera el prompt.
6. Leer el archivo:

```powershell
Get-Content .\logs\t09-entorno.log -Encoding UTF8
```

Deben aparecer `HILO_INICIADO` con `nombre=t09-worker` y `EDT=false`, `PASO 1/5`..`PASO 5/5`, `TRABAJO_COMPLETADO`, `HILO_FINALIZANDO`, `CIERRE_SOLICITADO` y `CIERRE_COMPLETADO`. El orden de algunos mensajes de cierre puede variar porque se emiten desde hilos distintos. Cada paso debe aparecer una sola vez por ejecución en cada destino de logging.

### Segunda ejecución: cerrar antes de terminar

Ejecutar de nuevo el mismo comando y cerrar la ventana antes de los cinco segundos. Esta ejecución reemplaza el log anterior. Deben aparecer `TRABAJO_INTERRUMPIDO`, `HILO_FINALIZANDO` y `CIERRE_COMPLETADO`, sin proceso esperando en la terminal. Si el trabajo ya había terminado al cerrar, repetir cerrando antes; no confundir una finalización normal con una interrupción.

## 5. Modo consola auxiliar

Para comprobar únicamente hilo/logs sin escritorio:

```powershell
java -cp .\target\classes com.elevatormanager.diagnostics.EnvironmentCheck --console
```

Este modo espera la terminación del trabajador, cierra handlers y finaliza automáticamente. Registra `CONSOLA_VERIFICADA`. **No sustituye la prueba gráfica de T09.** Las opciones no reconocidas se rechazan.

## 6. Registro de comprobaciones

| Comprobación | Evidencia actual | Estado |
| --- | --- | --- |
| Compilar diagnóstico con objetivo Java 17 | Compilado mediante el módulo `jdk.compiler` del Java 17 disponible en preparación. Se comprobó major 61. | Verificada en preparación. |
| Hilo separado del EDT | Ejecución real en modo consola, cinco pasos en `t09-worker`, `EDT=false`. | Verificada en preparación. |
| Registros a consola y archivo | Salida capturada y archivo UTF-8 con cinco pasos únicos, sin `TRABAJO_FALLIDO`. | Verificada en preparación. |
| Terminación del modo consola | Proceso terminó con código 0. | Verificada en preparación. |
| Construcción actual con Maven/JDK 27 | El usuario aún debe ejecutar la construcción que incorpora este archivo. | Pendiente. |
| Ventana gráfica y respuesta durante trabajo | Requiere escritorio Windows del usuario. | Pendiente. |
| Cierre normal y cierre anticipado desde ventana | Requiere ambas ejecuciones del apartado 4. | Pendiente. |

El ejecutable `javac` no está expuesto en el entorno de preparación, pero se comprobó que el módulo `jdk.compiler` está disponible y se utilizó directamente. No se ejecutó Maven ni una inspección gráfica aquí. La ausencia de un ejecutable en PATH no implica por sí sola ausencia del módulo correspondiente.

## 7. Criterio de cierre de T09

Cerrar la tarjeta solo después de confirmar construcción actual, ventana visible, respuesta durante el trabajo de fondo, mensajes en terminal y archivo y retorno del prompt al cerrar normalmente y antes de terminar. Este diagnóstico no acredita aún las pruebas funcionales del simulador descritas en `docs/06-pruebas.md`.

Tras comprobarlo:

```powershell
git status --short
git check-ignore logs/t09-entorno.log
git add src/main/java/com/elevatormanager/diagnostics/EnvironmentCheck.java docs/comprobaciones/T09-entorno.md
git diff --cached --stat
git commit -m "Agrega comprobacion de Swing hilos y logging para T09"
git status
```

Revisar que `target/` y `logs/` no estén preparados para commit. Si algo falla, conservar salida y estado de la ventana para corregir antes de cerrar la tarea; no marcar T09 completada solo porque se descargó el código.

## 8. Referencias

- [Uso del EDT en Swing](https://docs.oracle.com/javase/tutorial/uiswing/concurrency/dispatch.html).
- [FileHandler, Java SE 17](https://docs.oracle.com/en/java/javase/17/docs/api/java.logging/java/util/logging/FileHandler.html).
- [Handler, Java SE 17](https://docs.oracle.com/en/java/javase/17/docs/api/java.logging/java/util/logging/Handler.html).

## 9. Historial

| Versión | Fecha | Cambio |
| --- | --- | --- |
| 0.1.0 | 2026-10-20 | Diagnóstico T09 y guía de comprobación; aceptación visual aún pendiente. |
