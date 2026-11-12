import fs from 'node:fs';
import path from 'node:path';
const [root, reserveText='0'] = process.argv.slice(2);
const reserve=Number(reserveText), limit=128*1024*1024;
if (!Number.isSafeInteger(reserve) || reserve<0) throw Error('invalid reserve');
function total(dir) { let sum=0; for(const name of fs.readdirSync(dir)) { const p=path.join(dir,name),st=fs.lstatSync(p); if(st.isSymbolicLink() || (!st.isDirectory() && !st.isFile())) throw Error('invalid entry'); sum+=st.isDirectory()?total(p):st.size; if(!Number.isSafeInteger(sum) || sum>limit) throw Error('evidence limit'); } return sum; }
if(total(root)+reserve>limit) throw Error('evidence limit');
