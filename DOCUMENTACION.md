# Controlador SDN — Documentación Completa

## Tabla de Contenidos

1. [¿Qué es este proyecto?](#1-qué-es-este-proyecto)
2. [Arquitectura general](#2-arquitectura-general)
3. [Conceptos clave para entender el código](#3-conceptos-clave-para-entender-el-código)
4. [Estructura del proyecto](#4-estructura-del-proyecto)
5. [Explicación archivo por archivo](#5-explicación-archivo-por-archivo)
6. [Flujo de datos completo](#6-flujo-de-datos-completo)
7. [Cómo probar con simulación (curl)](#7-cómo-probar-con-simulación-curl)
8. [Pruebas con dispositivos REALES (laptop + celular)](#8-pruebas-con-dispositivos-reales-laptop--celular)
9. [API de gestión de dispositivos](#9-api-de-gestión-de-dispositivos)
10. [Base de datos H2 y consola web](#10-base-de-datos-h2-y-consola-web)
11. [Solución de problemas comunes](#11-solución-de-problemas-comunes)

---

## 1. ¿Qué es este proyecto?

Este es el **Plano de Control** de una red SDN (Software-Defined Networking) para IoT móvil.
Su trabajo es **decidir por qué canal** (Bluetooth o WiFi) se le entrega contenido a un
dispositivo móvil (llamado "Agente").

**Analogía simple:** Imagina un controlador de tráfico aéreo. Los aviones (datos) necesitan
llegar a su destino (dispositivo). El controlador (este proyecto) decide si el avión va por
la pista A (Bluetooth — vuelos cortos/ligeros) o la pista B (WiFi — vuelos pesados).

### Reglas de decisión

| Situación | Canal elegido | Comando enviado |
|---|---|---|
| Texto, respuesta de IA (Llama), archivo < 10 MB | **Bluetooth** | `PREPARE_BT` |
| Video, navegación web | **WiFi** | `SWITCH_WIFI` |
| Archivo ≥ 10 MB (cualquier tipo) | **WiFi** | `SWITCH_WIFI` |

---

## 2. Arquitectura general

```
  ┌─────────────────────────────────────────────────────────────────┐
  │              CONTROLADOR SDN (Spring Boot 4.x)                  │
  │                                                                  │
  │  ┌────────────┐    ┌──────────────────┐    ┌───────────────┐    │
  │  │  REST API   │───►│ CarrierSwitching │───►│  MqttGateway  │────┼──► MQTT: dispositivo/{MAC}/comando
  │  │ /network/* │    │ Service (decide) │    │  (publica)    │    │
  │  │ /devices/* │    └──────────────────┘    └───────────────┘    │
  │  └────────────┘              │                                   │
  │                    ┌─────────▼────────┐                         │
  │                    │  DeviceService    │                         │
  │                    │  (registra, BD)   │                         │
  │                    └─────────▲────────┘                         │
  │                              │                                   │
  │  ┌──────────────────────────┴───────────────────────────┐      │
  │  │  TelemetrySubscriber                                  │      │
  │  │  - Escucha dispositivo/+/metrics (telemetría)         │◄─────┼── MQTT Subscribe
  │  │  - Escucha dispositivo/+/registro (auto-registro)     │      │
  │  └──────────────────────────────────────────────────────┘      │
  │                                                                  │
  │  ┌────────────────────┐    ┌────────────────────────┐           │
  │  │ Base de datos H2    │    │ DeviceHealthChecker    │           │
  │  │ (dispositivos)      │    │ (marca offline cada 60s)│           │
  │  └────────────────────┘    └────────────────────────┘           │
  └─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
                    Broker MQTT (Mosquitto)
                    Escucha en 0.0.0.0:1883
                              │
              ┌───────────────┼───────────────┐
              ▼               ▼               ▼
          Tu Laptop       Tu Celular      Otros IoT
         (agente)       (app MQTT)       (futuros)
```

---

## 3. Conceptos clave para entender el código

### 3.1 Kotlin (el lenguaje)

Kotlin es un lenguaje que corre sobre la JVM (como Java) pero con sintaxis más concisa.

```kotlin
// Esto en Kotlin:
data class Persona(val nombre: String, val edad: Int)

// Equivale a esto en Java (simplificado):
// public class Persona {
//     private final String nombre;
//     private final int edad;
//     // + constructor, getters, equals, hashCode, toString... (~50 líneas)
// }
```

**Cosas de Kotlin que verás en el código:**

| Sintaxis Kotlin | Significado |
|---|---|
| `val x: String` | Variable de solo lectura (como `final` en Java) |
| `var x: String` | Variable mutable |
| `lateinit var` | "Lo inicializaré después" (Spring lo inyecta) |
| `data class` | Clase que auto-genera `toString`, `equals`, `hashCode`, `copy` |
| `?.` | Safe call — si es null, no ejecuta (evita NullPointerException) |
| `as?` | Cast seguro — si falla retorna null en vez de lanzar excepción |
| `apply { ... }` | "Configura este objeto" — ejecuta el bloque y retorna el objeto |
| `setOf(...)` | Crea un conjunto inmutable (como `Set.of()` en Java) |
| `it` | Referencia implícita al parámetro de un lambda |

### 3.2 Gradle con Kotlin DSL

Gradle es el sistema de build (como Maven pero más flexible). **Kotlin DSL** significa
que el archivo de configuración (`build.gradle.kts`) está escrito en Kotlin en vez de Groovy.

```kotlin
// build.gradle.kts — equivalencias con Maven:

plugins {                                    // = <build><plugins> en pom.xml
    kotlin("jvm") version "2.2.21"           // Compilador de Kotlin
    id("org.springframework.boot") ...       // Plugin de Spring Boot
}

dependencies {                               // = <dependencies> en pom.xml
    implementation("grupo:artefacto:ver")    // = <scope>compile</scope>
    testImplementation("...")                 // = <scope>test</scope>
    developmentOnly("...")                   // Solo en desarrollo, no en producción
}
```

**Comandos Gradle equivalentes a Maven:**

| Gradle | Maven equivalente | Qué hace |
|---|---|---|
| `./gradlew build` | `mvn package` | Compila + tests + empaqueta |
| `./gradlew compileKotlin` | `mvn compile` | Solo compila |
| `./gradlew bootRun` | `mvn spring-boot:run` | Ejecuta la aplicación |
| `./gradlew build -x test` | `mvn package -DskipTests` | Build sin tests |
| `./gradlew dependencies` | `mvn dependency:tree` | Árbol de dependencias |

### 3.3 Spring Boot — Conceptos usados

| Concepto | Para qué sirve | Dónde se usa |
|---|---|---|
| `@SpringBootApplication` | Punto de entrada de la app | `SdnControllerAppApplication.kt` |
| `@Configuration` + `@Bean` | Crear objetos que Spring gestiona | `MqttConfig.kt`, `JacksonConfig.kt` |
| `@Service` | Lógica de negocio | `CarrierSwitchingService.kt` |
| `@RestController` | Expone endpoints HTTP | `NetworkEventController.kt` |
| `@Component` | Componente genérico | `TelemetrySubscriber.kt` |
| `@Value("${...}")` | Inyecta valores del `application.properties` | Varios archivos |
| `@MessagingGateway` | Interfaz para enviar mensajes (Spring Integration) | `MqttGateway.kt` |
| `@ServiceActivator` | Recibe mensajes de un canal | `TelemetrySubscriber.kt` |

### 3.4 MQTT

MQTT es un protocolo de mensajería ligero para IoT. Funciona con el patrón **publicar/suscribir**:

- **Broker** (Mosquitto): El "cartero" central que enruta mensajes.
- **Tópico**: La "dirección" del mensaje (ej: `dispositivo/AA:BB:CC:DD:EE:FF/comando`).
- **Publicar**: Enviar un mensaje a un tópico.
- **Suscribir**: Escuchar mensajes de un tópico. El `+` es comodín: `dispositivo/+/metrics`
  escucha métricas de **todos** los dispositivos.

---

## 4. Estructura del proyecto

```
SDN_controller_app/
├── build.gradle.kts                    ← Dependencias y configuración de build
├── gradle.properties                   ← Ruta del JDK para Gradle
├── settings.gradle.kts                 ← Nombre del proyecto
├── mosquitto-lan.conf                  ← Config para abrir Mosquitto a la LAN
├── data/                               ← Base de datos H2 (se crea sola al ejecutar)
│
└── src/main/
    ├── resources/
    │   └── application.properties      ← Configuración (broker, WiFi, umbral, BD)
    │
    └── kotlin/org/sdn_controller_app/
        ├── SdnControllerAppApplication.kt      ← [1] Punto de entrada
        │
        ├── config/
        │   ├── MqttConfig.kt                   ← [2] Conexión al broker MQTT
        │   └── JacksonConfig.kt                ← [3] Serialización JSON
        │
        ├── model/
        │   ├── Device.kt                        ← [4] Entidad JPA (dispositivo en BD)
        │   ├── DeviceRegistrationRequest.kt     ← [5] DTO para registrar dispositivos
        │   ├── NetworkEventRequest.kt           ← [6] DTO de entrada (petición HTTP)
        │   └── CarrierCommand.kt                ← [7] DTO de salida (comando MQTT)
        │
        ├── repository/
        │   └── DeviceRepository.kt              ← [8] Acceso a BD de dispositivos
        │
        ├── mqtt/
        │   ├── MqttGateway.kt                   ← [9] Interfaz para publicar MQTT
        │   └── TelemetrySubscriber.kt           ← [10] Suscriptor de métricas + registro
        │
        ├── service/
        │   ├── CarrierSwitchingService.kt       ← [11] Cerebro: lógica de decisión
        │   ├── DeviceService.kt                 ← [12] Gestión de dispositivos
        │   └── DeviceHealthChecker.kt           ← [13] Marca offline si no hay heartbeat
        │
        └── controller/
            ├── NetworkEventController.kt        ← [14] POST /network/event
            └── DeviceController.kt              ← [15] API REST de dispositivos
```

---

## 5. Explicación archivo por archivo

### [1] `SdnControllerAppApplication.kt` — Punto de entrada

```kotlin
@SpringBootApplication       // Le dice a Spring: "Escanea todo este paquete y configura la app"
@IntegrationComponentScan    // Le dice a Spring Integration: "Busca @MessagingGateway aquí"
class SdnControllerAppApplication

fun main(args: Array<String>) {
    runApplication<SdnControllerAppApplication>(*args)  // Arranca Spring Boot
}
```

**¿Por qué `@IntegrationComponentScan`?** Sin esto, Spring no detecta la interfaz
`MqttGateway` anotada con `@MessagingGateway`, y no podríamos publicar mensajes MQTT.

---

### [2] `config/MqttConfig.kt` — Conexión MQTT

Este archivo configura **tres cosas**:

**a) `mqttClientFactory()`** — La fábrica de conexiones MQTT
```kotlin
@Bean
fun mqttClientFactory(): MqttPahoClientFactory {
    // Crea una conexión al broker Mosquitto con:
    // - URL del broker (tcp://localhost:1883)
    // - Reconexión automática si se cae
    // - Sesión limpia (no recuerda mensajes previos)
}
```

**b) Canal de SALIDA** — Para ENVIAR comandos a los dispositivos
```kotlin
@Bean fun mqttOutboundChannel(): MessageChannel  // El "tubo" por donde van los mensajes
@Bean fun mqttOutbound(): MessageHandler          // El publicador MQTT real
```

**c) Canal de ENTRADA** — Para RECIBIR telemetría de los dispositivos
```kotlin
@Bean fun mqttInboundChannel(): MessageChannel                    // El "tubo" de entrada
@Bean fun mqttInbound(): MqttPahoMessageDrivenChannelAdapter     // Se suscribe a dispositivo/+/metrics
```

---

### [3] `config/JacksonConfig.kt` — Serialización JSON

```kotlin
@Bean
fun objectMapper(): ObjectMapper {
    return ObjectMapper().apply {
        registerModule(KotlinModule.Builder().build())           // Soporte para data classes de Kotlin
        setSerializationInclusion(JsonInclude.Include.NON_NULL)  // No incluir campos null en el JSON
    }
}
```

**¿Por qué es necesario?** Spring Boot 4.x migró a Jackson 3.x (`tools.jackson.*`) para la
serialización HTTP, pero nuestro código MQTT usa Jackson 2.x (`com.fasterxml.jackson.*`).
Este bean provee el ObjectMapper de Jackson 2.x que los servicios necesitan.

**Efecto práctico:** Cuando el comando es `PREPARE_BT`, el JSON **no** incluye `ssid` ni
`password` (porque son `null`):
```json
{"action": "PREPARE_BT", "reason": "Contenido ligero..."}
```

---

### [4] `model/NetworkEventRequest.kt` — DTO de entrada

```kotlin
data class NetworkEventRequest(
    val deviceMac: String,    // "AA:BB:CC:DD:EE:FF"
    val contentType: String,  // "video/mp4", "text", "llama", "web", etc.
    val fileSize: Long        // Tamaño en bytes (ej: 5242880 = 5 MB)
)
```

Este es el JSON que recibe el endpoint `POST /network/event`. Spring lo convierte
automáticamente del cuerpo HTTP a este objeto Kotlin.

---

### [5] `model/CarrierCommand.kt` — DTO de salida

```kotlin
data class CarrierCommand(
    val action: String,           // "PREPARE_BT" o "SWITCH_WIFI"
    val ssid: String? = null,     // Solo presente si action = SWITCH_WIFI
    val password: String? = null, // Solo presente si action = SWITCH_WIFI
    val reason: String            // Explicación de la decisión
)
```

Este objeto se:
1. **Serializa a JSON** y se publica por MQTT al dispositivo.
2. **Retorna como respuesta HTTP** al cliente que hizo el POST.

---

### [6] `mqtt/MqttGateway.kt` — Publicador MQTT

```kotlin
@MessagingGateway(defaultRequestChannel = "mqttOutboundChannel")
interface MqttGateway {
    fun publish(@Header(MqttHeaders.TOPIC) topic: String, payload: String)
}
```

**¿Por qué es solo una interfaz sin implementación?** Spring Integration genera la
implementación automáticamente. Cuando llamas `mqttGateway.publish(topic, json)`, Spring:

1. Toma el `payload` (el JSON del comando).
2. Le pone el header `mqtt_topic` con el tópico dinámico (ej: `dispositivo/AA:BB:CC/comando`).
3. Lo envía por el `mqttOutboundChannel` al `MqttPahoMessageHandler`.
4. El handler lo publica en el broker Mosquitto.

---

### [7] `mqtt/TelemetrySubscriber.kt` — Suscriptor de métricas

Escucha **automáticamente** el tópico `dispositivo/+/metrics` (configurado en `MqttConfig`).

Cuando un dispositivo publica sus métricas:
```json
{"rssi": -45, "technology": "bluetooth", "battery": 78}
```

Este componente:
1. Extrae la MAC del tópico (ej: del tópico `dispositivo/AA:BB:CC/metrics` extrae `AA:BB:CC`)
2. Parsea el JSON
3. Registra en consola el **RSSI** (fuerza de señal) y la **tecnología activa**

---

### [8] `service/CarrierSwitchingService.kt` — El cerebro

Este es el componente más importante. Contiene la **lógica de decisión SDN**:

```
Solicitud recibida
        │
        ▼
  ¿Es video o web? ──── SÍ ──► SWITCH_WIFI (con SSID + password)
        │
        NO
        ▼
  ¿Es texto/IA Llama    SÍ ──► PREPARE_BT
   o archivo < 10 MB? ──┤
        │                NO
        ▼
  SWITCH_WIFI (archivo grande, fallback)
```

Después de decidir, el servicio:
1. Serializa el comando a JSON.
2. Lo publica por MQTT al tópico `dispositivo/{MAC}/comando`.
3. Retorna el comando al controlador REST.

---

### [9] `controller/NetworkEventController.kt` — Endpoint REST

```kotlin
@PostMapping("/event")
fun handleNetworkEvent(@RequestBody request: NetworkEventRequest): ResponseEntity<CarrierCommand>
```

Recibe peticiones HTTP en `POST http://localhost:8081/network/event`, delega al servicio
de Carrier Switching, y retorna el comando decidido como respuesta JSON.

---

### `application.properties` — Configuración

```properties
server.port=8081                        # Puerto HTTP del controlador
mqtt.broker.url=tcp://localhost:1883    # Dirección del broker Mosquitto
mqtt.client.id=sdn-controller          # ID del cliente MQTT
wifi.ssid=SDN_HIGH_SPEED               # SSID de la red WiFi de alta velocidad
wifi.password=sdn_secure_pass          # Contraseña WiFi
carrier.file-size-threshold-mb=10      # Umbral: archivos bajo este tamaño van por BT
spring.jackson.default-property-inclusion=non-null  # REST no envía campos null
```

### `build.gradle.kts` — Dependencias

| Dependencia | Para qué |
|---|---|
| `spring-boot-starter-web` | Servidor HTTP (Tomcat) + REST |
| `spring-boot-starter-integration` | Framework de mensajería (Spring Integration) |
| `spring-integration-mqtt` | Adaptador MQTT para Spring Integration |
| `org.eclipse.paho.client.mqttv3` | Cliente MQTT de Eclipse (protocolo real) |
| `jackson-module-kotlin` | Serializar/deserializar data classes Kotlin a JSON |
| `kotlin-reflect` | Reflexión para Spring y Jackson |

---

## 6. Flujo de datos completo

### Escenario: Un agente solicita un video de 50 MB

```
1. Algún servicio externo (o script de prueba) hace:
   POST http://localhost:8081/network/event
   {"deviceMac": "AA:BB:CC:DD:EE:FF", "contentType": "video/mp4", "fileSize": 52428800}

2. NetworkEventController recibe el JSON → lo convierte a NetworkEventRequest

3. CarrierSwitchingService.evaluateAndDispatch():
   - contentType contiene "video" → Regla 1 aplica
   - Decide: SWITCH_WIFI
   - Crea: CarrierCommand(action="SWITCH_WIFI", ssid="SDN_HIGH_SPEED", password="sdn_secure_pass", reason="...")
   - Serializa a JSON
   - Publica vía MqttGateway al tópico: dispositivo/AA:BB:CC:DD:EE:FF/comando

4. Mosquitto recibe el mensaje y lo entrega al agente suscrito a ese tópico

5. El agente móvil lee el comando y se conecta a la red WiFi indicada

6. El controlador retorna la respuesta HTTP al servicio que hizo el POST
```

---

## 7. Cómo probar con simulación (curl)

### Requisito previo: Instalar Mosquitto

```bash
sudo apt install mosquitto mosquitto-clients
sudo systemctl start mosquitto
```

### Arrancar el controlador

```bash
cd /home/jleon/2026/PUCP/Tesis/SDN/Mobile/SDN_controller_app
./gradlew bootRun
# O desde IntelliJ IDEA: botón verde ▶ junto a fun main(...)
```

### Pruebas rápidas con curl

```bash
# Texto pequeño → PREPARE_BT
curl -s -X POST http://localhost:8081/network/event \
  -H "Content-Type: application/json" \
  -d '{"deviceMac":"AA:BB:CC:DD:EE:FF","contentType":"text","fileSize":5000}'

# Video → SWITCH_WIFI
curl -s -X POST http://localhost:8081/network/event \
  -H "Content-Type: application/json" \
  -d '{"deviceMac":"AA:BB:CC:DD:EE:FF","contentType":"video/mp4","fileSize":52428800}'

# Telemetría simulada
mosquitto_pub -t "dispositivo/AA:BB:CC:DD:EE:FF/metrics" \
  -m '{"rssi":-45,"technology":"bluetooth","battery":78}'
```

---

## 8. Pruebas con dispositivos REALES (laptop + celular)

Esta es la guía paso a paso para que **tu laptop sea el controlador SDN** y
**tu celular sea un agente** que se comuniquen de verdad.

### 8.1 Preparar la red

Ambos dispositivos (laptop y celular) deben estar en la **misma red WiFi**.

**Tu laptop (controlador):**
- IP actual: `192.168.18.20` (puede cambiar, verificar con `hostname -I`)
- Ejecuta: el controlador Spring Boot + el broker Mosquitto

**Tu celular (agente):**
- Se conecta al broker MQTT de tu laptop
- Publica telemetría y recibe comandos

### 8.2 Configurar Mosquitto para aceptar conexiones de la LAN

Por defecto, Mosquitto solo escucha en `localhost`. Para que tu celular se conecte:

```bash
# Copiar la configuración que ya creamos
sudo cp mosquitto-lan.conf /etc/mosquitto/conf.d/lan.conf

# Reiniciar Mosquitto
sudo systemctl restart mosquitto

# Verificar que escucha en todas las interfaces
ss -tlnp | grep 1883
# Debe mostrar: 0.0.0.0:1883 (no 127.0.0.1:1883)
```

**Si usas firewall (ufw):**
```bash
sudo ufw allow 1883/tcp    # MQTT
sudo ufw allow 8081/tcp    # API REST del controlador
```

### 8.3 Instalar app MQTT en el celular

Instala una de estas apps gratuitas desde la Play Store (Android):

| App | Descripción |
|---|---|
| **MQTT Dashboard** (recomendada) | Interfaz visual, fácil de usar |
| **IoT MQTT Panel** | Más opciones de visualización |
| **MQTT Client** | Minimalista |

**Configurar la conexión en la app:**
- **Broker/Server:** `192.168.18.20` (la IP de tu laptop)
- **Port:** `1883`
- **Client ID:** `celular-agente-01` (cualquier nombre único)
- **Username/Password:** dejar vacío (no tenemos auth configurada)

### 8.4 Obtener la MAC de tu celular

**En Android:**
1. Ajustes → Acerca del teléfono → Estado → Dirección MAC WiFi
2. O: Ajustes → WiFi → red conectada → Detalles avanzados

Ejemplo: `A4:B1:C2:D3:E4:F5`

### 8.5 Registrar el celular en el controlador

**Opción A: Registro por REST (desde la laptop)**

```bash
curl -X POST http://localhost:8081/devices/register \
  -H "Content-Type: application/json" \
  -d '{
    "mac": "A4:B1:C2:D3:E4:F5",
    "name": "Celular de Jorge",
    "deviceType": "PHONE",
    "ipAddress": "192.168.18.XX"
  }'
```

**Opción B: Auto-registro por MQTT (desde el celular)**

En la app MQTT del celular, publicar en el tópico:
```
dispositivo/A4:B1:C2:D3:E4:F5/registro
```
Con el payload:
```json
{
  "name": "Celular de Jorge",
  "deviceType": "PHONE",
  "ip": "192.168.18.XX"
}
```

**Opción C: Auto-descubrimiento por telemetría**

Simplemente envía métricas desde el celular (paso 8.6) y el controlador
lo registrará automáticamente.

### 8.6 Enviar telemetría desde el celular

En la app MQTT, configura una publicación periódica (o manual):

- **Tópico:** `dispositivo/A4:B1:C2:D3:E4:F5/metrics`
- **Payload:**
```json
{
  "rssi": -52,
  "technology": "wifi",
  "battery": 85,
  "deviceType": "PHONE",
  "ip": "192.168.18.XX"
}
```

**En la consola del controlador verás:**
```
═══════════════════════════════════════════════════════
  Telemetría recibida del dispositivo: A4:B1:C2:D3:E4:F5
  RSSI             : -52 dBm
  Tecnología activa: wifi
  Estado BD        : Celular de Jorge — online=true
═══════════════════════════════════════════════════════
```

### 8.7 Suscribirse a comandos desde el celular

En la app MQTT, suscribirse al tópico:
```
dispositivo/A4:B1:C2:D3:E4:F5/comando
```

Ahora cuando el controlador tome una decisión para ese dispositivo,
**el celular recibirá el comando en tiempo real**.

### 8.8 Disparar una decisión de red para el celular

Desde la laptop:

```bash
# Escenario: El celular pide un video → SWITCH_WIFI
curl -X POST http://localhost:8081/network/event \
  -H "Content-Type: application/json" \
  -d '{
    "deviceMac": "A4:B1:C2:D3:E4:F5",
    "contentType": "video/mp4",
    "fileSize": 52428800
  }'
```

**En el celular (app MQTT) recibirás:**
```json
{
  "action": "SWITCH_WIFI",
  "ssid": "SDN_HIGH_SPEED",
  "password": "sdn_secure_pass",
  "reason": "Contenido de alto ancho de banda: video/mp4"
}
```

```bash
# Escenario: El celular pide un texto de IA → PREPARE_BT
curl -X POST http://localhost:8081/network/event \
  -H "Content-Type: application/json" \
  -d '{
    "deviceMac": "A4:B1:C2:D3:E4:F5",
    "contentType": "llama",
    "fileSize": 2048
  }'
```

**En el celular recibirás:**
```json
{
  "action": "PREPARE_BT",
  "reason": "Contenido ligero: llama, tamaño: 0 MB"
}
```

### 8.9 Verificar que el dispositivo está registrado

```bash
# Ver todos los dispositivos registrados
curl -s http://localhost:8081/devices | python3 -m json.tool

# Ver solo los que están online
curl -s http://localhost:8081/devices/online | python3 -m json.tool

# Ver un dispositivo específico
curl -s http://localhost:8081/devices/A4:B1:C2:D3:E4:F5 | python3 -m json.tool
```

### 8.10 Flujo completo real (resumen visual)

```
  TU CELULAR                                    TU LAPTOP
  ══════════                                    ═════════
                                                
  1. Conectar app MQTT ──────────────────────► Mosquitto (192.168.18.20:1883)
                                                
  2. Publicar registro:                        
     dispositivo/{MAC}/registro ──────────────► TelemetrySubscriber
     {"name":"Mi Cel","deviceType":"PHONE"}         │
                                                    ▼
                                                DeviceService → BD H2
                                                (dispositivo registrado ✓)

  3. Publicar telemetría periódica:            
     dispositivo/{MAC}/metrics ───────────────► TelemetrySubscriber
     {"rssi":-52,"technology":"wifi"}               │
                                                    ▼
                                                Actualiza BD (online, rssi, tech)

  4. Suscribirse a:                            
     dispositivo/{MAC}/comando                 

  5. (Desde la laptop) POST /network/event ──► CarrierSwitchingService
     con la MAC del celular                         │ decide: BT o WiFi
                                                    ▼
                                                MqttGateway.publish()
                                                    │
  6. Celular recibe comando: ◄─────────────────────┘
     {"action":"SWITCH_WIFI","ssid":"..."}     
```

---

## 9. API de gestión de dispositivos

### Endpoints disponibles

| Método | Endpoint | Descripción |
|---|---|---|
| `POST` | `/devices/register` | Registrar un dispositivo manualmente |
| `GET` | `/devices` | Listar todos los dispositivos |
| `GET` | `/devices/online` | Listar solo los dispositivos conectados |
| `GET` | `/devices/{mac}` | Consultar un dispositivo por MAC |
| `DELETE` | `/devices/{mac}` | Eliminar un dispositivo |
| `POST` | `/devices/cleanup?timeoutSeconds=120` | Marcar offline los inactivos |

### Tópicos MQTT

| Tópico | Dirección | Descripción |
|---|---|---|
| `dispositivo/{MAC}/metrics` | Dispositivo → Controlador | Telemetría (RSSI, tech, battery) |
| `dispositivo/{MAC}/registro` | Dispositivo → Controlador | Auto-registro por MQTT |
| `dispositivo/{MAC}/comando` | Controlador → Dispositivo | Comandos (PREPARE_BT, SWITCH_WIFI) |

### Ejemplo de respuesta GET /devices

```json
[
  {
    "mac": "A4:B1:C2:D3:E4:F5",
    "name": "Celular de Jorge",
    "deviceType": "PHONE",
    "activeTechnology": "wifi",
    "lastRssi": -52,
    "batteryLevel": 85,
    "ipAddress": "192.168.18.50",
    "online": true,
    "firstSeen": "2026-02-08T05:30:00Z",
    "lastSeen": "2026-02-08T05:35:22Z"
  }
]
```

---

## 10. Base de datos H2 y consola web

La base de datos está embebida en la aplicación — no necesitas instalar nada.
Los datos se guardan en el directorio `data/` del proyecto.

### Acceder a la consola web H2

1. Con el controlador corriendo, abre en el navegador: `http://localhost:8081/h2-console`
2. Configurar:
   - **JDBC URL:** `jdbc:h2:file:./data/sdn_devices`
   - **User Name:** `sa`
   - **Password:** (dejar vacío)
3. Click **Connect**

### Consultas SQL útiles

```sql
-- Ver todos los dispositivos
SELECT * FROM DEVICES;

-- Solo los que están online
SELECT MAC, NAME, ACTIVE_TECHNOLOGY, LAST_RSSI, ONLINE FROM DEVICES WHERE ONLINE = TRUE;

-- Último dispositivo visto
SELECT * FROM DEVICES ORDER BY LAST_SEEN DESC LIMIT 1;
```

---

## 11. Solución de problemas comunes

### "No qualifying bean of type ObjectMapper"

**Causa:** Spring Boot 4.x usa Jackson 3.x, no auto-crea el ObjectMapper de Jackson 2.x.
**Solución:** Ya resuelta con `JacksonConfig.kt`.

### "Connection refused" al publicar MQTT

**Causa:** Mosquitto no está corriendo o no escucha en la LAN.

```bash
sudo systemctl start mosquitto
sudo systemctl restart mosquitto
# Verificar
ss -tlnp | grep 1883
```

### El celular no puede conectarse al broker

1. ¿Están en la misma red WiFi?
2. ¿Copiaste `mosquitto-lan.conf`?
   ```bash
   sudo cp mosquitto-lan.conf /etc/mosquitto/conf.d/lan.conf
   sudo systemctl restart mosquitto
   ```
3. ¿El firewall bloquea el puerto?
   ```bash
   sudo ufw allow 1883/tcp
   ```
4. Probar conectividad desde el celular: abrir un navegador y visitar
   `http://192.168.18.20:8081/devices` — si ves `[]`, la red funciona.

### El dispositivo no aparece en /devices

- Envía telemetría por MQTT al tópico `dispositivo/{MAC}/metrics` y el controlador
  lo registrará automáticamente.
- O regístralo manualmente con `POST /devices/register`.

### "Port 8081 already in use"

```bash
lsof -i :8081
# Matar el proceso o cambiar el puerto en application.properties
```

### Gradle no encuentra Java

```bash
javac -version
# Si no tienes javac:
sudo apt install openjdk-21-jdk-headless
```

### La IP de la laptop cambió

```bash
hostname -I
# Actualizar la IP en la app MQTT del celular
```

---

## Resumen de lo que se logró

Se implementó un **Controlador SDN completo con dispositivos reales** que:

1. **Registra dispositivos** en una base de datos H2 (manual o automáticamente)
2. **Auto-descubre** dispositivos cuando envían telemetría MQTT
3. **Recibe eventos de red** por REST (`POST /network/event`)
4. **Decide el canal óptimo** (Bluetooth o WiFi) según el tipo y tamaño del contenido
5. **Publica comandos** por MQTT al tópico `dispositivo/{MAC}/comando`
6. **Recibe telemetría** de los dispositivos vía MQTT (`dispositivo/+/metrics`)
7. **Registra métricas** (RSSI, tecnología activa, batería) en BD y logs
8. **Monitorea salud** de dispositivos (marca offline si no hay heartbeat en 2 min)
9. **Expone API REST** para gestionar el inventario de dispositivos
10. **Provee consola web H2** para inspeccionar la base de datos
