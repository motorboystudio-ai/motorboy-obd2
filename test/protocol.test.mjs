// Protocol stack test: ELM327 layer + OBD framing + simulated car.
// Run: npm test   (or: node test/protocol.test.mjs)
import assert from 'node:assert/strict';

import { ELM327, OBDError } from '../public/js/protocol/elm327.js';
import { createSimulatorTransport } from '../public/js/transport/simulator.js';
import { dtcToHexBytes, hexToDtcCode, PIDS } from '../public/js/protocol/pids.js';

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

console.log('ECU OBD2 — protocol stack tests\n');

// --- DTC encoding helpers -------------------------------------------------
await ok('DTC encode/decode round-trip', () => {
  assert.deepEqual(dtcToHexBytes('P0301'), [0x21, 0x2d]);
  assert.deepEqual(dtcToHexBytes('P0420'), [0x21, 0xa4]);
  assert.equal(hexToDtcCode(0x21, 0x2d), 'P0301');
  assert.equal(hexToDtcCode(0x21, 0xa4), 'P0420');
  assert.equal(hexToDtcCode(0x00, 0x00), null);
});

// --- adapter handshake ----------------------------------------------------
const { transport, car } = createSimulatorTransport();
const obd = new ELM327(transport, { log: () => {} });

await ok('ATZ reset returns firmware version', async () => {
  await obd.reset();
  assert.match(obd.version, /ELM327/);
});

await ok('setup (ATL1/ATS1/ATE0/ATSH0)', async () => {
  await obd.setup();
  assert.equal(obd.echo, true);
});

await ok('ATDP auto-detect → CAN 413', async () => {
  const det = await obd.detectProtocol();
  assert.equal(det.code, 4);
  assert.equal(det.name, 'CAN413');
});

await ok('explicit setProtocol(CAN250) + back to CAN413', async () => {
  await obd.setProtocol('CAN250');
  assert.equal(obd.isCAN, true);
  await obd.setProtocol('CAN413');
});

// --- live PIDs (engine off) -------------------------------------------------
await ok('readPids engine-off (status + rpm)', async () => {
  const v = await obd.readPids([0x01, 0x0c]);
  assert.equal(v[0x01].running, false);
  assert.equal(v[0x01].mil, false);
  assert.equal(v[0x0c], 0);
});

// --- engine on -------------------------------------------------------------
car.setIgnition(true);
await sleep(1600); // cranking delay + settle

await ok('readPids engine-on (running, idle rpm)', async () => {
  const v = await obd.readPids([0x01, 0x0c]);
  assert.equal(v[0x01].running, true);
  assert.ok(v[0x0c] > 1200 && v[0x0c] < 1400, `bike idle rpm plausible: ${v[0x0c]}`);
});

await ok('throttle → rpm rises', async () => {
  car.setThrottle(0.6);
  await sleep(900);
  const v = await obd.readPids([0x0c, 0x0e]);
  assert.ok(v[0x0c] > 2000, `rpm under load: ${v[0x0c]}`);
  assert.ok(v[0x0e] > 50, `throttle: ${v[0x0e]}%`);
  car.setThrottle(0);
  await sleep(700);
});

await ok('multi-byte + 3-byte PIDs decode (speed/fuel/air/voltage/MAF/O2)', async () => {
  const v1 = await obd.readPids([0x08, 0x24]);
  const v2 = await obd.readPids([0x25, 0x2f]);
  const v3 = await obd.readPids([0x0b, 0x11]);
  const v4 = await obd.readPids([0x04, 0x05]);
  const v5 = await obd.readPids([0x42]);
  assert.ok(typeof v1[0x08] === 'number' && v1[0x24] > 0 && v1[0x24] < 100);
  assert.ok(v2[0x2f] > 12 && v2[0x2f] < 16);
  assert.ok(v3[0x0b] > 20);
  assert.ok(v3[0x11] >= 0 && v3[0x11] <= 1);
  assert.ok(v4[0x04] > -5 && v4[0x04] < 5);
  assert.ok(typeof v5[0x42] === 'number');
});

// --- DTC flow ---------------------------------------------------------------
await ok('readDTCs empty when no faults', async () => {
  assert.deepEqual(await obd.readDTCs(), []);
});

await ok('inject DTC → visible via mode 03', async () => {
  car.injectDTC('P0301');
  assert.deepEqual(await obd.readDTCs(), ['P0301']);
});

await ok('MIL bit set on engine status PID', async () => {
  const v = await obd.readPids([0x01]);
  assert.equal(v[0x01].mil, true);
});

await ok('freeze frame for DTC (mode 05)', async () => {
  const ff = await obd.readFreezeFrameDtc('P0301');
  assert.ok(ff.rpm > 0, `freeze rpm: ${ff.rpm}`);
  assert.ok(ff.fuelPct > 0);
  assert.ok(Number.isFinite(ff.voltage));
});

await ok('per-PID freeze frame (mode 05 2x)', async () => {
  const [hi, lo] = dtcToHexBytes('P0301');
  // Response format: 45 2x <value...> (0x45 stripped by requestData, no DTC bytes)
  const data = await obd.requestData(new Uint8Array([0x05, 0x2d, hi, lo]));
  assert.equal(data[0], 0x2d);
  const rpm = (data[1] * 256 + data[2]) / 4;
  assert.ok(rpm > 0, `freeze rpm: ${rpm}`);
});

await ok('multi-frame DTC list (4 DTCs split across CAN frames)', async () => {
  car.injectDTC('P0420');
  car.injectDTC('P0135');
  car.injectDTC('P0562');
  const codes = (await obd.readDTCs()).sort();
  assert.deepEqual(codes, ['P0135', 'P0301', 'P0420', 'P0562']);
});

// --- VIN --------------------------------------------------------------------
await ok('VIN via mode 09 (multi-frame, 17 chars)', async () => {
  const vin = await obd.readVin();
  assert.equal(vin.length, 17);
  assert.equal(vin, '5YFBP4GE6R0123456');
});

// --- readiness ----------------------------------------------------------------
await ok('readiness group reflects misfire DTC', async () => {
  const mask = await obd.readReadiness();
  assert.ok(mask & 0x01, 'misfire bit incomplete');
});

// --- clear ---------------------------------------------------------------------
await ok('clear DTCs (mode 04)', async () => {
  await obd.clearDTCs();
  assert.deepEqual(await obd.readDTCs(), []);
  const v = await obd.readPids([0x01]);
  assert.equal(v[0x01].mil, false);
});

// --- errors ---------------------------------------------------------------------
await ok('unsupported PID → NRC 0x31', async () => {
  await assert.rejects(() => obd.readPids([0x2a]), (e) => e instanceof OBDError && /NRC 0x31/.test(e.message));
});

await ok('chunkPids ISO-safe (≤3 data bytes/frame) vs CAN (≤4)', async () => {
  const pids = [0x01, 0x0c, 0x0d, 0x0e, 0x08, 0x24, 0x25, 0x2f, 0x0b, 0x11, 0x04, 0x05, 0x42];
  obd.protocolName = 'ISO9141';
  for (const chunk of obd.chunkPids(pids)) {
    const bytes = chunk.reduce((s, p) => s + PIDS[p].bytes, 0);
    assert.ok(bytes <= 3, `ISO chunk bytes: ${bytes}`);
  }
  obd.protocolName = 'CAN413';
  for (const chunk of obd.chunkPids(pids)) {
    const bytes = chunk.reduce((s, p) => s + PIDS[p].bytes, 0);
    assert.ok(bytes <= 4, `CAN chunk bytes: ${bytes}`);
  }
  assert.ok(obd.chunkPids(pids).length >= 2);
});

await transport.close();
console.log(`\nAll ${passed} tests passed ✓`);
process.exit(process.exitCode || 0);
