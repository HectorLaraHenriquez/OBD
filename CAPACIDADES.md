# Qué se puede extraer del MG GT con el iCar2

Mapa teórico (estándar OBD2/UDS). **No verificado en el auto real**: lo que el MG realmente responde se confirma con la pestaña *Escáner* → "Escaneo completo" y el informe descargado.

## Disponible (la app lo lee)

| Fuente | Datos | Dónde en la app |
|---|---|---|
| Modo 01 (PIDs) | Hasta ~100 parámetros en vivo del motor/emisiones; el auto declara cuáles soporta. ~60 decodificados; los demás se muestran en crudo | En vivo, Datos disponibles |
| Modo 02 | Datos congelados: condiciones del motor cuando se registró la falla | Escáner |
| Modos 03/07/0A | Fallas guardadas, pendientes, permanentes | Fallas |
| Modo 04 | Borrar fallas (con confirmación) | Fallas |
| Modo 06 | Autopruebas de la ECU (catalizador, O2, encendido, EVAP) con valor y límites | Escáner |
| Modo 09 | VIN, ID de calibración, CVN, nombre de ECU | Escáner |
| UDS (22 / 19) | Otros módulos: identificación (pieza, software, hardware, serie) y fallas, **solo lectura** | Escáner → Módulos |
| Adaptador (AT) | Voltaje de batería, versión ELM, protocolo, pin de ignición, servicio BLE usado | Escáner → Adaptador |
| Calculados | Consumo L/h, km/L, distancia, tiempo, ralentí, combustible usado, costo, aceleración | En vivo |

## No disponible con este enfoque

- Datos en vivo de ABS, airbag, carrocería, clima, tablero: son propietarios de MG/SAIC (no documentados).
- Odómetro, presión de neumáticos (TPMS), estado de salud de la batería: no son estándar.
- Codificación, adaptaciones, escritura en módulos, borrado de fallas por módulo (no implementado a propósito).
- Velocidad de refresco alta: iCar2 por BLE ≈ 5–10 lecturas/s en total.

## Para completar

1. Con el auto en contacto, abrir la app, *Escáner* → *Escaneo completo* y descargar el informe.
2. Pegar el informe en Claude: se ajusta el tablero por defecto, se descartan PIDs inútiles y se confirman módulos/direcciones.
3. Probar *Ampliado* en módulos (auto detenido) para ubicar ABS/airbag y ver si entregan fallas por UDS.
