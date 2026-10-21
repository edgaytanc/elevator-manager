# T15 — Definir invariantes y transiciones

- **Proyecto:** Elevator Manager.
- **Versión:** 0.1.0.
- **Fecha:** 2026-10-21, Guatemala.
- **Fase:** 2 — Crear carpetas y diseñar la arquitectura.
- **Estado:** diseño preparado y revisado; pendiente de incorporación y cierre por el usuario.
- **Fuentes:** enunciado, [decisiones 02](../02-decisiones.md), [arquitectura T12](../03-arquitectura.md), [algoritmo 04](../04-algoritmo-asignacion.md), [T13](T13-herencia-solicitudes.md) y [T14](T14-sincronizacion-estado-publicado.md).
- **Alcance:** reglas verificables y condiciones de transición. La implementación y ejecución de pruebas corresponden a las tarjetas posteriores.

## 1. Objetivo y aceptación

Precisar piso en tránsito, destino comprometido, atención por sentido, finalización de reset y cierre. Resolver los límites del edificio y derivar pruebas concretas sin cambiar las semánticas de Stop, pausa o asignación ya acordadas.

Una invariante debe cumplirse en cada estado observable coherente del propietario. Una transición modifica varios campos como un solo cambio lógico antes de publicar su snapshot. Los lectores no deben observar combinaciones parciales de esos campos. Las proyecciones de distintas cabinas pueden tener instantes diferentes, como establece T14.

Se distinguen tres niveles: estado global de la sesión, fase física de cada cabina y seguimiento de cada solicitud. Dirección UP/DOWN no es un estado global ni una fase de movimiento.

## 2. Vocabulario

| Término | Definición verificable |
| --- | --- |
| Piso actual | Último piso alcanzado; durante un tramo sigue siendo el piso que se dejó. |
| Tramo | Movimiento entre dos pisos adyacentes, sin inversión durante ese tramo. |
| Barrido | Recorrido monótono en un sentido, con frontera comprometida. |
| Frontera comprometida | Piso más lejano comprometido del barrido vigente; incluye destinos previos, no solo el siguiente piso. |
| Llegada | Cambio físico de piso al terminar un tramo; por sí sola no atiende solicitudes. |
| Inicio de servicio | Inicio de parada compatible o incorporación válida a una parada compatible abierta; completa las solicitudes atendidas. |
| Fin de parada | Agotamiento del tiempo de espera; habilita un posible movimiento posterior. |
| Época | Generación del sistema que cambia al comenzar reset y permite invalidar trabajo anterior. |
| Confirmación | Mensaje correlacionado que acredita una condición concreta de una operación. |
| Pendiente | Solicitud admitida que todavía no ha iniciado servicio ni fue cancelada/rechazada por un resultado posterior. |

## 3. Invariantes de configuración, identidad y estado

| ID | Invariante | Propietario / comprobación |
| --- | --- | --- |
| INV-01 | N y P son al menos 1; duraciones positivas y representables sin desbordar los cálculos previstos. | Configuración inmutable de sesión. |
| INV-02 | Los IDs de cabina son únicos y pertenecen a 1..N; todos comparten las mismas duraciones. | Composición y controlador. |
| INV-03 | Solicitudes son inmutables y pertenecen a una época; una solicitud antigua no se recicla cambiándole campos. | Modelo T13 y admisión. |
| INV-04 | Solo el worker de una cabina escribe su estado/agenda; solo el coordinador escribe el estado global. | Propiedad de T14. |
| INV-05 | Toda admisión manual se decide en RUNNING; toda confirmación de importación se decide en RESET y época vigente. | Controlador; no basta comprobar desde GUI. |
| INV-06 | Snapshots son copias inmutables completas; revisión por cabina creciente durante toda la sesión, incluido reset. | Publicación T14. |

El comienzo de sesión publica cabinas en piso 1, dirección UP y sin agenda activa. Antes de completar el formulario y construir la sesión, la ventana de T11 no implica que exista ya una simulación en RESET.

## 4. Invariantes físicas de cabina

| ID | Invariante | Condición observable |
| --- | --- | --- |
| INV-07 | El piso actual siempre está en 1..P. | Ningún comando provoca piso 0 o P+1. |
| INV-08 | Durante un tramo activo, el siguiente piso es adyacente y está en 1..P. | UP: siguiente = actual + 1; DOWN: siguiente = actual - 1. |
| INV-09 | El piso actual no cambia por progreso visual ni por recibir un comando. | Cambia al completar la llegada del tramo. |
| INV-10 | No se invierte dentro de un tramo ni mientras exista compromiso del barrido por delante. | Inversión solo alineada en piso y conforme al planificador; reset/cierre tienen sus reglas prioritarias. |
| INV-11 | Dirección y fase son independientes. | IDLE puede conservar UP o DOWN; inicial y fin de reset son UP. |
| INV-12 | Una cabina no inicia movimiento con tiempo de parada pendiente, salvo cancelación de ese servicio por reset o cierre. | Stop/pausa conservan el restante; Start/reanudar lo continúan. |
| INV-13 | Una fase suspendida conserva su información física y temporal. | HELD con fase suspendida MOVING conserva siguiente piso; HELD con DWELLING conserva restante de parada. |
| INV-14 | Cada tramo termina una sola vez y emite una sola llegada lógica. | Despertares repetidos no repiten ARRIVED ni avanzan otro piso instantáneamente. |

En MOVING, el progreso publicado está entre 0 y 1 sin representar una llegada ya consumada. Al completar el tramo se actualiza piso, se retira siguiente piso y se publica la fase resultante como una transición coherente. Una representación gráfica puede mostrar el extremo al interpolar, pero no modifica el piso del modelo.

En IDLE/DWELLING no hay siguiente piso de un tramo. La excepción explícita es HELD que suspendió MOVING: esa información sigue describiendo dónde está físicamente la cabina. Una cabina detenida entre pisos por pausa no equivale a IDLE alineada con un piso.

El tiempo restante nunca es negativo. En fase activa disminuye con el reloj monotónico; al procesar pausa se congela. La tolerancia real de planificación del sistema operativo no altera la regla: las pruebas deterministas usan reloj controlado y las reales tolerancias documentadas.

## 5. Invariantes del recorrido comprometido

| ID | Regla | Consecuencia |
| --- | --- | --- |
| INV-15 | Una llamada nueva detrás no reduce la frontera actual. | Si sube de 2 a 8, no da vuelta en 3 para atender una llamada detrás. |
| INV-16 | Una interna por delante puede ampliar la frontera; una externa compatible se incorpora solo hasta la frontera vigente. | Externas más allá se difieren conforme a 04. |
| INV-17 | Trabajo detrás o de sentido contrario se conserva diferido. | No se descarta por no encajar en el barrido actual. |
| INV-18 | Al alcanzar la frontera se atiende lo compatible antes de elegir el siguiente barrido. | Trabajo detrás permite invertir; sin él se puede continuar por delante o quedar IDLE. |

Si no hay barrido activo, se elige el objetivo pendiente más próximo por tramos, con desempate por orden de admisión y luego piso. Conservar UP estando libre no obliga a viajar hacia arriba si el objetivo requiere bajar.

**Ejemplo de compromiso:** piso 2/UP, interna al 8, llamada 3/DOWN. Primero se alcanza 8 y se sirve la interna; después se puede regresar a recoger 3/DOWN. Si también existe 5/UP, puede recogerse durante la subida sin abandonar el compromiso al 8.

**Viaje y servicio diferentes:** una cabina libre en 1 puede subir a recoger una llamada 3/DOWN. En 3 cambia a DOWN para iniciar ese servicio; la subida de aproximación no vuelve inválida la llamada. Si 3 fuera intermedio de otro compromiso UP hasta 8, no podría invertir allí para atenderla.

Reset invalida el barrido de servicio y crea retorno físico; es la excepción explícita que permite abandonar un compromiso anterior después de resolver el tramo físico ya iniciado.

## 6. Invariantes de solicitudes y servicio

| ID | Invariante | Comprobación |
| --- | --- | --- |
| INV-19 | Admisión, asignación y servicio son hechos diferentes. | ACK no apaga destinos ni marca atendida una externa. |
| INV-20 | Una interna solo se sirve en su cabina y piso; una externa exige piso y dirección de servicio compatibles. | Una parada UP en 5 no atiende 5/DOWN. |
| INV-21 | Al iniciar servicio, cada solicitud canónica se completa una vez y deja de figurar pendiente. | Su luz se apaga entonces; el tiempo de parada todavía puede continuar. |
| INV-22 | Una llegada sin servicio no completa solicitudes. | Incluye pisos de paso, Stop al llegar y retorno por reset. |
| INV-23 | Los duplicados activos usan claves funcionales; el transporte conserva sus propias identidades. | No se duplica parada por mensajes equivalentes. |
| INV-24 | Reset cancela pendientes, pero no cancela retroactivamente solicitudes ya servidas. | Historial conserva el servicio previo. |

Una parada compatible abierta puede admitir nuevas solicitudes compatibles y servirlas sin reiniciar su duración. Si ya terminó y debe abrirse una parada nueva, esta usa la duración completa. Una solicitud nueva tras servicio tiene identidad nueva y no se confunde con la ya completada.

Si se sirven UP y luego DOWN en el mismo piso al terminar un barrido, se registra inversión y se abre otra parada con duración completa. No se sirven ambos sentidos como si fueran una única parada indiferenciada.

Una selección al piso recién dejado durante MOVING o HELD/MOVING se difiere para regreso; la cabina ya no está disponible físicamente en él. Una selección al piso de una cabina alineada puede servirse sin desplazamiento si el estado global lo permite.

### 6.1 Seguimiento de solicitud

| Origen conceptual | Hecho | Resultado |
| --- | --- | --- |
| Entrada sin admitir | Dato inválido, estado incompatible o época vieja | Rechazo con causa, sin introducir pendiente activo. |
| Entrada sin admitir | Misma clave funcional todavía activa | Respuesta de duplicado vinculada a la identidad canónica. |
| Entrada válida | Admisión por controlador | Pendiente; todavía no necesariamente aceptada por cabina. |
| Externa pendiente | Aceptación de recogida por cabina | Asignada; sigue sin atenderse. |
| Orden enviada | Rechazo antes de aceptación | Se informa; externa puede recalcular conforme a 04. |
| Pendiente/externa asignada | Inicio de servicio compatible | Atendida una sola vez. |
| Pendiente | Reset o cierre | Cancelada; una respuesta vieja posterior no la reactiva. |
| Seguimiento con resultado incierto | Fallo sin confirmación fiable | Incidencia visible; no se finge atención ni se reasigna automáticamente. |

Son estados conceptuales de seguimiento; no se crean enums adicionales en esta tarea. Un lote preparado en RESET no es trabajo activo ya despachado.

## 7. Estados globales y acciones admitidas

| Estado | Acciones admitidas | Restricción principal |
| --- | --- | --- |
| RESET | Importar, Start, Reset, Cerrar. | Cabinas en 1/UP; puede existir lote preparado sin ejecutar. |
| RUNNING | Solicitudes manuales, Stop, Pausar, Reset, Cerrar; Start repetido sin duplicar workers. | No importar archivos. |
| STOPPING | Reset o Cerrar; siguen procesándose confirmaciones. | No Start ni nuevo servicio manual. |
| STOPPED | Start, Reset, Cerrar. | Se conservan pendientes sin avance. |
| PAUSING | Reset o Cerrar; siguen procesándose confirmaciones. | Todavía no afirmar que todo está congelado. |
| PAUSED | Reanudar, Stop, Reset, Cerrar. | Progreso y restantes congelados; no Start directo. |
| RESETTING | Cerrar; Reset repetido sin efecto adicional. | No servicio manual, importación ni Start. |
| CLOSING | Cerrar repetido sin duplicar operación. | Ninguna otra acción. |
| CLOSED | Cerrar repetido como operación ya concluida. | Sesión terminada; una sesión nueva requiere nueva composición. |

Las acciones no incluidas se rechazan con causa, sin mutar parcialmente el estado. Los controles recibidos durante una operación se serializan y se aplican según prioridad, época y correlación de T14. Un rechazo por acción incompatible no mata workers.

### 7.1 Transiciones globales

| Origen / acción | Estado transitorio | Condición de finalización | Destino |
| --- | --- | --- | --- |
| RESET / importar | Permanece RESET durante lectura | Lote íntegro y confirmación todavía en RESET/misma época | RESET con lote sustituido. |
| RESET / Start | Habilitación serializada | Se autoriza ejecución y se libera lote una vez, con controles previos al servicio | RUNNING. |
| STOPPED / Start | Habilitación serializada | Se autoriza continuar fases/pendientes conservados | RUNNING. |
| RUNNING / Stop | STOPPING | Manejador sin nuevo despacho y todas las cabinas en frontera segura | STOPPED. |
| PAUSED / Stop | No necesita avanzar físicamente | Coordinación confirma modo detenido conservando congelación | STOPPED. |
| RUNNING / Pausar | PAUSING | Manejador y todas las cabinas confirman congelación | PAUSED. |
| PAUSED / Reanudar | Habilitación serializada | Se autoriza continuar desde restantes preservados | RUNNING. |
| Estado permitido / Reset | RESETTING | Limpieza del manejador y retorno confirmado de todas las cabinas a 1/UP en época nueva | RESET. |
| Cualquier estado activo / Cerrar | CLOSING | Terminación real de workers y liberación coordinada de recursos | CLOSED. |

Habilitar ejecución no asegura que todos los hilos avancen simultáneamente; el envío a cada buzón conserva el orden y los controles sustituidos no pueden reactivar una operación anterior. Si la habilitación falla, se informa y detiene el avance normal, sin simular una sesión sana.

Las barreras terminales usan confirmaciones correlacionadas; no se completan contando snapshots visuales. Confirmaciones duplicadas, antiguas o de una operación sustituida no cuentan. Un timeout o worker fallido impide afirmar éxito y mantiene una incidencia visible; no se inventa aquí un nuevo estado global de error.

## 8. Fases locales y transiciones físicas

| Origen | Condición / acción | Cambio coherente |
| --- | --- | --- |
| IDLE alineada | Hay trabajo autorizado a otro piso | Elegir barrido y siguiente piso adyacente; iniciar MOVING. |
| IDLE alineada | Servicio válido en piso actual | Resolver sentido permitido, iniciar DWELLING y completar solicitudes compatibles. |
| MOVING | Vence restante del tramo | Actualizar piso y emitir llegada; decidir servicio o acción siguiente según controles/recorrido. |
| MOVING | Pausa | HELD con fase suspendida MOVING, siguiente piso, progreso y restante conservados. |
| DWELLING | Pausa o Stop | HELD con fase suspendida DWELLING y restante conservado. |
| MOVING | Stop | Mantener tramo ya iniciado; al llegar quedar detenida sin iniciar nuevo servicio. |
| HELD | Reanudar o Start desde estado permitido | Recuperar fase/restante; no consumir tiempo de suspensión. |
| DWELLING | Vence restante | Cerrar parada; planificar siguiente acción o quedar IDLE. |
| Cualquier fase no terminada | Reset | Cancelar servicio viejo, activar retorno; resolver tramo parcial si existe y volver a 1. |
| Retorno en piso 1 | Fin físico de reset | UP, IDLE, sin pendientes de época anterior; publicar y confirmar. |
| Cualquier fase | Cierre | Cancelar avance, liberar recursos y terminar worker. |
| TERMINATED | Cualquier orden de servicio | No ejecutar; informar cierre por la frontera de recepción que siga disponible. |

HELD siempre conserva la fase suspendida y el motivo de detención. Una cabina inmóvil por Stop alineada en un piso puede permanecer en IDLE con avance deshabilitado; no se exige simular un tramo inexistente. El modo de ejecución y el estado global impiden que esa fase IDLE consuma pendientes.

Al llegar durante STOPPING se publica el piso alcanzado, pero el servicio de ese piso se mantiene pendiente para Start. En cambio, una parada que había comenzado antes de Stop ya completó sus solicitudes; solo conserva el tiempo restante de parada.

## 9. Reset: condiciones obligatorias de fin

| ID | Invariante terminal |
| --- | --- |
| INV-25 | Al comenzar reset, la época avanza una sola vez y las nuevas admisiones quedan bloqueadas. |
| INV-26 | Cada cabina descarta agenda anterior, completa tramo parcial si existe y retorna físicamente a 1 sin recoger/dejar trabajo viejo. |
| INV-27 | RESET global solo se publica tras confirmaciones vigentes de limpieza del manejador y de todas las cabinas en 1/UP. |
| INV-28 | Reset funciona desde pausa/Stop y no termina en PAUSED/STOPPED: termina en RESET esperando Start. |

No basta vaciar colas ni cambiar piso directamente a 1. Los tramos pendientes del retorno consumen los tiempos correspondientes; el tramo parcial conserva su restante. Reset no introduce tiempo de parada de pasajeros. Si ya está alineada en 1, la cabina limpia y confirma sin viaje artificial.

Repetir reset en RESETTING no crea una época adicional. Un reset que sustituye Stop o pausa cancela su operación pendiente como sustituida, no como completada exitosamente. Una respuesta de época anterior no vuelve a encender botones ni restaura el lote.

## 10. Cierre y condiciones de progreso

| ID | Invariante |
| --- | --- |
| INV-29 | CLOSING impide nueva admisión y despierta workers aunque estén pausados, detenidos o esperando tiempo. |
| INV-30 | CLOSED exige terminación real de trabajadores y recursos coordinados; la GUI nunca espera join ni confirmaciones. |
| INV-31 | Start/Stop/pausa no crean otro conjunto de threads por sesión. |
| INV-32 | Un lote finito válido debe terminar o producir cancelación/incidencia explícita; no puede desaparecer silenciosamente. |

El cierre no necesita volver a piso 1 ni completar viajes. La ventana se libera tras el cierre coordinado cuando exista el motor; la implementación mínima de T11 se adaptará en su tarjeta de ciclo de vida. Un worker no se espera a sí mismo y el coordinador no bloquea esperando una tarea que necesita su mismo ejecutor.

La condición de progreso presupone threads planificados, canales operativos, duraciones finitas y ausencia de nuevas suspensiones indefinidas. No se promete ausencia de inanición para un flujo infinito de solicitudes ni éxito ante fallos de I/O o workers. Cada fallo se comunica y permite intentar cierre conforme a T14.

## 11. Límites y casos de prueba derivados

Estos resultados son especificaciones; no se ejecutaron en T15. En pruebas de tiempo se utilizará reloj controlado; las pruebas reales tendrán tolerancia explícita. Cada caso exige además que la UI permanezca responsive y que los eventos se correlacionen correctamente cuando corresponda.

| Caso | Preparación y acción | Resultado esperado / invariantes |
| --- | --- | --- |
| T15-C01 | P=1, interna de cabina válida a piso 1 | Servicio sin traslado; no se genera siguiente piso fuera del edificio. INV-07, 20. |
| T15-C02 | P=1, externas UP y DOWN en piso 1 | Ambas rechazadas contextualmente. INV-05, 20. |
| T15-C03 | P=5; pedir piso 0 o 6, o ID fuera de 1..N | Error controlado, sin pendiente ni movimiento inválido. INV-01, 02, 07. |
| T15-C04 | Cabina en 2→3; avanzar parcialmente el reloj | Actual sigue en 2, siguiente es 3; solo al llegar cambia a 3. INV-08, 09, 14. |
| T15-C05 | Piso 2/UP, frontera 8; nueva externa 3/DOWN | Mantener subida hasta 8; recoger 3/DOWN en retorno. INV-10, 15, 17. |
| T15-C06 | Mismo compromiso, llamadas 5/UP y 5/DOWN | Servir solo UP durante subida; DOWN sigue pendiente. INV-18–22. |
| T15-C07 | Frontera 8; externa compatible al 9, luego interna al 10 | La externa no amplía por sí sola frontera; la interna puede ampliarla y el plan reevalúa diferidas conforme a 04. INV-16. |
| T15-C08 | Libre en 1/UP; única externa 3/DOWN | Viajar UP hasta 3, cambiar a DOWN y servir allí. INV-10, 11, 20. |
| T15-C09 | En 2→3; nueva interna al piso 2 recién dejado | Programar regreso; no servicio inmediato en 2. INV-09, 17, 20. |
| T15-C10 | Parada UP abierta con 700 ms restantes; nueva compatible | Atender sin reiniciar; conserva restante hasta fin de parada. INV-12, 21. |
| T15-C11 | Frontera en 5, servicio UP y luego DOWN en 5 | Registrar inversión y nueva parada completa para DOWN. INV-18, 20. |
| T15-C12 | MOVING; Stop y completar tramo vigente | STOPPED en piso adyacente, sin nueva parada ni otro tramo; pendientes conservados. INV-12, 22. |
| T15-C13 | DWELLING; Stop, esperar tiempo de pared y Start | Conservar y terminar restante; no atender de nuevo solicitudes ya servidas. INV-12, 21, 24. |
| T15-C14 | MOVING a mitad de tramo; pausar y esperar | PAUSED solo tras barrera; piso, posición y restante se congelan. INV-13. |
| T15-C15 | Pausada entre 4→5; reset | Completar restante hasta 5, volver físicamente a 1, sin servicio anterior; terminar 1/UP en RESET. INV-25–28. |
| T15-C16 | Reset con una cabina aún en retorno; recibir ACK de otra | Seguir RESETTING; no permitir importar ni Start. INV-27. |
| T15-C17 | Durante reset recibir servicio/confirmación de época vieja | No restaurar pendientes ni completar barrera nueva. INV-03, 25, 27. |
| T15-C18 | Importación en RESET termina después de Start/reset | Resultado tardío no sustituye lote ni introduce solicitudes. INV-05. |
| T15-C19 | Cerrar desde PAUSED o STOPPED | Despertar y terminar todos; CLOSED solo tras verificación real. INV-29, 30. |
| T15-C20 | Repetir Start/Stop según acciones permitidas | Una sola población de workers; acciones incompatibles se rechazan. INV-31. |
| T15-C21 | P=1, cabina IDLE/UP sin trabajo | Estado válido; UP no obliga a intentar movimiento a piso 2. INV-07, 11. |
| T15-C22 | N=1 y varias externas válidas | Todas pasan por manejador; lote finito termina sin exigir una segunda cabina. INV-02, 32. |
| T15-C23 | Falta confirmación de worker en reset o cierre | Incidencia visible; no declarar RESET/CLOSED por timeout. INV-27, 30. |

Para T15-C07, una externa diferida puede pasar a ser compatible si después una interna amplía la frontera: no estaba prohibida para siempre, solo no podía extenderla por sí misma. Mantener la misma regla en ejecución y proyección ETA.

## 12. Trazabilidad y revisión

| Exigencia de T15 | Desarrollo |
| --- | --- |
| Piso en tránsito | Secciones 2, 4 y casos C04/C09/C14. |
| Destino comprometido | Sección 5 y casos C05–C08. |
| Atención por sentido | Sección 6 y casos C06/C10/C11. |
| Finalización de reset | Sección 9 y casos C15–C17/C23. |
| Cierre | Sección 10 y casos C19/C23. |
| Límites del edificio | INV-01/02/07/08 y casos C01–C03/C21/C22. |
| Transiciones verificables | Secciones 7–8 y tabla de pruebas. |

Revisión realizada contra 02, T12–T14 y el algoritmo 04 v0.1.0. No se cambian los desempates ni la gramática del protocolo. Las reglas por sentido y frontera se aplican simétricamente al bajar, respetando 1..P.

T15 completa la especificación prevista de la fase 2 cuando el usuario incorpore y cierre la tarjeta. Eso no acredita implementación del modelo, motor, concurrencia ni extras. La siguiente fase comienza con T16 — Implementar configuración inmutable.

## 13. Incorporación y cierre

Guardar en `docs/diseno/T15-invariantes-transiciones.md`. Esta entrega solo modifica documentación, por lo que no requiere repetir Maven.

Revisar el contenido y cambiar el estado a `Terminada; diseño incorporado y revisado`. Desde la raíz del proyecto:

```powershell
git status --short
git add -- docs/diseno/T15-invariantes-transiciones.md
git diff --cached --check
git diff --cached --stat
git diff --cached -- docs/diseno/T15-invariantes-transiciones.md
```

Si los cambios preparados corresponden a esta tarjeta:

```powershell
git commit -m "T15: definir invariantes y transiciones"
git status --short
```

Mover T15 a Terminada tras incorporar y revisar. No se presupone que commits de tarjetas anteriores existan solo porque su diseño se haya aceptado.

## 14. Historial

| Versión | Fecha | Cambio |
| --- | --- | --- |
| 0.1.0 | 2026-10-21 | 32 invariantes, transiciones globales/locales y 23 casos de aceptación derivados. |
