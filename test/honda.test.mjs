// Honda K-line (eculib port) protocol tests against the virtual Honda bike ECU.
// Run: node test/honda.test.mjs
import assert from 'node:assert/strict';

import {
  HondaECU, ECUSTATE, HONDA_DTC, checksum8bitHonda, formatMessage, formatReadAddress, addressFromBytes,
} from '../public/js/honda/kline.js';
import { createKlineSimulator } from '../public/js/transport/klineSimulator.js';

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
let passed = 0;

function ok(name, fn) {
  return Promise.resolve()
    .then(fn)
    .then(() => {
      passed++;
      console.log(`  ✓ ${name}`);
    })
    .catch((e) => {
      console.error(`  ✗ ${name}\n    ${e.message}`);
      process.exitCode = 1;
      throw e;
    });
}

console.log('Honda K-line protocol tests (eculib port)\n');

// --- checksum / framing ------------------------------------------------------
await ok('checksum8bitHonda: two’s complement of sum', () => {
  // sum = 0xE9 → (0xE9 ^ 0xFF) + 1 = 0x17
  assert.equal(checksum8bitHonda([0x72, 0x02, 0x74, 0x01]), 0x17);
  assert.equal(checksum8bitHonda([0x00, 0x01]), 0xff);
  assert.equal(checksum8bitHonda([]), 0x00);
  // any message must hash to 0 when the checksum byte is included
  const { msg } = formatMessage([0x72], [0x74, 0x01]);
  assert.equal(checksum8bitHonda(msg), 0);
});

await ok('formatMessage: layout mtype+len+data+ck, len byte == total', () => {
  const { msg, ml, dl } = formatMessage([0x72], [0x74, 0x01]);
  // 72 | 05 (2+1+2) | 74 01 | 14  — verified by hand
  assert.deepEqual(Array.from(msg), [0x72, 0x05, 0x74, 0x01, 0x14]);
  assert.equal(ml, 1);
  assert.equal(dl, 2);
  assert.equal(msg[ml], msg.length);
  const t3 = formatMessage([0x82, 0x82, 0x00], [0, 0, 0, 8]);
  assert.equal(t3.msg[3], 9); // len = 2 + 3 (mtype) + 4 (data)
});

// --- ECU handshake -----------------------------------------------------------
const sim = createKlineSimulator();
const ecu = new HondaECU(sim, { log: () => {} });

await ok('ping (FE 72) with ignition on', async () => {
  assert.equal(await ecu.ping(), true);
});

await ok('diag (72 00 F0)', async () => {
  assert.equal(await ecu.diag(), true);
});

await ok('detectEcuState → OK when ignition on', async () => {
  assert.equal(await ecu.detectEcuState(), ECUSTATE.OK);
});

await ok('ignition off → no responses, state OFF', async () => {
  sim.setIgnition(false);
  assert.equal(await ecu.ping(), false);
  assert.equal(await ecu.detectEcuState(), ECUSTATE.OFF);
  sim.setIgnition(true);
});

// --- faults -------------------------------------------------------------------
await ok('getFaults: clean ECU reports past fault from sim init', async () => {
  const f = await ecu.getFaults();
  assert.deepEqual(f.current, []);
  assert.deepEqual(f.past, ['01-02']);
});

await ok('inject faults → read back via 74/73 with dictionary', async () => {
  sim.injectFault('19-01');
  sim.injectFault('21-01');
  sim.injectFault('18-01', { past: true });
  const f = await ecu.getFaults();
  assert.deepEqual(f.current.sort(), ['19-01', '21-01']);
  assert.deepEqual(f.past.sort(), ['01-02', '18-01']);
  assert.equal(HONDA_DTC['19-01'], 'CKP sensor no signal');
  sim.clearFaults();
});

await ok('unknown command (no ECU response) → null', async () => {
  // mtype 0x55 is not handled by the ECU → no response → sendCommand returns null.
  const r = await ecu.sendCommand([0x55], [0x01], { retries: 0 });
  assert.equal(r, null);
});

// --- tables -------------------------------------------------------------------
await ok('probeTables: all 12 default tables found', async () => {
  const tables = await ecu.probeTables();
  assert.equal(Object.keys(tables).length, 12);
  for (const [t, [rdl, rdata]] of Object.entries(tables)) {
    assert.ok(rdl > 2, `table ${t} rdl`);
    assert.ok(rdata.length >= 3, `table ${t} data`);
  }
});

// --- flash / recovery handshakes ----------------------------------------------
await ok('initRecover (7b sequence) completes', async () => {
  assert.equal(await ecu.initRecover(), true);
});

await ok('initWrite (7d sequence) completes', async () => {
  assert.equal(await ecu.initWrite(), true);
});

await ok('erase + eraseWait (7e) completes', async () => {
  await ecu.erase();
  assert.equal(await ecu.eraseWait(), true);
});

await ok('postWrite confirms with 0x0f', async () => {
  assert.equal(await ecu.postWrite(), true);
});

// --- ROM read/write data transfer (not in eculib) ---------------------------
await ok('formatReadAddress: eculib byte order (low two swapped)', () => {
  assert.deepEqual(formatReadAddress(0x000300), [0x00, 0x00, 0x03]);
  assert.deepEqual(formatReadAddress(0x001234), [0x00, 0x34, 0x12]);
  assert.deepEqual(formatReadAddress(0xabcdef), [0xab, 0xef, 0xcd]);
  // inverse
  for (const loc of [0x0, 0x1, 0x300, 0x1234, 0xabcdef, 0x001f00]) {
    assert.equal(addressFromBytes(formatReadAddress(loc)), loc);
  }
});

await ok('readRom returns the simulator’s deterministic ROM image', async () => {
  const got = await ecu.readRom(0, 8);
  assert.deepEqual(Array.from(got), Array.from(sim.state.rom.slice(0, 8)));
});

await ok('writeRom + readRom round-trip (multi-frame, addr > 0)', async () => {
  const buf = new Uint8Array(100);
  for (let i = 0; i < buf.length; i++) buf[i] = (i * 31 + 7) & 0xff;
  await ecu.writeRom(0x100, buf, { onProgress: () => {} });
  const got = await ecu.readRom(0x100, 100);
  assert.deepEqual(Array.from(got), Array.from(buf));
});

await ok('erase sequence actually writes FF FF FF via 7e 01 0b', async () => {
  await ecu.erase();
  const got = await ecu.readRom(0, 3);
  assert.deepEqual(Array.from(got), [0xff, 0xff, 0xff]);
});

await ok('verifyRom passes after write and fails on mismatch', async () => {
  const buf = new Uint8Array(24).fill(0x5a);
  await ecu.writeRom(0x2000, buf);
  assert.equal(await ecu.verifyRom(0x2000, buf), true);
  const bad = new Uint8Array(buf);
  bad[10] ^= 0xff;
  await assert.rejects(() => ecu.verifyRom(0x2000, bad), /mismatch at 0x200a/i);
});

await sim.close();
console.log(`\nAll ${passed} Honda K-line tests passed ✓`);
process.exit(process.exitCode || 0);
