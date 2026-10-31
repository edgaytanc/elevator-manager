# T57 — Probar pausa en distintas fases

- **Fecha:** 31 de octubre de 2026, Guatemala.
- **Base:** T56 `7dfb8e5`; 28/838 pruebas Maven satisfactorias, staged diff correcto y árbol limpio, confirmados por David.
- **Estado:** automatización implementada y comprobada con Java 17; Maven y cierre de aceptación nativa pendientes.
- **Cambio de producción:** ninguno. Se conserva el motor Java, herencia de Request y actores paralelos de T54–T56.

## Objetivo

Comprobar que el sistema congela piso, siguiente piso, sentido, frontera comprometida, progreso, pendientes y tiempo restante, y que Resume consume el presupuesto restante. Una prueba que solo comprueba ausencia de llegada durante pausa no excluye el error de reiniciar el tramo al reanudar. Por eso se añaden límites inferiores y superiores, captura de la fase restaurada y medidas en los dos dominios temporales.

## Matriz de aceptación

| Escenarios | Configuración y criterio |
| --- | --- |
| Movimiento ascendente temprano/medio/tardío | Tramo de 1000 ms; solicitar pausa tras 150/600/800 ms, comprobar HELD/MOVING/PAUSE y destino conservado |
| Movimiento descendente medio/tardío | Posicionar primero en piso 2 y comprobar tramo 2 a 1, sentido DOWN y resto |
| Parada temprana/media/tardía | Parada de 1000 ms; pausar tras 150/600/800 ms; sin cambiar piso ni reiniciar espera |
| Parada después de bajar | Parada DOWN en piso 1 con presupuesto parcial |
| Cuatro pausas en el mismo tramo/parada | Duración original 1200 ms; consumir 170 ms por ciclo y pausar 80 ms; no recuperar presupuesto ni acumular deriva |
| Reposo antes del primer trabajo | Tres pausas sin solicitudes ni movimientos inventados; atender después una solicitud real |
| Reposo después del trabajo | Preservar piso 1/DOWN, sin restaurar UP ni reenviar solicitudes |
| Fases mixtas | Tres cabinas: MOVING, DWELLING e IDLE; una marca congelada y dos presupuestos temporales independientes |
| Llamadas opuestas y destino | Conservar llamada 2/UP, llamada 2/DOWN y destino 3 tras dos pausas; atender cada identidad una vez |
| Fronteras con duración mínima | Doce ciclos con movimiento/parada de 1 ms; admitir que la pausa alcance IDLE o una fase agotada sin perder solicitudes |
| Edificio de un piso | Pausar una parada parcial sin crear ningún traslado |
| Dieciséis cabinas libres | Cinco ciclos; N+1 ACK, actores en WAITING y mismos objetos Thread |

Son 18 escenarios, cada uno con adaptador JUnit. Las mediciones temporales detalladas son 14 por ejecución; reposo/fronteras/identidades se validan mediante otros oráculos. Las pausas tempranas de movimiento/parada duran 1100 ms, más que su fase completa, para excluir consumo por tiempo de pared. No se modifica ni adelanta el reloj de producción para acelerar las pruebas.

## Contrato temporal y tolerancia

Sea R el remainingNanos capturado en PAUSED y A la diferencia entre la referencia activa de ARRIVED/STOP_ENDED y la referencia congelada. Se exige R <= A <= R + 250 ms. No se permite adelanto. El tiempo de pared W desde antes de solicitar Resume hasta recibir el evento también debe satisfacer W <= R + 250 ms. W incluye programación del coordinador/actores y entrega por la cola; no incluye el intervalo pausado anterior.

Antes de finalizar, se observa la fase restaurada y se exige remainingNanos <= R, mismo piso, siguiente piso y frontera comprometida. Durante la pausa, snapshots completos y ledger deben ser exactamente iguales, sin tolerancia numérica; también se exige ausencia de eventos físicos nuevos después del ACK global. En medios/tardíos, el tiempo ya consumido supera los 250 ms: reiniciar el presupuesto completo no cabe en la tolerancia. Los casos tempranos complementan ese criterio con la captura restaurada sin incremento del restante.

La tolerancia de 250 ms admite latencia de planificación de una máquina de desarrollo sin convertir la pausa en movimiento. Es un criterio de aceptación de pruebas, no una promesa de tiempo real de Java/Swing. Una máquina severamente saturada puede incumplirlo por latencia: conservar el informe y diagnosticarlo; no ampliar el límite automáticamente ni declarar éxito por una repetición. No se usa una resta de tiempos entre nanoTime directo y reloj activo.

Las pausas repetidas también exigen que la fase completa acumule entre su duración original y esa duración +250 ms activos, mientras el tiempo de pared incluye los cuatro intervalos detenidos. La medición de pared comienza antes de enviar la solicitud para no descontar el tiempo de entrega del primer evento.

## Concurrencia y límites

Los actores y el controlador son reales. Las esperas de los fixtures tienen límite de diez segundos; JUnit aplica treinta segundos por prueba. Los sleeps solo posicionan temporalmente los estímulos o mantienen el intervalo de pausa; no forman parte del motor. Al terminar cada escenario se exige cierre real y ausencia de fallo de motor.

La observación de Thread.State se usa únicamente como evidencia complementaria de bloqueo en el caso de reposo; la garantía de pausa sigue siendo la barrera N+1. Ese escenario se ejecuta sin otras sesiones concurrentes de prueba, como en la configuración Maven predeterminada del proyecto. No se mide CPU ni se interpreta WAITING como reconocimiento del protocolo.

Las pruebas de 1 ms no intentan garantizar una fase parcial visible de duración menor que la resolución práctica del planificador. Verifican los invariantes en la fase realmente alcanzada, incluidos resto cero o IDLE. Las muestras tempranas/medias/tardías sí exigen una fase parcial y positiva, por lo que no pasan silenciosamente si el estímulo llega demasiado tarde.

## Entrega y cierre

PausePhaseScenarios es ejecutable sin JUnit y emite líneas T57_TIMING; PausePhaseTest ejecuta los mismos 18 escenarios con JUnit. [T57-codigo-completo.md](T57-codigo-completo.md) contiene ambas fuentes íntegras. [T57-resultados-locales.md](../comprobaciones/T57-resultados-locales.md) conserva las mediciones realmente observadas y [T57-validacion-local.md](../comprobaciones/T57-validacion-local.md) distingue la regresión Java independiente de Maven.

Se mantiene el estado de aceptación nativa parcial de T56 sin atribuirle pruebas no documentadas. [T57-aceptacion-fase-9.md](../comprobaciones/T57-aceptacion-fase-9.md) reúne las comprobaciones pendientes y el criterio de cierre. No se marca T57 como cerrada ni se inicia animación/fase 10 antes de confirmar la validación local y la aceptación restante.
