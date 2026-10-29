# T54 — Implementar control temporal pausable

- **Fecha:** 28 de octubre de 2026, Guatemala.
- **Base:** proyecto completo recibido, T53 `463d302`; fases 1–8 cerradas por David.
- **Estado:** cerrada; David confirmó 17/781 pruebas Maven satisfactorias, commit `4e34f89` y árbol limpio el 29 de octubre de 2026.

## Alcance

Crear la base temporal del extra 1. PausableClock mide tiempo activo con System.nanoTime y bloquea esperas con ReentrantLock/Condition. No usa sleep, sondeo ni reloj de calendario. La herencia de solicitudes existente y los hilos independientes del motor se conservan.

T54 entrega la primitiva y sus pruebas. La aplicación sigue mostrando los controles de la fase 8: T55 conectará reloj y coordinación global, T56 añadirá Pausar/Reanudar y prioridades, y T57 probará el conjunto. No se presenta esta entrega como pausa global terminada.

## Contrato

| Operación | Resultado |
| --- | --- |
| nowNanos | Marca lógica desde cero; avanza solo estando activo |
| pause / resume | Cambio atómico bajo lock; true si cambió, false si ya estaba así |
| isPaused | Consulta segura del estado local del reloj |
| await | Consume una duración activa desde la llamada |
| awaitElapsed | Consume solo el resto desde una marca previa del mismo reloj |
| close | Idempotente; despierta esperas activas y pausadas con IllegalStateException |

La pausa almacena el tiempo activo. La reanudación cambia la referencia física sin añadir el intervalo pausado. Cada espera recalcula su resto después de despertar, incluso ante despertares espurios. Mientras está pausado usa await sin timeout; activo usa awaitNanos con el resto. Todas las modificaciones señalan a todos los consumidores. Las interrupciones se propagan sin reanudar ni cerrar el reloj compartido.

Las duraciones negativas se rechazan; cero o un plazo ya cumplido retornan incluso estando pausado. Las duraciones que no caben en long producen ArithmeticException. Las marcas futuras se rechazan. La resta de nanoTime tolera el cruce del límite con signo para intervalos menores de Long.MAX_VALUE nanos; no se admite medir intervalos de 292 años o más. Una fuente de prueba debe respetar el mismo contrato monotónico.

## Integración prevista para T55

Las marcas de este reloj NO son intercambiables con System.nanoTime directo. Al integrar hay que utilizar una única base temporal coherente en estado, snapshots y cálculos temporales del planificador, sin confundirla con los plazos de cierre o las métricas de tiempo real. No basta sustituir el awaitNanos del trabajador.

El reloj no adquiere locks de los actores ni publica PAUSED. Una espera puede terminar justo antes de pause o encontrar un plazo ya cumplido durante pausa; esto no autoriza una transición de cabina. T55 debe arbitrar pausa y transición en el punto de coordinación del actor y confirmar que todos se han detenido antes de anunciar PAUSED. No se deben retener locks de actores mientras se espera en este reloj. Las esperas son bloqueantes y deben ejecutarse fuera del EDT.

`awaitElapsed` conserva la marca inicial al reintentar tras una interrupción; llamar de nuevo a await crearía un presupuesto nuevo. El cierre local del reloj cancela sus consumidores; la política global de Stop/Reset/cierre se implementa en T56.

## Archivos

- `src/main/java/com/elevatormanager/simulation/time/PausableClock.java`: implementación completa.
- `src/test/java/com/elevatormanager/simulation/time/PausableClockScenarios.java`: 17 escenarios independientes, incluidos hilos reales y estado de cabina real.
- `src/test/java/com/elevatormanager/simulation/time/PausableClockTest.java`: adaptadores JUnit con límite por prueba.
- `docs/implementacion/T54-codigo-completo.md`: las tres fuentes íntegras, legibles sin extraer código de fragmentos.
- `docs/comprobaciones/T54-validacion-local.md`: evidencia y comandos reproducibles.

## Uso de la entrega

Extraer el ZIP en una carpeta temporal y copiar su contenido elevator-manager sobre el repositorio local, conservando el .git existente. Se incluye el proyecto completo; se excluyen .git, target y logs. No se ha creado ni ejecutado ningún commit en el repositorio recibido. La modificación previa que marcaba T53 completada se conserva.

Las validaciones del acta fueron confirmadas por David. T54 está cerrada y T55 continúa con la coordinación global. El contenido restante describe el alcance original de la entrega T54.
