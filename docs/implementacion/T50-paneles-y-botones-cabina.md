# T50 — Crear paneles y botones internos de cabina

> Cierre completo confirmado al iniciar T51: 32 pruebas específicas, 104 combinadas y 682 acumuladas, BUILD SUCCESS, commit `1d3378b` y árbol limpio. Se aportaron registros de servicio/Reset completo/cierre y capturas de RESET y atención en piso 4 con destino 8 aún encendido. El informe siguiente conserva el estado previo al cierre.

- **Fecha:** 2026-10-28, Guatemala.
- **Fase:** 8 — Construir la interfaz gráfica obligatoria.
- **Base:** T49 validada en `2b17a64`, 28 pruebas específicas, 72 combinadas y 650 acumuladas; BUILD SUCCESS, llamadas servidas y árbol limpio.
- **Estado:** implementada y comprobada con escenarios/render local; pendiente de Maven y ventana nativa en Windows.

## Resultado y composición

CabinDashboardPanel genera N pestañas con nombre Elevador 1..N. Cada pestaña contiene un CabinPanel vinculado a una cabina real y una sesión fija. Un divisor horizontal conserva configuración/llamadas a la izquierda y cabinas a la derecha; el tamaño mínimo de MainWindow es 1060×760. Las pestañas se desplazan si no caben y cada panel tiene scroll propio. Esta adaptación mínima permite operar T50; el visor y la revisión de tamaño global corresponden a T51.

| Dato | Fuente y significado |
| --- | --- |
| Piso actual | currentFloor del snapshot; durante movimiento conserva el piso que acaba de dejar. |
| Siguiente | nextFloor del tramo, cuando existe; no se muestra como llegada anticipada. |
| Dirección | UP/Arriba o DOWN/Abajo del snapshot, incluso al estar quieta. |
| Estado | Estado global y fase local legible; indica retorno por Reset cuando corresponde. |
| Destinos internos | cabinRequests pendientes del snapshot; se muestran en resumen y luces de botones. |
| Llamadas externas | hallRequests locales y seguimiento global dirigido a esa cabina, con piso y sentido. |

El panel no crea elevadores, trabajadores ni sesiones adicionales. Las solicitudes siguen usando la herencia real Request/CabinRequest/HallRequest y los hilos independientes del motor existente.

## Botones y paginación

Hay un botón por destino visible, hasta 20 por página, dispuesto en cuatro columnas. Para P<=20 aparecen todos en una página; para P mayor se utilizan Anterior/Siguiente o el selector numérico de página y Enter. El último piso sigue disponible aunque P no sea múltiplo de 20. Se usan cálculos long para construir los últimos botones sin overflow en P=Integer.MAX_VALUE.

El coste de componentes de destinos es O(N·min(P,20)), además de N pestañas. No se limita el rango de pisos del modelo. El resumen mantiene los destinos pendientes de todas las páginas; cambiar página reconstruye sus luces desde el último snapshot. Seleccionar una página no solicita ningún viaje.

El rango matemático de configuración no garantiza recursos para cantidades extremas de elevadores; la preparación de sesión sigue sujeta a recursos como en T48. La prueba de P máximo no crea miles de millones de botones ni envía un viaje hasta ese piso.

## Admisión y luces

Solo RUNNING permite destinos internos. Se crea CabinRequest con UUID, época, fecha y el ID fijo de esta cabina, y se envía mediante SimulationController.submit. El controlador valida y dirige a la cola de esa cabina. El clic y el recibo ENQUEUED no alteran luces.

La luz amarilla, el punto ● y el estado selected del botón se derivan exclusivamente de cabinRequests del snapshot. Se enciende cuando se observa el destino admitido y pendiente; se apaga cuando deja de estar pendiente al comenzar su parada de servicio. Un destino atendido de inmediato en el piso actual puede no llegar a observarse encendido entre dos refrescos. Atender un destino no apaga los otros. Repetir un piso utiliza la deduplicación existente de cabina.

Stop conserva la agenda y sus luces, pero bloquea nuevos destinos. Start desde STOPPED habilita la misma cabina sin perder pendientes. Reset cancela solicitudes por cambio de época, por lo que limpia las luces aunque no exista servicio de esas solicitudes; no se presenta esa limpieza como atención. El cierre también cancela trabajo y limpia la representación al observar terminación. Estas dos cancelaciones explícitas son distintas de apagar por mero envío/asignación.

## Recogidas externas

Se muestran piso y sentido de las recogidas admitidas en la agenda local, más las dirigidas por el manejador a esta cabina en DISPATCHED, ASSIGNED o UNCONFIRMED. Esto conserva las asignaciones provisionales o pendientes de confirmación y evita borrarlas cuando se asignan. SERVED/CANCELLED dejan de aportar pendientes globales; la agenda local conserva los suyos hasta actualizar su snapshot. Alias de una misma recogida se agrupan por piso/sentido y los sentidos opuestos permanecen distintos.

Las recogidas no encienden botones de destino interno: son solicitudes externas, mostradas en su lista separada. La lista no es historial; los registros completos permanecen en los logs.

## Refresco y EDT

Se comparte el Timer coalescente de T48 a 100 ms. CabinDashboardPanel toma una colección de snapshots y una vista global de llamadas por refresco; agrupa las llamadas por cabina antes de renderizar. No espera futuros, toma eventos ni lee archivos en EDT. Los snapshots son inmutables y las capturas por cabina no constituyen un instante global atómico.

CabinPanel exige EDT para construcción y render. Comprueba cabina/configuración, época actual y revisión local; un snapshot antiguo no puede restaurar una luz ya atendida. Durante el cambio de época puede mostrar brevemente que espera posición actual, sin reutilizar datos de la época anterior. Los callbacks de envío comprueban época, RUNNING, disponibilidad y secuencia antes de actualizar el último mensaje. Ningún callback cambia luces.

El área de feedback tiene alto acotado y desplazamiento; se ajustó después de verificar que el ajuste de líneas podía consumir espacio excesivo. Los pendientes externos e internos también se pueden recorrer. T52 mantiene su alcance de verificar y optimizar el refresco global bajo carga, sin declararse cerrada por estas comprobaciones.

## Archivos

- `ui/CabinPanel.java`: datos, botones paginados, luces y recogidas de una cabina.
- `ui/CabinDashboardPanel.java`: N pestañas, captura y agrupación para refresco.
- `ui/SystemControlPanel.java`: composición, vinculación y refresco compartido con T48/T49.
- `ui/MainWindow.java`: tamaño mínimo.
- `ui/CabinPanelsScenarios.java` y `ui/CabinPanelsTest.java`: 32 escenarios y adaptadores JUnit.
- README, plan, cierre histórico T49 y tres Markdown T50.

No cambian modelo, controlador, algoritmos, protocolo, parser ni POM. T51–T53 siguen pendientes.

## Incorporación y comprobación en Windows

El ZIP contiene el proyecto fuente completo, con rutas relativas a la raíz Maven, sin .git, target ni logs generados. Extraer sobre elevator-manager conservando el repositorio local y los cambios personales.

```powershell
Set-Location 'D:\Users\David\Desktop\workspace\java\elevator-manager'
git status --short
Expand-Archive -LiteralPath "$HOME\Downloads\T50-paneles-y-botones-cabina.zip" -DestinationPath . -Force
mvn "-Dtest=CabinPanelsTest" test
mvn "-Dtest=CabinPanelsTest,HallCallsTest,SystemControlsTest,ConfigurationFormTest" test
mvn clean verify
java -jar .\target\elevator-manager-0.1.0-SNAPSHOT.jar
```

Se esperan 32 específicas, 104 combinadas y 682 acumuladas, sin fallos, errores u omisiones y BUILD SUCCESS. Maven no está disponible en el entorno de esta entrega.

## Recorrido visual

1. Configurar N=2, P=8, movimiento=1000 ms y espera=1000 ms. Validar: dos pestañas, piso 1/Arriba y destinos deshabilitados.
2. Pulsar Start. En Elevador 1 pulsar 8 y luego 4 antes de llegar a 4. Ambas luces deben aparecer amarillas al admitirse. Al atender 4, se apaga 4 y continúa encendido 8 hasta atenderlo.
3. Durante un tramo comprobar piso actual y siguiente: el piso actual solo cambia al llegar. Revisar dirección y fase local.
4. En Elevador 2 pulsar 6. La solicitud pertenece a esa cabina; cambiar pestañas no pierde luces ni estado de la otra.
5. Hacer una llamada externa desde el panel compartido, por ejemplo piso 3/Bajar. Revisar la pestaña de la cabina elegida por el manejador: debe mostrar piso y sentido hasta atenderla. No se exige una cabina concreta.
6. Durante trabajo pendiente pulsar Stop: botones deshabilitados y luces conservadas. Start continúa. Reset limpia luces y recogidas, muestra retorno y finalmente piso 1/Arriba para todas; no inicia trabajo nuevo hasta Start.
7. Cerrar durante movimiento y comprobar retorno al prompt. Repetir con X. Un cierre durante Reset no acredita que se hubiera completado el retorno de todas las cabinas.
8. En otra ejecución usar N=1/P=25 y movimiento=100 ms: seleccionar página 2, pulsar 25 y volver a página 1. El resumen conserva 25; al volver a página 2 su luz refleja la agenda actual.
9. En otra ejecución N=1/P=1: destino interno 1 disponible en RUNNING y parada de servicio; ninguna llamada externa imposible habilitada.
10. Comprobar pestañas, divisor y desplazamiento con la escala habitual de Windows. T51 ampliará la revisión de muchas cabinas y el visor de eventos.

Los renders locales no sustituyen este recorrido de ventana nativa.

## Commit sugerido

Después de confirmar Maven y revisión visual:

```powershell
git diff --check
git add -- README.md docs/01-analisis-y-plan.md docs/implementacion/T49-llamadas-externas-compartidas.md docs/comprobaciones/T49-validacion-local.md docs/implementacion/T50-paneles-y-botones-cabina.md docs/implementacion/T50-codigo-completo.md docs/comprobaciones/T50-validacion-local.md src/main/java/com/elevatormanager/ui/CabinPanel.java src/main/java/com/elevatormanager/ui/CabinDashboardPanel.java src/main/java/com/elevatormanager/ui/SystemControlPanel.java src/main/java/com/elevatormanager/ui/MainWindow.java src/test/java/com/elevatormanager/ui/CabinPanelsScenarios.java src/test/java/com/elevatormanager/ui/CabinPanelsTest.java
git diff --cached --check
git commit -m "T50: crear paneles y destinos internos por cabina"
git status --short
```

T50 se cerrará tras los resultados de David. La siguiente tarjeta es T51 — Agregar visor de eventos y adaptación de tamaño.
