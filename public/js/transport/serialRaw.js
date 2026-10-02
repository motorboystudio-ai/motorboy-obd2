// Raw byte-stream transport over Web Serial — for FTDI/K-line dongles talking
// Honda bike ECU K-line @ 10400 baud (same hardware path as Python eculib).
import { OBDError } from '../protocol/elm327.js';

export async function createSerialRawTransport({ log = () => {}, baudRate = 10400 } = {}) {
  if (!navigator.serial) {
    throw new OBDError('Web Serial is not supported in this browser (use Chrome/Edge)');
  }

  log('Selecting K-line serial port…');
  const port = await navigator.serial.requestPort();
  await port.open({ baudRate, dataBits: 8, stopBits: 1, parity: 'none' });
  log(`K-line serial opened @ ${baudRate} baud`);

  let q = [];
  let waiter = null;
  let closed = false;
  let onCloseCb = null;

  const notify = () => {
    const w = waiter;
    waiter = null;
    if (w) w();
  };

  const reader = port.readable.getReader();
  const loopPromise = (async () => {
    try {
      while (!closed) {
        const { value, done } = await reader.read();
        if (done) break;
        if (value && value.length) {
          for (const b of value) q.push(b);
          notify();
        }
      }
    } catch (err) {
      if (!closed && onCloseCb) {
        log(`K-line serial ended: ${err.message}`);
        onCloseCb();
      }
    }
  })();

  return {
    name: 'K-line Serial',
    init: async () => {},
    drain() {
      q.length = 0;
    },
    write: async (bytes) => {
      if (closed) throw new Error('K-line serial closed');
      const writer = port.writable.getWriter();
      try {
        await writer.write(Uint8Array.from(bytes));
      } finally {
        writer.releaseLock();
      }
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
      try {
        await reader.cancel();
      } catch {
        /* noop */
      }
      try {
        await port.close();
      } catch {
        /* noop */
      }
      loopPromise.catch(() => {});
    },
  };
}
