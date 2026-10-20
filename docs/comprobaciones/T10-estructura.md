# T10 — Crear la estructura del proyecto

- **Proyecto:** Elevator Manager.
- **Fase:** 2 — Crear carpetas y diseñar la arquitectura.
- **Fecha:** 2026-10-20, Guatemala.
- **Estado:** entrega preparada; pendiente de aplicación y validación en el equipo de desarrollo.
- **Base:** sección 4 y tarjeta T10 del plan Kanban; documentos 02 y 03.

## Objetivo y aceptación

Crear paquetes y carpetas de fuentes, pruebas, recursos, ejemplos, documentación y evidencias. Las rutas deben coincidir con las declaraciones `package`; Maven y los logs no deben incorporar archivos generados a Git.

## Contexto conservado

Windows 11, VS Code, JDK 27, Maven 3.8.6 y compilación objetivo Java 17. T09 está cerrada tras comprobar Swing, hilo `t09-worker` fuera del EDT, respuesta de botón/pulsos y logging. Se conserva la entrada `com.elevatormanager.app.ElevatorManager`.

Este ZIP añade documentación de paquetes y marcadores de directorios. No contiene sustituciones de `ElevatorManager.java`, `EnvironmentCheck.java`, `pom.xml`, `.gitignore`, README ni documentos 01..06. El archivo `.gitignore` del equipo se completa mediante los comandos siguientes. Antes de copiar, comprobar que los archivos añadidos no tengan versiones locales que se deban conservar.

## Contenido

Los diez archivos `package-info.java` documentan los paquetes `app`, `diagnostics`, `model`, `model.request`, `simulation`, `dispatch`, `protocol`, `io`, `logging` y `ui`. No contienen clases funcionales ni crean hilos. El paquete `diagnostics` conserva el diagnóstico de T09.

Se incluyen marcadores `.gitkeep` en:

- `src/main/resources/`.
- `src/test/java/com/elevatormanager/model/request/`.
- `src/test/java/com/elevatormanager/simulation/`.
- `src/test/java/com/elevatormanager/dispatch/`.
- `src/test/java/com/elevatormanager/protocol/`.
- `src/test/java/com/elevatormanager/io/`.
- `src/test/resources/requests/`.
- `examples/requests/`.
- `evidence/`.

Git conserva archivos, no carpetas vacías; `.gitkeep` es una convención sin efecto sobre Java. Se pueden retirar estos marcadores cuando exista contenido real. Las carpetas `docs/` y `docs/comprobaciones/` se conservan y alojan este documento. `target/` lo crea Maven y `logs/` se crea al ejecutar el diagnóstico o el simulador; no necesitan marcadores.

No se crean aún clases vacías para Request, HallRequest, CabinRequest, controladores, trabajadores ni GUI. Sus diseños/implementaciones corresponden a las tarjetas posteriores. La herencia y el paralelismo siguen siendo requisitos del proyecto.

## Aplicación en Windows

1. Descargar y descomprimir `ElevatorManager-T10-estructura.zip` en una carpeta temporal.
2. Copiar las carpetas `src`, `docs`, `examples` y `evidence` sobre la raíz del proyecto existente. No crear un segundo nivel `elevator-manager`.
3. Abrir PowerShell en la raíz:

```powershell
Set-Location 'D:\Users\David\Desktop\workspace\java\elevator-manager'
```

4. Agregar exclusiones sin reemplazar las reglas existentes:

```powershell
$ignorePathT10 = Join-Path (Get-Location) '.gitignore'
if (-not (Test-Path -LiteralPath $ignorePathT10)) {
    New-Item -ItemType File -Path $ignorePathT10 | Out-Null
}
$ignoreLinesT10 = @(Get-Content -LiteralPath $ignorePathT10)
foreach ($ruleT10 in @('/target/', '/logs/')) {
    if ($ignoreLinesT10 -notcontains $ruleT10) {
        Add-Content -LiteralPath $ignorePathT10 -Value "`r`n$ruleT10" -Encoding UTF8
        $ignoreLinesT10 += $ruleT10
    }
}
```

## Validación

Ejecutar desde la raíz del proyecto, con la ventana de T09 cerrada:

```powershell
mvn clean verify
```

Resultado esperado: `BUILD SUCCESS`. Esta tarjeta no introduce pruebas funcionales; un mensaje de ausencia de pruebas no certifica el comportamiento del simulador.

Comprobar todos los archivos Java, incluidos los existentes, contra sus rutas:

```powershell
$javaRootT10 = (Resolve-Path '.\src\main\java').Path
$errorsT10 = @()
Get-ChildItem -LiteralPath $javaRootT10 -Recurse -Filter '*.java' | ForEach-Object {
    $relativeT10 = $_.FullName.Substring($javaRootT10.Length + 1)
    $directoryT10 = Split-Path -Path $relativeT10 -Parent
    $expectedT10 = $directoryT10 -replace '[\\/]', '.'
    $sourceT10 = Get-Content -LiteralPath $_.FullName -Raw -Encoding UTF8
    $matchT10 = [regex]::Match($sourceT10, '(?m)^\s*package\s+([A-Za-z_][\w]*(?:\.[A-Za-z_][\w]*)*)\s*;')
    if (-not $matchT10.Success -or $matchT10.Groups[1].Value -ne $expectedT10) {
        $errorsT10 += $relativeT10
    }
}
if ($errorsT10.Count -gt 0) {
    $errorsT10 | ForEach-Object { Write-Host "PACKAGE INCORRECTO: $_" }
    throw 'Hay declaraciones package incompatibles con sus rutas.'
}
Write-Host 'PACKAGES OK'
```

Comprobar exclusiones, archivos generados previamente versionados y cambios:

```powershell
git check-ignore --no-index target/prueba-t10.tmp logs/prueba-t10.log
git ls-files -- target logs
git diff --check
git status --short
```

Resultados esperados:

- `git check-ignore` muestra ambas rutas aunque los archivos de prueba no existan.
- `git ls-files -- target logs` no muestra archivos. Una regla ignore no deja de versionar archivos ya registrados: si aparecen, revisar sus rutas antes de cerrar T10.
- `git diff --check` no muestra errores.
- `git status` muestra los archivos nuevos y, si se añadieron reglas, `.gitignore`; no muestra contenido generado de `target/` o `logs/`.

Verificar que los archivos conservados no tienen cambios causados por esta entrega:

```powershell
git diff -- pom.xml src/main/java/com/elevatormanager/app/ElevatorManager.java src/main/java/com/elevatormanager/diagnostics/EnvironmentCheck.java
```

Cualquier cambio previo del usuario permanece; este ZIP no incluye esos archivos. No se exige volver a probar visualmente T09 para un cambio exclusivamente estructural si la compilación pasa y sus fuentes no han cambiado.

## Cierre y Git

Tras completar las comprobaciones, cambiar el estado de este documento a `Completada y validada en el equipo de desarrollo` y registrar el resultado. Después:

```powershell
git add -- src/main/java src/main/resources src/test examples/requests evidence docs/comprobaciones/T10-estructura.md .gitignore
git diff --cached --stat
git diff --cached --check
```

Revisar que el contenido preparado corresponda a T10 y conservar cualquier cambio previo fuera del commit. Si es correcto:

```powershell
git commit -m "T10: crear estructura de paquetes y carpetas"
```

Mover T10 a Terminada solo al obtener los resultados esperados. La siguiente tarjeta es T11 — Crear la entrada ElevatorManager; se trabajará sobre la entrada ya existente.

## Verificación de esta entrega

Se comprobaron el contenido del ZIP, las declaraciones package frente a sus rutas y las exclusiones /target/ y /logs/ en un repositorio temporal. No se ejecutó Maven ni javac en este entorno, que no dispone de esas herramientas; la construcción e integración se verifican con los comandos anteriores en el proyecto real.
