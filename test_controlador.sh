#!/bin/bash
BASE_URL="http://localhost:8081/network/event"

echo "═══ Prueba 1: Texto pequeño (esperado: PREPARE_BT) ═══"
curl -s -X POST "$BASE_URL" \
  -H "Content-Type: application/json" \
  -d '{"deviceMac":"AA:BB:CC:DD:EE:FF","contentType":"text","fileSize":5000}' | python3 -m json.tool
echo ""

echo "═══ Prueba 2: Video (esperado: SWITCH_WIFI) ═══"
curl -s -X POST "$BASE_URL" \
  -H "Content-Type: application/json" \
  -d '{"deviceMac":"11:22:33:44:55:66","contentType":"video/mp4","fileSize":52428800}' | python3 -m json.tool
echo ""

echo "═══ Prueba 3: IA Llama (esperado: PREPARE_BT) ═══"
curl -s -X POST "$BASE_URL" \
  -H "Content-Type: application/json" \
  -d '{"deviceMac":"AA:BB:CC:DD:EE:FF","contentType":"llama","fileSize":2048}' | python3 -m json.tool
echo ""

echo "═══ Prueba 4: Navegación web (esperado: SWITCH_WIFI) ═══"
curl -s -X POST "$BASE_URL" \
  -H "Content-Type: application/json" \
  -d '{"deviceMac":"AA:BB:CC:DD:EE:FF","contentType":"web","fileSize":1024}' | python3 -m json.tool
echo ""

echo "═══ Prueba 5: Archivo grande genérico (esperado: SWITCH_WIFI) ═══"
curl -s -X POST "$BASE_URL" \
  -H "Content-Type: application/json" \
  -d '{"deviceMac":"AA:BB:CC:DD:EE:FF","contentType":"application/octet-stream","fileSize":20971520}' | python3 -m json.tool
echo ""

echo "═══ Prueba 6: Telemetría MQTT ═══"
mosquitto_pub -t "dispositivo/AA:BB:CC:DD:EE:FF/metrics" \
  -m '{"rssi":-67,"technology":"wifi","battery":42}'
echo "Métricas enviadas. Revisa la consola del controlador."