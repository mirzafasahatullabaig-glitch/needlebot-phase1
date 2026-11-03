// NeedleBot Phase 1 - ESP32-S3 BLE peripheral with Serial text loop.
// Type in the Arduino Serial Monitor -> sent over BLE to the Android host.
// Host replies over BLE -> printed here as [RX AI Response].

#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <BLE2902.h>
#include <driver/i2s.h>   // Arduino-ESP32 3.x ships the IDF I2S driver
#include <Wire.h>
#include <Adafruit_SSD1306.h>  // install "Adafruit SSD1306" (+ GFX) in Library Manager

// --- INMP441 I2S mic (SD=GPIO6, WS=GPIO5, SCK=GPIO4, L/R=GND => left slot) ---
#define MIC_SD   6
#define MIC_WS   5
#define MIC_SCK  4
#define MIC_PORT I2S_NUM_0
#define MIC_SR   16000   // 16 kHz is plenty for speech

// --- SSD1306 OLED on I2C (SDA=GPIO7, SCL=GPIO15, addr 0x3C) ---
// S3 has no GPIO22; any free pin works for I2C. 7 sits by the mic pins. -1 = no reset.
#define OLED_SDA  7
#define OLED_SCL  15
#define OLED_ADDR 0x3C
static Adafruit_SSD1306 oled(128, 64, &Wire, -1);

#define DEVICE_NAME       "NeedleBot-S3"
#define SERVICE_UUID      "4fa86970-43ac-11ed-b878-0242ac120002"
#define NOTIFY_CHAR_UUID  "4fa86c40-43ac-11ed-b878-0242ac120002"  // ESP32 -> Mobile
#define WRITE_CHAR_UUID   "4fa86dae-43ac-11ed-b878-0242ac120002"  // Mobile -> ESP32

static BLECharacteristic *notifyChar = nullptr;
static bool deviceConnected = false;
static String lastTx = "";   // last message we sent to the phone
static String lastRx = "";   // last reply received from the phone
static bool screenDirty = true;

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
      lastRx = value;
      screenDirty = true;
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

// Bring up I2S for the INMP441. Samples arrive 24-bit left-justified in a
// 32-bit slot, so we read int32 and shift down.
static void micBegin() {
  i2s_config_t cfg = {
    .mode = (i2s_mode_t)(I2S_MODE_MASTER | I2S_MODE_RX),
    .sample_rate = MIC_SR,
    .bits_per_sample = I2S_BITS_PER_SAMPLE_32BIT,
    .channel_format = I2S_CHANNEL_FMT_ONLY_LEFT,  // L/R tied to GND
    .communication_format = I2S_COMM_FORMAT_STAND_I2S,
    .intr_alloc_flags = ESP_INTR_FLAG_LEVEL1,
    .dma_buf_count = 4,
    .dma_buf_len = 256,
    .use_apll = false,
  };
  i2s_pin_config_t pins = {
    .bck_io_num = MIC_SCK,
    .ws_io_num = MIC_WS,
    .data_out_num = I2S_PIN_NO_CHANGE,
    .data_in_num = MIC_SD,
  };
  i2s_driver_install(MIC_PORT, &cfg, 0, nullptr);
  i2s_set_pin(MIC_PORT, &pins);
}

// Mean absolute amplitude of one DMA block — a simple level meter.
static uint32_t micLevel() {
  int32_t buf[256];
  size_t nbytes = 0;
  i2s_read(MIC_PORT, buf, sizeof(buf), &nbytes, portMAX_DELAY);
  size_t n = nbytes / sizeof(int32_t);
  if (n == 0) return 0;
  // Remove the mic's DC bias first, else a quiet mic reads as a big wandering
  // number: take the block mean, then the mean absolute deviation from it.
  int64_t mean = 0;
  for (size_t i = 0; i < n; i++) mean += buf[i] >> 8;  // 24-bit in a 32-bit slot
  mean /= (int64_t)n;
  int64_t sum = 0;
  for (size_t i = 0; i < n; i++) {
    int64_t d = (buf[i] >> 8) - mean;
    sum += d < 0 ? -d : d;
  }
  return (uint32_t)(sum / n);
}

// Draw the whole 128x64 screen: header, BLE state + mic bar, last message
// sent and last reply received. println wraps long lines on its own.
static void drawScreen(uint32_t micLvl) {
  oled.clearDisplay();
  oled.setCursor(0, 0);
  oled.print("NeedleBot  ");
  oled.println(deviceConnected ? "[on]" : "[adv]");
  // mic bar: 0..~50000 mapped to full width
  int w = micLvl / 400;
  if (w > 127) w = 127;
  oled.fillRect(0, 10, w, 4, SSD1306_WHITE);
  oled.setCursor(0, 18);
  oled.print(">");  oled.println(lastTx);   // what we sent
  oled.setCursor(0, 38);
  oled.print("<");  oled.println(lastRx);   // what came back
  oled.display();
}

void setup() {
  Serial.begin(115200);
  delay(300);
  Serial.println("\n[NeedleBot-S3] starting");

  micBegin();
  Serial.println("[MIC] INMP441 I2S ready");

  Wire.begin(OLED_SDA, OLED_SCL);
  if (oled.begin(SSD1306_SWITCHCAPVCC, OLED_ADDR)) {
    oled.setTextColor(SSD1306_WHITE);
    oled.setTextSize(1);
    drawScreen(0);
    Serial.println("[OLED] SSD1306 ready");
  } else {
    Serial.println("[OLED] not found at 0x3C - check wiring/addr");
  }

  BLEDevice::init(DEVICE_NAME);
  BLEDevice::setMTU(185);  // raise MTU so full responses fit one write (default ~23)
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
    lastTx = line;
    screenDirty = true;
  }

  // Redraw once a second (for the live mic bar) or whenever a message changed.
  static uint32_t last = 0;
  if (screenDirty || millis() - last > 1000) {
    last = millis();
    screenDirty = false;
    uint32_t lvl = micLevel();
    Serial.print("[MIC level]: ");
    Serial.println(lvl);
    drawScreen(lvl);
  }
  delay(10);
}
