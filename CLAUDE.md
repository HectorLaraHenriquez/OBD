# Tablero OBD — MG GT (contexto del proyecto)

Documento de traspaso para continuar el desarrollo con Claude (Claude Code / Cowork) conectado a este repo.

## Objetivo

App web propia para leer datos OBD2 del auto con un adaptador Bluetooth barato, verlos en un dashboard en el teléfono e interpretarlos con IA. A futuro, posible servicio de "escáner express" con informe para terceros (revisión precompra de autos usados).

## Contexto

- **Auto:** MG GT 1.5 aspirado, manual 5 velocidades, primera generación (2016+). Radio solo con Apple CarPlay. Uso para trabajar en Uber (Chile).
- **Adaptador:** Vgate **iCar2**, versión **Bluetooth 4.0 (BLE)**. Compatible ELM327.
  - No aparece en ajustes de Bluetooth de Android; se conecta desde la app.
  - Se duerme solo; la luz solo se enciende al recibir comandos.
  - Probado y funcionando con Car Scanner (modo Bluetooth 4.0 LE) antes de este dashboard.
  - Acepta una sola conexión a la vez: cerrar Car Scanner antes de usar el dashboard.
- **Teléfono:** Android, Chrome.
- **Hosting:** GitHub Pages, repo `HectorLaraHenriquez/obd`, URL `https://hectorlarahenriquez.github.io/obd/`.

## Por qué web y no app nativa

Chrome en Android soporta **Web Bluetooth** solo para BLE, lo que calza con el iCar2. Requiere HTTPS (GitHub Pages lo da) y ubicación activada. No funciona dentro del visor de artifacts de claude.ai (Bluetooth bloqueado), ni en iPhone (salvo navegador Bluefy). Si en el futuro se usa un adaptador Bluetooth clásico (SPP) o Wi-Fi, habría que pasar a app nativa (Kotlin/Flutter).

## Estructura

- `index.html` — app completa en un solo archivo (HTML + CSS + JS, sin dependencias salvo Google Fonts).
- `CLAUDE.md` — este documento.
- `CAPACIDADES.md` — qué datos se pueden extraer del auto y cuáles no.
- `OLD/` — versiones anteriores de `index.html`.

## Arquitectura de `index.html`

### Transporte
- `class Ble`: `navigator.bluetooth.requestDevice({acceptAllDevices:true, optionalServices:SVC})`, recorre servicios y elige el primero que tenga una característica notify/indicate (RX) y otra write/writeWithoutResponse (TX). Escribe en trozos de 20 bytes.
- UUIDs de servicio probados en `SVC`: `fff0`, `ffe0`, `18f0`, `e7810a71-73ae-499d-8c15-faa9aef0c3f2` (Vgate), `ae30`, `beef`. **Pendiente confirmar cuál usa realmente el iCar2** (ver Consola / logs).
- `class Sim`: simulador que responde como un ELM327 en un MG GT (protocolo CAN, PIDs típicos, un código P0133 de ejemplo, VIN falso `DEMO0MGGT15MT0001`). La página arranca en modo demo.

### Capa ELM327 (`elm`)
- Cola de comandos con lock (un comando a la vez); respuesta completa al llegar `>`. Timeout por comando.
- Inicialización: `ATZ, ATE0, ATL0, ATS0, ATH0, ATSP0`, luego `0100` (búsqueda de protocolo, timeout 12 s), `ATDPN`, `ATDP`.
- `ATS0` y `ATH0` → respuestas sin espacios ni cabeceras (ej. `410C1AF8`).

### Parsing
- `parse01(resp, pid)`: busca líneas que empiezan con `41`+PID; quita prefijos de trama multi-frame (`0:`, `1:`…).
- `discover()`: consulta `0100, 0120, 0140…` (bitmasks) y llena `S.sup` con PIDs soportados.
- `parseDTC(resp, mode)`: modos `03` (guardados), `07` (pendientes), `0A` (permanentes). En CAN el primer byte es el conteo de códigos.
- `parseVIN`: modo `0902`, best effort.
- Monitores de emisiones: PID `0101` (MIL, cantidad de DTC, monitores listos/incompletos).

### Tabla `PIDS`
Nombre en español, categoría, unidad y fórmula de decodificación para ~40 PIDs del modo 01. Los de texto usan `t`, los numéricos `d` (1 byte) o `dv` (2 bytes).

### Valores calculados (`VIRT`)
- `BAT`: voltaje con `ATRV` (lo mide el adaptador).
- `LPH` consumo L/h: usa PID `5E` si existe; si no, MAF (`10`); si no, **speed-density** con MAP (`0B`), RPM (`0C`) e IAT (`0F`): eficiencia volumétrica 0,85, cilindrada 1,498 L, AFR 14,7, densidad gasolina 745 g/L. El MG GT 1.5 probablemente no tiene MAF.
- `KML` rendimiento: velocidad / L/h (solo sobre 3 km/h).

### UI (pestañas)
1. **En vivo:** computador de viaje (distancia, tiempo, ralentí, combustible, costo con precio editable, aceleración calculada); VIN, protocolo, check engine, n.º de fallas; tarjetas con valor, mín/máx de la sesión y sparkline (canvas, últimas 120 lecturas). Estados de alerta: refrigerante >99 °C aviso, >105 °C crítico; fuel trims >10 % aviso, >20 % crítico; voltaje según motor encendido/apagado.
2. **Datos disponibles:** PIDs soportados agrupados, botón "Leer todos una vez", casillas para elegir medidores (se guardan en `localStorage`, clave `obd_watch`), y lista de PIDs que el auto no reporta.
3. **Fallas:** códigos con descripción (diccionario `DTC_DB` de ~50 códigos genéricos) y severidad; borrado (`04`) con confirmación en dos pasos; monitores de emisiones.
4. **Registro:** grabar viaje y exportar CSV; "Resumen para IA" (texto para pegar en Claude con VIN, fallas, monitores y min/prom/máx de cada parámetro).
5. **Escáner:** escaneo completo de solo lectura: adaptador, modo 09 (VIN/calibración/CVN), modo 02 (datos congelados), modo 06 (autopruebas), sondeo de módulos por UDS (`3E00`, `22 F1xx`, `19 02`) rápido (7E0–7E7, 7A0–7BF) o ampliado (700–7DE). Informe descargable `.txt` (usa el resumen para IA).
6. **Consola:** comandos ELM327 directos; pausa la lectura en vivo.

Diseño: tokens CSS, dark-first con tema claro, fuentes Barlow Condensed / IBM Plex Sans / IBM Plex Mono, responsive para móvil.

## Limitaciones conocidas

- Solo OBD2 genérico (motor/emisiones). **ABS, airbag y carrocería no** son accesibles con este enfoque; requieren direcciones de módulo y datos propietarios por marca (UDS). El GT manual no tiene módulo de transmisión.
- El iCar2 por BLE es lento (~5–10 lecturas/s): más PIDs en el tablero = menor frecuencia por PID.
- VIN y DTC multi-frame se parsean en modo best effort; validar con el auto real.
- Consumo calculado es estimación; calibrar VE con cargas reales de combustible.
- Descarga CSV no funciona dentro del visor de artifacts; sí en GitHub Pages.

## Pendiente / próximos pasos

1. **Validar con el auto real:** qué servicio BLE se conecta, qué PIDs reporta el MG (captura de "Datos disponibles"), respuesta de `0902` y `03`.
2. Ajustar tablero por defecto a los PIDs reales del MG.
3. Calibrar consumo (VE) comparando con estanque lleno a lleno.
4. Integración IA directa: botón que envíe el resumen a la API de Claude (requiere backend o clave del usuario; no exponer claves en un repo público).
5. Historial persistente de viajes y mantención por kilometraje.
6. Ideas para Uber: rentabilidad por viaje (consumo × precio bencina vs. ingresos), detección de anomalías (temperatura, voltaje, fuel trims) antes de que encienda una luz, tiempo en ralentí.
7. Servicio escáner express: plantilla de informe PDF a partir del resumen; para clientes se necesitaría un escáner de todos los sistemas (ej. ThinkDiag) además de este.

## Reglas de trabajo

- Mantener `index.html` como archivo único autocontenido, sin build.
- Antes de reemplazar un entregable, mover la versión anterior a `OLD/`.
- No guardar claves de API ni datos personales en el repo (es público).
- Seguridad: la UI no debe incentivar manipular el teléfono conduciendo.
