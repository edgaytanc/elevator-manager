# T32 — Evidencia de validación local

- **Fecha:** 2026-10-26.
- **Entorno:** Linux, OpenJDK 17.0.20, Swing headless.
- **Base:** fuentes anteriores hasta T31 y archivos completos de T32.
- **Compilación:** --release 17, UTF-8; código de salida 0.

```bash
java com.sun.tools.javac.Main --release 17 -encoding UTF-8 -d verification/t32-classes @verification/t32-sources.txt
```

Se ejecutaron los main de los ocho grupos siguientes, con código de salida 0:

```text
T32: 26 escenarios satisfactorios; pendientes, alias, confirmaciones y reintentos con hilos reales.
T31: 18 escenarios satisfactorios; selección por ETA, desempate y revalidación concurrente.
T30: 20 escenarios satisfactorios; incluye 60 rutas contrastadas con ElevatorState.
T29: 12 escenarios satisfactorios (hilos reales, N=1/N=3 y EDT).
T25: 20 escenarios satisfactorios.
T26: 21 escenarios satisfactorios.
T27: 20 escenarios satisfactorios.
T28: 8 escenarios satisfactorios.
```

**Total: 145 escenarios: 26 nuevos y 119 de regresión.** Las sesenta rutas del contraste de T30 pertenecen a uno de sus veinte escenarios y no se cuentan de nuevo.

Se verificó con trabajadores reales que 24 solicitudes externas equivalentes, recibidas antes del arranque de cabinas, generan un solo comando y comparten el estado SERVED. También se comprobó que la suscripción del manejador no consume los canales originales de eventos/resultados, que un rechazo confirmado reasigna con un nuevo ID y que una cabina terminada antes de servir permite completar la llamada con otra cabina.

Los contratos del registro se verificaron con eventos construidos de forma determinista: no se acepta una llegada ni una finalización sin admisión/correlación/parada compatible como servicio confirmado. Los alias históricos y las canónicas receptoras distintas conservan sus responsabilidades.

No se ejecutó Maven aquí ni se creó un commit en el equipo de David. Las 229 pruebas acumuladas esperadas deben confirmarse en su repositorio. Esta ficha registra resultados observados; no es una transcripción de Surefire. Los archivos verification y los binarios locales son temporales y no forman parte del ZIP.
