# T47 — Crear formulario de configuración

> Cierre confirmado al iniciar T48: 16 pruebas específicas y 594 acumuladas, BUILD SUCCESS, commit `5f30a29`, árbol limpio y capturas de validación inválida/válida. T47 queda cerrada. El informe siguiente conserva el estado de su entrega original.

- **Fecha:** 2026-10-28, Guatemala.
- **Fase:** 8 — Construir la interfaz gráfica obligatoria.
- **Base:** T46, commit `84f6261`; fases 1–7 cerradas por David, 578 pruebas acumuladas reportadas.
- **Estado:** implementada y comprobada con escenarios locales; pendiente de Maven y comprobación de ventana nativa en Windows.

## Alcance y continuidad

Se recuperó contexto de los chats solicitados mediante Contexto personal; no se dispone de una transcripción íntegra de todos ellos. Se contrastó el enunciado y el proyecto actualizado, que es la referencia técnica. Se mantiene el flujo de una tarjeta por vez, Java 17/Maven/JUnit/Swing, código completo, ZIP con rutas relativas a la raíz, documentación Markdown y commit después de validar.

T47 implementa el formulario requerido por ElevatorManager. La simulación y sus controles se conectarán en T48; confirmar parámetros no crea un controlador ni ejecuta trabajo. Esto evita crear actores desde el EDT durante la validación, especialmente al probar límites de N/P. El rango matemático de T16 se conserva; aceptar un valor no garantiza recursos suficientes para instanciar esa cantidad de elevadores en una futura sesión.

## Archivos Java

| Archivo | Responsabilidad |
| --- | --- |
| model/SimulationConfig.java | Mantiene fromText y constructor; agrega Field y textErrors con mapa inmutable, ordenado, de errores simultáneos. Reutiliza las mismas funciones privadas de T16. |
| ui/ConfigurationPanel.java | JPanel con cuatro campos, ms visibles, diagnósticos, botón y confirmación con Enter. Mantiene Optional de configuración válida e invalida ante cualquier edición. |
| ui/MainWindow.java | Integra el formulario en JScrollPane; mantiene WindowCloseHandler para botón y X, y bloquea el formulario mientras se cierra. |
| ui/ConfigurationFormScenarios.java (test) | 16 escenarios independientes, componentes Swing reales y EDT real sin JFrame. |
| ui/ConfigurationFormTest.java (test) | 16 adaptadores JUnit con Timeout. |

La herencia de JPanel tiene un propósito gráfico; la herencia de solicitudes y los trabajadores paralelos existentes se conservan. No se introducen hilos para validar cuatro números. El formulario exige EDT en construcción, validación y lectura; el futuro Start deberá revalidar y utilizar la configuración confirmada sin efectuar esperas en EDT.

## Reglas y comportamiento

- N/P: 1 a Integer.MAX_VALUE.
- Movimiento y espera: 1 a Long.MAX_VALUE / 1_000_000 ms, con conversión segura a nanos.
- Misma sintaxis decimal entera de fromText: espacios exteriores permitidos; vacío, decimales, notación científica y desbordamientos rechazados.
- Se muestran todos los errores debajo de los campos y se solicita foco en el primero inválido.
- Botón o Enter revalida las cuatro entradas antes de publicar un SimulationConfig.
- Modificar cualquier campo borra la confirmación y los diagnósticos anteriores; el siguiente intento muestra los nuevos errores.
- No se utilizan diálogos modales para errores de entrada; los labels están asociados a sus campos para accesibilidad.
- El formulario se desplaza si los mensajes o la ventana requieren más espacio. El aspecto de JFrame/JScrollPane y el foco nativo se comprobarán en Windows.

## Incorporación y validación en Windows

Desde la raíz del repositorio, sin anidar otra carpeta elevator-manager:

```powershell
Set-Location 'D:\Users\David\Desktop\workspace\java\elevator-manager'
git status --short
Expand-Archive -LiteralPath "$HOME\Downloads\T47-formulario-configuracion.zip" -DestinationPath . -Force
mvn "-Dtest=ConfigurationFormTest" test
mvn clean verify
java -jar .\target\elevator-manager-0.1.0-SNAPSHOT.jar
```

Se esperan 16 específicas y 594 acumuladas sin fallos, errores u omisiones y BUILD SUCCESS. Si una construcción falla, revisar su salida antes de continuar.

## Comprobación visual

1. Abrir el JAR: título, N, P, movimiento y espera; ambos tiempos identificados en ms.
2. Validar con todo vacío: cuatro errores, ninguno en diálogo modal.
3. Probar N=0, P=-1, movimiento=abc, espera=0: cuatro diagnósticos; no se crea sesión ni se mueven cabinas.
4. Corregir a 2, 8, 1000, 500: mensaje de configuración válida.
5. Cambiar P a 0: desaparece la confirmación; al pulsar Enter aparece su error.
6. Cambiar P a 8 y confirmar con Enter: válida nuevamente. Probar 1,1,1,1.
7. Probar 2147483648 en N, 9223372036854775808 en movimiento y 9223372036855 en espera: rechazo por campo. Corregir de nuevo.
8. Reducir el tamaño, recorrer el formulario con Tab y comprobar desplazamiento y mensajes. Comprobar también escala de pantalla habitual de Windows.
9. Cerrar con botón y comprobar retorno al prompt; abrir de nuevo y repetir con X.

No se presenta esta lista como ejecutada en Windows. El render headless inspeccionado verifica el panel con errores, no eventos de foco, decoración nativa ni el cierre de JFrame.

## Commit sugerido

Después de confirmar Maven y la revisión visual:

```powershell
git diff --check
git add -- README.md docs/01-analisis-y-plan.md docs/implementacion/T46-ejemplos-y-errores-lectura.md docs/comprobaciones/T46-validacion-local.md docs/implementacion/T47-formulario-configuracion.md docs/implementacion/T47-codigo-completo.md docs/comprobaciones/T47-validacion-local.md src/main/java/com/elevatormanager/model/SimulationConfig.java src/main/java/com/elevatormanager/ui/ConfigurationPanel.java src/main/java/com/elevatormanager/ui/MainWindow.java src/test/java/com/elevatormanager/ui/ConfigurationFormScenarios.java src/test/java/com/elevatormanager/ui/ConfigurationFormTest.java
git diff --cached --check
git commit -m "T47: crear formulario de configuracion con validacion por campo"
git status --short
```

T47 no se marca cerrada hasta recibir esos resultados. La siguiente tarjeta es T48 — Crear panel de controles del sistema.
