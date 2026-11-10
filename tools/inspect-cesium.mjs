import { readFileSync } from 'node:fs';

// Diagnostics keep service credentials and tile contents in memory only.
const token = readFileSync(new URL('../local.properties', import.meta.url), 'utf8')
  .match(/^CESIUM_ION_TOKEN=(.+)$/m)?.[1]?.trim();
const latitude = Number(process.argv[2] ?? 52.52) * Math.PI / 180;
const longitude = Number(process.argv[3] ?? 13.405) * Math.PI / 180;
const n = 6378137 / Math.sqrt(1 - 0.00669437999014 * Math.sin(latitude) ** 2);
const point = [n * Math.cos(latitude) * Math.cos(longitude), n * Math.cos(latitude) * Math.sin(longitude), n * (1 - 0.00669437999014) * Math.sin(latitude)];
const radius = 180;
let requests = 0;
async function get(url, binary = false) {
  if (++requests > 100) throw new Error('Diagnostic request budget exceeded');
  const response = await fetch(url);
  if (!response.ok) throw new Error(`Service HTTP ${response.status}: ${(await response.text()).replace(/AIza[\w-]+/g, '[redacted]').slice(0, 600)}`);
  return binary ? Buffer.from(await response.arrayBuffer()) : response.json();
}
function intersects(tile) {
  const box = tile.boundingVolume?.box;
  if (!box) return true;
  const delta = point.map((p, i) => p - box[i]);
  let squared = 0;
  for (let k = 3; k < 12; k += 3) {
    const extent = Math.hypot(...box.slice(k, k + 3));
    const distance = Math.abs(delta.reduce((s, p, i) => s + p * box[k + i], 0) / extent);
    squared += Math.max(0, distance - extent) ** 2;
  }
  return squared < radius ** 2;
}
const endpoint = await get(`https://api.cesium.com/v1/assets/2275207/endpoint?access_token=${encodeURIComponent(token)}`);
const rootUrl = endpoint.options?.url ?? endpoint.url;
const key = new URL(rootUrl).searchParams.get('key');
const selected = [];
async function visit(tile, base, depth = 0) {
  if (!intersects(tile) || selected.length >= 5) return;
  const raw = tile.content?.uri ?? tile.content?.url;
  let url;
  if (raw) {
    url = new URL(raw, base);
    for (const [name, value] of new URL(base).searchParams) {
      if (!url.searchParams.has(name)) url.searchParams.set(name, value);
    }
    if (key) url.searchParams.set('key', key);
  }
  if (url?.pathname.endsWith('.json')) {
    const external = await get(url);
    await visit(external.root, url, depth + 1);
  } else if (tile.children?.length && tile.geometricError > 3) {
    for (const child of tile.children) await visit(child, base, depth + 1);
  } else if (url?.pathname.endsWith('.glb')) {
    const bytes = await get(url, true);
    const json = JSON.parse(bytes.subarray(20, 20 + bytes.readUInt32LE(12)).toString());
    console.log(JSON.stringify({depth, error: tile.geometricError, box: tile.boundingVolume, transform: tile.transform, bytes: bytes.length, asset: json.asset, extensions: json.extensions, required: json.extensionsRequired, nodes: json.nodes, accessors: json.accessors?.map(a => ({type:a.type,min:a.min,max:a.max,count:a.count})), primitives:json.meshes?.map(m => m.primitives), images:json.images?.map(i => ({mimeType:i.mimeType,bufferView:i.bufferView})), material:json.materials}, null, 2));
    selected.push(true);
  } else if (tile.children) {
    for (const child of tile.children) await visit(child, base, depth + 1);
  }
}
await visit((await get(rootUrl)).root, rootUrl);
console.log(JSON.stringify({requests, tiles: selected.length}));
