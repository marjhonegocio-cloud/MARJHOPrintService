# Plan de pruebas físicas - Epson L4150

## Antes de imprimir
- [ ] Confirmar que el teléfono y la Epson están en la misma LAN o que existe ruta entre las redes.
- [ ] Obtener la IP actual desde la hoja de estado de red o el router.
- [ ] Confirmar TCP 9100 desde MARJHO Print Service.
- [ ] Confirmar que el flujo ESC/P-R es aceptado por este firmware.

## Pruebas de impresión
- [ ] Imprimir PDF de una página A4 en monocromo.
- [ ] Imprimir PDF de una página A4 en color.
- [ ] Imprimir PDF de dos páginas.
- [ ] Comprobar orientación vertical y horizontal.
- [ ] Comprobar márgenes y escala.
- [ ] Probar papel Carta.
- [ ] Cancelar antes de iniciar la transmisión.
- [ ] Desconectar Wi-Fi durante un trabajo y verificar el estado de error.
- [ ] Repetir en modo Wi-Fi Direct.
- [ ] Probar desde al menos dos aplicaciones Android distintas.

## Criterio de aceptación

No declarar el complemento compatible con L4150 hasta que las pruebas de hardware produzcan páginas correctas de forma repetible. Una conexión TCP exitosa o un PrintJob completado solo indica que los datos se enviaron; no confirma que el papel haya salido correctamente.
