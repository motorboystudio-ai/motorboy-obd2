// Built-in simulator: a virtual car ECU + ELM327 line protocol, so the whole
// app can be exercised in the browser with no hardware.
import { dtcToHexBytes } from '../protocol/pids.js';

const VIN = '5YFBP4GE6R0123456'; // 17 chars

function clamp(v, min, max) {
  return Math.min(max, Math.max(min, v));
}
function lerp(a, b, t) {
  return a + (b - a) * Math.min(1, t);
}
function twoBytes(n) {
  n = Math.max(0, Math.min(65535, Math.round(n)));
  return [(n >> 8) & 0xff, n & 0xff];
}
function threeBytes(n) {
  n = Math.max(0, Math.min(16777215, Math.round(n)));
  return [(n >> 16) & 0xff, (n >> 8) & 0xff, n & 0xff];
}
function toHex(bytes) {
  return Array.from(bytes, (b) => b.toString(16).padStart(2, '0')).join('');
}
/** Encode a signed fuel trim percentage (-100..100) as 2 OBD bytes: (v+100)*1.28. */
function encTrim(v) {
  return twoBytes((v + 100) * 1.28);
}
function hexToBytes(hex) {
  const bytes = new Uint8Array(hex.length >> 1);
  for (let i = 0; i < bytes.length; i++) bytes[i] = parseInt(hex.substr(i * 2, 2), 16);
  return bytes;
}

/** Virtual car: simple but plausible physics + OBD message handling. */
class SimulatedCar {
  constructor() {
    this.ignition = false;
    this.running = false;
    this.startTimer = 0;
    this.time = 0;
    this.throttleSet = 0;
    this.throttleActual = 0;

    this.rpm = 0;
    this.speed = 0; // km/h
    this.coolant = 25;
    this.airTemp = 22;
    this.fuel = 62; // %
    this.voltage = 12.4;
    this.o2 = 0.5;
    this.o2Phase = 0;
    this.maf = 0; // g/s
    this.trimST = 0;
    this.trimLT = 0;

    this.dtcs = new Map(); // 'P0301' → { snapshot, t }
  }

  setIgnition(on) {
    this.ignition = !!on;
    if (!on) this.startTimer = 0;
  }

  setThrottle(v) {
    this.throttleSet = clamp(Number(v) || 0, 0, 1);
  }

  injectDTC(code) {
    const norm = String(code).toUpperCase();
    if (!this.dtcs.has(norm)) {
      this.dtcs.set(norm, { snapshot: this._snapshot(), t: this.time });
    }
  }

  clearDTCs() {
    this.dtcs.clear();
  }

  _snapshot() {
    return {
      rpm: this.rpm,
      speed: this.speed,
      coolant: this.coolant,
      fuel: this.fuel,
      throttle: this.throttleSet,
      voltage: this.voltage,
      maf: this.maf,
      airTemp: this.airTemp,
      t: this.time,
    };
  }

  tick(dt) {
    this.time += dt;

    if (this.ignition) {
      if (!this.running) {
        this.startTimer += dt;
        if (this.startTimer > 0.8) this.running = true; // cranking delay
      }
    } else {
      this.running = false;
    }

    if (this.running) {
      // Virtual 600cc 4-cyl motorcycle engine: idle ~1300, redline ~14,500.
      const targetRpm = 1300 + Math.pow(this.throttleSet, 1.2) * 12700;
      this.rpm += (targetRpm - this.rpm) * Math.min(1, dt * 1.8) + (Math.random() - 0.5) * 12;
      this.rpm = clamp(this.rpm, 1250, 14800);

      const targetSpeed = Math.max(0, this.rpm - 1300) * 0.19;
      this.speed += (targetSpeed - this.speed) * Math.min(1, dt * 0.9);
      if (this.speed < 0.4 && this.throttleSet < 0.05) this.speed = 0;

      const targetCoolant = 88 + (this.rpm / 14800) * 8;
      this.coolant += (targetCoolant - this.coolant) * Math.min(1, dt / 120);

      this.fuel = Math.max(3, this.fuel - dt * (0.002 + (this.rpm / 14800) * 0.02));

      this.o2Phase += dt * (0.8 + this.throttleSet * 2.5);
      this.o2 = this.coolant > 70 ? 0.5 + 0.4 * Math.sign(Math.sin(this.o2Phase * Math.PI)) : 0.45;

      this.maf = Math.max(0, 2.5 + this.throttleSet * 34 + (this.rpm / 14800) * 30 + (Math.random() - 0.5) * 0.4);
      this.trimST = Math.sin(this.time * 0.2) * 1.2 + this.throttleSet * 2;
      this.trimLT = Math.sin(this.time * 0.01) * 0.8;
      this.voltage = 14.35 + Math.sin(this.time * 0.3) * 0.08;
      this.airTemp = 22 + this.throttleSet * 14 + Math.sin(this.time * 0.1) * 0.5;
      this.throttleActual = clamp(this.throttleSet + (Math.random() - 0.5) * 0.004, 0, 1);
    } else {
      this.rpm = lerp(this.rpm, 0, dt * 2);
      this.speed = lerp(this.speed, 0, dt * 0.5);
      this.maf = lerp(this.maf, 0, dt * 2);
      this.trimST = lerp(this.trimST, 0, dt);
      this.trimLT = lerp(this.trimLT, 0, dt);
      this.o2 = 0.5;
      this.voltage = lerp(this.voltage, 12.4, dt * 0.5);
      this.throttleActual = 0;
      this.coolant += (25 - this.coolant) * Math.min(1, dt / 300);
    }
  }

  /** Encode one PID value (mode 01) or null if unsupported. */
  _pidValue(pid) {
    const r = this.running;
    switch (pid) {
      case 0x01: {
        let b = 0;
        if (this.dtcs.size) b |= 0x01; // MIL on
        if (r) b |= 0x02 | 0x08; // running + PFI
        if (r && this.coolant > 70) b |= 0x04; // closed loop
        if (r && this.coolant > 55) b |= 0x40; // warm
        return [b];
      }
      case 0x02:
      case 0x0d:
        return [clamp(this.coolant + 40, 0, 255)];
      case 0x04:
        return encTrim(clamp(this.trimST, -100, 100));
      case 0x05:
        return encTrim(clamp(this.trimLT, -100, 100));
      case 0x06:
        return twoBytes(420);
      case 0x07:
        return twoBytes((this.rpm + 4) * 4);
      case 0x0c:
        return twoBytes(this.rpm * 4);
      case 0x08:
        return twoBytes(this.speed * 4);
      case 0x0b:
        return [r ? Math.round(30 + this.throttleActual * 80) : 25];
      case 0x0e:
        return [Math.round(this.throttleActual * 255)];
      case 0x11:
      case 0x13:
        return twoBytes(this.o2 * 320);
      case 0x24:
        return [Math.round(this.fuel * 2.55)];
      case 0x25:
        return [clamp(this.airTemp + 40, 0, 255)];
      case 0x29:
        return [100];
      case 0x2f:
        return twoBytes(this.voltage * 256);
      case 0x42:
        return threeBytes(this.maf * 100);
      default:
        return null;
    }
  }

  /**
   * Handle an OBD payload (header stripped) → { frames: [Uint8Array] }
   * Frames are already chunked to the bus limit (8 CAN / 6 ISO data bytes).
   */
  respond(payload, { frameLimit = 8 } = {}) {
    const mode = payload[0];
    switch (mode) {
      case 0x01:
        return this._mode01(payload.slice(1));
      case 0x02:
        return this._nrc(payload[1] ?? 0x21, 0x31);
      case 0x03: {
        if (!this.dtcs.size) return this._frames([new Uint8Array([0x43, 0x00])]);
        const data = [0x43];
        for (const code of this.dtcs.keys()) data.push(...dtcToHexBytes(code));
        return this._frames(new Uint8Array(data));
      }
      case 0x04:
        this.dtcs.clear();
        return this._frames([new Uint8Array([0x44])]);
      case 0x05:
        return this._mode05(payload.slice(1));
      case 0x09: {
        if (payload[1] === 0x49) {
          const vinHex = Array.from(VIN, (c) => c.charCodeAt(0));
          return this._frames([new Uint8Array([0x49, 0x41, ...vinHex])]);
        }
        return this._nrc(payload[1] ?? 0x49, 0x31);
      }
      case 0x0c: {
        const misfire = [...this.dtcs.keys()].some((c) => /^P03\d\d$/.test(c));
        return this._frames([new Uint8Array([0x4c, misfire ? 0x01 : 0x00])]);
      }
      default:
        return this._nrc(payload[1] ?? 0x00, 0x11);
    }
  }

  _mode01(pids) {
    // Strip trailing zero padding (there is no PID 0x00).
    let end = pids.length;
    while (end > 0 && pids[end - 1] === 0x00) end--;
    if (end === 0) return this._nrc(0x01, 0x31);
    const real = pids.slice(0, end);
    const out = [0x41, ...real];
    for (const pid of real) {
      const bytes = this._pidValue(pid);
      if (!bytes) return this._nrc(0x01, 0x31);
      out.push(...bytes);
    }
    return this._frames([new Uint8Array(out)]);
  }

  _mode05(args) {
    // Per-PID sub-function bytes (PID | 0x20). Only these exact bytes are
    // sub-functions — any other 0x2x-0x5x byte is a DTC query (P-codes
    // share the 0x2x range, so a bitwise test would misinterpret DTCs).
    const FREEZE_SUB = new Set([0x2c, 0x2d, 0x2e, 0x2f, 0x30, 0x31, 0x62, 0x64]);
    if (FREEZE_SUB.has(args[0])) {
      // 05 2x <DTC hi> <DTC lo> — sub byte = PID | 0x20
      const sub = args[0];
      const pid = sub === 0x62 ? 0x42 : sub === 0x64 ? 0x44 : sub; // 0x2C-0x2F & 0x30/0x31 pass through
      const code = this._dtcName(args[1], args[2]);
      const rec = this.dtcs.get(code);
      if (!rec) return this._nrc(0x05, 0x31);
      const s = rec.snapshot;
      const val =
        pid === 0x42 ? threeBytes(s.maf * 100) :
        pid === 0x2d ? twoBytes(s.rpm * 4) :
        pid === 0x2c ? twoBytes(s.speed * 4) :
        pid === 0x2e ? [clamp(s.coolant + 40, 0, 255)] :
        pid === 0x2f ? [Math.round(s.fuel * 2.55)] :
        pid === 0x30 ? [Math.round(s.throttle * 255)] :
        pid === 0x31 ? twoBytes(s.voltage * 256) :
        pid === 0x44 ? [clamp(s.airTemp + 40, 0, 255)] :
        null;
      if (!val) return this._nrc(0x05, 0x31);
      return this._frames([new Uint8Array([0x45, sub, ...val])]);
    }
    const code = this._dtcName(args[0], args[1]);
    const rec = this.dtcs.get(code);
    if (!code || !rec) return this._nrc(0x05, 0x31);
    const s = rec.snapshot;
    const data = [0x45, args[0], args[1]];
    data.push(...twoBytes(s.speed * 4)); // 2C speed
    data.push(...twoBytes(s.rpm * 4)); // 2D rpm
    data.push(clamp(s.coolant + 40, 0, 255)); // 2E coolant
    data.push(Math.round(s.fuel * 2.55)); // 2F fuel
    data.push(Math.round(s.throttle * 255)); // 30 throttle
    data.push(...twoBytes(s.voltage * 256)); // 31 voltage
    data.push(...threeBytes(s.maf * 100)); // 42 MAF
    data.push(clamp(s.airTemp + 40, 0, 255)); // 44 intake air temp
    return this._frames(new Uint8Array(data));
  }

  _dtcName(hi, lo) {
    const value = (hi << 8) | lo;
    const type = { 2: 'P', 3: 'C', 4: 'B', 5: 'U' }[value >> 12];
    return type ? `${type}${String(value & 0x0fff).padStart(4, '0')}` : null;
  }

  _nrc(pid, nrc) {
    return this._frames([new Uint8Array([0x7f, pid, nrc])]);
  }

  _frames(dataOrList) {
    const data = Array.isArray(dataOrList) ? dataOrList[0] : dataOrList;
    const frames = [];
    for (let i = 0; i < data.length; i += this._frameLimit) {
      frames.push(data.slice(i, i + this._frameLimit));
    }
    if (!frames.length) frames.push(new Uint8Array([0x7f, 0x00, 0x11]));
    return { frames };
  }
}

/**
 * Create a simulator transport speaking the ELM327 line protocol.
 * Returns { transport, car }.
 */
export function createSimulatorTransport({ log = () => {} } = {}) {
  const car = new SimulatedCar();
  const state = { echo: false, headers: true, protocol: 4 /* CAN 413 */ };
  let onDataCb = null;
  let closed = false;

  const timer = setInterval(() => {
    if (!closed) car.tick(0.05);
  }, 50);

  const send = (lines) => {
    const delay = 8 + Math.random() * 30;
    setTimeout(() => {
      if (closed || !onDataCb) return;
      for (const l of lines) onDataCb(l);
    }, delay);
  };

  const atspCodes = { ATSP0: 3, ATSP1: 1, ATSP2: 2, ATSP3: 4, ATSP4: 5 };

  const atEcho = (cmd, ...extra) => {
    const out = state.echo ? [cmd] : [];
    out.push(...extra, 'READY');
    send(out);
  };

  const isCAN = () => state.protocol === 4 || state.protocol === 5;

  const handlePayload = (payload, raw = false, echoLine = null) => {
    const limit = isCAN() ? 8 : 6;
    car._frameLimit = limit;
    const { frames } = car.respond(payload, { frameLimit: limit });
    // Echo + response + READY must be one atomic burst (like a real adapter),
    // otherwise a late echo can land in the next command's response window.
    const out = [];
    if (echoLine) out.push(echoLine);
    for (const f of frames) {
      if (raw) {
        out.push(toHex(f).toUpperCase()); // ATD: raw bytes, no OBD header
        continue;
      }
      let hex = toHex(f).toUpperCase();
      if (isCAN()) hex = (hex + '0000000000000000').slice(0, 16);
      else hex = (hex + '0000000000').slice(0, 12);
      out.push((isCAN() ? '7D81' : '7E81') + hex);
    }
    out.push('READY');
    send(out);
  };

  const handleLine = (cmd) => {
    const upper = cmd.toUpperCase();

    if (upper === 'ATZ') {
      state.echo = false;
      state.headers = true;
      send(['ELM327 v2.3 (ecu-obd2 simulator)']);
      return;
    }
    if (/^ATL[01]$/.test(upper)) {
      state.echo = upper === 'ATL1';
      atEcho(upper);
      return;
    }
    if (upper === 'ATE0' || upper === 'ATE1') {
      atEcho(upper);
      return;
    }
    if (upper === 'ATSH0' || upper === 'ATSH1') {
      atEcho(upper);
      return;
    }
    if (/^ATS[01]$/.test(upper)) {
      state.headers = upper === 'ATS1';
      atEcho(upper);
      return;
    }
    if (upper in atspCodes) {
      state.protocol = atspCodes[upper];
      atEcho(upper);
      return;
    }
    if (upper === 'ATDP') {
      atEcho(upper, `OBDII PROTOCOL 0X${state.protocol}`);
      return;
    }
    if (/^ATD([0-9A-F]+)$/.test(upper)) {
      handlePayload(hexToBytes(upper.slice(3).toLowerCase()), true, state.echo ? cmd : null);
      return;
    }
    if (/^[0-9A-F]{14,24}$/.test(upper)) {
      handlePayload(hexToBytes(upper.slice(4).toLowerCase()), false, state.echo ? cmd : null);
      return;
    }
    send(state.echo ? [cmd, 'ERROR'] : ['ERROR']);
  };

  const transport = {
    name: 'Simulator',
    car,
    init: async () => {},
    onData: (cb) => {
      onDataCb = cb;
    },
    onClose: () => {},
    sendLine: async (line) => {
      if (closed) throw new Error('Simulator closed');
      const cmd = String(line).trim();
      if (cmd) handleLine(cmd);
    },
    close: async () => {
      closed = true;
      clearInterval(timer);
    },
  };
  return { transport, car };
}
