# T61 — Recorrido de claridad y rendimiento visual

- **Estado:** pendiente de resultados de David.
- **Fecha de preparación:** 2 de noviembre de 2026, Guatemala.
- **Base:** T60 `6883d66`, 910 pruebas totales y Git limpio.

## Incorporación

Extraer T61-claridad-y-rendimiento-visual.zip en una carpeta temporal. Combinar el contenido de elevator-manager sobre el repositorio local conservando .git. Se entrega el proyecto íntegro; se excluyen .git, target, logs y volcados JVM.

```powershell
mvn "-Dtest=VisualQualityTest" test
mvn clean verify
git diff --check
java -jar .\target\elevator-manager-0.1.0-SNAPSHOT.jar
```

Esperado: 16 pruebas específicas y 926 totales, sin fallos/errores/omitidas y BUILD SUCCESS. Son resultados esperados para Windows; Maven no se ejecutó en el entorno de preparación. Conservar T61_THREADS, T61_PULSE y las cuatro líneas T61_RENDER. En JUnit, el orden de los casos puede cambiar y los números de rendimiento pertenecen a cada equipo/ejecución.

## Matriz nativa

Volver a abrir el JAR para cambiar la configuración inmutable.

| Configuración | Comprobar |
| --- | --- |
| N=1, P=1 | Piso 1, E1 y encabezado separados, sin división por cero |
| N=3, P=10, movimiento 3000 ms, parada 1000 ms | IDs distintos y posiciones intermedias; comparación con Piso actual/Siguiente |
| N=20, P=100, tiempos visibles | Desplazarse a E20/piso 1 y al piso 100; pisos fijos al mover horizontalmente y títulos fijos al mover verticalmente |
| Ventana maximizada | Etiquetas legibles, cabinas dentro de huecos y controles accesibles |
| Ventana reducida, aproximadamente 800×600 | Acceso por barras interiores/exteriores, controles globales y ambas pestañas |
| Fuente/escala de pantalla habitual | Sin textos fuera de la cabina ni títulos recortados ambiguos; mostrar completa una columna para leer su título |

1. Enviar distintas cabinas a pisos distintos y observar subida y bajada simultáneas. Alternar Edificio/Cabinas y redimensionar durante el trayecto: no debe reiniciarse el avance ni perderse la identidad.
2. Recorrer ambos ejes del edificio. Las etiquetas de piso deben seguir en el borde izquierdo visible y los títulos en el borde superior visible. Una columna parcialmente fuera de vista puede omitir su título; desplazar hasta mostrarla completa.
3. Pausar entre pisos durante varios segundos; reanudar. Repetir durante una parada y en reposo. No debe acumularse movimiento por tiempo de pared ni aparecer parpadeo por redibujos idénticos.
4. Probar Stop desde movimiento y desde pausa: termina el adyacente. Continuar con Start y confirmar pendientes conservados.
5. Probar Reset desde altura y desde pausa: observar retorno físico hasta 1/UP y limpieza. Confirmar acceso a carga de archivo en RESET.
6. En ejecuciones separadas, cerrar desde pausa con Cerrar y X. Confirmar retorno al prompt. Reabrir y cerrar varias veces sin procesos Java residuales de estas ejecuciones.
7. Observar respuesta al cambiar pestaña, mover barras y pulsar controles mientras se animan varias cabinas. Las medidas headless no sustituyen esta revisión. Si se observa consumo alto o lentitud, registrar configuración, tiempos, tamaño de ventana y acción; no repetir hasta esconder el problema.

La tabla T61_RENDER mide dibujo de un buffer y no FPS del monitor. La ausencia de hilos por fotograma se acredita por automatización; la apariencia y respuesta real del escritorio se registran aquí con lo observado.

## Git, después de validar

```powershell
git diff --check
git add -- src/main/java/com/elevatormanager/ui/BuildingPanel.java src/test/java/com/elevatormanager/ui/VisualQualityScenarios.java src/test/java/com/elevatormanager/ui/VisualQualityTest.java docs/01-analisis-y-plan.md docs/comprobaciones/T60-recorrido-gui.md docs/implementacion/T61-claridad-y-rendimiento-visual.md docs/implementacion/T61-codigo-completo.md docs/comprobaciones/T61-validacion-local.md docs/comprobaciones/T61-resultados-locales.md docs/comprobaciones/T61-recorrido-gui.md docs/comprobaciones/T61-aceptacion-fase-10.md
git diff --cached --check
git diff --cached --stat
git commit -m "T61: verificar claridad y rendimiento visual"
git status --short
```

El cierre de fase 10 se registra en T61-aceptacion-fase-10.md después de confirmar la matriz. Esta entrega no crea un commit en nombre de David ni inicia T62.
