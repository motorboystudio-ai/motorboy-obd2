# ECU OBD2 — Motorcycle ECU Tool 🏍️

เครื่องมือสำหรับ **ECU มอเตอร์ไซค์** 2 โหมด:

1. **OBD-II (Bluetooth เป็นหลัก)** — ELM327 ผ่าน **Web Bluetooth (BLE)** (หรือ USB Serial) — สำหรับรถที่มี OBD-II port
2. **Bike ECU — Honda K-line** (port ของ [eculib](https://github.com/sirisakboy/eculib) + ROM read/write ที่ eculib ยังไม่มี) — ต่อ FTDI/K-line dongle ตรงเข้า ECU ผ่าน Web Serial @ 10400 baud

มี **Simulator** ทั้งสองโหมด — ทดสอบในเบราว์เซอร์ได้โดยไม่ต้องมีอุปกรณ์จริง
และ **APK** (GitHub Actions auto-build) สำหรับติดตั้งบน Android

## รันโปรเจ็ค

```bash
npm start          # → http://localhost:3000  (เปลี่ยนพอร์: PORT=8080 npm start)
npm test           # 39 automated tests (OBD-II 20 + Honda K-line 19)
```

## ใช้กับ OBD2 Bluetooth 📶

เลือก device = **ELM327 — Bluetooth (BLE)** (ตัวเลือกแรกโดยดีฟอลต์) → Connect → เลือก adapter ในเบราว์เซอร์ → auto-detect protocol (CAN413/CAN250/ISO9141/VPW/PWM) → live data + DTC

- ต้องใช้ **HTTPS** (Web Bluetooth เป็น secure-context API) — Chrome/Edge เท่านั้น
- **Classic Bluetooth (SPP)** ที่พบใน adapter ถูก ๆ ใช้ไม่ได้ในเบราว์เซอร์ — ต้องใช้สาย USB (ตัวเลือก USB Serial)
- บนมือถือ: เปิดแอปนี้ผ่าน Chrome + ต่อ ELM327 BLE + เปิดไฟเครื่อง = ใช้ได้เต็มรูปแบบ

## โหมด Bike ECU (Honda K-line)

JS port ของโปรโตคอล Honda K-line จาก `eculib/honda.py` — message format `mtype + length + data + 8-bit checksum` (two’s complement), response validation ตามกฎ eculib (mtype 1 byte → resp = `mtype & 0x0F`, 2 bytes → เท่ากัน, 3 bytes → `mtype[:2] | 0x10`)

| ฟังก์ชัน | คำสั่ง | หมายเหตุ |
|---|---|---|
| Ping | `FE 72` | ทดสอบว่า ECU ตอบ |
| Diag | `72 00 F0` | ตรวจสถานะ diagnostic |
| Detect state | `72 71 00` + fallback 7d/7b/7e/82 | OK / READ / WRITE / RECOVER… (ตาม state machine ของ eculib) |
| Faults current | `72 74 xx` | รหัสรูปแบบ `19-01` พร้อมคำอธิบายจาก DTC dictionary ของ eculib |
| Faults past | `72 73 xx` | รหัสที่เคยเกิดขึ้น |
| Probe tables | `72 71 xx` | ตารางข้อมูล 12 ตาราง (0x10…0xD1) |
| **ROM read** | `7e 01 06 <addr3>` | ดึง ROM ออกเป็น .bin (chunk 24 bytes/frame, progress + CRC32 + download) |
| **ROM write** | `7e 01 0b <addr3> <data>` | เขียน .bin เข้า ECU (3 bytes/frame ตามรูปแบบ erase frame ของ eculib, auto init-write + **readback verify**) |
| Init recovery | `7b …` | sequences ตาม eculib |
| Init write | `7d …` | sequences ตาม eculib |
| Erase + wait | `7e …` | ⚠ **ลบ ROM** — ใช้กับผู้เชี่ยวชาญเท่านั้น |
| Post-write | `7e 09/0a/0c/0d` | finalize หลังเขียน ROM |

**ROM read/write** = ส่วนที่ eculib ยังไม่มี — address 3 bytes ใช้ byte order เดียวกับ `format_read()` ของ eculib (`[loc>>16, loc&0xff, (loc>>8)&0xff]`), write frame ใช้รูปเดียวกับการ erase ของ eculib (`7e 01 0b addr data`), read sub-command `01 06` เป็น convention ของ port นี้ — **ต้อง verify กับ ECU จริง** (ค่า sub-command/configurable ผ่าน `readSub`/`writeSub`)

> ⚠ 10400 baud: 128 KB ≈ หลายนาที — **อย่าถอดสาย/ดับเครื่องระหว่าง transfer**

**ต่ออุปกรณ์จริง:** เลือก *FTDI / K-line dongle — Serial 10400* → Connect → เลือก port ในเบราว์เซอร์ (ต้องใช้ Chrome/Edge)

> หมายเหตุ: eculib ต้นฉบับใช้ FTDI bit-mode ตรวจสถานะสาย K-line (`kline()`) และ init pulse — Web Serial ทำไม่ได้ จึงใช้การ timeout แทน (ECU off → ตอบ `OFF`) ส่วน init pulse เป็น no-op

## โหมด OBD-II

เหมือนเดิม — live data (RPM gauge สำหรับไบค์: redline ~13,500), DTC (mode 03/04/05/0C), VIN, protocol log; ELM327 ผ่าน BLE หรือ USB Serial, โปรโตคอล auto/CAN413/CAN250/ISO9141/VPW/PWM

## APK + GitHub CI 📦

```
ci/build-apk.yml               workflow (staged — see note below)
android/                       Android WebView wrapper (AGP 8.5, minSdk 26)
```

- CI: JDK 17 + Android SDK + Gradle 8.7 → `assembleDebug` (debug-signed ติดตั้งได้เลย) → upload artifact + **publish GitHub Release** (prerelease `apk-<run>`)
- APK = WebView โหลด web app ที่ bundle ใน assets — **Simulator mode ใช้ได้เต็มรูปแบบใน APK**
- ข้อจำกัด: Android WebView ไม่เปิด Web Bluetooth/Web Serial → โหมด OBD2 Bluetooth / K-line จริงต้องใช้ **Chrome บน Android** (เปิดหน้าเว็บที่ deploy ไว้)

> **⚠ เปิดใช้ CI:** ไฟล์ workflow ถูก staged ไว้ที่ `ci/build-apk.yml` เพราะ GitHub App ที่ใช้ push ไม่มีสิทธิ์แก้ `.github/workflows/`
> → ให้ owner ของ repo **copy ไฟล์นี้ไปไว้ที่ `.github/workflows/build-apk.yml`** (สร้างไฟล์ใหม่ใน GitHub Web UI ได้เลย) หรือ grant สิทธิ์ `workflows` ให้ app แล้ว push commit เลื่อนไฟล์เข้าที่ CI จะ auto-build ทุก push/PR เข้า `main`

## โครงสร้าง

```
server.mjs                     zero-dep static server
ci/build-apk.yml               GitHub Actions workflow (staged — ดู section APK)
android/                       WebView APK wrapper
public/
  index.html                   dashboard (OBD + Bike K-line + ROM)
  css/styles.css
  js/
    honda/
      kline.js                 ★ Honda K-line protocol (port จาก eculib) + DTC dict + ROM read/write
    protocol/
      elm327.js                ELM327 line protocol + OBD framing
      pids.js                  PID table, SAE DTC dict, NRC, readiness
    transport/
      klineSimulator.js        ★ Honda bike ECU จำลอง (raw K-line + ROM 128KB)
      simulator.js             OBD-II bike simulator (600cc 4-cyl)
      serialRaw.js             ★ Web Serial raw bytes @ 10400 (FTDI K-line)
      bluetooth.js             Web Bluetooth (ELM327 BLE)
      serial.js                Web Serial line-based (ELM327 USB)
    ui/
      gauges.js                SVG gauges + tiles
      app.js                   app wiring (OBD + Bike + ROM)
test/
  protocol.test.mjs            OBD-II stack tests (20)
  honda.test.mjs               Honda K-line tests (19, รวม ROM read/write)
```

## จุดที่ต่างจาก eculib (Python) — port แบบ defensive

- `get_faults()` ต้นฉบับ crash ถ้า `send_command` คืน None → เพิ่ม guard
- `do_erase_wait()` ต้นฉบับ loop รันไม่รู้จบถ้า ECU ติด → เพิ่ม max-try guard
- K-line state/echo ตอบรับเป็น **atomic burst** (echo → response) เหมือน adapter จริง
- ความแตกต่างเรื่อง K-line line-state/init pulse ดูด้านบน

## ข้อจำกัด

- Web Bluetooth/Serial รองรับเฉพาะ Chromium (Chrome/Edge) ผ่าน HTTPS
- ROM read sub-command (`01 06`) เป็น convention ของ port นี้ — write frame ใช้รูปของ eculib โดยตรง
- APK (WebView) ไม่ถึง Web Bluetooth/Serial → ใช้โหมดจริงผ่าน Chrome บน Android
- Simulator เป็นค่าประมาณเพื่อ demo/ทดสอบ
