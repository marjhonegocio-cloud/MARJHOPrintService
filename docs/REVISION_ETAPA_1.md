# MARJHO Print Service — revisión de etapa 1

## Objetivo
Servicio de impresión Android independiente para Epson L4150, reutilizando el controlador ESC/P-R local existente.

## Cambios aplicados en esta revisión
- Se incorporó `RleEscpr.kt`, requerido por `EscprRasterCodec` y ausente en el primer paquete independiente.
- Se conectó la cancelación del trabajo Android con el callback `comprobarCancelacion` del controlador ESC/P-R.
- `ImpresorEscprDirecto.kt` y `EscprRasterCodec.kt` conservan su lógica original; solo cambia la declaración `package` para el nuevo módulo.

## Estado
- Estructura de proyecto: preparada.
- Registro de `PrintService`: incluido.
- Configuración manual de IP: incluida.
- Recepción de PDF desde Android Print Framework: implementada.
- Conversión PDF a raster ESC/P-R: reutiliza el controlador MARJHO.
- Envío local TCP 9100: implementado.
- Compilación: pendiente. El intento `./gradlew :app:assembleDebug --no-daemon` falló porque el entorno no pudo resolver `services.gradle.org` (`UnknownHostException`).
- Prueba real en Epson L4150: pendiente.

## Validaciones físicas requeridas
1. Instalar APK debug compilado en un teléfono Android.
2. Habilitar MARJHO Print Service en Ajustes > Impresión.
3. Configurar la IP de la Epson L4150 en la misma red local.
4. Verificar conectividad TCP 9100.
5. Imprimir primero un PDF de una sola página A4 con texto y líneas finas.
6. Confirmar orientación, márgenes, escala, color y salida física.
7. Probar varias páginas solo después de validar la hoja individual.

La respuesta TCP 9100 solo confirma que el puerto acepta conexión; no prueba por sí sola que la impresora haya procesado correctamente el flujo ESC/P-R.
