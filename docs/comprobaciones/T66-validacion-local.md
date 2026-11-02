# T66 — Validación local

- **Fecha:** 2026-11-02, Guatemala.
- **Base aceptada:** T65, commit `0648e6d`, 1,114 pruebas.
- **Estado:** entrega implementada; Maven Windows/demo y aceptación final pendientes.

## Incorporar y ejecutar

Extraer el ZIP sobre la raíz del proyecto, conservando .git. pom.xml, README.md, src y docs deben quedar al mismo nivel. No copiar target ni logs generados de otro equipo.

```powershell
Set-Location 'D:\Users\David\Desktop\workspace\java\elevator-manager'
mvn "-Dtest=ProtocolAcceptanceTest" test
mvn clean verify
java -cp .\target\classes com.elevatormanager.diagnostics.ProtocolAcceptanceDemo
java -cp .\target\elevator-manager-0.1.0-SNAPSHOT.jar com.elevatormanager.diagnostics.ProtocolAcceptanceDemo
```

| Paso | Esperado | Estado recibido |
| --- | --- | --- |
| ProtocolAcceptanceTest | 22, cero fallos/errores/omitidas | Pendiente |
| mvn clean verify | 1,136, cero fallos/errores/omitidas; BUILD SUCCESS | Pendiente |
| Compilación Maven | 84 fuentes main, 104 fuentes test, release 17 | Pendiente |
| Demo completa, clases y JAR | 22 T66_CASE_OK y marcador final correcto | Pendiente |
| Recorrido GUI | Pausa/animación/controles/logs conservados | Pendiente |
| Git | diff/staged sin errores; commit y estado limpio | Pendiente |

La demo completa termina exactamente en:

```text
T66_PROTOCOL_OK; scenarios=22; failures=0; threadsJoined=true
```

## Reproducir casos y guardar evidencia

```powershell
java -cp .\target\classes com.elevatormanager.diagnostics.ProtocolAcceptanceDemo RESET_IN_FLIGHT
java -cp .\target\classes com.elevatormanager.diagnostics.ProtocolAcceptanceDemo QUEUED_BEFORE_RESET
java -cp .\target\classes com.elevatormanager.diagnostics.ProtocolAcceptanceDemo COMPLETED_REPLAY
java -cp .\target\classes com.elevatormanager.diagnostics.ProtocolAcceptanceDemo CONTROLLER_EXTRAS
java -cp .\target\classes com.elevatormanager.diagnostics.ProtocolAcceptanceDemo | Tee-Object -FilePath .\logs\t66-protocolo-local.txt
```

Cada ejecución aislada informa scenarios=1; no debe contarse como un caso adicional al total 22. Los tiempos y el entrelazado de la traza varían. Una excepción o la ausencia del marcador final implica fallo. La demo no requiere entrada interactiva ni cambia la entrada principal del JAR gráfico.

## Revisión y commit sugerido

Después de validar también el [recorrido GUI](T66-recorrido-gui.md):

```powershell
git diff --check
git add -- README.md src docs
git diff --cached --check
git diff --cached --stat
git commit -m "T66: probar y demostrar protocolo completo"
git status --short
```

El commit se crea en el equipo de David; esta entrega no lo inventa ni lo crea automáticamente. El hash y los resultados recibidos permitirán cerrar T66/fase 11.
