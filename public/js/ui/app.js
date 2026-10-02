// Main application: connection flow, live-data polling, DTCs, UI.
import { ELM327, OBDError } from '../protocol/elm327.js';
import { PIDS, DTC_INFO, READINESS_BITS } from '../protocol/pids.js';
import { HondaECU, ECUSTATE, ECUSTATE_NAMES, HONDA_DTC, toHexStr } from '../honda/kline.js';
import { createSimulatorTransport } from '../transport/simulator.js';
import { createBluetoothTransport } from '../transport/bluetooth.js';
import { createSerialTransport } from '../transport/serial.js';
import { createSerialRawTransport } from '../transport/serialRaw.js';
import { createKlineSimulator } from '../transport/klineSimulator.js';
import { createGauge, createTile } from './gauges.js';

const $ = (sel) => document.querySelector(sel);

const LIVE_PIDS = [0x01, 0x0c, 0x0d, 0x0e, 0x08, 0x24, 0x25, 0x2f, 0x0b, 0x11, 0x04, 0x05, 0x42];

const state = {
  transport: null,
  obd: null,
  connected: false,
  busy: false,
  device: 'bluetooth',
  protocolName: null,
  unit: 'metric',
  interval: 250,
  pollTimer: null,
  pollIndex: 0,
  pollBusy: false,
  chunks: [],
  dtcs: [],
  vin: '',
  mil: false,
  engineRunning: false,
  closedLoop: false,
  sim: null,
  latest: {}, // pid → decoded raw value
};

let toastTimer = null;

// ------------------------------------------------------------------- logging

const logEl = $('#log');
function logLine(msg, dir = 'info', kind = null) {
  const line = document.createElement('div');
  line.className = 'logline ' + (kind || dir);
  const t = new Date().toLocaleTimeString('en-GB', { hour12: false });
  const arrow = dir === 'send' ? '»' : dir === 'recv' ? '«' : '•';
  line.innerHTML = `<span class="log-time">${t}</span> <span class="log-arrow">${arrow}</span> <span class="log-msg"></span>`;
  line.querySelector('.log-msg').textContent = msg;
  logEl.appendChild(line);
  while (logEl.children.length > 400) logEl.removeChild(logEl.firstChild);
  logEl.scrollTop = logEl.scrollHeight;
}

function toast(msg, isError = false) {
  const t = $('#toast');
  t.textContent = msg;
  t.className = 'toast' + (isError ? ' error' : '');
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => t.classList.add('hidden'), 3500);
}

function fmtSize(n) {
  return n >= 1048576 ? (n / 1048576).toFixed(2) + ' MB' : (n / 1024).toFixed(1) + ' KB';
}

// CRC-32 (ISO-HDLC) for ROM checksums
const CRC_TABLE = (() => {
  const t = new Uint32Array(256);
  for (let n = 0; n < 256; n++) {
    let c = n;
    for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
    t[n] = c >>> 0;
  }
  return t;
})();
function crc32(bytes) {
  let c = 0xffffffff;
  for (let i = 0; i < bytes.length; i++) c = CRC_TABLE[(c ^ bytes[i]) & 0xff] ^ (c >>> 8);
  return (c ^ 0xffffffff) >>> 0;
}

// -------------------------------------------------------------------- units

const disp = {
  speed: (v) => (state.unit === 'metric' ? v : v * 0.621371),
  speedUnit: () => (state.unit === 'metric' ? 'km/h' : 'mph'),
  temp: (v) => (state.unit === 'metric' ? v : (v * 9) / 5 + 32),
  tempUnit: () => (state.unit === 'metric' ? '°C' : '°F'),
};

// -------------------------------------------------------------------- gauges

let gauges = {};
let tiles = {};

function buildGauges() {
  const metric = state.unit === 'metric';
  // Bike tachometer: redline ~13,500 on a 15,000 scale.
  const rpm = createGauge($('#gauge-rpm'), {
    label: 'RPM', min: 0, max: 15000, redline: 13500, ticks: 10,
    labelFormat: (v) => (v / 1500).toString(), format: (v) => v.toFixed(0),
  });
  const speed = createGauge($('#gauge-speed'), {
    label: 'SPEED', unit: disp.speedUnit(), min: 0, max: metric ? 240 : 150, ticks: 8,
    labelFormat: (v) => String(Math.round(v / 30) * 30), format: (v) => v.toFixed(0),
  });
  const coolant = createGauge($('#gauge-coolant'), {
    label: 'COOLANT', unit: disp.tempUnit(), min: metric ? -20 : -4, max: metric ? 140 : 284,
    redline: metric ? 110 : 230, ticks: 8,
    labelFormat: (v) => String(Math.round(v)),
    format: (v) => v.toFixed(0),
  });
  const fuel = createGauge($('#gauge-fuel'), {
    label: 'FUEL', unit: '%', min: 0, max: 100, ticks: 10,
    labelFormat: (v) => (v % 25 === 0 ? String(v) : ''), format: (v) => v.toFixed(0),
  });
  gauges = { rpm, speed, coolant, fuel };

  tiles = {
    throttle: createTile($('#tile-throttle'), { format: (v) => v.toFixed(0), unit: '%' }),
    airtemp: createTile($('#tile-airtemp'), { format: (v) => v.toFixed(0), unit: disp.tempUnit() }),
    maf: createTile($('#tile-maf'), { format: (v) => v.toFixed(1), unit: 'g/s' }),
    voltage: createTile($('#tile-voltage'), { format: (v) => v.toFixed(1), unit: 'V' }),
    map: createTile($('#tile-map'), { format: (v) => v.toFixed(0), unit: 'kPa' }),
    o2: createTile($('#tile-o2'), { format: (v) => v.toFixed(2), unit: 'V' }),
    trimst: createTile($('#tile-trimst'), { format: (v) => v.toFixed(2), unit: '%', signed: true }),
    trimlt: createTile($('#tile-trimlt'), { format: (v) => v.toFixed(2), unit: '%', signed: true }),
  };

  // re-render with whatever we already have
  render();
}

function resetGauges() {
  for (const g of Object.values(gauges)) g.reset();
  for (const t of Object.values(tiles)) t.reset();
}

// --------------------------------------------------------------------- render

function render() {
  const l = state.latest;
  if (l[0x01]) {
    state.engineRunning = l[0x01].running;
    state.closedLoop = l[0x01].closedLoop;
    state.mil = l[0x01].mil || state.dtcs.length > 0;
    updateBadges();
  }
  if (l[0x0c] != null) gauges.rpm.set(l[0x0c]);
  if (l[0x0d] != null) gauges.coolant.set(disp.temp(l[0x0d]));
  if (l[0x08] != null) gauges.speed.set(disp.speed(l[0x08]));
  if (l[0x24] != null) gauges.fuel.set(l[0x24]);
  if (l[0x0e] != null) tiles.throttle.set(l[0x0e]);
  if (l[0x25] != null) tiles.airtemp.set(disp.temp(l[0x25]));
  if (l[0x42] != null) tiles.maf.set(l[0x42]);
  if (l[0x2f] != null) tiles.voltage.set(l[0x2f]);
  if (l[0x0b] != null) tiles.map.set(l[0x0b]);
  if (l[0x11] != null) tiles.o2.set(l[0x11]);
  if (l[0x04] != null) tiles.trimst.set(l[0x04]);
  if (l[0x05] != null) tiles.trimlt.set(l[0x05]);
}

function updateBadges() {
  $('#mil-lamp').classList.toggle('hidden', !state.mil);
  const eng = $('#engine-badge');
  eng.classList.remove('hidden');
  eng.textContent = state.engineRunning ? 'ENGINE ON' : 'ENGINE OFF';
  eng.className = 'badge ' + (state.engineRunning ? 'ok' : 'muted');
  if (state.closedLoop) eng.title = 'Closed-loop (fuel control active)';
}

function setConnStatus(mode) {
  // mode: 'off' | 'ok' | 'error'
  const dot = $('#conn-dot');
  dot.className = 'dot ' + mode;
  $('#conn-text').textContent = mode === 'ok' ? 'Connected' : mode === 'error' ? 'Error' : 'Disconnected';
}

function updateProtocolBadge() {
  const b = $('#proto-badge');
  if (state.protocolName) {
    b.textContent = state.protocolName;
    b.classList.remove('hidden');
  } else {
    b.classList.add('hidden');
  }
}

// -------------------------------------------------------------------- polling

function startPolling() {
  stopPolling();
  state.chunks = state.obd.chunkPids(LIVE_PIDS);
  state.pollIndex = 0;
  const tick = async () => {
    if (!state.connected || state.pollBusy) return;
    state.pollBusy = true;
    const chunk = state.chunks[state.pollIndex % state.chunks.length];
    state.pollIndex++;
    try {
      const values = await state.obd.readPids(chunk);
      Object.assign(state.latest, values);
      render();
    } catch (e) {
      if (/closed/i.test(e.message)) {
        // link dropped (adapter unplug / BLE disconnect)
        logLine(`Connection lost: ${e.message}`, 'info', 'error');
        disconnect();
        return;
      }
      logLine(`poll: ${e.message}`, 'info', 'warn');
      if (/NAK|Timeout/i.test(e.message)) setConnStatus('error');
    } finally {
      state.pollBusy = false;
    }
  };
  state.pollTimer = setInterval(tick, state.interval);
  tick();
}

function stopPolling() {
  if (state.pollTimer) {
    clearInterval(state.pollTimer);
    state.pollTimer = null;
  }
  state.pollBusy = false;
}

// ---------------------------------------------------------------------- DTCs

function renderDTCs() {
  const list = $('#dtc-list');
  list.innerHTML = '';
  if (!state.dtcs.length) {
    const li = document.createElement('li');
    li.className = 'dtc-empty';
    li.textContent = 'No stored DTCs — no active fault codes.';
    list.appendChild(li);
  } else {
    for (const code of state.dtcs) {
      const li = document.createElement('li');
      li.className = 'dtc-row';
      li.innerHTML = `<span class="dtc-code">${code}</span><span class="dtc-desc"></span>`;
      li.querySelector('.dtc-desc').textContent = DTC_INFO[code] || 'Manufacturer specific code';
      li.title = 'Click to read freeze frame';
      li.addEventListener('click', () => showFreeze(code));
      list.appendChild(li);
    }
  }
  state.mil = state.mil || state.dtcs.length > 0;
  updateBadges();
}

async function readDTCs() {
  if (!state.obd) return;
  logLine('Reading DTCs (mode 03)…', 'info');
  try {
    state.dtcs = await state.obd.readDTCs();
    renderDTCs();
    // readiness (best effort)
    try {
      const mask = await state.obd.readReadiness();
      renderReadiness(mask);
    } catch {
      $('#readiness').classList.add('hidden');
    }
  } catch (e) {
    toast(`DTC read failed: ${e.message}`, true);
    logLine(e.message, 'info', 'error');
  }
}

function renderReadiness(mask) {
  const box = $('#readiness');
  box.innerHTML = '';
  const title = document.createElement('span');
  title.className = 'readiness-title';
  title.textContent = 'Readiness group 1:';
  box.appendChild(title);
  for (const [name, bit] of READINESS_BITS) {
    const s = document.createElement('span');
    const ok = !!(mask & (1 << bit));
    s.className = 'readiness-item ' + (ok ? 'ok' : 'fail');
    s.textContent = `${ok ? '✓' : '✗'} ${name}`;
    box.appendChild(s);
  }
  box.classList.remove('hidden');
}

const FREEZE_LABELS = [
  ['speedKmh', 'Speed', (v) => `${disp.speed(v).toFixed(0)} ${disp.speedUnit()}`],
  ['rpm', 'Engine RPM', (v) => v.toFixed(0)],
  ['coolantC', 'Coolant temp', (v) => `${disp.temp(v).toFixed(0)} ${disp.tempUnit()}`],
  ['fuelPct', 'Fuel level', (v) => v.toFixed(0) + ' %'],
  ['throttlePct', 'Throttle', (v) => v.toFixed(0) + ' %'],
  ['voltage', 'System voltage', (v) => v.toFixed(2) + ' V'],
  ['maf', 'MAF', (v) => v.toFixed(1) + ' g/s'],
  ['airTempC', 'Intake air temp', (v) => `${disp.temp(v).toFixed(0)} ${disp.tempUnit()}`],
];

async function showFreeze(code) {
  const box = $('#freeze');
  box.classList.remove('hidden');
  box.innerHTML = `<div class="freeze-title">Freeze frame — ${code}</div>`;
  try {
    const ff = await state.obd.readFreezeFrameDtc(code);
    const rows = FREEZE_LABELS.filter(([k]) => ff[k] != null);
    if (!rows.length) {
      box.insertAdjacentHTML('beforeend', '<div class="dtc-empty">ECU returned no freeze-frame data.</div>');
      return;
    }
    const grid = document.createElement('div');
    grid.className = 'freeze-grid';
    for (const [key, label, fmt] of rows) {
      grid.insertAdjacentHTML('beforeend',
        `<div class="freeze-item"><span class="freeze-label">${label}</span><span class="freeze-value mono">${fmt(ff[key])}</span></div>`);
    }
    box.appendChild(grid);
  } catch (e) {
    box.insertAdjacentHTML('beforeend', `<div class="dtc-empty">No freeze frame: ${e.message}</div>`);
  }
}

async function clearDTCs() {
  if (!state.obd) return;
  if (!confirm('Clear all DTCs and readiness memory? (OBD mode 04)')) return;
  try {
    await state.obd.clearDTCs();
    toast('DTCs cleared');
    state.latest = {};
    state.dtcs = [];
    renderDTCs();
    render();
  } catch (e) {
    toast(`Clear failed: ${e.message}`, true);
  }
}

// ------------------------------------------------------------------ connect

async function connect() {
  if (state.busy) return;
  state.busy = true;
  const btn = $('#connect-btn');
  btn.disabled = true;
  btn.textContent = 'Connecting…';

  try {
    const device = state.device;
    let transport;
    if (device === 'sim') {
      const { transport: t, car } = createSimulatorTransport({ log: (m, d, k) => logLine(m, d, k) });
      transport = t;
      state.sim = car;
      logLine('Simulator created (CAN 413, virtual 600cc 4-cyl bike engine)');
    } else if (device === 'bluetooth') {
      transport = await createBluetoothTransport({ log: (m) => logLine(m, 'info') });
      state.sim = null;
    } else {
      transport = await createSerialTransport({ log: (m) => logLine(m, 'info'), baudRate: Number($('#baud').value) });
      state.sim = null;
    }

    await transport.init();

    const obd = new ELM327(transport, { log: (m, d, k) => logLine(m, d, k) });
    await obd.reset();
    logLine(`Adapter firmware: ${obd.version || 'unknown'}`, 'info');
    await obd.setup();

    // protocol
    const protoSel = $('#protocol').value;
    if (protoSel === 'auto') {
      const det = await obd.detectProtocol();
      if (det && det.atsp) {
        await obd.setProtocol(det.name);
        logLine(`Auto-detected protocol: ${det.name} (0X${det.code})`, 'info');
      } else {
        logLine('Protocol auto-detect: nothing found — will try raw requests', 'info', 'warn');
      }
    } else {
      await obd.setProtocol(protoSel);
    }
    state.protocolName = obd.protocolName;
    updateProtocolBadge();

    state.transport = transport;
    state.obd = obd;
    state.connected = true;
    state.latest = {};
    state.dtcs = [];

    setConnStatus('ok');
    setConnectedUI(true);
    buildGauges();

    // VIN (best effort)
    try {
      state.vin = await obd.readVin();
      $('#vin').textContent = state.vin;
    } catch (e) {
      state.vin = '';
      $('#vin').textContent = '';
      logLine(`VIN: ${e.message}`, 'info', 'warn');
    }

    // initial DTC read
    try {
      state.dtcs = await obd.readDTCs();
      renderDTCs();
    } catch (e) {
      logLine(`Initial DTC read: ${e.message}`, 'info', 'warn');
    }
    updateBadges();

    startPolling();
    if (device === 'sim') $('#sim-panel').classList.remove('hidden');
    logLine('Connected. Live data polling started.', 'info', 'ok');
  } catch (e) {
    logLine(`Connect failed: ${e.message}`, 'info', 'error');
    toast(`Connect failed: ${e.message}`, true);
    await cleanup();
  } finally {
    state.busy = false;
    btn.disabled = false;
  }
}

function setConnectedUI(on) {
  $('#connect-btn').textContent = on ? 'Disconnect' : 'Connect';
  $('#gauge-grid').classList.toggle('hidden', !on);
  $('#tile-grid').classList.toggle('hidden', !on);
  ['device', 'protocol', 'baud', 'interval', 'units'].forEach((id) => {
    const el = document.getElementById(id);
    if (id === 'interval' || id === 'units') el.disabled = !on;
    else el.disabled = on;
  });
}

async function cleanup() {
  stopPolling();
  state.connected = false;
  if (state.transport) {
    try {
      await state.transport.close();
    } catch {
      /* noop */
    }
  }
  state.obd = null;
  state.transport = null;
  state.latest = {};
  state.sim = null;
  setConnStatus('off');
  updateProtocolBadge();
  $('#vin').textContent = '';
  $('#sim-panel').classList.add('hidden');
  $('#readiness').classList.add('hidden');
  $('#freeze').classList.add('hidden');
  $('#mil-lamp').classList.add('hidden');
  $('#engine-badge').classList.add('hidden');
  const list = $('#dtc-list');
  list.innerHTML = '<li class="dtc-empty">Not read yet — connect and press “Read (03)”.</li>';
  resetGauges();
  setConnectedUI(false);
}

async function disconnect() {
  await cleanup();
  logLine('Disconnected.', 'info');
}

// ===================================================== Bike ECU (Honda K-line)

const bike = { ecu: null, transport: null, connected: false, sim: null, busy: false };

function bikeLog(msg, dir = 'info', kind = null) {
  logLine(`[K-line] ${msg}`, dir, kind);
}

function setBikeUI(on) {
  for (const id of ['bike-ping', 'bike-state-btn', 'bike-faults', 'bike-tables',
    'rom-read', 'rom-write',
    'bike-init-recover', 'bike-init-write', 'bike-erase', 'bike-post-write']) {
    document.getElementById(id).disabled = !on;
  }
  document.getElementById('bike-device').disabled = on;
  document.getElementById('bike-connect').textContent = on ? 'Disconnect' : 'Connect';
}

function bikeStateBadge(s) {
  const b = $('#bike-state');
  b.textContent = `ECU: ${ECUSTATE_NAMES[s] ?? s}`;
  b.className = 'badge ' + (s === ECUSTATE.OK ? 'ok' : s === ECUSTATE.OFF ? 'muted' : 'warn');
  b.classList.remove('hidden');
}

async function bikeConnect() {
  if (bike.busy) return;
  bike.busy = true;
  try {
    const device = $('#bike-device').value;
    let transport;
    if (device === 'sim') {
      transport = createKlineSimulator({ log: (m) => bikeLog(m) });
      bike.sim = transport;
    } else {
      transport = await createSerialRawTransport({ log: (m) => bikeLog(m), baudRate: 10400 });
      bike.sim = null;
    }
    await transport.init();
    transport.onClose(() => {
      bikeLog('K-line link dropped', 'info', 'error');
      bikeDisconnect();
    });
    bike.ecu = new HondaECU(transport, { log: (m, d) => bikeLog(m, d) });
    bike.transport = transport;
    bike.connected = true;
    setBikeUI(true);
    $('#bike-sim-panel').classList.toggle('hidden', device !== 'sim');
    bikeLog('K-line connected');
    await bikeDetectState();
  } catch (e) {
    bikeLog(`connect failed: ${e.message}`, 'info', 'error');
    toast(`Bike connect failed: ${e.message}`, true);
    await bikeDisconnect();
  } finally {
    bike.busy = false;
  }
}

async function bikeDisconnect() {
  if (bike.transport) {
    try {
      await bike.transport.close();
    } catch {
      /* noop */
    }
  }
  bike.ecu = null;
  bike.transport = null;
  bike.connected = false;
  bike.sim = null;
  setBikeUI(false);
  $('#bike-sim-panel').classList.add('hidden');
  $('#bike-state').classList.add('hidden');
  $('#bike-tables-panel').classList.add('hidden');
  for (const id of ['bike-current', 'bike-past']) {
    document.getElementById(id).innerHTML = '<li class="dtc-empty">Not read yet</li>';
  }
}

async function bikePing() {
  if (!bike.ecu) return;
  const ok = await bike.ecu.ping();
  bikeLog(`ping: ${ok ? 'OK' : 'no response'}`, 'info', ok ? 'ok' : 'error');
  toast(ok ? 'Ping OK' : 'Ping failed — no response from ECU', !ok);
}

async function bikeDetectState() {
  if (!bike.ecu) return;
  bikeLog('detecting ECU state…', 'info');
  const s = await bike.ecu.detectEcuState();
  bikeStateBadge(s);
  bikeLog(`ECU state: ${ECUSTATE_NAMES[s]}`, 'info', s === ECUSTATE.OK ? 'ok' : 'warn');
}

function renderBikeFaults(faults) {
  const render = (el, codes) => {
    el.innerHTML = '';
    if (!codes.length) {
      el.innerHTML = '<li class="dtc-empty">No fault codes</li>';
      return;
    }
    for (const c of codes) {
      const li = document.createElement('li');
      li.className = 'dtc-row';
      li.innerHTML = `<span class="dtc-code">${c}</span><span class="dtc-desc"></span>`;
      li.querySelector('.dtc-desc').textContent = HONDA_DTC[c] || 'Honda code (no description)';
      el.appendChild(li);
    }
  };
  render($('#bike-current'), faults.current);
  render($('#bike-past'), faults.past);
}

async function bikeReadFaults() {
  if (!bike.ecu) return;
  bikeLog('reading faults (74 current / 73 past)…', 'info');
  const f = await bike.ecu.getFaults();
  renderBikeFaults(f);
  bikeLog(`faults: ${f.current.length} current, ${f.past.length} past`, 'info');
}

async function bikeProbeTables() {
  if (!bike.ecu) return;
  bikeLog('probing info tables (71)…', 'info');
  const tables = await bike.ecu.probeTables();
  const box = $('#bike-tables');
  box.innerHTML = '';
  $('#bike-tables-panel').classList.remove('hidden');
  const entries = Object.entries(tables);
  if (!entries.length) {
    box.innerHTML = '<div class="dtc-empty">No tables found (probe failed)</div>';
    return;
  }
  for (const [t, [, rdata]] of entries) {
    const d = document.createElement('div');
    d.className = 'table-item';
    d.innerHTML = `<span class="t-name">0x${Number(t).toString(16).padStart(2, '0')}</span><span>${toHexStr(rdata)}</span>`;
    box.appendChild(d);
  }
  bikeLog(`tables: ${entries.length} found`, 'info', 'ok');
}

const BIKE_FLASH_WARNINGS = {
  recover: 'Init RECOVERY mode (7b sequence)? The ECU will switch to recovery mode.',
  write: 'Init WRITE mode (7d sequence)? The ECU will switch to write mode.',
  erase: 'ERASE ECU flash memory (7e sequence + wait)? This DELETES ROM content. Continue only if you know exactly what you are doing.',
  post: 'Run post-write finalization (7e 09/0a/0c/0d)?',
};

// ------------------------------------------------------------------ ROM I/O

let romBytes = null; // loaded .bin image waiting to be written

function setRomProgress(done, total, label) {
  const p = $('#rom-progress');
  p.classList.remove('hidden');
  const pct = total ? Math.round((done / total) * 100) : 100;
  $('#rom-progress-bar').style.width = pct + '%';
  $('#rom-progress-text').textContent = `${label} ${pct}%  (${fmtSize(done)} / ${fmtSize(total)})`;
}

function showRomInfo(text) {
  const el = $('#rom-info');
  el.textContent = text;
  el.classList.remove('hidden');
}

async function romRead() {
  if (!bike.ecu) return;
  const size = Number($('#rom-size').value);
  try {
    setRomProgress(0, size, 'reading');
    bikeLog(`ROM read started: ${fmtSize(size)} @ 0x000000…`, 'warn');
    const bytes = await bike.ecu.readRom(0, size, {
      onProgress: (d, t) => setRomProgress(d, t, 'reading'),
    });
    const crc = crc32(bytes).toString(16).padStart(8, '0').toUpperCase();
    showRomInfo(`read ${fmtSize(size)} from ECU — CRC32 ${crc}`);
    const blob = new Blob([bytes], { type: 'application/octet-stream' });
    const a = document.createElement('a');
    a.href = URL.createObjectURL(blob);
    a.download = 'ecu-rom-000000.bin';
    document.body.appendChild(a);
    a.click();
    a.remove();
    setTimeout(() => URL.revokeObjectURL(a.href), 5000);
    bikeLog(`ROM read done — CRC32 ${crc} (file downloaded)`, 'info', 'ok');
    toast(`ROM downloaded (${fmtSize(size)})`);
  } catch (e) {
    bikeLog(`ROM read failed: ${e.message}`, 'info', 'error');
    toast(`ROM read failed: ${e.message}`, true);
  } finally {
    setRomProgress(size, size, 'done');
    setTimeout(() => $('#rom-progress').classList.add('hidden'), 1500);
  }
}

function romLoadFile(file) {
  const reader = new FileReader();
  reader.onload = () => {
    romBytes = new Uint8Array(reader.result);
    const crc = crc32(romBytes).toString(16).padStart(8, '0').toUpperCase();
    showRomInfo(`loaded ${file.name} — ${fmtSize(romBytes.length)} — CRC32 ${crc} (ready to write)`);
    bikeLog(`ROM image loaded: ${file.name} (${fmtSize(romBytes.length)}, CRC32 ${crc})`, 'info', 'ok');
    toast(`ROM image loaded: ${file.name}`);
  };
  reader.onerror = () => toast('Could not read file', true);
  reader.readAsArrayBuffer(file);
}

async function romWrite() {
  if (!bike.ecu) return;
  if (!romBytes) {
    toast('Load a .bin image first', true);
    return;
  }
  const n = romBytes.length;
  if (!confirm(
    `Write ${fmtSize(n)} to the ECU flash?\n\n` +
    'This OVERWRITES ECU contents. The app will auto init-write, ' +
    'write chunk by chunk, then read back and verify.\n\n' +
    'Do NOT turn off ignition or unplug during the transfer. Continue?',
  )) return;
  try {
    bikeLog('ROM: init-write sequence (7d)…', 'warn');
    await bike.ecu.initWrite();
    bikeLog(`ROM write started: ${fmtSize(n)} @ 10400 baud (this takes a while)…`, 'warn');
    await bike.ecu.writeRom(0, romBytes, {
      onProgress: (d, t) => setRomProgress(d, t, 'writing'),
    });
    setRomProgress(0, n, 'verifying');
    bikeLog('ROM: readback verify…', 'warn');
    await bike.ecu.verifyRom(0, romBytes);
    setRomProgress(n, n, 'done ✓');
    showRomInfo(`written ${fmtSize(n)} + verified OK — run Post-write (7e) to finalize`);
    bikeLog('ROM write + verify OK', 'info', 'ok');
    toast('ROM written and verified ✓ — run Post-write to finalize');
  } catch (e) {
    bikeLog(`ROM write failed: ${e.message}`, 'info', 'error');
    toast(`ROM write failed: ${e.message}`, true);
  } finally {
    setTimeout(() => $('#rom-progress').classList.add('hidden'), 2500);
  }
}

async function bikeFlash(kind) {
  if (!bike.ecu) return;
  if (!confirm(BIKE_FLASH_WARNINGS[kind])) return;
  try {
    bikeLog(`flash: ${kind} sequence started`, 'warn');
    if (kind === 'recover') await bike.ecu.initRecover();
    else if (kind === 'write') await bike.ecu.initWrite();
    else if (kind === 'erase') {
      await bike.ecu.erase();
      const ok = await bike.ecu.eraseWait();
      bikeLog(ok ? 'erase: done' : 'erase: wait ended without final ack', 'info', ok ? 'ok' : 'warn');
    } else if (kind === 'post') {
      const ok = await bike.ecu.postWrite();
      bikeLog(`post-write: ${ok ? 'confirmed (0x0f)' : 'NOT confirmed'}`, 'info', ok ? 'ok' : 'warn');
    }
    await bikeDetectState();
    toast('Sequence finished');
  } catch (e) {
    bikeLog(`flash: ${e.message}`, 'info', 'error');
    toast(`Sequence failed: ${e.message}`, true);
  }
}

// -------------------------------------------------------------------- wiring

async function wire() {
  const deviceSel = $('#device');
  deviceSel.addEventListener('change', () => {
    state.device = deviceSel.value;
    $('#baud-label').classList.toggle('hidden', state.device !== 'serial');
  });

  $('#connect-btn').addEventListener('click', () => {
    if (state.connected) disconnect();
    else connect();
  });

  $('#protocol').addEventListener('change', (e) => {
    logLine(`Protocol setting: ${e.target.value}`, 'info');
  });

  $('#interval').addEventListener('change', (e) => {
    state.interval = Number(e.target.value);
    if (state.connected) startPolling();
  });

  $('#units').addEventListener('change', (e) => {
    state.unit = e.target.value;
    if (state.connected) buildGauges();
    else {
      const u = $('#tile-airtemp')?.querySelector('.tile-unit');
      if (u) u.textContent = disp.tempUnit();
    }
  });

  // DTC buttons
  $('#dtc-read').addEventListener('click', readDTCs);
  $('#dtc-clear').addEventListener('click', clearDTCs);

  // log
  $('#log-clear').addEventListener('click', () => {
    logEl.innerHTML = '';
  });

  // simulator controls
  const simDtcSel = $('#sim-dtc');
  for (const code of ['P0301', 'P0300', 'P0420', 'P0442', 'P0135', 'P0030', 'P0171', 'P0101', 'P0562', 'P0700']) {
    const opt = document.createElement('option');
    opt.value = code;
    opt.textContent = `${code} — ${DTC_INFO[code] || ''}`;
    simDtcSel.appendChild(opt);
  }

  $('#sim-ignition').addEventListener('change', (e) => {
    if (state.sim) {
      state.sim.setIgnition(e.target.checked);
      logLine(`Simulator: ignition ${e.target.checked ? 'ON' : 'OFF'}`, 'info');
    }
  });

  const throttle = $('#sim-throttle');
  throttle.addEventListener('input', () => {
    $('#sim-throttle-val').textContent = throttle.value + '%';
    if (state.sim) state.sim.setThrottle(Number(throttle.value) / 100);
  });

  $('#sim-inject').addEventListener('click', async () => {
    if (!state.sim) return;
    const code = simDtcSel.value;
    state.sim.injectDTC(code);
    logLine(`Simulator: injected ${code} — re-reading DTCs via OBD…`, 'info');
    await readDTCs();
  });

  $('#sim-clear').addEventListener('click', async () => {
    if (!state.sim) return;
    try {
      await state.obd.clearDTCs();
      state.latest = {};
      render();
      await readDTCs();
      toast('DTCs cleared via OBD mode 04');
    } catch (e) {
      toast(`Clear failed: ${e.message}`, true);
    }
  });

  // ---- Bike ECU (Honda K-line)
  $('#bike-connect').addEventListener('click', () => (bike.connected ? bikeDisconnect() : bikeConnect()));
  $('#bike-ping').addEventListener('click', bikePing);
  $('#bike-state-btn').addEventListener('click', bikeDetectState);
  $('#bike-faults').addEventListener('click', bikeReadFaults);
  $('#bike-tables').addEventListener('click', bikeProbeTables);
  $('#bike-init-recover').addEventListener('click', () => bikeFlash('recover'));
  $('#bike-init-write').addEventListener('click', () => bikeFlash('write'));
  $('#bike-erase').addEventListener('click', () => bikeFlash('erase'));
  $('#bike-post-write').addEventListener('click', () => bikeFlash('post'));

  // ROM read/write
  $('#rom-read').addEventListener('click', romRead);
  $('#rom-write').addEventListener('click', romWrite);
  $('#rom-file').addEventListener('change', (e) => {
    const f = e.target.files[0];
    if (f) romLoadFile(f);
    e.target.value = '';
  });

  $('#bike-sim-ignition').addEventListener('change', (e) => {
    if (bike.sim) bike.sim.setIgnition(e.target.checked);
  });
  $('#bike-sim-inject').addEventListener('click', async () => {
    if (!bike.sim) return;
    const code = $('#bike-sim-fault').value;
    bike.sim.injectFault(code);
    bikeLog(`sim: injected ${code} — re-reading faults…`, 'info');
    await bikeReadFaults();
  });
  $('#bike-sim-clear').addEventListener('click', async () => {
    if (!bike.sim) return;
    bike.sim.clearFaults();
    await bikeReadFaults();
  });
}

wire();
logLine('ECU OBD2 ready. Pick a device and press Connect.', 'info');
