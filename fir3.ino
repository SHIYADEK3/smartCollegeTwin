/************************************************************
 * ESP32 SMART PRESENCE SYSTEM
 * + Telegram Night Alert
 * + Manual Light Control
 * + Manual Buzzer Control
 ************************************************************/

#include <WiFi.h>
#include <time.h>
#include <Firebase_ESP_Client.h>
#include <WiFiClientSecure.h>
#include <HTTPClient.h>

#include "addons/TokenHelper.h"
#include "addons/RTDBHelper.h"

// ================= WIFI =================
#define WIFI_SSID "Galaxy A35 5G 6465"
#define WIFI_PASSWORD "22446688"

// ================= FIREBASE =================
#define DATABASE_URL "https://pdr-app-9eed8-default-rtdb.firebaseio.com/"
#define DATABASE_SECRET "kYQ1u68Zz9RkSvIF75QyX4Qbr8LuTJiTGWRR3puo"

// ================= TELEGRAM =================
String botToken = "8022047100:AAG2ShPinKt0k0beQoksJaHwYxufealHd_4";
String chatID   = "2048630385";

// ================= TIME =================
const long gmtOffset_sec = 19800;
const int daylightOffset_sec = 0;

// ================= PINS =================
#define PIR_PIN 26
#define LED_PIN 25
#define BUZZER_PIN 27

// ================= LOGIC =================
unsigned long lastMotionTime = 0;
unsigned long lastCuckooTime = 0;

const unsigned long PRESENCE_LATCH_TIME = 10000;
const unsigned long CUCKOO_ON_TIME  = 150;
const unsigned long CUCKOO_OFF_TIME = 1000;

bool presence = false;
bool cuckooState = false;

// Manual Mode
bool manualMode = false;
bool manualLight = false;
bool manualBuzzer = false;

// Telegram control
bool telegramSent = false;

// Firebase
FirebaseData fbdo;
FirebaseAuth auth;
FirebaseConfig config;


// =================================================
// ================= TELEGRAM FUNCTION =============
// =================================================
void sendTelegram(String message) {

  WiFiClientSecure client;
  client.setInsecure();

  HTTPClient http;

  String url = "https://api.telegram.org/bot" + botToken +
               "/sendMessage?chat_id=" + chatID +
               "&text=" + message;

  http.begin(client, url);
  int httpCode = http.GET();

  if (httpCode > 0) {
    Serial.println("Telegram Alert Sent");
  } else {
    Serial.println("Telegram Failed");
  }

  http.end();
}


// =================================================
// ================= SETUP =========================
// =================================================
void setup() {

  Serial.begin(115200);
  Serial.println("\n===== SYSTEM BOOT =====");

  pinMode(PIR_PIN, INPUT);
  pinMode(LED_PIN, OUTPUT);
  pinMode(BUZZER_PIN, OUTPUT);

  digitalWrite(LED_PIN, LOW);
  digitalWrite(BUZZER_PIN, LOW);

  delay(60000); // PIR warm-up

  WiFi.begin(WIFI_SSID, WIFI_PASSWORD);
  while (WiFi.status() != WL_CONNECTED) {
    delay(500);
    Serial.print(".");
  }

  Serial.println("\nWiFi CONNECTED");

  configTime(gmtOffset_sec, daylightOffset_sec, "pool.ntp.org");
  delay(3000);

  config.database_url = DATABASE_URL;
  config.signer.tokens.legacy_token = DATABASE_SECRET;
  Firebase.begin(&config, &auth);
  Firebase.reconnectWiFi(true);

  Serial.println("Firebase CONNECTED");

  sendTelegram("ESP32 Smart Presence System is ONLINE 🚀");
}


// =================================================
// ========== READ DASHBOARD =======================
// =================================================
void readDashboardControls() {

  String mode;

  if (Firebase.RTDB.getString(&fbdo, "/dashboard/mode")) {
    mode = fbdo.stringData();
    manualMode = (mode == "manual");
  }

  if (manualMode) {

    if (Firebase.RTDB.getBool(&fbdo, "/dashboard/lightControl")) {
      manualLight = fbdo.boolData();
    }

    if (Firebase.RTDB.getBool(&fbdo, "/dashboard/buzzerControl")) {
      manualBuzzer = fbdo.boolData();
    }
  }
}


// =================================================
// ================= LOOP ==========================
// =================================================
void loop() {

  unsigned long now = millis();
  bool pir = digitalRead(PIR_PIN);

  // Presence latch
  if (pir == HIGH) {
    lastMotionTime = now;
    presence = true;
  }

  if (presence && now - lastMotionTime > PRESENCE_LATCH_TIME) {
    presence = false;
  }

  // ===== Time (FULL HH:MM:SS) =====
  struct tm timeinfo;
  getLocalTime(&timeinfo);

  int hourNow = timeinfo.tm_hour;
  int minuteNow = timeinfo.tm_min;
  int secondNow = timeinfo.tm_sec;

  bool isNight = (hourNow >= 13 || hourNow <= 5);

  String currentTime =
    String(hourNow) + ":" +
    String(minuteNow) + ":" +
    String(secondNow);

  // Dashboard read
  readDashboardControls();

  bool autoLightState = presence;
  bool autoBuzzerState = presence && isNight;

  bool lightState;
  bool buzzerState;

 // ===== FINAL DECISION (STABLE AUTO + MANUAL) =====

if (manualMode) {

  // Manual has full priority
  lightState = manualLight;
  buzzerState = manualBuzzer;

} else {

  // AUTO MODE
  if (presence) {
    lightState = true;
  } else {
    lightState = false;
  }

  if (presence && isNight) {
    buzzerState = true;
  } else {
    buzzerState = false;
  }
}
  // LED
  digitalWrite(LED_PIN, lightState ? HIGH : LOW);

  // Buzzer logic
  if (!manualMode && buzzerState) {

    if (!cuckooState && now - lastCuckooTime > CUCKOO_OFF_TIME) {
      digitalWrite(BUZZER_PIN, HIGH);
      cuckooState = true;
      lastCuckooTime = now;
    }
    else if (cuckooState && now - lastCuckooTime > CUCKOO_ON_TIME) {
      digitalWrite(BUZZER_PIN, LOW);
      cuckooState = false;
      lastCuckooTime = now;
    }

    // Telegram alert once per detection
    if (!telegramSent) {
      sendTelegram("🚨 Motion detected after hours at " + currentTime + " in Classroom A");
      telegramSent = true;
    }

  } else {

    digitalWrite(BUZZER_PIN, buzzerState ? HIGH : LOW);
    cuckooState = false;
    telegramSent = false;
  }

  // Firebase mirror
  Firebase.RTDB.setBool(&fbdo, "/iot/motion", presence);
  Firebase.RTDB.setBool(&fbdo, "/iot/light", lightState);
  Firebase.RTDB.setBool(&fbdo, "/iot/buzzer", buzzerState);
  Firebase.RTDB.setString(&fbdo, "/iot/lastUpdate", "ESP32 ONLINE");

  // ===== Debug Output with FULL TIME =====
  Serial.print("Mode: ");
  Serial.print(manualMode ? "MANUAL" : "AUTO");
  Serial.print(" | PIR: ");
  Serial.print(pir ? "MOTION" : "NO MOTION");
  Serial.print(" | Presence: ");
  Serial.print(presence ? "YES" : "NO");
  Serial.print(" | LED: ");
  Serial.print(lightState ? "ON" : "OFF");
  Serial.print(" | Buzzer: ");
  Serial.print(buzzerState ? "ON" : "OFF");
  Serial.print(" | Time: ");
  Serial.println(currentTime);

  delay(100);
}