# MARJHO Print Service — Etapa 2: editor y formatos

## Implementado
- Se incorporó Jetpack Compose a la app independiente.
- Se recuperó y migró `EditorEtiquetaState.kt` y `EditorEtiquetaCanvas.kt` desde MARJHO.
- El editor permite arrastrar visualmente nombre, precio y código.
- Controles de tamaño para nombre y precio.
- Selector de hoja con varias etiquetas o etiqueta individual.
- Medidas personalizadas de etiqueta en milímetros.
- Distribución por columnas y filas, margen y separaciones.
- Campos de ejemplo para nombre, precio y código.
- Activación/desactivación de nombre, precio y código.
- Vista previa esquemática de la hoja.
- Generación de PDF y apertura del diálogo de impresión de Android.

## Límite técnico importante de esta etapa
La Epson L4150 es una impresora alimentada por hojas. El modo «etiqueta individual» crea un PDF A4 portador y centra la etiqueta con sus dimensiones configuradas; no implica que la L4150 pueda alimentar rollos o papel continuo de etiquetas.

El motor ESC/P-R actual configura internamente raster A4 a 360 dpi. Por seguridad, esta etapa no anuncia tamaños A5, Carta, Legal ni rollos como tamaños físicos ya validados por el controlador. Aunque el editor permite definir etiquetas pequeñas y distribuirlas sobre A4, el soporte de otros tamaños físicos requiere adaptar la secuencia ESC/P-R y validar cada tamaño con papel real. No debe asumirse que basta con cambiar el tamaño del PDF.

## Validación
No se pudo ejecutar `assembleDebug` en este entorno porque Gradle Wrapper no pudo resolver `services.gradle.org` (UnknownHostException). Abrir el proyecto en Android Studio, sincronizar Gradle y compilar. No se entrega APK porque no se ha compilado ni probado en hardware.

## Primera prueba sugerida
1. Configurar IP de la Epson L4150.
2. Crear una hoja A4 con etiquetas 70 × 35 mm, 2 columnas y 8 filas.
3. Confirmar en vista previa que las etiquetas caben.
4. Generar PDF y abrir el diálogo de impresión.
5. Seleccionar MARJHO Print Service y realizar una prueba de una sola hoja.
6. Verificar escala y márgenes físicos antes de imprimir lotes.
