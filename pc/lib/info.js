// PC status (CPU, RAM) and weather from Open-Meteo (free, no API key).
const os = require('os');
const { EventEmitter } = require('events');

const events = new EventEmitter();
let lastCpu = null;
let stats = { cpu: 0, ram: 0 };
let weather = null;

function cpuTimes() {
  let idle = 0;
  let total = 0;
  for (const c of os.cpus()) {
    for (const v of Object.values(c.times)) total += v;
    idle += c.times.idle;
  }
  return { idle, total };
}

function sampleStats() {
  const now = cpuTimes();
  if (lastCpu) {
    const total = now.total - lastCpu.total;
    const idle = now.idle - lastCpu.idle;
    stats = {
      cpu: total > 0 ? Math.round(100 * (1 - idle / total)) : 0,
      ram: Math.round(100 * (1 - os.freemem() / os.totalmem()))
    };
    events.emit('stats', stats);
  }
  lastCpu = now;
}

const CODES = [
  [[0], 'Clear'], [[1], 'Mostly clear'], [[2], 'Partly cloudy'], [[3], 'Overcast'],
  [[45, 48], 'Fog'], [[51, 53, 55, 56, 57], 'Drizzle'], [[61, 63, 65, 66, 67, 80, 81, 82], 'Rain'],
  [[71, 73, 75, 77, 85, 86], 'Snow'], [[95, 96, 99], 'Thunderstorm']
];

function describe(code) {
  const hit = CODES.find(([list]) => list.includes(code));
  return hit ? hit[1] : '';
}

let weatherCfg = null;
let weatherTimer = null;

async function refreshWeather() {
  clearTimeout(weatherTimer);
  weatherTimer = setTimeout(refreshWeather, 15 * 60 * 1000);
  if (!weatherCfg) return;
  try {
    const { lat, lon, city } = weatherCfg;
    const res = await fetch(`https://api.open-meteo.com/v1/forecast?latitude=${lat}&longitude=${lon}&current=temperature_2m,weather_code&timezone=auto`);
    const j = await res.json();
    weather = { city, temp: Math.round(j.current.temperature_2m), text: describe(j.current.weather_code) };
    events.emit('weather', weather);
  } catch {
    // Without internet the last known weather stays.
  }
}

function setWeatherConfig(cfg) {
  const changed = !weatherCfg || cfg.lat !== weatherCfg.lat || cfg.lon !== weatherCfg.lon || cfg.city !== weatherCfg.city;
  weatherCfg = { ...cfg };
  if (changed) refreshWeather();
}

// Finds a city by name (for settings).
async function geocode(name) {
  const res = await fetch('https://geocoding-api.open-meteo.com/v1/search?count=5&language=sk&name=' + encodeURIComponent(name));
  const j = await res.json();
  return (j.results || []).map((r) => ({ city: r.name, country: r.country || '', lat: r.latitude, lon: r.longitude }));
}

function start() {
  sampleStats();
  setInterval(sampleStats, 2000);
}

module.exports = { start, events, setWeatherConfig, geocode, stats: () => stats, weather: () => weather };
