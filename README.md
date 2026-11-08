# MARJHO Print Service 1.0.0

Complemento Android independiente basado en `android.printservice.PrintService`.
Su objetivo es aparecer en el selector de impresión del sistema Android y recibir documentos PDF de otras aplicaciones.

## Alcance de esta entrega

- APK independiente con identificador `com.marjho.printservice`.
- Servicio Android de impresión registrado mediante `android.printservice.PrintService`.
- Pantalla de configuración para guardar manualmente la IP local de la Epson.
- Prueba de conectividad TCP al puerto 9100.
- Publicación de una impresora virtual del servicio en el selector Android.
- Recepción de trabajos PDF desde el sistema.
- Reutilización del controlador raster ESC/P-R existente de MARJHO (`ImpresorEscprDirecto` y `EscprRasterCodec`).
- Envío local al puerto TCP 9100; no usa Epson Connect, nube ni Firebase.
- Límite conservado del controlador MARJHO: hasta 20 páginas A4 por trabajo.

## Importante: Epson L4150

La documentación oficial de Epson para L4150 especifica USB, Wi-Fi 802.11 b/g/n y Wi-Fi Direct. No especifica conector Ethernet RJ-45. Puede estar en la misma LAN que equipos conectados al router por cable Ethernet, pero la propia impresora se conecta por Wi-Fi. Para conexión Ethernet física directa hace falta otro modelo o un servidor/puente de impresión compatible.

La documentación de funciones de red consultada no marca IPP convencional como compatible para esta serie. Por eso esta versión NO envía PDF por IPP. Reutiliza el flujo ESC/P-R sobre TCP 9100 del proyecto MARJHO. El puerto debe comprobarse en la red real; que acepte TCP no garantiza por sí solo que la impresora acepte el flujo ESC/P-R.

Fuentes oficiales consultadas:
- Epson Argentina L4150: https://epson.com.ar/Para-el-hogar/Impresoras/Inyecci%C3%B3n-de-tinta/Impresora-Multifuncional-Epson-EcoTank-L4150/p/C11CG25303
- Manual de usuario L4150: https://files.support.epson.com/docid/cpd5/cpd54034.pdf
- Lista de funciones de red L4150: https://download4.epson.biz/sec_pubs/l4150_series/useg/en/manual.pdf

## Compilar

Requisitos:
- Android Studio reciente.
- JDK 17.
- Android SDK Platform 36.
- Conexión inicial para descargar Gradle y dependencias.

Abrir esta carpeta como proyecto Gradle y ejecutar:

```bash
./gradlew :app:assembleDebug
```

APK esperado:

```text
app/build/outputs/apk/debug/app-debug.apk
```

En esta revisión se corrigió una dependencia de código faltante (`RleEscpr.kt`) y se conectó la cancelación del trabajo con el controlador. Se intentó ejecutar `:app:assembleDebug`, pero este entorno no pudo descargar Gradle por falta de resolución DNS (`UnknownHostException: services.gradle.org`). El código se entrega como proyecto fuente, no como APK compilado ni validado en hardware.

## Instalar y configurar

1. Compilar e instalar `app-debug.apk` en Android.
2. Abrir MARJHO Print Service.
3. Obtener la IP de la impresora imprimiendo su hoja de estado de red o consultando la lista DHCP del router.
4. Ingresar esa IP y guardar.
5. Ejecutar la prueba TCP 9100.
6. Abrir "Ajustes de impresión de Android".
7. Habilitar MARJHO Print Service.
8. Desde otra aplicación, usar Compartir/Imprimir o Imprimir y seleccionar `EPSON L4150 Series (MARJHO)`.

El teléfono y la impresora deben poder comunicarse en la red local. En Wi-Fi Direct, conectar el teléfono al SSID Wi-Fi Direct de la impresora y usar la IP que informe la impresora para ese modo.

## Limitaciones de esta versión

- La IP se configura manualmente; el descubrimiento automático mDNS/subred todavía no está implementado.
- La ficha del servicio publica A4 y Carta, 360 dpi, color/monocromo y sin dúplex.
- No se garantiza compatibilidad con todos los tamaños, bandejas ni opciones de calidad de Epson.
- El envío directo TCP 9100/ESC-P-R debe probarse con el hardware y firmware concretos. La prueba de puerto solo valida conectividad TCP.
- El servicio recibe PDF. Aplicaciones que no utilicen el framework de impresión Android no aparecerán automáticamente.
- Ethernet físico no es posible en la L4150 sin hardware adicional porque el modelo no tiene puerto RJ-45.
- La cancelación de un trabajo durante la transmisión no garantiza que la impresora pueda retirar páginas ya recibidas.

## Arquitectura

```text
Aplicación Android
      |
      v
Android Print Framework
      |
      v
MarjhoPrintService (PrintService)
      |-- PrinterDiscoverySession
      |-- cola/cancelación de PrintJob
      |-- copia segura del PDF temporal
      |
      v
ImpresorEscprDirecto
      |
      v
EscprRasterCodec
      |
      v
TCP local :9100 --> Epson L4150
```

## Archivos principales

- `app/src/main/java/com/marjho/printservice/MarjhoPrintService.kt`: servicio de impresión Android.
- `app/src/main/java/com/marjho/printservice/MainActivity.kt`: configuración y prueba de conexión.
- `app/src/main/java/com/marjho/printservice/driver/ImpresorEscprDirecto.kt`: controlador existente extraído de MARJHO.
- `app/src/main/java/com/marjho/printservice/driver/EscprRasterCodec.kt`: codificador raster existente extraído de MARJHO.
- `app/src/main/java/com/marjho/printservice/driver/RleEscpr.kt`: compresión RLE requerida por el codificador raster.
- `app/src/main/AndroidManifest.xml`: registro del servicio del sistema.

## Licencia y atribución

Los archivos ESC/P-R reutilizados conservan sus comentarios originales. El codificador indica que toma como referencia el proyecto abierto `python-epson` (Jason S. McMullan, MIT, 2016), y aclara que no es un SDK oficial de Epson. Antes de distribuir comercialmente el APK, revisar el texto de licencia y las obligaciones de atribución del código de referencia original.

## Etapa 2 — Editor de etiquetas y vista previa

Se agregó un editor visual independiente con Compose. Permite mover nombre, precio y código sobre la etiqueta, cambiar tamaños, alternar campos, elegir hoja con múltiples etiquetas o etiqueta individual, configurar ancho/alto en mm, filas, columnas, márgenes y separaciones. Genera un PDF y abre el diálogo nativo de impresión Android.

**Limitación de esta etapa:** la L4150 continúa usando raster ESC/P-R A4 a 360 dpi. La etiqueta individual se centra en una hoja A4 portadora. Los tamaños físicos A5/Carta/Legal y rollos no se deben considerar compatibles hasta adaptar el protocolo y probarlos con papel real. Ver `docs/ETAPA_2_EDITOR_Y_FORMATOS.md`.


## Etapa 3 — Red local y documentos (primera integración)

Se reorganizó la pantalla en secciones y se añadieron:
- Descubrimiento DNS-SD/mDNS de servicios `_ipp._tcp`, `_ipps._tcp`, `_printer._tcp` y `_pdl-datastream._tcp`.
- Ventana emergente de resultados; al tocar un resultado se guarda su IP como destino activo.
- IP manual, prueba TCP 9100 y envío directo ESC/P-R para PDF A4.
- Importación mediante Storage Access Framework de PDF, DOCX, TXT e imágenes.
- Vista previa de primera página PDF.
- Edición de texto plano TXT.
- Extracción del texto visible de DOCX para editarlo como borrador y guardarlo como TXT.
- Edición básica de imagen: rotar 90 grados y guardar PNG.
- Conversión de imagen a PDF A4 para imprimir.
- Se conserva el editor de etiquetas y el PrintService del sistema.

### Límites explícitos de esta etapa
- La extracción DOCX no reescribe el DOCX original ni conserva tablas, estilos, encabezados o imágenes; se exporta el texto editado como TXT.
- El PDF se visualiza y se puede imprimir; no se modifica el texto original del PDF ni se anotan/reestructuran páginas en esta etapa.
- El editor de imagen de esta etapa solo rota; no incluye todavía recorte, capas, filtros o borrador.
- La búsqueda mDNS solo detecta servicios anunciados. Una impresora encontrada no implica que acepte ESC/P-R. El envío directo ESC/P-R sigue siendo específico del flujo validado para Epson L4150 y debe probarse en hardware.
- El envío directo conserva el límite del controlador de 20 páginas por trabajo.
- Para DOCX/TXT y PDF que no estén en un formato compatible con el controlador, utilizar una aplicación externa para convertirlos a PDF y luego abrir ese PDF en MARJHO.
- La selección de un servicio IPP no activa automáticamente IPP: la ficha encontrada solo identifica el anuncio DNS-SD. La negociación IPP y la consulta de capacidades se implementarán como módulo separado, evitando enviar comandos incorrectos.

### Compilación
Abrir la carpeta `MARJHO_Print_Service` en Android Studio con JDK 21 y Android SDK 36. Sincronizar Gradle y ejecutar `:app:assembleDebug`.
