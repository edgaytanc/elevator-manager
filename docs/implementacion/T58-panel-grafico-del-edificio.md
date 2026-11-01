# T58 — Diseñar el panel gráfico del edificio

- **Fecha:** 1 de noviembre de 2026, Guatemala.
- **Fase:** 10, extra 2.
- **Base:** T57, commit `8237158`; 18 pruebas específicas y 856 totales confirmadas previamente. David declara cerradas las fases 1 a 9 y autoriza T58.
- **Estado:** implementada y comprobada en Linux headless; pendiente de validación Maven y aceptación visual de David.

## Resultado

Se incorpora una pestaña **Edificio** junto a **Cabinas**, seleccionada inicialmente. Representa los pisos con etiquetas, el piso 1 abajo y un hueco vertical por elevador. Cada cabina muestra su ID y se ubica en el último piso lógico confirmado por el modelo. Antes de configurar se muestra una indicación; al preparar la sesión todas aparecen en piso 1.

La vista Cabinas conserva los destinos internos y los requerimientos externos existentes. El edificio es de consulta: no envía comandos, no confirma llegadas y no modifica el motor. Se preservan Java 17, Swing, Maven, la jerarquía real Request/HallRequest/CabinRequest y los actores independientes del manejador y de los elevadores.

## Archivos

| Archivo | Cambio |
| --- | --- |
| `src/main/java/com/elevatormanager/ui/BuildingPanel.java` | Nuevo panel y geometría pura anidada |
| `src/main/java/com/elevatormanager/ui/SystemControlPanel.java` | Pestañas, enlace y publicación al panel con snapshots existentes |
| `src/test/java/com/elevatormanager/ui/BuildingPanelScenarios.java` | 12 escenarios ejecutables sin JUnit |
| `src/test/java/com/elevatormanager/ui/BuildingPanelTest.java` | 12 adaptadores JUnit, timeout de 30 s por caso |
| `docs/01-analisis-y-plan.md` | Cierre declarado de fase 9 y registro T58 |
| `docs/comprobaciones/T57-aceptacion-fase-9.md` | Anotación de cierre sin inventar capturas individuales |
| `docs/implementacion/T58-codigo-completo.md` | Los cuatro archivos Java anteriores completos |
| `docs/comprobaciones/T58-validacion-local.md` | Resultados y límites de esta validación |
| `docs/comprobaciones/T58-recorrido-gui.md` | Guion reproducible de aceptación y commit |

El ZIP de entrada contenía cambios previos en MainWindow.java y un documento docs/analisis_de_arquitectura_elevator_manager.md. Se conservan byte a byte respecto de esa entrada. No se atribuyen a T58 ni se incluyen en los comandos de staging específico.

## Escala, coordenadas y desplazamiento

El área virtual reserva 112 px para las etiquetas, 20 px a la derecha, 48 px arriba y 20 px abajo. La preferencia mínima es de 112 px por cabina y 36 px por piso. Scrollable hace que el panel ocupe el viewport en los ejes donde cabe; cuando no cabe, conserva su tamaño preferido y habilita barras de desplazamiento. El edificio alto se presenta inicialmente cerca del piso 1, sin volver a centrarlo en cada refresco.

Con H como altura, la altura de celda es `(H - 48 - 20) / P`; el centro del piso f es `48 + (P - f + 0.5) * alturaCelda`. No se divide entre P-1: P=1 se dibuja en el centro del área útil. Los huecos están ordenados por ID de izquierda a derecha, con cabinas de hasta 76 por 28 px.

Las dimensiones se calculan en long y se saturan antes de convertir a int, evitando overflow con valores de configuración válidos. Cerca de Integer.MAX_VALUE, el espacio de coordenadas de Swing no permite garantizar legibilidad de miles de millones de etiquetas: la comprobación extrema acredita límites numéricos y ausencia de componentes masivos, no una vista legible de tal magnitud. La aceptación visual utiliza cantidades representativas, incluidas 20 cabinas y 100 pisos.

El panel no crea JLabels/JPanels por piso o hueco. Dibuja solo los pisos y columnas que intersecan el clip visible, más sus extremos. No se construye una imagen del tamaño del edificio: el raster usado es el del viewport. Las barras permiten alcanzar tanto la última cabina como los extremos del edificio.

## Publicación y concurrencia

SystemControlPanel mantiene su Timer existente. UiSnapshotPump conserva la captura fuera del EDT y publica un Frame inmutable. BuildingPanel almacena esa lista compartida y dibuja en EDT; render rechaza sesiones y épocas ajenas y conjuntos cuyo tamaño no corresponde a N. No vuelve a consultar snapshots, no consume eventos y no recorre todas las cabinas para agregarlas en EDT. La geometría y los bucles de pintura se limitan a elementos visibles.

La cabina se dibuja en `snapshot.currentFloor()`. Durante MOVING conserva el piso dejado hasta recibir una llegada del modelo; no utiliza tiempo de pared ni `nextFloor` para adelantarla. Una época antigua nunca sustituye el dibujo; mientras llega una captura vigente se conserva la imagen previa. Esta vista discreta no acredita el comportamiento animado de reset/pausa.

No se añaden hilos, temporizadores ni recursos de cierre. Se mantienen los contratos de T52 y las prioridades de T56. La interpolación temporal corresponde a T59; congelación y retorno animados a T60; la revisión de rendimiento y claridad a T61. Las etiquetas de pisos y títulos de huecos forman parte del área desplazable: al recorrer un edificio grande puede ser necesario volver al margen o al encabezado para leerlos.

## Escenarios nuevos

1. P=1: coordenada central válida y cabina dentro de los límites.
2. Orden de pisos y cabinas, separación y correspondencia coordenada/piso.
3. Edificio pequeño: expansión y recálculo al cambiar el viewport.
4. Edificio de 20 cabinas y 100 pisos: dos ejes de scroll y acceso a la última cabina en piso 1.
5. Raster de tres cabinas en pisos distintos: cada posición contiene el color real de cabina.
6. Raster recortado y trasladado: cabina 40 de un edificio de 200 pisos.
7. Cantidades Integer.MAX_VALUE: dimensiones positivas, sin controles masivos y pintura acotada al clip.
8. Rechazo de sesión ajena y aceptación de la vigente.
9. Rechazo de época obsoleta, aceptación de nueva época y limpieza al reenlazar.
10. Pintar MOVING no altera piso lógico ni presupuesto temporal.
11. Constructor y enlace rechazan acceso fuera del EDT.
12. Integración con SystemControlPanel, formulario y actores reales: cabina 2 llega a 4 y se publica en el edificio mientras cabina 1 permanece en 1; cierre efectivo.

La geometría y raster se prueban sin JFrame. La inspección en Windows sigue siendo necesaria para confirmar tamaño de ventana, selección con ratón y barras nativas. El código completo, sin fragmentos ni elipsis, está en T58-codigo-completo.md y en los archivos Java del ZIP.
