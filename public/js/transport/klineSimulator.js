// Virtual Honda motorcycle ECU speaking raw K-line, so the bike-ECU mode can be
// exercised in the browser with no hardware. Mirrors the commands eculib uses:
// ping (FE 72), diag (72 00 F0), state/tables (72 71 x), faults (72 73/74 x),
// and the 7b/7d/7e recovery/write/erase handshakes.
import { formatMessage, checksum8bitHonda, addressFromBytes } from '../honda/kline.js';

/** Response mtype per eculib's validation rules. */
function respMtype(reqMtype) {
  if (reqMtype.length === 3) return [reqMtype[0] | 0x10, reqMtype[1] | 0x10, reqMtype[2]];
  if (reqMtype.length === 2) return [...reqMtype];
  return [reqMtype[0] & 0x0f];
}
const respMsg = (reqMtype, data) => formatMessage(respMtype(reqMtype), data).msg;

const codeToBytes = (code) => {
  const m = code.match(/^([0-9a-f]{2})-([0-9a-f]{2})$/i);
  return [parseInt(m[1], 16), parseInt(m[2], 16)];
};

// Deterministic virtual ROM (128 KB) so read/write/verify loops are testable.
function buildRom(size = 0x20000) {
  const rom = new Uint8Array(size);
  for (let i = 0; i < size; i++) rom[i] = ((i * 7) + ((i >> 8) & 0xff) * 3) & 0xff;
  const header = 'HONDA-ROM-SIM-ECU-01\0\0';
  for (let i = 0; i < header.length && i < size; i++) rom[i] = header.charCodeAt(i);
  for (let i = 0; i < 32 && 0x100 + i < size; i++) rom[0x100 + i] = (i * 13) & 0xff; // fake calib block
  return rom;
}

export function createKlineSimulator({ log = () => {} } = {}) {
  const state = {
    ignition: true,
    currentFaults: [], // e.g. ['19-01']
    pastFaults: ['01-02'],
    rom: buildRom(),
    version: [0x01, 0x00, 0x00, 0x08, 0x00, 0x12, 0x34, 0x56],
    tables: {
      0x10: [0x00, 0x04, 0x00, 0x10, 0x00],
      0x11: [0x00, 0x04, 0x00, 0x20, 0x00],
      0x17: [0x00, 0x04, 0x00, 0x10, 0x00],
      0x20: [0x00, 0x04, 0x00, 0x40, 0x00],
      0x21: [0x00, 0x04, 0x00, 0x40, 0x00],
      0x60: [0x00, 0x04, 0x00, 0x10, 0x00],
      0x61: [0x00, 0x04, 0x00, 0x10, 0x00],
      0x67: [0x00, 0x04, 0x00, 0x10, 0x00],
      0x70: [0x00, 0x04, 0x00, 0x10, 0x00],
      0x71: [0x00, 0x04, 0x00, 0x10, 0x00],
      0xd0: [0x00, 0x04, 0x00, 0x10, 0x00],
      0xd1: [0x00, 0x04, 0x00, 0x10, 0x00],
    },
    eraseDone: true,
  };

  // ------------------------------------------------ raw byte queue transport
  let q = [];
  let waiter = null;
  let closed = false;
  let onCloseCb = null;
  const notify = () => {
    const w = waiter;
    waiter = null;
    if (w) w();
  };
  const push = (bytes) => {
    if (closed) return;
    setTimeout(() => {
      if (closed) return;
      for (const b of bytes) q.push(b);
      notify();
    }, 4 + Math.random() * 10);
  };

  // --------------------------------------------------- request stream parser
  let buf = [];
  function parseStream() {
    while (buf.length >= 2) {
      const b0 = buf[0];
      const ml = b0 === 0x82 ? 3 : 1; // known 3-byte mtype: 82 82 00
      if (buf.length < ml + 1) return;
      const msgsize = buf[ml];
      // The length byte already counts the whole message (incl. itself + checksum).
      const total = msgsize;
      if (total < ml + 2 || total > 32) {
        buf = []; // framing error
        return;
      }
      if (buf.length < total) return; // wait for the rest
      const msg = buf.splice(0, total);
      if (checksum8bitHonda(msg.slice(0, -1)) !== msg[msg.length - 1]) {
        buf = []; // bad checksum → drop
        return;
      }
      handleRequest(msg, ml);
    }
  }

  function faultGroup(faults, group) {
    const data = [0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00];
    if (group === 1) {
      data[2] = faults.length;
      faults.slice(0, 3).forEach((c, i) => {
        const [a, b] = codeToBytes(c);
        data[3 + i * 2] = a;
        data[4 + i * 2] = b;
      });
    }
    return data;
  }

  function handleRequest(msg, ml) {
    if (!state.ignition) return; // ECU off: K-line dead, no echo/response
    const mtype = msg.slice(0, ml);
    const data = msg.slice(ml + 1, msg.length - 1);

    let response = null;
    const f0 = mtype[0];

    if (f0 === 0xfe) {
      // ping
      response = respMsg(mtype, [0x72]);
    } else if (f0 === 0x72) {
      const sub = data[0];
      if (sub === 0x00) response = respMsg(mtype, [0x00, 0xf0]); // diag
      else if (sub === 0x71) {
        const t = data[1];
        if (t === 0x00) response = respMsg(mtype, state.version);
        else if (state.tables[t] !== undefined) response = respMsg(mtype, state.tables[t]);
        else response = respMsg(mtype, [0x00, 0x00]);
      } else if (sub === 0x74) response = respMsg(mtype, faultGroup(state.currentFaults, data[1]));
      else if (sub === 0x73) response = respMsg(mtype, faultGroup(state.pastFaults, data[1]));
    } else if (f0 === 0x7d) {
      response = respMsg(mtype, [0x00]); // recover-old capable
    } else if (f0 === 0x7b) {
      response = respMsg(mtype, [0x00]); // recover-new capable
    } else if (f0 === 0x7e) {
      const s1 = data[0];
      const s2 = data[1];
      if (s1 === 0x01 && s2 === 0x00 && data.length === 3) {
        response = respMsg(mtype, [0x00, 0x01]); // write-capable check
      } else if (s1 === 0x01 && s2 === 0x05) {
        response = respMsg(mtype, [0x01, 0x00]); // write/erase status: idle/done
      } else if (s1 === 0x01 && s2 === 0x0d) {
        response = respMsg(mtype, [0x01, 0x0f]); // post-write confirm
      } else if (s1 === 0x01 && s2 === 0x06 && data.length >= 5) {
        // ROM read: 01 06 <addr3> → up to 24 bytes
        const addr = addressFromBytes(data.slice(2, 5));
        const end = Math.min(state.rom.length, addr + 24);
        if (addr < state.rom.length) response = respMsg(mtype, Array.from(state.rom.subarray(addr, end)));
        else response = respMsg(mtype, [0xff, 0xff]); // out of range
      } else if (s1 === 0x01 && s2 === 0x0b && data.length >= 6) {
        // ROM write: 01 0b <addr3> <data...> (same shape as eculib's erase frame)
        const addr = addressFromBytes(data.slice(2, 5));
        const romData = data.slice(5);
        for (let i = 0; i < romData.length && addr + i < state.rom.length; i++) {
          state.rom[addr + i] = romData[i];
        }
        response = respMsg(mtype, [0x00]);
      } else {
        response = respMsg(mtype, [0x00]);
      }
    } else if (f0 === 0x82) {
      response = respMsg(mtype, [0x08]); // read-capable
    }

    // Echo (full request incl. checksum) + response must be ONE atomic burst
    // in that order — eculib reads the echo first, then the response.
    if (response) push([...msg, ...response]);
    else push(msg);
  }

  // ------------------------------------------------------------- public API
  return {
    name: 'K-line Simulator',
    state,
    init: async () => {},
    drain() {
      q.length = 0;
    },
    write: async (bytes) => {
      if (closed) throw new Error('Simulator closed');
      for (const b of bytes) buf.push(b);
      parseStream();
    },
    readUntil: (n, timeoutMs = 100) =>
      new Promise((resolve) => {
        let finished = false;
        const t = setTimeout(finish, timeoutMs);
        function finish() {
          if (finished) return;
          finished = true;
          clearTimeout(t);
          const out = q.splice(0, n);
          resolve(new Uint8Array(out));
        }
        if (q.length) finish();
        else waiter = finish;
      }),
    onClose: (cb) => {
      onCloseCb = cb;
    },
    close: async () => {
      closed = true;
      if (onCloseCb) onCloseCb();
    },
    // demo controls
    setIgnition: (on) => {
      state.ignition = !!on;
    },
    injectFault: (code, { past = false } = {}) => {
      const list = past ? state.pastFaults : state.currentFaults;
      if (!list.includes(code)) list.push(code);
    },
    clearFaults: () => {
      state.currentFaults = [];
      state.pastFaults = [];
    },
  };
}
