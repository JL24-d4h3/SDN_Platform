# ═══════════════════════════════════════════════════════════════
#  GUÍA DE PRUEBA: Laptop + Celular (sin código en el celular)
# ═══════════════════════════════════════════════════════════════
#
#  Fecha: Febrero 2026
#  Requisitos: Laptop Linux (Ubuntu), Celular Android, misma WiFi
#
# ═══════════════════════════════════════════════════════════════

## ¿Qué funciona hoy?

```
┌──────────────────────────────────────────────────────────────┐
│                    PRUEBA ACTUAL                             │
│                                                              │
│  ┌──────────┐    MQTT    ┌─────────────┐    MQTT    ┌──────┐│
│  │ Celular  │ ◄────────► │  Mosquitto  │ ◄────────► │Spring││
│  │ (app     │            │  (broker)   │            │ Boot ││
│  │  MQTT)   │            │  :1883 LAN  │            │:8081 ││
│  └──────────┘            └─────────────┘            └──────┘│
│       │                                               │     │
│       │              ¿Bidireccional?                   │     │
│       │                                               │     │
│       ├─► Celular PUBLICA telemetría ──► Controller ✓ │     │
│       ├─► Celular PUBLICA registro ───► Controller  ✓ │     │
│       ◄── Controller PUBLICA comando ◄── curl/REST  ✓ │     │
│       │                                               │     │
│  SÍ, funciona en ambas direcciones via MQTT           │     │
└──────────────────────────────────────────────────────────────┘
```

**Lo que NO funciona todavía** (necesita la app Android):
- El celular NO enciende/apaga BT/WiFi automáticamente
- El celular NO envía datos reales por BLE Long Range
- Eso lo hará la app Android que crearemos después

**Pero lo que SÍ puedes probar hoy:**
1. ✓ El celular envía telemetría → el controlador la registra en BD
2. ✓ Creas una sesión desde curl → el controlador envía PREPARE_BT por MQTT → el celular lo ve
3. ✓ El celular aparece en GET /devices como dispositivo registrado
4. ✓ El flujo completo de sesión (CREATED→OUTBOUND→PROCESSING→INBOUND→DELIVERED→CLOSED)
5. ✓ La H2 console muestra todo lo que pasó

---

## PASO 1: Instalar app MQTT en el celular

Descargar **UNA** de estas apps (todas gratis):

| App | Play Store | Recomendada |
|-----|-----------|-------------|
| **MQTT Dashboard** (IoT) | [Link](https://play.google.com/store/apps/details?id=com.thn.iotmqttdashboard) | ★ Más visual |
| **MyMQTT** | [Link](https://play.google.com/store/apps/details?id=at.tripwire.mqtt.client) | ★ Más simple |
| **MQTT Client** (Rahul) | [Link](https://play.google.com/store/apps/details?id=in.dc297.mqttcliente) | Buena también |

**Mi recomendación: MyMQTT** — simple, funciona bien, puedes publicar y suscribirte.

---

## PASO 2: Verificar que laptop y celular están en la misma WiFi

En la laptop:
```bash
hostname -I | awk '{print $1}'
# Debería mostrar: 192.168.18.20 (o tu IP actual)
```

En el celular:
- Ir a Configuración → WiFi → Info de red
- Verificar que la IP empieza con `192.168.18.X`
- Si NO están en la misma red, NO funcionará

---

## PASO 3: Iniciar Mosquitto en la laptop (escuchando en LAN)

```bash
# Verificar que no está corriendo ya
sudo systemctl stop mosquitto 2>/dev/null

# Iniciar con config LAN (permite conexiones externas)
cd ~/2026/PUCP/Tesis/SDN/Mobile/SDN_controller_app
mosquitto -c mosquitto-lan.conf -v
```

Deberías ver:
```
mosquitto version X.X.X starting
Config loaded from mosquitto-lan.conf
Opening ipv4 listen socket on listener port 1883.
```

**Déjalo corriendo en esa terminal.**

---

## PASO 4: Iniciar el controlador SDN

En OTRA terminal:
```bash
cd ~/2026/PUCP/Tesis/SDN/Mobile/SDN_controller_app
./gradlew bootRun
```

Espera hasta ver:
```
Started SdnControllerAppApplication in X.XX seconds
```

**Déjalo corriendo en esa terminal.**

---

## PASO 5: Verificar desde la laptop que todo funciona

En una TERCERA terminal:
```bash
# Verificar controlador
curl -s http://localhost:8081/devices | python3 -m json.tool

# Verificar MQTT (publicar y ver en la terminal de Mosquitto)
mosquitto_pub -h localhost -t "test/ping" -m "hola desde laptop"

# Suscribirse a todos los tópicos de dispositivos (para monitorear)
mosquitto_sub -h localhost -t "dispositivo/#" -v
```

---

## PASO 6: Configurar la app MQTT en el celular

### Si usas MyMQTT:

1. Abrir la app
2. **Settings** (⚙️):
   - Broker Host: `192.168.18.20`  ← IP de tu laptop
   - Broker Port: `1883`
   - Client ID: `celular-android-01`
   - Username: (vacío)
   - Password: (vacío)
3. Tocar **Connect**

### Si usas MQTT Dashboard:
1. Tocar **+** para agregar conexión
2. Nombre: "SDN Controller"
3. URL: `192.168.18.20`
4. Puerto: `1883`
5. Client ID: `celular-android-01`
6. Guardar y conectar

**Verificación:** En la terminal de Mosquitto deberías ver:
```
New connection from 192.168.18.X on port 1883.
New client connected from 192.168.18.X as celular-android-01
```

---

## PASO 7: Suscribir el celular a comandos

En la app MQTT del celular:

**Suscribirse a:**
```
dispositivo/CELULAR-01/comando
```

(Usamos `CELULAR-01` como MAC ficticia para simplificar las pruebas.
En producción sería la MAC real del celular.)

---

## PASO 8: El celular publica telemetría (simula un agente)

En la app MQTT del celular, **publicar** al tópico:
```
dispositivo/CELULAR-01/metrics
```

Con este payload (copiar y pegar exacto):
```json
{"mac":"CELULAR-01","rssi":-42,"technology":"wifi","batteryLevel":85,"ipAddress":"192.168.18.50"}
```

**Resultado esperado:**
- En la terminal de Mosquitto: ves el mensaje pasar
- En los logs del controlador (bootRun): ves "Telemetría recibida de CELULAR-01"
- En `curl http://localhost:8081/devices`: CELULAR-01 aparece como dispositivo registrado

---

## PASO 9: Publicar registro del celular

Publicar al tópico:
```
dispositivo/CELULAR-01/registro
```

Payload:
```json
{"mac":"CELULAR-01","name":"Mi Samsung S10","deviceType":"PHONE","ipAddress":"192.168.18.50"}
```

Verificar:
```bash
curl -s http://localhost:8081/devices | python3 -m json.tool
# Debe mostrar "CELULAR-01" con name "Mi Samsung S10"
```

---

## PASO 10: ★ Prueba completa de sesión (ida y vuelta)

Esto es lo más importante — demuestra el flujo SDN end-to-end.

### 10a. Crear sesión desde la laptop (simula que el celular pide contenido)

```bash
curl -X POST http://localhost:8081/sessions/request \
  -H "Content-Type: application/json" \
  -d '{
    "originMac": "CELULAR-01",
    "query": "algoritmo de dijkstra explicacion",
    "expectedContentType": "text"
  }' | python3 -m json.tool
```

**Lo que pasa:**
1. El controlador crea una sesión (estado: OUTBOUND)
2. Decide: solicitud ligera → canal de IDA = BLUETOOTH
3. Publica `PREPARE_BT` al tópico `dispositivo/CELULAR-01/comando`
4. **EN EL CELULAR:** la app MQTT muestra el comando JSON que llegó:
   ```json
   {"sessionId":"abc12345","action":"PREPARE_BT","reason":"Sesión abc12345: activar BT..."}
   ```

**Copia el `sessionId` de la respuesta** (ej: `abc12345`). Lo necesitas para los siguientes pasos.

### 10b. Marcar solicitud en tránsito

```bash
curl -X POST http://localhost:8081/sessions/abc12345/processing | python3 -m json.tool
```

### 10c. Simular que la respuesta está lista (es un video de 50MB)

```bash
curl -X POST http://localhost:8081/sessions/abc12345/response \
  -H "Content-Type: application/json" \
  -d '{
    "sessionId": "abc12345",
    "contentType": "video/mp4",
    "responseSize": 52428800
  }' | python3 -m json.tool
```

**Lo que pasa:**
1. El controlador ve: video de 50MB → canal de VUELTA = WIFI
2. Publica `SWITCH_WIFI` al tópico `dispositivo/CELULAR-01/comando`
3. **EN EL CELULAR:** llega OTRO comando:
   ```json
   {"sessionId":"abc12345","action":"SWITCH_WIFI","ssid":"SDN_HIGH_SPEED","password":"sdn_secure_pass","reason":"..."}
   ```

### 10d. Confirmar entrega

```bash
curl -X POST http://localhost:8081/sessions/abc12345/delivered | python3 -m json.tool
```

**Lo que pasa:**
1. Estado → DELIVERED → CLOSED
2. Publica `RELEASE_RADIO` al celular
3. **EN EL CELULAR:** llega el comando final:
   ```json
   {"sessionId":"abc12345","action":"RELEASE_RADIO","reason":"Sesión abc12345: solicitud completada..."}
   ```

### 10e. Ver la sesión completa

```bash
# Sesión específica
curl -s http://localhost:8081/sessions/abc12345 | python3 -m json.tool

# Historial del celular
curl -s http://localhost:8081/sessions/device/CELULAR-01 | python3 -m json.tool

# En la consola H2 (navegador)
# Ir a: http://localhost:8081/h2-console
# JDBC URL: jdbc:h2:file:./data/sdn_devices
# User: sa, password: (vacío)
# SQL: SELECT * FROM REQUEST_SESSIONS;
```

---

## PASO 11: Prueba con contenido ligero (va y vuelve por BT)

```bash
# Solicitud de texto
curl -X POST http://localhost:8081/sessions/request \
  -H "Content-Type: application/json" \
  -d '{"originMac":"CELULAR-01","query":"hola mundo","expectedContentType":"text"}' \
  | python3 -m json.tool

# Guardar el sessionId...

# Respuesta ligera (2KB de texto)
curl -X POST http://localhost:8081/sessions/XXXXXXXX/response \
  -H "Content-Type: application/json" \
  -d '{"sessionId":"XXXXXXXX","contentType":"text/plain","responseSize":2048}' \
  | python3 -m json.tool

# Resultado: inboundCarrier = "BLUETOOTH" (no WiFi, porque es poco)
```

---

## Resumen: qué ves dónde

| Lugar | Qué ves |
|-------|---------|
| **Celular (app MQTT)** | Comandos JSON: PREPARE_BT, SWITCH_WIFI, RELEASE_RADIO |
| **Terminal Mosquitto** | Todos los mensajes MQTT pasando |
| **Terminal bootRun** | Logs del controlador con decisiones de canal |
| **curl** | Respuestas JSON de las sesiones |
| **H2 Console** | Base de datos con dispositivos y sesiones |
| **GET /devices** | Celular registrado con su telemetría |

---

## Troubleshooting

### "Connection refused" desde el celular
- Verificar que Mosquitto corre con `-c mosquitto-lan.conf` (NO el default)
- Verificar que ambos están en la misma WiFi
- Verificar firewall: `sudo ufw allow 1883/tcp` (si usas ufw)

### El celular se conecta pero no recibe comandos
- Verificar que se suscribió a `dispositivo/CELULAR-01/comando` (exacto)
- El tópico es case-sensitive

### "Port 8081 already in use"
```bash
sudo lsof -i :8081 | grep LISTEN
# Matar el proceso o cambiar puerto en application.properties
```

### Los mensajes MQTT no llegan
```bash
# Probar MQTT directamente (en dos terminales):
# Terminal A:
mosquitto_sub -h localhost -t "test/#" -v
# Terminal B:
mosquitto_pub -h localhost -t "test/hola" -m "funciona"
# Si no llega, Mosquitto no está corriendo bien
```
