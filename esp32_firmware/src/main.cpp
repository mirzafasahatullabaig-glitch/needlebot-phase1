// NeedleBot Phase 1 - ESP32-S3 BLE peripheral with Serial text loop.
// Type in the Arduino Serial Monitor -> sent over BLE to the Android host.
// Host replies over BLE -> printed here as [RX AI Response].

#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <BLE2902.h>

#define DEVICE_NAME       "NeedleBot-S3"
#define SERVICE_UUID      "4fa86970-43ac-11ed-b878-0242ac120002"
#define NOTIFY_CHAR_UUID  "4fa86c40-43ac-11ed-b878-0242ac120002"  // ESP32 -> Mobile
#define WRITE_CHAR_UUID   "4fa86dae-43ac-11ed-b878-0242ac120002"  // Mobile -> ESP32

static BLECharacteristic *notifyChar = nullptr;
static bool deviceConnected = false;

class ServerCallbacks : public BLEServerCallbacks {
  void onConnect(BLEServer *server) override {
    deviceConnected = true;
    Serial.println("[BLE] Client connected");
  }
  void onDisconnect(BLEServer *server) override {
    deviceConnected = false;
    Serial.println("[BLE] Client disconnected, re-advertising");
    server->startAdvertising();  // auto-restart advertising on disconnect
  }
};

class WriteCallbacks : public BLECharacteristicCallbacks {
  void onWrite(BLECharacteristic *chr) override {
    String value = chr->getValue();  // Arduino-ESP32 3.x returns String
    if (value.length() > 0) {
      Serial.print("[RX AI Response]: ");
      Serial.println(value);
    }
  }
};

// Escape the characters that would break a JSON string literal.
static String jsonEscape(const String &s) {
  String out;
  for (size_t i = 0; i < s.length(); i++) {
    char c = s[i];
    if (c == '"' || c == '\\') { out += '\\'; out += c; }
    else if (c == '\n') out += "\\n";
    else if (c == '\r') ; // drop CR
    else out += c;
  }
  return out;
}

void setup() {
  Serial.begin(115200);
  delay(300);
  Serial.println("\n[NeedleBot-S3] starting");

  BLEDevice::init(DEVICE_NAME);
  BLEServer *server = BLEDevice::createServer();
  server->setCallbacks(new ServerCallbacks());

  BLEService *service = server->createService(SERVICE_UUID);

  notifyChar = service->createCharacteristic(
      NOTIFY_CHAR_UUID, BLECharacteristic::PROPERTY_NOTIFY);
  notifyChar->addDescriptor(new BLE2902());

  BLECharacteristic *writeChar = service->createCharacteristic(
      WRITE_CHAR_UUID,
      BLECharacteristic::PROPERTY_WRITE | BLECharacteristic::PROPERTY_WRITE_NR);
  writeChar->setCallbacks(new WriteCallbacks());

  service->start();

  BLEAdvertising *adv = BLEDevice::getAdvertising();
  adv->addServiceUUID(SERVICE_UUID);
  adv->setScanResponse(true);
  BLEDevice::startAdvertising();

  Serial.println("[BLE] advertising as " DEVICE_NAME);
  Serial.println("Type a message and press Enter:");
}

void loop() {
  if (Serial.available()) {
    String line = Serial.readStringUntil('\n');
    line.trim();
    if (line.length() == 0) return;

    if (!deviceConnected) {
      Serial.println("[BLE] not connected, message dropped");
      return;
    }

    String payload = "{\"text\":\"" + jsonEscape(line) +
                     "\",\"timestamp\":" + String(millis()) + "}";
    notifyChar->setValue(payload.c_str());
    notifyChar->notify();
    Serial.print("[TX]: ");
    Serial.println(payload);
  }
  delay(10);
}
