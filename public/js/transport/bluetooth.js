// Web Bluetooth transport for BLE ELM327 adapters.
// The app requires a secure context (https or localhost) — the preview host is https.
import { OBDError } from '../protocol/elm327.js';

export async function createBluetoothTransport({ log = () => {} } = {}) {
  if (!navigator.bluetooth) {
    throw new OBDError('Web Bluetooth is not supported in this browser (use Chrome/Edge over HTTPS)');
  }

  log('Selecting BLE ELM327 adapter…');
  const device = await navigator.bluetooth.requestDevice({
    acceptAllDevices: true,
    optionalServices: [],
  });
  log(`BLE device selected: ${device.name || 'unknown'}`);

  const server = await device.gatt.connect();

  // Find a writable (and preferably notifiable) characteristic.
  let writeChar = null;
  let notifyChar = null;
  const services = await server.getPrimaryServices();
  for (const svc of services) {
    try {
      const chars = await svc.getCharacteristics();
      for (const ch of chars) {
        if (!writeChar && (ch.properties.write || ch.properties.writeWithoutResponse)) writeChar = ch;
        if (!notifyChar && ch.properties.notify) notifyChar = ch;
      }
    } catch {
      /* service not accessible — skip */
    }
    if (writeChar && notifyChar) break;
  }
  if (!writeChar || !notifyChar) {
    try {
      server.disconnect();
    } catch {
      /* noop */
    }
    throw new OBDError('Device has no writable + notifiable BLE characteristics (not an ELM327?)');
  }

  const encoder = new TextEncoder();
  const decoder = new TextDecoder('utf-8');
  let onDataCb = null;
  let buf = new Uint8Array(0);

  const flushLines = () => {
    if (!onDataCb) return;
    let start = 0;
    for (let i = 0; i < buf.length; i++) {
      const b = buf[i];
      if (b === 0x0a) {
        const line = decoder.decode(buf.slice(start, i));
        onDataCb(line);
        start = i + 1;
      } else if (b === 0x0d) {
        const line = decoder.decode(buf.slice(start, i));
        onDataCb(line);
        start = i + (buf[i + 1] === 0x0a ? 2 : 1);
      }
    }
    buf = buf.slice(start);
  };

  return {
    name: 'Bluetooth',
    init: async () => {
      notifyChar.addEventListener('valuechanged', (e) => {
        const chunk = e.target.value;
        buf = new Uint8Array([...buf, ...chunk]);
        flushLines();
      });
      await notifyChar.startNotifications();
      log('BLE notifications started');
    },
    onData: (cb) => {
      onDataCb = cb;
    },
    onClose: (cb) => {
      device.addEventListener('gattserverdisconnected', () => {
        log('BLE adapter disconnected');
        cb();
      });
    },
    sendLine: async (line) => {
      const data = encoder.encode(line + '\r\n');
      if (writeChar.properties.writeWithoutResponse) {
        await writeChar.writeValueWithoutResponse(data);
      } else {
        await writeChar.writeValueWithResponse(data);
      }
    },
    close: async () => {
      try {
        await notifyChar.stopNotifications();
      } catch {
        /* noop */
      }
      try {
        server.disconnect();
      } catch {
        /* noop */
      }
    },
  };
}
