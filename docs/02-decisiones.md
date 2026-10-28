# 02 — Registro de decisiones

- **Proyecto:** Elevator Manager.
- **Versión:** 0.1.0.
- **Fecha:** 2026-10-19, Guatemala.
- **Estado:** propuesta de diseño previa al código; pendiente de revisión, sin aprobación presunta.
- **Fuente:** enunciado adjunto y [01 — Análisis y plan](01-analisis-y-plan.md).
- **Relacionados:** [arquitectura](03-arquitectura.md), [asignación](04-algoritmo-asignacion.md), [protocolo](05-protocolo.md), [pruebas](06-pruebas.md).

## 1. Propósito y reglas de mantenimiento

Resolver las libertades del enunciado antes de que distintas partes del programa les den significados incompatibles. Los requisitos del enunciado tienen precedencia; cada decisión siguiente es una propuesta concreta para implementarlos. Este archivo no acredita implementación ni aprobación del usuario.

Cada cambio debe registrar motivo, documentos afectados y pruebas que deben actualizarse. Una decisión sustituida se conserva en el historial. La versión 0.1.0 de estos documentos desarrolla el plan 1.0.0: este último es la planificación original, no una certificación de que el software exista.

## 2. Plataforma y construcción

| ID | Decisión propuesta | Motivo y consecuencias |
| --- | --- | --- |
| D01 | Compilar para Java 17; usar Swing/AWT. | Base coherente con el plan. No implica que Java 17 sea la versión más reciente. Sin dependencia gráfica externa. |
| D02 | Maven para construcción; JUnit Jupiter solo para pruebas. | Construcción reproducible, separación de fuentes y pruebas. Versiones exactas de plugins/JUnit se fijarán al preparar el entorno, tras verificar compatibilidad; no usar `LATEST`. |
| D03 | Usar `java.util.logging`, `java.util.concurrent` y `java.nio.file`. | APIs del JDK cubren registro, concurrencia y archivos; no agregar frameworks sin necesidad. |
| D04 | Entrada `com.elevatormanager.app.ElevatorManager`. | Conserva el nombre exigido y permite paquetes. Configuración por formulario al abrir. Manifiesto con esa entrada. |
| D05 | Una JVM, un trabajador por cabina y un trabajador manejador. | Los procesos independientes del enunciado se simulan con Threads; Swing usa además su EDT. No se requieren procesos del sistema ni red. |

## 3. Modelo y alcance

| ID | Decisión propuesta | Motivo y consecuencias |
| --- | --- | --- |
| D06 | N ≥ 1, P ≥ 1; pisos 1..P; tiempos positivos en ms. | Validación única para GUI, archivo y protocolo. P = 1 se admite sin llamadas externas válidas. Proteger conversiones y sumas de desbordamiento. |
| D07 | Configuración inmutable durante una sesión. | Todos comparten tiempos. Para cambiar N/P/tiempos, cerrar y abrir otra sesión; no introducir reconfiguración concurrente. |
| D08 | Estado lógico y posición visual distintos. | En tránsito `currentFloor` es el último piso dejado; `nextFloor` y progreso describen movimiento entre pisos. |
| D09 | Herencia de `Request` en `HallRequest` y `CabinRequest`. | Relación real «es una solicitud», con datos comunes y comportamiento polimórfico de validación contextual y descripción. Implementar y usar ese comportamiento, no solo declarar `extends`. |
| D10 | Simular solicitudes y paradas, sin pasajeros individuales. | Capacidad, peso, ingreso físico y destinos automáticos de pasajeros no son exigidos. Una selección interna es una entrada sintética del usuario. |
| D11 | Dirección `UP`/`DOWN`, separada de fase operativa. | Estar en reposo no borra dirección. Inicial y fin de reset: piso 1, dirección UP. |

## 4. Semántica de controles

### 4.1 Estados globales

| Estado | Significado |
| --- | --- |
| RESET | Cabinas en piso 1/UP, sin trabajo activo. Puede existir un lote preparado sin ejecutar. |
| RUNNING | Se despacha y ejecuta trabajo. |
| STOPPING | Se espera confirmación de detención segura. |
| STOPPED | No avanza trabajo; se conservan pendientes. |
| PAUSING | Barrera transitoria: se solicita congelación, todavía sin afirmar que todas las cabinas estén congeladas. |
| PAUSED | Manejador y cabinas confirman congelación, incluido el tiempo restante. |
| RESETTING | Se invalida el trabajo anterior y las cabinas retornan a piso 1. |
| CLOSING | Se solicita terminación y se liberan recursos. |
| CLOSED | Recursos cerrados y trabajadores terminados. |

`PAUSING` y `CLOSING` precisan estados transitorios que el plan original no enumeraba; son refinamientos para no anunciar una operación como completa antes de recibir confirmaciones.

### 4.2 Transiciones y acciones

| Estado de origen | Acción | Resultado |
| --- | --- | --- |
| RESET | Importar | Sustituye lote preparado de manera atómica; permanece RESET. |
| RESET | Start | RUNNING; publica el lote preparado una sola vez, en orden de líneas. |
| STOPPED | Start | RUNNING; continúa pendientes y parada parcialmente consumida. |
| RUNNING | Stop | STOPPING → STOPPED tras confirmación de todos. |
| RUNNING | Pausar | PAUSING → PAUSED tras confirmación de todos. |
| PAUSED | Reanudar | RUNNING; continúa cada fase con su tiempo restante. |
| PAUSED | Stop | STOPPED; conserva congelación y trabajo. Start continúa. |
| RESET, RUNNING, STOPPING, STOPPED, PAUSING, PAUSED | Reset | RESETTING → RESET al confirmar todas las cabinas. |
| RESETTING | Reset | Sin efecto adicional; no iniciar otro reset ni generar nueva época. |
| Cualquier estado salvo CLOSED | Cerrar | CLOSING → CLOSED tras cierre confirmado. |
| CLOSING/CLOSED | Acciones distintas de cerrar | Rechazadas. Cerrar repetido no duplica recursos. |

- **D12 — Stop:** deja terminar únicamente el tramo entre dos pisos que ya empezó, sin iniciar otro ni una nueva parada de servicio. Si una parada ya estaba iniciada, congela su tiempo restante. La llegada física en STOPPING no completa por sí sola una solicitud: el servicio pendiente comienza tras Start. Una cabina ya inmóvil confirma de inmediato.
- **D13 — Pausa:** congela el tramo o la parada donde se encuentre; no espera la llegada al siguiente piso. Se conserva progreso y tiempo restante, con barrera PAUSING. Diferencia observable respecto a Stop.
- **D14 — Reset:** tiene prioridad sobre Stop y pausa. Cancela solicitudes y paradas anteriores; si la cabina está entre pisos, completa el tramo físico ya iniciado desde el progreso conservado, sin atender la parada antigua, y vuelve al piso 1. Cada tramo conserva el tiempo restante o completo que corresponda. Al llegar restablece UP; el reset no agrega tiempo de servicio de pasajeros.
- **D15 — Solicitudes manuales:** solo en RUNNING; en los demás estados se rechazan con causa visible. Importación solamente en RESET. Start repetido en RUNNING no crea otro conjunto de trabajadores; transiciones incompatibles se rechazan.
- **D16 — Prioridades:** cerrar > reset > controles de detención/pausa > comandos de servicio. Un reset despierta trabajadores pausados o detenidos. La interfaz nunca espera confirmaciones bloqueando el EDT.

## 5. Solicitudes, asignación y archivos

| ID | Decisión propuesta | Consecuencia verificable |
| --- | --- | --- |
| D17 | Llamadas externas compartidas por piso/sentido; destinos internos vinculados a cabina. | UP prohibido en P, DOWN prohibido en 1. No elegir cabina desde un botón externo. |
| D18 | Unificar duplicados activos: externa por `(época,piso,sentido)`, interna por `(época,cabina,piso)`. | Tras finalizar/cancelar, se admite otra solicitud equivalente; diferenciar duplicado de transporte y duplicado funcional. |
| D19 | El manejador asigna todas las externas; el controlador envía internas a la cabina elegida. | La cabina recibe ambos tipos por su frontera de mensajes. El manejador mantiene seguimiento de externas hasta su servicio efectivo. |
| D20 | Asignación por ETA de recogida; recorrido colectivo por sentido con frontera comprometida. | Se documentan reglas exactas en 04. ETA es una estimación sobre snapshots, no una promesa de óptimo global. |
| D21 | Piso actual solicitado durante tránsito se agenda para regreso. | No atender desde una cabina que ya dejó físicamente ese piso. Cabina inmóvil en el piso puede iniciar servicio sin moverse. |
| D22 | Importación UTF-8 de líneas `HALL piso direccion` y `CABIN id piso`. | Sin tiempos programados ni simulación automática de pasajeros; contrato de archivo 1.0.0 en [07](07-formato-archivo-solicitudes.md), separado de los mensajes V1 de 05. |
| D23 | Validar todo el archivo antes de confirmar el lote. | Un error o cancelación conserva el lote anterior. Otro archivo válido lo sustituye, no lo acumula. |
| D24 | No reasignar llamadas aceptadas por una cabina en operación normal. | Reduce carreras y dobles recogidas. Un rechazo antes de aceptación permite recalcular; fallo de trabajador deja solicitud en error visible, sin afirmar atención. |

## 6. Concurrencia, mensajes y observabilidad

- **D25 — Propiedad:** cada trabajador es el único escritor de su agenda y estado. El manejador es el único escritor de su registro de externas. Lectores usan snapshots inmutables con revisión; el controlador serializa acciones globales.
- **D26 — Canales:** separar entrada de control y entrada de servicio para que reset/cierre/pausa puedan procesarse aun sin movimiento o con mucho trabajo. Una condición o mecanismo de señalización despierta al trabajador; no basta dejar reset detrás de una espera larga.
- **D27 — Protocolo real:** mensajes de texto UTF-8, una línea por mensaje, en colas en memoria; gramática y respuestas en 05. Los comandos se codifican y parsean realmente. No se necesitan sockets.
- **D28 — Época:** el controlador incrementa generación al comenzar reset; invalida admisiones viejas antes de emitir nuevos controles. Confirmación de reset solo si todas las cabinas están en la época nueva y piso 1/UP. Cerrar siempre puede finalizar al trabajador aunque exista una incidencia de épocas.
- **D29 — Doble comprobación:** antes de confirmar una importación o enviar una orden verificar estado/época vigentes. Un snapshot sirve para estimar, no autoriza una mutación tardía.
- **D30 — Logs:** un archivo `elevador<ID>.log` por cabina y salida a terminal; visor gráfico adicional con historial acotado. Eventos incluyen ID, instante, época y correlación cuando exista. Sin duplicar handlers por reinicios de controles.
- **D31 — Tiempo:** reloj monotónico para duraciones; reloj de calendario para la fecha de logs. Pausa no consume tiempo simulado. Los ensayos reales aceptan tolerancia explícita.
- **D32 — Fallos:** no silenciar excepciones. Rechazar mensajes inválidos sin matar trabajadores; si un trabajador falla, marcarlo no disponible, detener el avance normal y permitir cierre. No declarar reset completo si falta una cabina. No se exige recuperación automática de fallos internos en esta versión.

## 7. Puntos por cerrar durante la preparación

| Punto | Cuándo se decide | No impide |
| --- | --- | --- |
| Distribución y versión exacta del JDK, Maven, JUnit y plugins | T05–T08, comprobando el equipo real. | Diseñar con nivel Java 17. |
| Tema visual, colores, tamaño inicial y frecuencia de refresco | T47–T52/T58–T61. | Fijar EDT, snapshots y datos obligatorios. |
| Tamaño máximo del visor y límites prácticos del importador | Implementación y medición; documentar si se introducen. | Definir importación atómica y sin límites académicos inventados. |
| Tolerancia de pruebas reales y timeout operativo | Calibración del entorno; propuesta inicial en 06. | Probar reglas con tiempo controlado y sin esperas arbitrarias. |

No son autorizaciones pendientes ni se requiere una aprobación nueva para ejecutar tareas ya solicitadas. Este turno comprende documentación; no se inicia implementación.

## 8. Historial y siguiente documento

| Versión | Fecha | Cambio |
| --- | --- | --- |
| 0.1.0 | 2026-10-19 | Propuesta inicial de 32 decisiones y semántica de controles. |

## 9. Precisión T43 — 2026-10-28

D22–D23 se concretan en [07 — Formato de archivo](07-formato-archivo-solicitudes.md). Se mantienen UTF-8, HALL/CABIN, comentarios de línea completa, sustitución atómica y ausencia de calendarización. Se permiten ceros iniciales y un único BOM al comienzo; tabs y comentarios al final de una instrucción siguen prohibidos. Los límites son 1 MiB, 10,000 líneas físicas y 4,096 puntos de código por línea.

D18 continúa aplicándose a solicitudes activas: el importador conserva todas las líneas válidas y su orden; no elimina repetidos. El orden es de presentación al controlador, no de servicio concurrente. T44 implementará este contrato; T43 es documental.

**Siguiente lectura:** [03 — Arquitectura](03-arquitectura.md).
