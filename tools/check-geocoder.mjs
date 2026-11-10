import {readFileSync} from 'node:fs';
const token=readFileSync(new URL('../local.properties',import.meta.url),'utf8').match(/^CESIUM_ION_TOKEN=(.+)$/m)?.[1]?.trim();
const url=new URL('https://api.cesium.com/v1/geocode/search');
url.searchParams.set('access_token',token);
url.searchParams.set('geocoder','google');
url.searchParams.set('text',process.argv[2] ?? '52.52,13.405');
const r=await fetch(url);
const j=await r.json();
console.log(JSON.stringify({status:r.status,features:j.features?.map(f=>({properties:f.properties,bbox:f.bbox})),attributions:j.attributions},null,2));
