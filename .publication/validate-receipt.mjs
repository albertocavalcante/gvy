import fs from 'node:fs';
import { createHash } from 'node:crypto';
const [path, expected] = process.argv.slice(2);
const fail = () => { throw new Error('invalid receipt'); };
const bytes = fs.readFileSync(path);
if (!/^[a-f0-9]{64}$/.test(expected) || createHash('sha256').update(bytes).digest('hex') !== expected) fail();
const r = JSON.parse(bytes);
const obj = (v, keys) => { if (!v || typeof v !== 'object' || Array.isArray(v) || Object.keys(v).sort().join() !== [...keys].sort().join()) fail(); };
const hash = (v, n) => { if (typeof v !== 'string' || !new RegExp(`^[a-f0-9]{${n}}$`).test(v)) fail(); };
const paths = v => { if (!Array.isArray(v) || !v.length || new Set(v).size !== v.length || v.some(x => typeof x !== 'string' || !/^tests\/[A-Za-z0-9_./-]+$/.test(x) || x.includes('..'))) fail(); };
if (!['red-only','red-green'].includes(r.mode)) fail();
const phases=r.mode==='red-only'?['red']:['red','green'];
obj(r, ['base','mode',...phases,'helpers']); hash(r.base,40);
for (const phase of phases) {
  const v=r[phase]; obj(v,['tree','patch','paths','classes','failures']); hash(v.tree,40); paths(v.paths);
  obj(v.patch,['sha256','compressedSha256','bytes','compressedBytes']); hash(v.patch.sha256,64); hash(v.patch.compressedSha256,64);
  for (const n of ['bytes','compressedBytes']) if (!Number.isSafeInteger(v.patch[n]) || v.patch[n] < 1 || v.patch[n] > 131072) fail();
  if (!Array.isArray(v.classes) || !v.classes.length || v.classes.some(c => { try { obj(c,['name','count']); } catch { return true; } return !/^[A-Za-z][A-Za-z0-9]*Test$/.test(c.name) || !Number.isSafeInteger(c.count) || c.count < 1; }) || new Set(v.classes.map(c=>c.name)).size !== v.classes.length) fail();
  if (!Array.isArray(v.failures) || v.failures.some(f => { try { obj(f,['class','name','types','includes']); } catch { return true; } return !Array.isArray(f.includes) || !f.includes.length || f.includes.length>8 || f.includes.some(x=>typeof x!=='string'||!x.length||x.length>256) || !v.classes.some(c=>c.name===f.class) || typeof f.name !== 'string' || !f.name || !Array.isArray(f.types) || f.types.length !== 2 || new Set(f.types).size !== 2 || f.types.some(t => !['java.lang.AssertionError','org.opentest4j.AssertionFailedError'].includes(t)); })) fail();
  if ((phase === 'red') !== (v.failures.length > 0) || new Set(v.failures.map(f=>`${f.class}:${f.name}`)).size !== v.failures.length) fail();
}
if (r.red.paths.some(p => !p.endsWith('Test.kt') && p !== 'tests/build.gradle.kts')) fail();
obj(r.helpers,['phase','results','worker','capture','budget','copy']); for (const h of Object.values(r.helpers)) hash(h,64);
console.log('receipt valid');
