// ELM327 command layer + OBD-II (SAE J1979 / ISO 15765-4) request engine.
//
// Transport contract (implemented by BLE / Web Serial / simulator):
//   {
//     name: string
//     init(): Promise<void>        — begin I/O (notifications / read loop)
//     onData(fn): void             — fn(line: string), line-split already done
//     onClose(fn): void            — fn() when the link drops
//     sendLine(line): Promise<void>— send one command line (transport appends CRLF)
//     close(): Promise<void>
//   }
import { PIDS, NRC, dtcToHexBytes, hexToDtcCode } from './pids.js';

export class OBDError extends Error {
  constructor(message, { code = null, raw = '' } = {}) {
    super(message);
    this.name = 'OBDError';
    this.code = code;
    this.raw = raw;
  }
}

export function toHex(bytes) {
  return Array.from(bytes, (b) => b.toString(16).padStart(2, '0')).join('');
}

export function hexToBytes(hex) {
  const clean = String(hex).replace(/\s+/g, '').toUpperCase();
  const bytes = new Uint8Array(clean.length >> 1);
  for (let i = 0; i < bytes.length; i++) bytes[i] = parseInt(clean.substr(i * 2, 2), 16);
  return bytes;
}

function bytesToAscii(bytes) {
  return Array.from(bytes, (b) => String.fromCharCode(b)).join('');
}

const AT_TIMEOUT = 5000;
const DATA_TIMEOUT = 4000;

// ATDP report code → protocol name + ATSP command
const ATDP_MAP = {
  1: { name: 'VPW', atsp: 'ATSP1' },
  2: { name: 'PWM', atsp: 'ATSP2' },
  3: { name: 'ISO9141', atsp: 'ATSP0' },
  4: { name: 'CAN413', atsp: 'ATSP3' },
  5: { name: 'CAN250', atsp: 'ATSP4' },
};
const NAME_TO_ATSP = { ISO9141: 'ATSP0', VPW: 'ATSP1', PWM: 'ATSP2', CAN413: 'ATSP3', CAN250: 'ATSP4' };

// Freeze-frame values for mode 05 <DTC> (PID order per SAE J1979).
const FREEZE_TABLE = [
  ['speedKmh', 2, (b) => (b[0] * 256 + b[1]) / 4],
  ['rpm', 2, (b) => (b[0] * 256 + b[1]) / 4],
  ['coolantC', 1, (b) => b[0] - 40],
  ['fuelPct', 1, (b) => (b[0] * 100) / 255],
  ['throttlePct', 1, (b) => (b[0] * 100) / 255],
  ['voltage', 2, (b) => (b[0] * 256 + b[1]) / 256],
  ['maf', 3, (b) => (b[0] * 65536 + b[1] * 256 + b[2]) / 100],
  ['airTempC', 1, (b) => b[0] - 40],
];

export class ELM327 {
  constructor(transport, { log = () => {} } = {}) {
    this.t = transport;
    this.log = log;
    this.version = '';
    this.echo = false;
    this.headers = true;
    this.protocolName = null;
    this.pending = null;
    this._closed = false;

    transport.onData((line) => this._onLine(line));
    if (typeof transport.onClose === 'function') {
      transport.onClose(() => {
        this._closed = true;
        const p = this.pending;
        if (p) {
          this.pending = null;
          p._clear();
          p.fail(new OBDError('Connection closed'));
        }
      });
    }
  }

  get isCAN() {
    return this.protocolName === 'CAN413' || this.protocolName === 'CAN250';
  }

  // ------------------------------------------------------------- line pump

  _onLine(rawLine) {
    const raw = String(rawLine ?? '').trim();
    if (!raw) return;
    const p = this.pending;
    if (!p) {
      this.log(raw, 'recv', 'info');
      return;
    }
    // Local echo (ATL1): the adapter repeats the command back to us.
    if (raw.toUpperCase() === p.cmd.toUpperCase()) return;

    const upper = raw.toUpperCase();
    if (/^READY\b/i.test(upper)) {
      p._clear();
      p.done();
      return;
    }
    if (/^ERROR\b/i.test(upper)) {
      p._clear();
      p.fail(new OBDError(`Adapter error: ${raw}`, { code: 'ADAPTER_ERROR', raw }));
      return;
    }
    if (/^NAK\b/i.test(upper)) {
      p._clear();
      p.fail(new OBDError('NAK — no answer from ECU', { code: 'NAK', raw }));
      return;
    }
    if (/^NO DATA\b/i.test(upper)) {
      p._clear();
      p.fail(new OBDError('NO DATA from adapter', { code: 'NO_DATA', raw }));
      return;
    }
    // A framed line carrying a REQUEST header (7DF1/7D01) can never be an
    // ECU response (responses use 7D81/7EA1 or 7E81) — it is a late local
    // echo or a stray line. Discard instead of corrupting this response.
    if (/^[0-9A-F]{4,26}$/i.test(upper) && (upper.startsWith('7DF1') || upper.startsWith('7D01'))) {
      this.log(raw, 'recv', 'info');
      return;
    }

    p.lines.push(raw);
    this.log(raw, 'recv');
    if (p.match && p.match.test(raw)) {
      p._clear();
      p.done();
    }
  }

  _wait(cmd, { timeout = AT_TIMEOUT, match = null } = {}) {
    if (this.pending) {
      const prev = this.pending;
      this.pending = null;
      prev._clear();
      prev.fail(new OBDError('Overlapped request (previous still pending)'));
    }
    const self = this;
    return new Promise((resolve, reject) => {
      const lines = [];
      let tmo;
      const p = {
        cmd,
        lines,
        match,
        _clear() {
          clearTimeout(tmo);
        },
        done: () => {
          if (self.pending !== p) return;
          self.pending = null;
          resolve(lines);
        },
        fail: (err) => {
          if (self.pending !== p) return;
          self.pending = null;
          reject(err);
        },
      };
      self.pending = p;
      tmo = setTimeout(() => {
        if (self.pending !== p) return;
        self.pending = null;
        // Be lenient: if we collected frames before the (missing) READY, use them.
        if (lines.length) resolve(lines);
        else reject(new OBDError(`Timeout waiting for adapter response (command: ${cmd})`, { code: 'TIMEOUT' }));
      }, timeout);
    });
  }

  async at(cmd, opts = {}) {
    this.log(cmd, 'send');
    const wait = this._wait(cmd, opts);
    try {
      await this.t.sendLine(cmd);
    } catch (err) {
      const p = this.pending;
      this.pending = null;
      if (p) p._clear();
      throw err;
    }
    return wait;
  }

  // ------------------------------------------------------- adapter control

  /** ATZ reset; capture firmware version string. */
  async reset() {
    const lines = await this.at('ATZ', { timeout: 6000, match: /ELM327|OBD|v\d/i });
    this.version = lines.find((l) => /elm327|obd|v\d/i.test(l)) || lines[0] || '';
    // Firmware state after reset:
    this.echo = false;
    this.headers = true;
  }

  /** Recommended baseline: local echo on, headers on, errors off, 8-byte CAN. */
  async setup() {
    await this.at('ATL1');
    this.echo = true;
    await this.at('ATS1');
    this.headers = true;
    await this.at('ATE0');
    try {
      await this.at('ATSH0');
    } catch {
      /* older firmware without ATSH — ignore */
    }
  }

  /** ATDP auto-detect → { code, name, atsp } | null. */
  async detectProtocol() {
    const lines = await this.at('ATDP', { timeout: 8000 });
    const m = lines
      .join(' ')
      .toUpperCase()
      .match(/PROTOCOL 0X([0-5])/);
    if (!m) return null;
    const code = Number(m[1]);
    const info = ATDP_MAP[code];
    return { code, name: info ? info.name : 'UNKNOWN', atsp: info ? info.atsp : null };
  }

  /** Explicit protocol selection (ISO9141 | VPW | PWM | CAN413 | CAN250). */
  async setProtocol(name) {
    const cmd = NAME_TO_ATSP[name];
    if (!cmd) throw new OBDError(`Unknown protocol '${name}'`);
    await this.at(cmd);
    this.protocolName = name;
    return name;
  }

  // ------------------------------------------------------------- framing

  /** Build the adapter data line for a payload (OBD message bytes). */
  framePayload(payload) {
    let hex = toHex(payload).toUpperCase();
    if (this.isCAN) {
      // 10-byte CAN frame: 7DF1 header + 8 data bytes (zero padded)
      hex = (hex + '0000000000000000').slice(0, 16);
      return '7DF1' + hex;
    }
    // 8-byte ISO 9141 / J1850 frame: 7E81 header + 6 data bytes
    hex = (hex + '0000000000').slice(0, 12);
    return '7E81' + hex;
  }

  _frameToPayload(line) {
    const upper = String(line).toUpperCase().trim();
    const lead = upper.slice(0, 4);
    if (['7DF1', '7D01', '7D81', '7E81', '7E01', '7EA1', '4E41'].includes(lead)) {
      return hexToBytes(upper.slice(4));
    }
    return hexToBytes(upper); // raw (headers off)
  }

  /**
   * Send one OBD message, collect (multi-frame) response, strip frames,
   * check for NRC and return the concatenated payload (mode/PID ack byte removed).
   */
  async requestData(payload, { timeout = DATA_TIMEOUT } = {}) {
    const cmd = this.framePayload(payload);
    const lines = await this.at(cmd, { timeout });
    const frames = lines
      .map((l) => l.trim())
      .filter((l) => /^[0-9A-Fa-f]{4,26}$/.test(l))
      .map((l) => this._frameToPayload(l));
    if (!frames.length) {
      throw new OBDError('Adapter returned no data frame', { raw: lines.join(' | ') });
    }

    const first = frames[0];
    if (first[0] === 0x7f) {
      const nrc = first[2];
      const msg = NRC[nrc] ?? `unknown code 0x${nrc.toString(16).padStart(2, '0')}`;
      throw new OBDError(`ECU rejected request (NRC 0x${nrc.toString(16)}: ${msg})`, {
        code: `NRC-${nrc.toString(16)}`,
        raw: toHex(first),
      });
    }

    const total = first.length - 1 + frames.slice(1).reduce((s, f) => s + f.length, 0);
    const data = new Uint8Array(total);
    data.set(first.slice(1), 0);
    let off = first.length - 1;
    for (const f of frames.slice(1)) {
      data.set(f, off);
      off += f.length;
    }
    return data;
  }

  // ------------------------------------------------------------ read APIs

  /** Split a PID list into frames that fit the protocol (ISO-safe = ≤3 data bytes). */
  chunkPids(pids) {
    const limit = this.isCAN ? 4 : 3;
    const chunks = [];
    let cur = [];
    let used = 0;
    for (const pid of pids) {
      const spec = PIDS[pid];
      const b = spec ? spec.bytes : 1;
      if (cur.length && used + b > limit) {
        chunks.push(cur);
        cur = [];
        used = 0;
      }
      cur.push(pid);
      used += b;
    }
    if (cur.length) chunks.push(cur);
    return chunks;
  }

  /** Mode 01: read a batch of PIDs → { [pid]: decodedValue }. */
  readPids(pids) {
    const payload = new Uint8Array([0x01, ...pids]);
    return this.requestData(payload).then((data) => {
      // data (mode-ack byte already stripped) = [<pid echo...>, <values...>]
      let off = pids.length;
      const out = {};
      for (const pid of pids) {
        const spec = PIDS[pid];
        if (!spec) throw new OBDError(`PID 0x${pid.toString(16)} not in table`);
        const raw = data.slice(off, off + spec.bytes);
        off += spec.bytes;
        out[pid] = spec.decode ? spec.decode(raw) : Array.from(raw);
      }
      return out;
    });
  }

  /** Mode 03: read all stored DTCs → ['P0301', ...]. */
  async readDTCs() {
    const data = await this.requestData(new Uint8Array([0x03]));
    const codes = [];
    const seen = new Set();
    for (let i = 0; i + 1 < data.length; i += 2) {
      const hi = data[i];
      const lo = data[i + 1];
      if (hi === 0 && lo === 0) continue; // zero padding
      const code = hexToDtcCode(hi, lo);
      if (!code) continue;
      if (!seen.has(code)) {
        seen.add(code);
        codes.push(code);
      }
    }
    return codes;
  }

  /** Mode 04: clear DTCs (and readiness memory). */
  async clearDTCs() {
    // The 0x44 ack byte is stripped by requestData (and NRCs rejected there),
    // so reaching this point means the ECU accepted the clear.
    await this.requestData(new Uint8Array([0x04]));
    return true;
  }

  /** Mode 0C: readiness group 1 → bitmask. */
  async readReadiness() {
    const data = await this.requestData(new Uint8Array([0x0c]));
    return data[0] ?? 0;
  }

  /** Mode 05 <DTC>: freeze frame snapshot → { speedKmh, rpm, ... }. */
  async readFreezeFrameDtc(code) {
    const [hi, lo] = dtcToHexBytes(code);
    const data = await this.requestData(new Uint8Array([0x05, hi, lo]));
    // data (0x45 ack already stripped) = [hi, lo, <freeze values...>]
    const values = data.slice(2);
    const out = {};
    let off = 0;
    for (const [key, len, dec] of FREEZE_TABLE) {
      if (off + len > values.length) break;
      out[key] = dec(values.slice(off, off + len));
      off += len;
    }
    return out;
  }

  /** Mode 09 49: VIN (17 chars). Falls back to raw ATD if the ECU NRCs. */
  async readVin() {
    try {
      const data = await this.requestData(new Uint8Array([0x09, 0x49]));
      // data = [41, <vin bytes...>]
      const vinBytes = data.slice(1);
      if (vinBytes.length < 17) throw new OBDError('VIN response too short');
      return bytesToAscii(vinBytes.slice(0, 17));
    } catch (err) {
      if (!(err instanceof OBDError)) throw err;
      // Fallback: raw data transfer (no OBD header) — classic ELM327 way.
      const lines = await this.at('ATD0949', { timeout: DATA_TIMEOUT });
      const hex = lines.join('').replace(/\s+/g, '').toUpperCase();
      const m = hex.match(/41([0-9A-F]{34})/);
      if (!m) throw new OBDError(`VIN read failed (adapter: ${lines.join(' ') || 'no response'})`);
      return bytesToAscii(hexToBytes(m[1]));
    }
  }
}
