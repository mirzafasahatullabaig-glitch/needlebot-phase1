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
#include <WiFi.h>

// Wi-Fi for the audio stream. NOTE: inlined here for single-file upload — do
// NOT commit real credentials; put your real network name/password only to
// flash, then blank them before pushing (this file is tracked in git).
#define WIFI_SSID  "YOUR_WIFI_SSID"
#define WIFI_PASS  "YOUR_WIFI_PASSWORD"

// --- INMP441 I2S mic (SD=GPIO6, WS=GPIO5, SCK=GPIO4, L/R=GND => left slot) ---
#define MIC_SD   6
#define MIC_WS   5
#define MIC_SCK  4
#define MIC_PORT I2S_NUM_0
#define MIC_SR   16000   // 16 kHz is plenty for speech

// --- Push button = push-to-talk. Wire button between this GPIO and GND;
// internal pull-up makes it HIGH when released, LOW while pressed.
// GPIO10 (moved off GPIO1, a boot/strapping-adjacent pin that flickered). ---
#define BTN_PIN  10
#define BTN_PRESSED (digitalRead(BTN_PIN) == LOW)

// --- Wi-Fi audio stream: raw 16-bit mono PCM @ 16 kHz on this TCP port while
// pad 1 is held. A client (phone, or a PC test script) connects and reads it. ---
#define AUDIO_PORT 7001
static WiFiServer audioServer(AUDIO_PORT);
static WiFiClient audioClient;

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

static void toast(const char *msg);   // defined below, used by the callbacks

class ServerCallbacks : public BLEServerCallbacks {
  void onConnect(BLEServer *server) override {
    deviceConnected = true;
    Serial.println("[BLE] Client connected");
    toast("phone linked");
  }
  void onDisconnect(BLEServer *server) override {
    deviceConnected = false;
    Serial.println("[BLE] Client disconnected, re-advertising");
    server->startAdvertising();  // auto-restart advertising on disconnect
    toast("phone lost");
  }
};

// Pull the string value out of a one-key JSON object {"key":"...."} the app
// sends. Minimal (no JSON lib): finds "key":" then copies to the next unescaped
// quote. Returns "" if the key isn't present. Good enough for our own payloads.
static String jsonStrField(const String &s, const char *key) {
  String needle = String("\"") + key + "\":\"";
  int a = s.indexOf(needle);
  if (a < 0) return "";
  a += needle.length();
  String out;
  for (int i = a; i < (int)s.length(); i++) {
    char c = s[i];
    if (c == '\\' && i + 1 < (int)s.length()) { out += s[++i]; continue; }  // keep escaped char
    if (c == '"') break;
    out += c;
  }
  return out;
}

class WriteCallbacks : public BLECharacteristicCallbacks {
  void onWrite(BLECharacteristic *chr) override {
    String value = chr->getValue();  // Arduino-ESP32 3.x returns String
    if (value.length() == 0) return;
    // Phone-notification mirror: {"notif":"App: text"} -> toast it, don't treat
    // it as an AI reply. Anything else is a reply (plain text, back-compatible).
    String n = jsonStrField(value, "notif");
    if (n.length()) {
      Serial.print("[NOTIF]: ");
      Serial.println(n);
      toast(n.c_str());
      return;
    }
    Serial.print("[RX AI Response]: ");
    Serial.println(value);
    lastRx = value;
    toast("reply in");
    screenDirty = true;
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

// Voice level of one DMA block. The INMP441 has a big, slowly wandering DC
// offset, so subtract this block's mean first. Speech shows up as the peak
// deviation within the block, not the average, so return the max — that moves
// clearly on "hello" while a quiet block stays low. All per-block (no state
// carried across calls, so nothing can diverge) and integer-only.
static uint32_t micLevel() {
  int32_t buf[256];
  size_t nbytes = 0;
  i2s_read(MIC_PORT, buf, sizeof(buf), &nbytes, portMAX_DELAY);
  size_t n = nbytes / sizeof(int32_t);
  if (n == 0) return 0;
  int64_t mean = 0;
  for (size_t i = 0; i < n; i++) mean += buf[i] >> 8;  // 24-bit in a 32-bit slot
  mean /= (int64_t)n;
  int64_t peak = 0;
  for (size_t i = 0; i < n; i++) {
    int64_t d = (buf[i] >> 8) - mean;
    if (d < 0) d = -d;
    if (d > peak) peak = d;
  }
  return (uint32_t)peak;
}

// Stream mic audio to the TCP client as raw 16-bit mono PCM @ 16 kHz while the
// button is held. The INMP441's 24-bit sample (buf[i] >> 8) carries a big,
// wandering DC offset, so we subtract the block mean first (same as micLevel)
// then scale the AC part down to int16. Scaled so ~8M speech peaks land near
// half-scale (~16k), leaving headroom instead of slamming the clip rail: a
// railed square wave is what made Vosk hear only noise and return "".
// shortcut: fixed MIC_SHIFT tuned to observed 1M-8M peaks; if speech is too
// quiet lower the shift, if it clips raise it.
#define MIC_SHIFT 9   // divide the DC-removed 24-bit value by 512

// --- Toast notifications: a short message that pops over the bottom of the
// screen then fades back to the face after a couple of seconds. toast() just
// sets state; drawFace() renders it. Non-blocking so BLE/Wi-Fi keep running. ---
static String toastMsg = "";
static uint32_t toastUntil = 0;   // millis() when the toast expires
static void toast(const char *msg) {
  toastMsg = msg;
  toastUntil = millis() + 2200;
  screenDirty = true;
}

// --- Idle robot face (echoes the app's OrbView). Two eyes that blink, an
// antenna that pulses when connected, and a mouth. Status dots + mic bar sit
// under it; the last reply scrolls along the bottom unless a toast is up. ---
static uint32_t lastBlink = 0;
static bool blinking = false;

// Draw one eye: open = filled circle, blinking = a flat line.
static void drawEye(int cx, int cy, bool open) {
  if (open) oled.fillCircle(cx, cy, 6, SSD1306_WHITE);
  else      oled.drawFastHLine(cx - 6, cy, 13, SSD1306_WHITE);
}

static void drawFace(uint32_t micLvl) {
  oled.clearDisplay();

  // Blink: shut for ~120ms roughly every 3s (cheap, time-based, no RNG state).
  uint32_t now = millis();
  if (!blinking && now - lastBlink > 3000) { blinking = true;  lastBlink = now; }
  if (blinking  && now - lastBlink > 120)  { blinking = false; lastBlink = now; }

  // Antenna: dot on a stalk, filled (pulsing) only while BLE is connected.
  oled.drawFastVLine(64, 2, 6, SSD1306_WHITE);
  bool pulse = deviceConnected && ((now / 400) & 1);
  if (pulse) oled.fillCircle(64, 2, 2, SSD1306_WHITE);
  else       oled.drawCircle(64, 2, 2, SSD1306_WHITE);

  // Head outline + eyes + mouth.
  oled.drawRoundRect(40, 10, 48, 34, 6, SSD1306_WHITE);
  drawEye(54, 24, !blinking);
  drawEye(74, 24, !blinking);
  // Mouth widens with mic level so the face "reacts" to sound.
  int mw = 6 + (int)(micLvl / 2000);
  if (mw > 20) mw = 20;
  oled.drawFastHLine(64 - mw / 2, 37, mw, SSD1306_WHITE);

  // Status dots, top-left: BLE then Wi-Fi (filled = up, hollow = down).
  if (deviceConnected) oled.fillCircle(4, 4, 3, SSD1306_WHITE);
  else                 oled.drawCircle(4, 4, 3, SSD1306_WHITE);
  if (WiFi.status() == WL_CONNECTED) oled.fillCircle(4, 14, 3, SSD1306_WHITE);
  else                               oled.drawCircle(4, 14, 3, SSD1306_WHITE);

  // Mic bar up the right edge (vertical VU), 0..~40000 -> 0..40px.
  int h = micLvl / 1000;
  if (h > 40) h = 40;
  oled.fillRect(122, 44 - h, 4, h, SSD1306_WHITE);

  // Bottom strip: a toast if one is live, else the last reply text.
  if (toastMsg.length() && (int32_t)(toastUntil - now) > 0) {
    oled.drawRoundRect(0, 48, 128, 16, 3, SSD1306_WHITE);
    oled.setCursor(4, 52);
    oled.print(toastMsg.substring(0, 20));   // ~20 chars fit in the box at 6px/char
  } else {
    toastMsg = "";                       // expired: clear so idle text shows
    oled.setCursor(0, 52);
    if (lastRx.length()) { oled.print("< "); oled.print(lastRx); }
    else                   oled.print("ready");
  }
  oled.display();
}

// LISTENING screen: a scrolling CRO-style waveform driven by live mic peaks,
// echoing the app's ScopeView. `lvl` is this block's peak (0..~40000); we push
// it as one column and scroll left. Keeps its own trace buffer (per-call, reset
// each capture by streamAudioWhileHeld calling scopeReset()).
static uint8_t scope[128];
static void scopeReset() { for (int i = 0; i < 128; i++) scope[i] = 0; }
static void drawScope(uint32_t lvl) {
  for (int i = 0; i < 127; i++) scope[i] = scope[i + 1];   // scroll left
  int h = lvl / 1200;                                      // 0..~33
  if (h > 33) h = 33;
  scope[127] = (uint8_t)h;

  oled.clearDisplay();
  oled.setTextSize(1);
  oled.setCursor(0, 0);
  oled.print("LISTENING");
  oled.drawFastHLine(0, 11, 128, SSD1306_WHITE);
  // Mirror each column around the centre line for a scope look.
  int mid = 40;
  for (int i = 0; i < 128; i++) {
    int h2 = scope[i];
    oled.drawFastVLine(i, mid - h2, 2 * h2 + 1, SSD1306_WHITE);
  }
  oled.display();
}

// True while the button is held, tolerating contact bounce: only reports
// "released" after the pin reads HIGH continuously for ~60 ms. A momentary
// flicker mid-hold no longer ends the capture.
static bool buttonHeld() {
  if (BTN_PRESSED) return true;
  uint32_t t0 = millis();
  while (millis() - t0 < 60) {
    if (BTN_PRESSED) return true;   // bounced back down -> still held
    delay(5);
  }
  return false;                      // HIGH for 60 ms straight -> truly released
}

static void streamAudioWhileHeld() {
  Serial.println("[AUDIO] streaming (button held)");
  scopeReset();
  int32_t buf[256];
  int16_t pcm[256];
  size_t totalBytes = 0;
  uint32_t lastScope = 0;
  while (buttonHeld() && audioClient.connected()) {
    size_t nbytes = 0;
    i2s_read(MIC_PORT, buf, sizeof(buf), &nbytes, portMAX_DELAY);
    size_t n = nbytes / sizeof(int32_t);
    int64_t mean = 0;                        // remove this block's DC offset
    for (size_t i = 0; i < n; i++) mean += buf[i] >> 8;
    if (n) mean /= (int64_t)n;
    int32_t peak = 0;                        // block peak -> scope column
    for (size_t i = 0; i < n; i++) {
      int32_t ac = (int32_t)((buf[i] >> 8) - mean);
      int32_t s = ac >> MIC_SHIFT;
      if (s > 32767) s = 32767;              // saturate, don't wrap
      else if (s < -32768) s = -32768;
      pcm[i] = (int16_t)s;
      int32_t a = ac < 0 ? -ac : ac;
      if (a > peak) peak = a;
    }
    if (audioClient.write((uint8_t *)pcm, n * sizeof(int16_t)) == 0) break;  // client gone
    totalBytes += n * sizeof(int16_t);
    // Redraw the scope ~every 40ms (not every DMA block) so SPI I/O to the OLED
    // doesn't starve the audio read and cause gaps/underruns in the stream.
    if (millis() - lastScope > 40) { lastScope = millis(); drawScope((uint32_t)peak); }
  }
  audioClient.flush();
  audioClient.stop();                        // clean end-of-stream for the reader
  Serial.printf("[AUDIO] stopped (released), sent %u bytes\n", (unsigned)totalBytes);
  toast("sent");
  screenDirty = true;                        // force the normal screen back
}

// The idle screen is the robot face (defined above). Kept as drawScreen so the
// two existing call sites (setup, loop) don't change.
static void drawScreen(uint32_t micLvl) { drawFace(micLvl); }

void setup() {
  Serial.begin(115200);
  delay(300);
  Serial.println("\n[NeedleBot-S3] starting");

  micBegin();
  Serial.println("[MIC] INMP441 I2S ready");

  pinMode(BTN_PIN, INPUT_PULLUP);   // button to GND: HIGH released, LOW pressed

  WiFi.mode(WIFI_STA);
  WiFi.begin(WIFI_SSID, WIFI_PASS);
  Serial.print("[WiFi] connecting");
  for (int i = 0; i < 40 && WiFi.status() != WL_CONNECTED; i++) { delay(250); Serial.print("."); }
  if (WiFi.status() == WL_CONNECTED) {
    Serial.printf("\n[WiFi] connected, IP %s  audio port %d\n",
                  WiFi.localIP().toString().c_str(), AUDIO_PORT);
    audioServer.begin();
  } else {
    Serial.println("\n[WiFi] FAILED - audio stream disabled, BLE text still works");
  }

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

  // Push-to-talk: hold the button to stream mic audio over Wi-Fi to the phone.
  // Press -> BLE wake tells the app to open the audio socket -> we wait briefly
  // for it to connect, then stream until release (walkie-talkie).
  static bool waked = false;
  if (BTN_PRESSED && WiFi.status() == WL_CONNECTED) {
    if (!waked) {
      if (deviceConnected) {
        notifyChar->setValue("{\"wake\":1}");
        notifyChar->notify();
        Serial.println("[WAKE] sent (button pressed)");
      } else {
        Serial.println("[WAKE] button pressed but BLE not connected - app can't be told");
      }
      waked = true;
      // Give the phone up to ~2s to receive the wake and open the TCP socket.
      // Don't abort on a button bounce; only a debounced release stops us.
      uint32_t t0 = millis();
      while (buttonHeld() && millis() - t0 < 2000 &&
             (!audioClient || !audioClient.connected())) {
        audioClient = audioServer.available();
        delay(20);
      }
    }
    if (audioClient && audioClient.connected()) {
      streamAudioWhileHeld();   // blocks until debounced release
      waked = false;
      return;
    } else if (!buttonHeld()) {
      Serial.println("[AUDIO] released before phone connected (or no client)");
      waked = false;
    } else {
      Serial.println("[AUDIO] no client connected - phone on same WiFi + app open + BLE connected?");
      toast("no audio link");
    }
  } else if (!buttonHeld()) {
    waked = false;              // button truly released -> re-arm for next press
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
