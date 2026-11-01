# T58 — Validación y recorrido GUI en Windows

- **Estado:** pendiente de resultados de David.
- **Alcance:** panel gráfico por piso confirmado, inicio de fase 10.

## Incorporación

Extraer T58-panel-grafico-del-edificio.zip en una carpeta temporal. Copiar el contenido de elevator-manager sobre el repositorio local, combinando carpetas y reemplazando archivos. Conservar el .git local. El ZIP contiene el proyecto completo y excluye .git, target, logs y volcados de fallo de la JVM; no sustituir el repositorio por una carpeta que pierda su historial.

Desde la raíz:

```powershell
mvn "-Dtest=BuildingPanelTest" test
mvn clean verify
git diff --check
java -jar .\target\elevator-manager-0.1.0-SNAPSHOT.jar
```

Esperado: 12 pruebas específicas y 868 totales, cero fallos/errores/omitidas y BUILD SUCCESS. Son resultados esperados; no se han ejecutado con Maven en el entorno de preparación.

## Comprobación visual

Cerrar la sesión y volver a abrir el JAR para cambiar la configuración inmutable entre casos.

| Caso | Parámetros | Comprobar |
| --- | --- | --- |
| Mínimo | N=1, P=1, movimiento=1000 ms, parada=1500 ms | Piso 1 legible y cabina E1 dentro del hueco, sin división por cero; Start estable |
| Varias cabinas en un piso | N=4, P=1, tiempos anteriores | Cuatro huecos y cabinas distintas en piso 1 |
| Edificio normal | N=4, P=10, tiempos anteriores | Piso 10 arriba, piso 1 abajo; cuatro cabinas por ID; escala al agrandar la ventana |
| Edificio grande | N=20, P=100, tiempos anteriores | Ambas barras; acceder a elevador 20, piso 1 y piso 100; sin solapamiento de cabinas |
| Ventana estrecha | N=4, P=10 | Acceso a Edificio/Cabinas, controles globales y scroll exterior/interior |

1. Abrir el JAR y validar configuración. Aparece Edificio; las cabinas están en piso 1 y la vista alta inicia cerca de ese piso. El dibujo aún no interpola entre pisos.
2. Cambiar a Cabinas, pulsar Start y marcar un destino para una cabina. Volver a Edificio: solo esa cabina cambia de piso cuando el modelo confirma llegadas. Los actores siguen trabajando aunque se cambie de pestaña.
3. Mandar cabinas a diferentes pisos. Comparar posiciones del edificio con Piso actual en cada panel de Cabinas; no confundir Siguiente con una llegada confirmada.
4. Recorrer barras horizontal/vertical hasta los extremos. Las etiquetas viajan con el dibujo: volver al margen izquierdo para pisos y al extremo superior para títulos si quedan fuera de vista. La cabina conserva su ID.
5. Redimensionar la ventana: cuando el edificio cabe se adapta; cuando no cabe sigue siendo accesible mediante desplazamiento. El refresco no debe llevar el scroll de vuelta al inicio.
6. Comprobar que solicitudes, Stop, Start, Pausar/Reanudar y Reset conservan la funcionalidad ya validada; cerrar normalmente y confirmar retorno al prompt. El retorno continuo y la congelación entre pisos no son criterios de esta tarjeta: corresponden a T59/T60.

Si hay un resultado distinto al esperado, conservar salida completa y describir N, P, tiempos, control utilizado y resultado visible antes de cerrar T58.

## Commit sugerido, tras validar

El staging explícito evita incluir por accidente los cambios previos de MainWindow.java o el documento de arquitectura que ya venían en el ZIP de entrada. Revisarlos por separado si aún están sin commit.

```powershell
git diff --check
git add -- src/main/java/com/elevatormanager/ui/BuildingPanel.java src/main/java/com/elevatormanager/ui/SystemControlPanel.java src/test/java/com/elevatormanager/ui/BuildingPanelScenarios.java src/test/java/com/elevatormanager/ui/BuildingPanelTest.java docs/01-analisis-y-plan.md docs/comprobaciones/T57-aceptacion-fase-9.md docs/implementacion/T58-panel-grafico-del-edificio.md docs/implementacion/T58-codigo-completo.md docs/comprobaciones/T58-validacion-local.md docs/comprobaciones/T58-recorrido-gui.md
git diff --cached --check
git diff --cached --stat
git commit -m "T58: implementar panel grafico del edificio"
git status --short
```

T58 se mantiene pendiente de aceptación local hasta recibir los resultados. Este commit no implica completar T59–T61 ni cerrar fase 10. El empaquetado no crea commits en nombre de David.
