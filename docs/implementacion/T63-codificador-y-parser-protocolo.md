# T63 — Implementar codificador y parser del protocolo

- **Fecha:** 2 de noviembre de 2026, Guatemala.
- **Fase:** 11 — Protocolo textual, extra 3.
- **Base aceptada:** T62, commit `5b310e5`, revisión/aprobación de David y árbol limpio.
- **Estado:** código implementado y verificado con Java 17 y escenarios independientes; pendiente de Maven local y aceptación de David. T64 no iniciada.

## Resultado y diseño

`ProtocolCodec` convierte DTOs tipados V1 a una cadena canónica y devuelve un DTO validado o un diagnóstico controlado al recibir texto. Implementa las nueve órdenes y todas las respuestas/eventos/STATE de [05](../05-protocolo.md). No depende de Swing, no crea hilos y no ejecuta órdenes al decodificar. Solo conserva una configuración inmutable, por lo que puede compartirse entre hilos.

`ProtocolMessage` usa una interfaz sellada y records por familia: Pickup, Destination, Control, Ack, Error, Terminal, Move, Arrived, DirectionChanged, StoppedAt y State. UUID, dirección, tipo de control, resultado, error y cancelación se conservan como tipos, no como cadenas libres. Los constructores rechazan formas incoherentes; el codec comprueba además edificio y duraciones. Las listas de STATE se copian y exigen unicidad/orden; no se ordenan silenciosamente entradas inválidas.

`ProtocolDecodeResult` distingue éxito de fallo. Un fallo contiene código, correlación recuperable, detalle limitado y hasta 256 caracteres escapados de entrada; no retiene la línea completa. Puede convertirse explícitamente a DTO ERROR para el receptor de comandos. La decodificación de respuestas nunca envía ERROR automáticamente. La emisión informa EncodingException con diagnóstico estructurado si el mensaje no cabe o contradice la configuración.

Se valida tamaño UTF-8 antes de tokenizar, framing ASCII, versión, cabecera, verbo, aridad, tipos y dominio. El límite exacto de 1 MiB incluye terminador; excesos no se truncan. El framing conserva como máximo 29 tokens y el escritor está acotado. Se admiten espacios múltiples y un LF/CRLF final, y se emite siempre un solo espacio sin terminador.

`ProtocolCommands` enlaza con los Command existentes. PICKUP/DESTINATION/RESET hacen ida y vuelta preservando identidad y contenido; para las solicitudes el llamador proporciona explícitamente createdAt, que no existe en V1. Los otros controles están representados por DTO y se adaptarán al motor posteriormente. No se modifican las clases de herencia Request/HallRequest/CabinRequest, los trabajadores, el controlador, el reloj ni la animación.

## API completa y límites de esta tarjeta

| Método | Resultado |
| --- | --- |
| ProtocolCodec.encode(message) | String canónico; EncodingException controlada si no puede emitirse. |
| ProtocolCodec.decode(text) | Cualquier familia V1 válida o Failure. |
| ProtocolCodec.decodeCommand(text) | Solo CMD; valida datos, sin admitirlos en una sesión. |
| ProtocolCodec.decodeResponse(text) | Solo respuestas/eventos; un fallo es diagnóstico local. |
| ProtocolCommands.fromDomain(command) | DTO de uno de los tres Command existentes, sin asignar IDs. |
| ProtocolCommands.toDomain(message, createdAt) | Command de servicio/reset con fecha explícita del llamador. Otros tipos se rechazan expresamente. |

No se comprueban aún rol de productor, registro histórico de IDs, época vigente, estado de simulación o barreras: son responsabilidad de admisión en T64–T65. Por ejemplo, RESET de época 0 se puede decodificar, aunque el receptor lo rechace por no ser la época siguiente. El codec tampoco afirma haber enviado una orden a una cabina. La sustitución efectiva de canales es T64 y la integración de respuestas/reset T65.

## Archivos de código

| Archivo | Responsabilidad |
| --- | --- |
| src/main/java/com/elevatormanager/protocol/ProtocolMessage.java | DTOs/enumeraciones inmutables V1. |
| src/main/java/com/elevatormanager/protocol/ProtocolDecodeResult.java | Resultado y diagnóstico limitado. |
| src/main/java/com/elevatormanager/protocol/ProtocolCodec.java | Codificador, parser y validación de configuración. |
| src/main/java/com/elevatormanager/protocol/ProtocolCommands.java | Adaptación explícita al dominio actual. |
| src/main/java/com/elevatormanager/protocol/package-info.java | Alcance del paquete actualizado. |
| src/main/java/com/elevatormanager/diagnostics/ProtocolCodecDemo.java | Demostración reproducible del codec, sin motor. |
| src/test/java/com/elevatormanager/protocol/ProtocolCodecScenarios.java | 76 escenarios independientes. |
| src/test/java/com/elevatormanager/protocol/ProtocolCodecTest.java | Los mismos 76 escenarios con JUnit. |

Los ocho archivos íntegros están en [T63 — Código completo](T63-codigo-completo.md). El ZIP incluye todos los .java de la aplicación y todas sus pruebas, pom.xml, recursos y documentación; no contiene fragmentos ni parches sueltos. Excluye .git, target y logs generados. No se cambian las dependencias ni release 17.

## Validación ejecutada

Se compilaron 125 fuentes independientes reales con Java 17. Pasaron 76 escenarios específicos y 169 de regresión seleccionada en sus ejecuciones finales (245 total). Un escenario GUI T60 agotó inicialmente la espera de MOVING y la clase pasó al repetirla; se conserva evidencia y contraste con base T62 en [resultados](../comprobaciones/T63-resultados-locales.md). No se atribuye esa intermitencia a una causa demostrada ni se alteran sus plazos/código.

La demo convierte Command.Pickup a texto y reconstruye su contenido; rechaza una llamada UP en piso superior y vuelve a procesar una válida. Se ejecutó desde clases y JAR auxiliar. No se ejecutó Maven: están pendientes 76 específicas y el total esperado de 1,002 (926 previas + 76 nuevas). Ver [validación local](../comprobaciones/T63-validacion-local.md).

## Incorporación en Windows

Primero comprobar que no hay cambios personales que se sobrescribirían. El ZIP tiene raíz Maven y se extrae sobre el directorio actual; conserva .git del repositorio local.

```powershell
Set-Location 'D:\Users\David\Desktop\workspace\java\elevator-manager'
git status --short
Expand-Archive -LiteralPath "$HOME\Downloads\T63-codificador-y-parser-protocolo.zip" -DestinationPath . -Force
git diff --check
git diff --stat
git status --short
mvn "-Dtest=ProtocolCodecTest" test
mvn clean verify
java -cp .\target\classes com.elevatormanager.diagnostics.ProtocolCodecDemo
```

Resultado esperado: 76 específicas, cero fallos/errores; Maven completo con 1,002 pruebas y BUILD SUCCESS; demo termina en T63_CODEC_OK. El desglose de la demo está en los resultados locales. Si una prueba falla, conservar nombre y traza antes de declarar cerrada T63.

## Commit sugerido tras validar

Actualizar el estado de T63 en el plan con los resultados recibidos y marcar su tarjeta cuando pase la validación local.

```powershell
git diff --check
git add -- src/main/java/com/elevatormanager/protocol src/main/java/com/elevatormanager/diagnostics/ProtocolCodecDemo.java src/test/java/com/elevatormanager/protocol docs
git diff --cached --check
git diff --cached --stat
git commit -m "T63: implementar codificador y parser del protocolo"
git status --short
```

No se realiza el commit en nombre de David. Tras la aceptación de T63, corresponde T64 — Sustituir la frontera manejador–cabina por mensajes de texto.
