# T63 — Validación local y aceptación pendiente

- **Fecha:** 2026-11-02, Guatemala.
- **Base confirmada por David:** T62 aprobada, commit `5b310e5`, árbol limpio.
- **Estado:** implementada; verificación Java independiente realizada, Maven Windows y aceptación pendientes.

## Evidencia del entorno de implementación

| Comprobación | Resultado |
| --- | --- |
| Compilación Java | OpenJDK 17.0.20, compilador real jdk.compiler, release 17/UTF-8; 125 fuentes independientes compilados. |
| Pruebas T63 | 76 escenarios correctos; mismos 76 adaptadores JUnit preparados para Maven. |
| Regresión seleccionada | 169 escenarios en sus ejecuciones finales; sumados a T63: 245. |
| Intermitencia GUI conservada | T60 agotó una espera inicial; clase completa pasó al repetir, al igual que la clase base T62. Ver resultados completos. |
| Demostración | Ejecutada desde clases y JAR auxiliar; conserva UUID/contenido y recupera tras INVALID_DIRECTION. |
| Límites reales | 1 MiB exacto con/sin LF/CRLF; exceso UTF-8; STATE serializado demasiado grande; sin truncar. |
| Robustez | 2,000 round-trips pseudoaleatorios, 5,000 entradas malformadas y mutaciones por familia, recuperación posterior y cuatro consumidores concurrentes. |
| Aislamiento | Trabajadores, modelo, controlador, GUI, reloj, POM y dependencias previos conservados; solo nuevos Java y Javadoc del paquete protocol. |
| Integridad de entrega | Código íntegro de ocho archivos en Markdown y todos los Java de producción/pruebas en ZIP. |
| Git/Markdown | diff --check, enlaces locales y correspondencia exacta de bloques Java con archivos comprobados. |

Las pruebas ejecutan el codec real, no un parser de prueba. El script temporal para compilar/ejecutar sin Maven y el JAR auxiliar permanecen fuera del proyecto entregado. No se usan clases JUnit falsas ni se presenta el main independiente como una ejecución Surefire.

## Validación en Windows

```powershell
Set-Location 'D:\Users\David\Desktop\workspace\java\elevator-manager'
mvn "-Dtest=ProtocolCodecTest" test
mvn clean verify
java -cp .\target\classes com.elevatormanager.diagnostics.ProtocolCodecDemo
git diff --check
git status --short
```

| Criterio | Esperado | Estado recibido |
| --- | --- | --- |
| ProtocolCodecTest | 76, sin fallos ni errores | Pendiente de David |
| Suite Maven completa | 1,002, sin fallos ni errores; BUILD SUCCESS | Pendiente de David |
| Demo | sameContent=true, INVALID_DIRECTION, T63_CODEC_OK | Pendiente de David |
| Revisión del código/documentación | Sin incidencias pendientes de T63 | Pendiente |
| Commit y árbol limpio | Registrar hash real | Pendiente |

## Límites de lo demostrado

La compilación independiente excluye los adaptadores que importan JUnit porque Maven/dependencias de prueba no están instalados en este entorno. El total de 1,002 es esperado, no observado. Las verificaciones headless no constituyen un nuevo recorrido nativo de Windows.

Decode devuelve datos válidos; no significa ACK ni ejecución. El codec no registra idempotencia, no autoriza productores y no decide época/estado vigente. Todavía no se transportan órdenes de cabina como texto: T64–T65 harán esa integración y T66 demostrará el protocolo completo. Los extras y la herencia/hilos anteriores siguen presentes en el código íntegro.


## Validación recibida de David — 2026-11-02

T63 queda cerrada: 76 específicas y 1,002 totales, sin fallos, errores ni omitidas; ambos comandos Maven con BUILD SUCCESS. Demo correcta, incluido sameContent=true e INVALID_DIRECTION; final T63_CODEC_OK. AnimationSynchronizationTest: 24 casos correctos en esta ejecución. Git comprobado, commit `6c8d7d3` y árbol limpio. Las tablas con «pendiente» anteriores describen el momento de entrega original y quedan actualizadas por esta confirmación.
