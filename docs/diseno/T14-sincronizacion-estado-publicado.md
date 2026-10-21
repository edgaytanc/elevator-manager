# T14 — Diseñar sincronización y estado publicado

- **Proyecto:** Elevator Manager.
- **Versión:** 0.1.0.
- **Fecha:** 2026-10-21, Guatemala.
- **Fase:** 2 — Crear carpetas y diseñar la arquitectura.
- **Estado:** diseño preparado y revisado; pendiente de incorporación y cierre por el usuario.
- **Base:** [arquitectura T12](../03-arquitectura.md), [decisiones](../02-decisiones.md) y [herencia T13](T13-herencia-solicitudes.md).
- **Alcance:** colas, exclusión mutua, esperas, publicación y coordinación entre hilos. No implementa todavía trabajadores Java.

## 1. Objetivo y aceptación

Definir propiedad del estado, buzones, bloqueos, condiciones de espera y snapshots. Ninguna colección mutable se comparte sin protección y ningún hilo espera una respuesta de otro sosteniendo un bloqueo global. Swing nunca espera viajes, archivos ni terminación de trabajadores.

Se conserva una JVM con un trabajador manejador y uno por cabina. El EDT y los ejecutores auxiliares de coordinación/I/O no sustituyen esos trabajadores. Las solicitudes de T13 son inmutables; los contenedores que las almacenan requieren su propia protección.

## 2. Propiedad del estado

| Estado mutable | Único escritor | Lectores y acceso permitido |
| --- | --- | --- |
| Estado global, época, operaciones y lote preparado | Coordinador de SimulationController. | Otros componentes reciben mensajes o proyecciones inmutables. |
| Registro de admisión y claves de duplicados | Coordinador del controlador. | La GUI recibe resultados; el manejador comunica hechos para actualizarlo. |
| Pendientes externas, asignaciones y envíos en curso | ManagerWorker. | Controlador/UI reciben resultados publicados; no obtienen el mapa interno. |
| Piso, dirección, fase, tiempo restante y agenda | ElevatorWorker de esa cabina. | Estrategia/controlador/UI leen ElevatorSnapshot. |
| Contenido de un buzón | Productores y consumidor bajo el bloqueo de ese buzón. | Nadie expone sus deques o modifica sus nodos sin adquirirlo. |
| Referencia al último snapshot | Su trabajador mediante publicación atómica. | Lectura atómica; contenido profundamente inmutable. |
| Componentes y modelo visual Swing | EDT. | Los demás hilos solo programan entrega de datos al EDT. |
| Recursos de logging | Capa de logging; configuración/cierre coordinados. | Workers emiten registros; no modifican handlers concurrentemente. |

No se crea un bloqueo global para modificar todas las cabinas. Cada cabina puede avanzar aunque otra esté esperando tiempo o procesando una orden.

## 3. Herramientas Java previstas

| Necesidad | Mecanismo seleccionado |
| --- | --- |
| Acciones globales serializadas | Un ejecutor de coordinación de un solo hilo; sus tareas son breves y nunca esperan confirmaciones. |
| Buzón con controles prioritarios y entradas de servicio | Deques privados protegidos por un ReentrantLock y una Condition común por buzón. |
| Publicación del estado de una cabina | AtomicReference de ElevatorSnapshot. |
| Publicación de proyección global | AtomicReference de una proyección inmutable construida por el coordinador. |
| Espera temporal interrumpible y señalable | Condition.await / awaitNanos con predicado comprobado en bucle. |
| Notificación al usuario sin bloqueo | Resultados asíncronos; su terminación no debe ejecutar código de UI fuera del EDT. |
| Lectura de archivos | Ejecutor de I/O separado; devuelve resultado inmutable al coordinador. |
| Actualización gráfica | UiEventBridge y SwingUtilities.invokeLater; refresco visual acotado. |
| Duraciones | SimulationClock monotónico; System.nanoTime en la implementación real y reloj controlable para pruebas. |

La elección de buzón compuesto sustituye el uso ingenuo de una única BlockingQueue de servicio: un reset no debe quedarse detrás de cientos de destinos ni de una espera de movimiento. No se combinan dos colas independientes con un take que solo pueda despertar por una de ellas.

## 4. Contrato del buzón

Cada cabina y el manejador disponen de un buzón. Sus entradas se dividen en controles, respuestas/eventos fiables y servicio, según el receptor. Los controles distinguen las prioridades cerrar > reset > detener/pausar/reanudar > servicio; las confirmaciones se atienden sin quedar detrás del trabajo normal. Dentro de una misma prioridad se conserva el orden de inserción.

### 4.1 Productor

1. Construye un mensaje inmutable; para órdenes del protocolo conserva el texto codificado conforme a 05.
2. Adquiere únicamente el bloqueo del buzón destino.
3. Comprueba si está abierto para esa clase de mensaje y encola o devuelve rechazo explícito.
4. Señala la condición antes de liberar el bloqueo.
5. Devuelve el resultado del envío; no espera aceptación del comando ni servicio.

El envío es una sección crítica corta: no hace I/O ni bloquea esperando espacio. Si se incorporan límites de capacidad, deberá existir una política explícita de rechazo para servicio y garantía de acceso de controles; esa ampliación no forma parte de T14.

### 4.2 Consumidor

1. Adquiere el bloqueo de su propio buzón.
2. Consulta prioridades y predicados de trabajo/tiempo; si nada procede, espera sobre la Condition en un bucle.
3. Al despertar, vuelve a comprobar controles, modo operativo y plazo; un despertar por sí mismo no prueba que haya vencido el tiempo.
4. Extrae el siguiente mensaje o compromete una transición local breve, preservando sus invariantes.
5. Libera el bloqueo antes de parsear trabajo costoso, enviar a otro buzón, registrar en archivo o notificar consumidores.

El estado de cabina sigue teniendo un único escritor. El bloqueo del buzón permite hacer indivisible la comprobación de controles pendientes y el compromiso local de iniciar/completar una acción; no concede al productor permiso para editar la cabina.

Un mensaje extraído para parsearlo fuera del bloqueo no queda autorizado para ejecutarse: antes de mutar estado o iniciar movimiento se comprueban otra vez los controles, época y modo vigentes bajo la sección crítica local. Así una orden extraída antes del reset no se ejecuta después por haber pasado una comprobación antigua.

### 4.3 Ausencia de señales perdidas

La consulta del predicado y la llamada a await usan el mismo bloqueo que la inserción y signal. Await libera el bloqueo mientras espera y lo recupera al retornar. Si el productor inserta antes, el consumidor ve el predicado; si inserta durante la espera, lo despierta. También se toleran despertares espurios.

Pausa y Stop no suspenden el consumo de controles y confirmaciones. Un trabajador detenido sigue vivo y puede recibir reset o cierre. No se usa Thread.suspend, Thread.resume ni Thread.stop.

## 5. Bloqueos y prevención de interbloqueos

| Regla | Consecuencia |
| --- | --- |
| Nunca adquirir un segundo bloqueo de buzón conservando el primero. | Se extrae/prepara localmente y se envía después de liberar. |
| Ningún lock durante get/join/espera de una respuesta remota. | La confirmación vuelve como mensaje y actualiza el seguimiento. |
| Ningún lock durante callbacks, logging, lectura de archivo o invokeAndWait. | El receptor externo no reentra en una sección crítica ni bloquea el avance de otra cabina. |
| Estado privado escrito solo por su actor. | No hacen falta candados adicionales para exponer agendas; no se exponen. |
| No usar invokeAndWait para publicar estados. | Los workers no esperan al EDT. |
| Configuración y solicitudes son inmutables. | Pueden compartirse sin coordinar mutaciones de esos objetos. |

El único anidamiento permitido es la lógica local sin adquirir otro lock. Si una futura implementación necesita otro recurso compartido, deberá documentar su propiedad antes de añadir bloqueos. Los monitores internos de las bibliotecas no justifican mantener nuestro lock mientras se invocan consumidores externos.

## 6. Tiempo de movimiento y parada

El trabajador conserva fase, instante monotónico de referencia, duración restante y progreso. Un traslado completo entre pisos y una parada usan las duraciones de la configuración compartida; el tiempo real de consola o GUI no modifica esas duraciones.

Antes de procesar un despertar se descuenta el tiempo monotónico efectivamente transcurrido en la fase activa. Se protegen conversiones de ms a ns y no se forman sumas que puedan desbordarse; se calculan diferencias monotónicas y restantes con rangos validados. AwaitNanos puede retornar tarde: se limita el restante a cero y se completa una sola transición lógica correspondiente a esa fase.

| Situación | Comportamiento |
| --- | --- |
| Sin trabajo | Espera sin sondeo continuo. |
| En movimiento o parada | Espera interrumpible hasta el próximo plazo o señal de comando. |
| Nueva solicitud durante movimiento | Despierta, actualiza restante, procesa admisión y sigue el tramo físico vigente. |
| Pausa | Congela el progreso alcanzado al procesar el control y el tiempo restante; continúa consumiendo controles. |
| Reanudar | Reinicia referencia monotónica y conserva restante; el tiempo pasado en pausa no se descuenta. |
| Stop en movimiento | Deja terminar solo el tramo adyacente ya comprometido; no inicia otro ni nueva parada de servicio. |
| Stop en parada | Congela el tiempo restante de esa parada. |
| Reset desde pausa/Stop | Despierta y completa el tramo parcial conservado si existe; vuelve a 1 sin servicio antiguo. |
| Cierre | Cancela avance, despierta espera y finaliza; no exige regreso físico al piso 1. |

El orden de llegada de una señal y del vencimiento se resuelve por el compromiso local bajo el lock: si la llegada física ya se comprometió, el control actúa sobre ese piso; si no, se aplica al tramo vigente. La confirmación incluye el estado realmente alcanzado, no la hora de clic del usuario.

## 7. Publicación de snapshots

### 7.1 Instantánea de cabina

El worker construye un ElevatorSnapshot completo a partir de su estado coherente y lo publica con una única escritura en AtomicReference. Nunca modifica un snapshot después de publicarlo.

| Campo conceptual | Garantía |
| --- | --- |
| ID y época | Identifican cabina y generación. |
| Revisión | Creciente durante la sesión de esa cabina; no vuelve a cero al hacer reset. |
| Piso actual | Último piso alcanzado/dejado; cambia solo al confirmar llegada física. |
| Siguiente piso y progreso | Describen el tramo en curso sin fingir otra llegada. |
| Dirección, fase y modo | Permiten distinguir movimiento, parada, suspensión y retorno por reset. |
| Restante e instante monotónico de referencia | Permiten representar pausa y estimar avance visual. |
| Destinos internos y llamadas asignadas | Copias inmutables, incluidos sus elementos; nunca vistas vivas de colecciones privadas. |
| Frontera comprometida y datos de recorrido | Datos suficientes para estimación sin consultar la agenda mutable. |

Se publica tras cambios observables y al congelar/reanudar una fase. La animación puede interpolar con el reloj monotónico dentro de la fase autorizada; no determina llegadas ni permite superar el final publicado como si ya se hubiese confirmado el siguiente piso.

Una colección inmodificable que envuelve la lista original sigue siendo insegura si el worker cambia esa lista. Se requieren copias de contenido inmutable. Un record de Java tampoco aporta inmutabilidad profunda automáticamente.

### 7.2 Proyección global

Solo el coordinador construye y publica la proyección global: estado, época, revisión global, incidencias, lote preparado y snapshots de cabinas. La lectura de N referencias atómicas no constituye una fotografía simultánea de todo el sistema.

Durante RESETTING puede haber snapshots de épocas distintas. La vista los identifica como pendientes de actualización y no mezcla sus pendientes antiguos con trabajo vigente. Una respuesta de época anterior no cambia las admisiones ni confirma la barrera nueva.

La estrategia consulta cada referencia una vez para formar su conjunto de estimación. Ese conjunto es inmutable y puede quedar obsoleto; la cabina vuelve a validar al recibir la orden. No se bloquean todas las cabinas para obtener una estimación.

## 8. Eventos fiables y vista gráfica

Las confirmaciones, rechazos y servicios se entregan al propietario de su seguimiento por canales fiables en memoria. El productor prepara primero una lista local de eventos y la emite después de liberar su lock. Esa emisión se completa antes de procesar una transición posterior; por emisor se conserva el orden lógico. Un fallo de entrega es una incidencia, nunca un evento perdido silenciosamente.

Fiable aquí significa sin descarte en ejecución normal, no persistencia frente a caída de la JVM. No se añade almacenamiento duradero al alcance académico.

| Consumidor | Entrega |
| --- | --- |
| Controlador | Confirmaciones de operación, incidencias y actualizaciones de admisión. |
| Manejador | Aceptaciones/rechazos de recogida y servicios de externas. |
| UI | Proyección/snapshots para pintar y avisos visibles. |
| Logging | Transiciones físicas y de control para terminal y archivo por cabina. |

Una única ruta lógica recibe cada respuesta y la distribuye según destinatario. Controlador y manejador no compiten por hacer take de la misma cola de respuestas.

UiEventBridge conserva el último estado visual y programa como máximo una entrega de refresco pendiente; al terminarla, vuelve a comprobar si llegó una revisión nueva para no perder el último refresco. El EDT ignora revisiones viejas por cabina y proyecciones globales viejas. Puede omitir estados intermedios de pintura, pero nunca las confirmaciones que necesita la lógica.

Los callbacks de resultados asíncronos pueden ejecutarse en el hilo que los completa. La UI siempre vuelve al EDT. El coordinador no debe ejecutar listeners arbitrarios y lentos: las entregas se desacoplan y documentan como no bloqueantes. Si un listener falla, se comunica la incidencia sin matar al trabajador de cabina.

## 9. Barreras de controles

Cada operación global tiene identidad propia, época, participantes esperados y conjunto de confirmaciones recibidas. El coordinador los modifica exclusivamente en su ejecutor. Dos confirmaciones de un participante no cuentan como dos participantes. Un snapshot visual no reemplaza una confirmación correlacionada.

### 9.1 Stop

1. El coordinador serializa Stop, publica STOPPING y cierra admisión manual de nuevo servicio.
2. Envía control al manejador y a todas las cabinas. El manejador deja de despachar y confirma ese modo.
3. Las cabinas alcanzan la frontera segura de la sección 6 y confirman su inmovilidad; conservan agenda y entradas de servicio pendientes.
4. Publica STOPPED solo cuando el manejador y todas las cabinas han confirmado la misma operación.

La prioridad de control puede adelantar a órdenes de servicio ya encoladas. Esas órdenes no se pierden: permanecen pendientes, sin ejecutarse, para Start. Los envíos en curso del manejador se registran antes de emitir y no se abandonan al detenerse. Confirmaciones/rechazos tardíos se procesan aunque esté detenido. No se despacha nada nuevo después de su confirmación.

### 9.2 Pausa

Se pasa a PAUSING, se detiene despacho y cada cabina congela su fase antes de confirmar. Solo entonces se publica PAUSED. Reanudar habilita el avance desde los restantes conservados. Durante la barrera las cabinas no se congelan al mismo nanosegundo; la interfaz no anuncia PAUSED anticipadamente.

### 9.3 Reset

1. El coordinador pasa a RESETTING y aumenta época una sola vez; invalida lote, admisiones y operaciones de servicio anteriores.
2. Envía reset prioritario al manejador y a todas las cabinas. Cada receptor adopta la época nueva y descarta su servicio anterior, incluso si ya lo había extraído para parsear.
3. El manejador invalida pendientes/asignaciones anteriores y confirma adopción de época. No vuelve a despachar trabajo antiguo.
4. Cada cabina publica la época nueva, termina el tramo parcial si existe y retorna a 1; confirma reset solo en 1/UP y sin trabajo antiguo.
5. El coordinador publica RESET al recibir todas las confirmaciones vigentes, incluidas las de limpieza del manejador.

No hace falta vaciar colas desde un hilo externo: cada propietario limpia su estado y su buzón al procesar el control. Un envío viejo que llegue después de limpiar se rechaza por época. Esto resuelve la carrera entre vaciar una cola y un productor que todavía estaba enviando.

Reset repetido durante RESETTING no aumenta otra vez la época. Si reemplaza una operación Stop/pausa pendiente, esta se termina como sustituida/cancelada, no como éxito. Sus confirmaciones tardías no completan el reset. Si se agotara el rango de época, se informa un fallo y se requiere cierre; no se permite volver a una época antigua por desbordamiento.

### 9.4 Start y reanudación

Start no crea nuevos workers. El coordinador habilita el avance y la admisión en una secuencia serializada; libera el lote preparado una sola vez en el orden de sus líneas. Reanudar continúa fases congeladas. Las órdenes de habilitación llegan antes del servicio nuevo por la prioridad y el orden del mismo buzón.

Una habilitación antigua pendiente no puede deshacer un Stop/pausa posterior: los controles de ciclo de vida incluyen correlación/orden de operación y cada receptor ignora los sustituidos. La codificación concreta se ajustará al protocolo 05 al implementar; T14 no inventa una gramática paralela.

### 9.5 Cierre

El coordinador pasa a CLOSING, rechaza admisiones, cancela importaciones/operaciones pendientes y emite cierre prioritario. El cierre despierta esperas de pausa, Stop o tiempo, y no se rechaza por una diferencia de época.

Cada actor libera recursos propios y señala fin. Un supervisor fuera del EDT comprueba terminación real de los threads, además de los avisos, y cierra I/O y handlers después de los últimos registros. Finalmente el coordinador publica CLOSED y la UI dispone la ventana; el ejecutor del coordinador se termina después de publicar su resultado.

Un trabajador no hace join de sí mismo. El coordinador no espera su propio cierre. Un timeout es una incidencia y no equivale a CLOSED. No se usa System.exit como sustituto de terminar correctamente los trabajadores.

## 10. Importaciones, cancelación y carreras

El coordinador captura RESET y época y encarga la lectura fuera del EDT. El lector no modifica estado compartido. Al devolver el lote, se encola una tarea de confirmación que verifica de nuevo RESET, época y vigencia de esa importación.

Si se permiten dos lecturas concurrentes, cada una tiene identidad y solo la importación que siga vigente puede confirmar; una lectura vieja que termine más tarde no sobrescribe la más reciente. Cancelar o fallar conserva el lote previo. Start/reset/cierre invalidan la posibilidad de confirmar el resultado tardío aunque la lectura no se haya podido interrumpir de inmediato.

La admisión manual también se decide en el coordinador: comprobar RUNNING desde el EDT solo mejora la experiencia y no autoriza el envío. Validación, comprobación de época y registro de admisión se serializan con los controles. El envío fallido devuelve una respuesta explícita y no deja una solicitud marcada engañosamente como asignada.

## 11. Interrupciones y fallos

| Situación | Tratamiento |
| --- | --- |
| await interrumpido durante cierre | Terminar conforme al cierre y liberar recursos. |
| Interrupción inesperada | Comunicar incidencia y ejecutar la política de terminación; no ignorarla ni continuar en un bucle de interrupciones. |
| Mensaje inválido | Rechazo correlacionado; el worker sigue atendiendo controles. |
| Excepción no recuperable de worker | Marcar no disponible, detener el avance normal y permitir cierre; no confirmar reset/Stop exitosos por ausencia del actor. |
| Fallo de logging o entrega fiable | Informar causa por el canal de incidencias; no silenciar ni declarar una acción no realizada. |
| Servicio sin confirmación por fallo | Resultado incierto visible; no reasignar automáticamente una recogida potencialmente realizada. |

La interrupción no es el protocolo normal de pausa/reset; se utilizan controles explícitos y señalización. La implementación cerrará recursos en finally y mantendrá el estado de interrupción cuando corresponda al propagar el fallo.

## 12. Casos de aceptación para implementación

Estos casos se derivan del diseño; todavía no están ejecutados. Usar reloj controlado y sincronizadores de pruebas, evitando depender de sleeps arbitrarios.

| Caso de carrera | Resultado exigido |
| --- | --- |
| Control insertado justo antes de await | El actor observa el predicado o recibe señal; no queda dormido indefinidamente. |
| Despertar sin mensaje ni plazo vencido | Reevalúa y vuelve a esperar; no completa un piso ficticio. |
| Reset con muchas órdenes pendientes | Se procesa prioritariamente; ningún servicio viejo se ejecuta después de adoptar época. |
| Reset después de extraer una orden | La revalidación previa a mutación la rechaza. |
| Envío viejo posterior a limpieza de buzón | Se rechaza por época, sin reintroducir pendientes. |
| Stop adelantando servicio encolado | Conserva órdenes sin servirlas; Start permite continuar. |
| Pausa a mitad de tramo | Tras confirmación, progreso/restante no cambian hasta reanudar o reset. |
| Movimiento de dos cabinas | La espera temporal de una no bloquea la otra ni el manejador. |
| Confirmación duplicada o de operación sustituida | No incrementa indebidamente la barrera ni termina la operación vigente. |
| Snapshot antiguo que llega tarde a UI | No hace retroceder su revisión visible. |
| UI que intenta modificar destinos publicados | La modificación no es posible; el estado privado permanece intacto. |
| Importación termina después de Start/reset | No sustituye el lote ni introduce servicio tardío. |
| Cierre desde pausa/Stop | Todos despiertan y terminan; el EDT sigue respondiendo. |
| Callback lento o fallido | No se ejecuta bajo lock ni bloquea la cabina que originó el evento. |

## 13. Revisión y cierre de T14

| Criterio | Cobertura |
| --- | --- |
| Propiedad de colecciones y estado definida | Secciones 2–4. |
| Bloqueo/condiciones para esperas | Secciones 3–6. |
| Ausencia de bloqueos globales y esperas remotas bajo lock | Sección 5. |
| Snapshots seguros y publicación atómica | Secciones 7–8. |
| Controles y respuestas obsoletas coherentes | Secciones 9–11. |
| Pruebas concretas derivables | Sección 12. |

El diseño se revisó contra T12/T13 y conserva las semánticas de 02. La ausencia de interbloqueos es un objetivo justificado por las reglas de no anidamiento y propiedad, no una certificación de código que aún no existe. La implementación deberá respetarlas y ejecutar las pruebas.

Guardar este archivo en `docs/diseno/T14-sincronizacion-estado-publicado.md`. No se modifican Java ni POM y no hace falta repetir Maven para esta tarea documental.

Desde la raíz del proyecto:

```powershell
git status --short
git add -- docs/diseno/T14-sincronizacion-estado-publicado.md
git diff --cached --check
git diff --cached --stat
git diff --cached -- docs/diseno/T14-sincronizacion-estado-publicado.md
```

Revisar el documento y actualizar su estado a `Terminada; diseño incorporado y revisado`. Volver a prepararlo y, si el contenido corresponde a T14:

```powershell
git add -- docs/diseno/T14-sincronizacion-estado-publicado.md
git diff --cached --check
git commit -m "T14: disenar sincronizacion y estado publicado"
git status --short
```

Mover T14 a Terminada tras incorporar y revisar. Próxima tarjeta: **T15 — Definir invariantes y transiciones**.

## 14. Historial

| Versión | Fecha | Cambio |
| --- | --- | --- |
| 0.1.0 | 2026-10-21 | Propiedad, buzones, locks, condiciones, snapshots, barreras y carreras verificables. |
