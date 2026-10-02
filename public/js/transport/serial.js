// Web Serial transport for USB-serial ELM327 adapters.
import { OBDError } from '../protocol/elm327.js';

export async function createSerialTransport({ log = () => {}, baudRate = 9600 } = {}) {
  if (!navigator.serial) {
    throw new OBDError('Web Serial is not supported in this browser (use Chrome/Edge)');
  }

  log('Selecting serial port…');
  const port = await navigator.serial.requestPort();
  await port.open({ baudRate, dataBits: 8, stopBits: 1, parity: 'none' });
  log(`Serial port opened @ ${baudRate} baud`);

  const encoder = new TextEncoder();
  const decoder = new TextDecoder('utf-8');
  let onDataCb = null;
  let onCloseCb = null;
  let buf = '';
  let closed = false;

  const reader = port.readable.getReader();
  const readLoop = async () => {
    try {
      while (!closed) {
        const { value, done } = await reader.read();
        if (done) break;
        buf += decoder.decode(value, { stream: true });
        let idx;
        while ((idx = buf.search(/[\r\n]/)) !== -1) {
          const line = buf.slice(0, idx);
          buf = buf.slice(idx + 1).replace(/^\r/, '');
          if (onDataCb) onDataCb(line);
        }
      }
    } catch (err) {
      if (!closed && onCloseCb) {
        log(`Serial read ended: ${err.message}`);
        onCloseCb();
      }
    }
  };
  const readPromise = readLoop();

  return {
    name: 'Serial',
    init: async () => {},
    onData: (cb) => {
      onDataCb = cb;
    },
    onClose: (cb) => {
      onCloseCb = cb;
    },
    sendLine: async (line) => {
      if (closed) throw new Error('Serial port closed');
      const writer = port.writable.getWriter();
      try {
        await writer.write(encoder.encode(line + '\r\n'));
      } finally {
        writer.releaseLock();
      }
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
      readPromise.catch(() => {});
    },
  };
}
