// Honda motorcycle ECU — K-line protocol (JS port of sirisakboy/eculib, honda.py).
//
// Raw byte-stream K-line @ 10.4 kbps (no KWP2000 headers — Honda's variant is a
// plain framed stream: mtype + length + data + 8-bit checksum).
//
// Transport contract (raw bytes):
//   {
//     init(): Promise<void>
//     write(bytes): Promise<void>
//     readUntil(n, timeoutMs): Promise<Uint8Array>  — up to n bytes, may be short
//     drain(): void                                  — discard buffered bytes
//     close(): Promise<void>
//     onClose(cb): void (optional)
//   }

export const ECUSTATE = {
  UNDEFINED: -1,
  OFF: 0,
  READ: 1,
  READING: 2,
  OK: 3,
  RECOVER_OLD: 4,
  RECOVER_NEW: 5,
  WRITE: 6,
  WRITING: 7,
  ERASING: 8,
  INIT_WRITE: 9,
  INIT_RECOVER: 10,
  ERROR: 11,
  UNKNOWN: 12,
};

export const ECUSTATE_NAMES = {
  [-1]: 'UNDEFINED',
  0: 'OFF (ignition off)',
  1: 'READ',
  2: 'READING',
  3: 'OK (normal)',
  4: 'RECOVER OLD',
  5: 'RECOVER NEW',
  6: 'WRITE',
  7: 'WRITING',
  8: 'ERASING',
  9: 'INIT WRITE',
  10: 'INIT RECOVER',
  11: 'ERROR',
  12: 'UNKNOWN',
};

// Honda DTC dictionary (ported from eculib/honda.py)
export const HONDA_DTC = {
  '01-01': 'MAP sensor circuit low voltage',
  '01-02': 'MAP sensor circuit high voltage',
  '02-01': 'MAP sensor performance problem',
  '07-01': 'ECT sensor circuit low voltage',
  '07-02': 'ECT sensor circuit high voltage',
  '08-01': 'TP sensor circuit low voltage',
  '08-02': 'TP sensor circuit high voltage',
  '09-01': 'IAT sensor circuit low voltage',
  '09-02': 'IAT sensor circuit high voltage',
  '11-01': 'VS sensor no signal',
  '12-01': 'No.1 primary injector circuit malfunction',
  '13-01': 'No.2 primary injector circuit malfunction',
  '14-01': 'No.3 primary injector circuit malfunction',
  '15-01': 'No.4 primary injector circuit malfunction',
  '16-01': 'No.1 secondary injector circuit malfunction',
  '17-01': 'No.2 secondary injector circuit malfunction',
  '18-01': 'CMP sensor no signal',
  '19-01': 'CKP sensor no signal',
  '21-01': 'O2 sensor malfunction',
  '23-01': 'O2 sensor heater malfunction',
  '25-02': 'Knock sensor circuit malfunction',
  '25-03': 'Knock sensor circuit malfunction',
  '29-01': 'IACV circuit malfunction',
  '33-02': 'ECM EEPROM malfunction',
  '34-01': 'ECV POT low voltage malfunction',
  '34-02': 'ECV POT high voltage malfunction',
  '35-01': 'EGCA malfunction',
  '48-01': 'No.3 secondary injector circuit malfunction',
  '49-01': 'No.4 secondary injector circuit malfunction',
  '51-01': 'HESD linear solenoid malfunction',
  '54-01': 'Bank angle sensor circuit low voltage',
  '54-02': 'Bank angle sensor circuit high voltage',
  '56-01': 'Knock sensor IC malfunction',
  '86-01': 'Serial communication malfunction',
};

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

/**
 * Encode a 24-bit flash address in the Honda byte order used by eculib's
 * format_read: [loc>>16, loc&0xff, (loc>>8)&0xff] (low two bytes swapped).
 */
export function formatReadAddress(location) {
  if (!Number.isInteger(location) || location < 0 || location > 0x00ffffff) {
    throw new Error(`ROM address out of range: ${location}`);
  }
  return [(location >> 16) & 0xff, location & 0xff, (location >> 8) & 0xff];
}

/** Inverse of formatReadAddress. */
export function addressFromBytes([a0, a1, a2]) {
  return (a0 << 16) | (a2 << 8) | a1;
}

export function toHexStr(bytes) {
  return Array.from(bytes, (b) => b.toString(16).padStart(2, '0')).join(' ');
}

/** Honda 8-bit checksum: two's complement of the byte sum. */
export function checksum8bitHonda(data) {
  let sum = 0;
  for (const b of data) sum += b;
  return ((sum ^ 0xff) + 1) & 0xff;
}

/** Build a Honda K-line message: mtype + length + data + checksum. */
export function formatMessage(mtype, data = []) {
  const ml = mtype.length;
  const dl = data.length;
  const msgsize = 0x02 + ml + dl;
  const msg = [...mtype, msgsize, ...data];
  msg.push(checksum8bitHonda(msg));
  if (msg[ml] !== msg.length) throw new Error('formatMessage: length mismatch');
  return { msg: Uint8Array.from(msg), ml, dl };
}

export class HondaECU {
  /**
   * @param {object} transport raw-byte K-line transport
   * @param {object} opts { log(msg, kind), kline(): boolean|null (line state, if HW supports) }
   */
  constructor(transport, { log = () => {}, kline = null } = {}) {
    this.t = transport;
    this.log = log;
    this.klineFn = kline; // null = not available (generic UART)
  }

  /** K-line level state if the hardware exposes it (FTDI SPU in eculib). */
  kline() {
    if (typeof this.klineFn === 'function') return this.klineFn();
    if (typeof this.t.kline === 'function') return this.t.kline();
    return true; // unknown — assume line powered
  }

  /** K-line master initialization pulses (FTDI bit-mode in eculib — no-op on generic UART). */
  async init() {
    this.log('K-line init pulse (skipped on generic UART transport)', 'info');
    await sleep(50);
    return true;
  }

  /**
   * Low-level: write a framed message, consume the ECU echo, read the full
   * response (mtype + length + data + checksum). Returns Uint8Array | null.
   * (Port of HondaECU.send)
   */
  async send(msg, ml) {
    await this.t.write(msg);

    // 1) Consume the ECU's echo of our request.
    const t0 = Date.now();
    const echoBudget = 60 + msg.length * 2;
    let got = 0;
    while (got < msg.length) {
      const remain = echoBudget - (Date.now() - t0);
      if (remain <= 0) return null;
      const b = await this.t.readUntil(msg.length - got, Math.min(40, remain));
      if (!b.length) return null; // no echo → ECU not answering
      got += b.length;
    }

    // 2) Response header: mtype + length byte.
    const t1 = Date.now();
    const buf = [];
    let gotLen = 0;
    while (gotLen < ml + 1) {
      const remain = 120 - (Date.now() - t1);
      if (remain <= 0) return null;
      const b = await this.t.readUntil(ml + 1 - gotLen, Math.min(40, remain));
      if (!b.length) return null;
      for (const x of b) buf.push(x);
      gotLen += b.length;
    }

    // 3) Remaining data per the length byte (stream ends on a gap).
    let need = buf[buf.length - 1] - ml - 1;
    while (need > 0) {
      const b = await this.t.readUntil(need, 40);
      if (!b.length) return null;
      for (const x of b) buf.push(x);
      need -= b.length;
    }
    return new Uint8Array(buf);
  }

  /**
   * Send a command and validate the response (port of HondaECU.send_command).
   * Returns { rmtype, rml, rdata, rdl } | null.
   */
  async sendCommand(mtype, data = [], { retries = 1, delay = 0 } = {}) {
    const { msg, ml } = formatMessage(mtype, data);
    for (let r = 0; r <= retries; r++) {
      this.log(`> ${toHexStr(msg)}`, 'send');
      try {
        this.t.drain?.();
      } catch {
        /* noop */
      }
      const resp = await this.send(msg, ml);
      if (resp) {
        this.log(`< ${toHexStr(resp)}`, 'recv');
        if (checksum8bitHonda(resp.slice(0, -1)) === resp[resp.length - 1]) {
          const rmtype = Array.from(resp.slice(0, ml));
          let valid = false;
          if (ml === 3) valid = rmtype[0] === (mtype[0] | 0x10) && rmtype[1] === (mtype[1] | 0x10);
          else if (ml === 2) valid = rmtype[0] === mtype[0] && rmtype[1] === mtype[1];
          else if (ml === 1) valid = rmtype[0] === (mtype[0] & 0x0f);
          if (valid) {
            const rml = resp[ml];
            const rdl = rml - 2 - rmtype.length;
            const rdata = Array.from(resp.slice(ml + 1, resp.length - 1));
            if (delay > 0) await sleep(delay);
            return { rmtype, rml, rdata, rdl };
          }
        }
      }
    }
    return null;
  }

  async ping() {
    return (await this.sendCommand([0xfe], [0x72], { retries: 0 })) !== null;
  }

  async diag() {
    return (await this.sendCommand([0x72], [0x00, 0xf0], { retries: 0 })) !== null;
  }

  /**
   * Detect ECU state machine (port of HondaECU.detect_ecu_state).
   * Note: without K-line level sensing (generic UART), a dead line reports
   * OFF instead of eculib's UNKNOWN.
   */
  async detectEcuState() {
    if (!this.kline()) return ECUSTATE.OFF;

    let t0 = await this.sendCommand([0x72], [0x71, 0x00], { retries: 0 });
    if (t0 === null) {
      await this.init();
      await this.ping();
      t0 = await this.sendCommand([0x72], [0x71, 0x00], { retries: 0 });
    }
    if (t0 !== null && !(t0.rdata[5] === 0 && t0.rdata[6] === 0)) return ECUSTATE.OK;

    const recoverOld = await this.sendCommand([0x7d], [0x01, 0x01, 0x00], { retries: 0 });
    if (recoverOld) return ECUSTATE.RECOVER_OLD;

    const recoverNew = await this.sendCommand([0x7b], [0x00, 0x01, 0x01], { retries: 0 });
    if (recoverNew) return ECUSTATE.RECOVER_NEW;

    const ws = await this.sendCommand([0x7e], [0x01, 0x01, 0x00], { retries: 0 });
    if (ws !== null) {
      if (ws.rdata[1] === 0xf0) return ECUSTATE.ERROR;
      return ECUSTATE.WRITE;
    }

    const readResp = await this.sendCommand([0x82, 0x82, 0x00], [0x00, 0x00, 0x00, 0x08], { retries: 0 });
    if (readResp) return ECUSTATE.READ;

    return t0 !== null ? ECUSTATE.UNKNOWN : ECUSTATE.OFF;
  }

  /** Probe info-tables (port of HondaECU.probe_tables). */
  async probeTables(tables = null) {
    if (!tables) tables = [0x10, 0x11, 0x17, 0x20, 0x21, 0x60, 0x61, 0x67, 0x70, 0x71, 0xd0, 0xd1];
    const ret = {};
    for (const t of tables) {
      const info = await this.sendCommand([0x72], [0x71, t]);
      if (info) {
        if (info.rdl > 2) ret[t] = [info.rdl, info.rdata];
        else return {};
      }
    }
    return ret;
  }

  /** Read current (0x74) + past (0x73) fault codes. Codes look like '19-01'. */
  async getFaults() {
    const faults = { current: [], past: [] };
    for (const [key, sub] of [
      ['current', 0x74],
      ['past', 0x73],
    ]) {
      for (let i = 1; i < 0x0c; i++) {
        const info = await this.sendCommand([0x72], [sub, i], { retries: 0 });
        if (!info) break; // guard: eculib crashes here on None
        const d = info.rdata;
        for (const j of [3, 5, 7]) {
          if (j + 1 < d.length && d[j] !== 0) {
            faults[key].push(`${d[j].toString(16).padStart(2, '0')}-${d[j + 1].toString(16).padStart(2, '0')}`);
          }
        }
        if (d[2] === 0) break;
      }
    }
    return faults;
  }

  // ------------------------------------------------- flash / recovery (7x)
  async initRecover() {
    await this.sendCommand([0x7b], [0x00, 0x01, 0x01], { delay: 500 });
    await this.sendCommand([0x7b], [0x00, 0x01, 0x02], { delay: 500 });
    await this.sendCommand([0x7b], [0x00, 0x01, 0x03], { delay: 500 });
    await this.sendCommand([0x7b], [0x00, 0x02, 0x76, 0x03, 0x17], { delay: 500 });
    await this.sendCommand([0x7b], [0x00, 0x03, 0x75, 0x05, 0x13], { delay: 500 });
    return true;
  }

  async initWrite() {
    await this.sendCommand([0x7d], [0x01, 0x01, 0x01], { delay: 500 });
    await this.sendCommand([0x7d], [0x01, 0x01, 0x02], { delay: 500 });
    await this.sendCommand([0x7d], [0x01, 0x01, 0x03], { delay: 500 });
    await this.sendCommand([0x7d], [0x01, 0x02, 0x50, 0x47, 0x4d], { delay: 500 });
    await this.sendCommand([0x7d], [0x01, 0x03, 0x2d, 0x46, 0x49], { delay: 500 });
    return true;
  }

  async erase() {
    await this.sendCommand([0x7e], [0x01, 0x02], { delay: 500 });
    await this.sendCommand([0x7e], [0x01, 0x03, 0x00, 0x00], { delay: 500 });
    await this.sendCommand([0x7e], [0x01, 0x0b, 0x00, 0x00, 0x00, 0xff, 0xff, 0xff], { delay: 500 });
    await this.sendCommand([0x7e], [0x01, 0x0e, 0x01, 0x90], { delay: 500 });
    await this.sendCommand([0x7e], [0x01, 0x01, 0x01], { delay: 500 });
    await this.sendCommand([0x7e], [0x01, 0x04, 0xff], { delay: 500 });
    return true;
  }

  /**
   * Wait until erase reports done.
   * (eculib's loop spins forever on a stuck ECU — this version has a max-try guard.)
   */
  async eraseWait({ maxRounds = 60 } = {}) {
    for (let round = 0; round < maxRounds; round++) {
      const info = await this.sendCommand([0x7e], [0x01, 0x05], { retries: 0 });
      if (info && info.rdata[1] === 0x00) {
        return (await this.sendCommand([0x7e], [0x01, 0x01, 0x00], { retries: 0 })) !== null;
      }
      await sleep(500);
    }
    throw new Error('Erase wait: ECU never reported erase done');
  }

  /** Post-write finalization; returns true when the ECU confirms (0x0f). */
  async postWrite() {
    await this.sendCommand([0x7e], [0x01, 0x09], { delay: 500 });
    await this.sendCommand([0x7e], [0x01, 0x0a], { delay: 500 });
    await this.sendCommand([0x7e], [0x01, 0x0c], { delay: 500 });
    const info = await this.sendCommand([0x7e], [0x01, 0x0d], { delay: 500 });
    return info ? info.rdata[1] === 0x0f : false;
  }

  // ============================================================ ROM transfer
  // eculib has the flash state machine (erase/init/write handshakes) but no
  // actual data transfer — this layer adds it.
  //
  // Write frame follows the ONLY known-good eculib example (do_erase):
  //   7E 01 0B <addr3> <data...>
  // Read sub-command 01 06 mirrors that pattern — verify on real hardware.

  /** Read `length` bytes of ROM starting at `address`. */
  async readRom(address, length, { onProgress = null, readSub = 0x06, chunk = 24 } = {}) {
    const out = new Uint8Array(length);
    let off = 0;
    while (off < length) {
      const n = Math.min(chunk, length - off);
      const resp = await this.sendCommand(
        [0x7e],
        [0x01, readSub, ...formatReadAddress(address + off)],
        { retries: 1 },
      );
      if (!resp) throw new Error(`ROM read failed at 0x${(address + off).toString(16)}`);
      if (resp.rdata.length < n) throw new Error(`ROM read short at 0x${(address + off).toString(16)}`);
      for (let i = 0; i < n; i++) out[off + i] = resp.rdata[i];
      off += n;
      if (onProgress) onProgress(off, length);
    }
    return out;
  }

  /**
   * Write `bytes` to ROM at `address` (3 data bytes per frame by default,
   * matching eculib's erase frame), then poll until the ECU reports done.
   */
  async writeRom(address, bytes, { onProgress = null, writeSub = 0x0b, chunk = 3 } = {}) {
    const data = Uint8Array.from(bytes);
    let off = 0;
    while (off < data.length) {
      const n = Math.min(chunk, data.length - off);
      const resp = await this.sendCommand(
        [0x7e],
        [0x01, writeSub, ...formatReadAddress(address + off), ...Array.from(data.slice(off, off + n))],
        { retries: 2, delay: 5 },
      );
      if (!resp) throw new Error(`ROM write failed at 0x${(address + off).toString(16)}`);
      off += n;
      if (onProgress) onProgress(off, data.length);
    }
    await this.writeWait();
    return true;
  }

  /** Poll 7E 01 05 until the ECU reports the write buffer idle (rdata[1] == 0). */
  async writeWait({ maxRounds = 60 } = {}) {
    for (let round = 0; round < maxRounds; round++) {
      const info = await this.sendCommand([0x7e], [0x01, 0x05], { retries: 0 });
      if (info && info.rdata[1] === 0x00) return true;
      await sleep(300);
    }
    throw new Error('Write wait: ECU did not report write complete');
  }

  /** Read ROM back and compare byte-by-byte; throws on the first mismatch. */
  async verifyRom(address, bytes, opts = {}) {
    const ref = Uint8Array.from(bytes);
    const got = await this.readRom(address, ref.length, opts);
    for (let i = 0; i < ref.length; i++) {
      if (got[i] !== ref[i]) {
        throw new Error(
          `ROM verify mismatch at 0x${(address + i).toString(16)} (want 0x${ref[i].toString(16).padStart(2, '0')}, got 0x${got[i].toString(16).padStart(2, '0')})`,
        );
      }
    }
    return true;
  }
}
