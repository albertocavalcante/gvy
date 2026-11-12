import fs from 'node:fs';
import { execFileSync } from 'node:child_process';
const [phase, dir, receiptPath] = process.argv.slice(2);
if (!['red','green'].includes(phase)) throw Error('invalid phase');
const r=JSON.parse(fs.readFileSync(receiptPath,'utf8'))[phase];
const cmd=JSON.parse(fs.readFileSync(`${dir}/command.json`,'utf8'));
if (cmd.commandExit !== (phase==='red'?1:0) || cmd.captureExit!==0) throw Error('command failure');
const parser=String.raw`
import json, sys, xml.etree.ElementTree as ET
p, name = sys.argv[1:]
with open(p, 'rb') as f:
    data = f.read(4194305)
if len(data) > 4194304 or b'<!DOCTYPE' in data.upper() or b'<!ENTITY' in data.upper():
    raise SystemExit('invalid XML')
root = ET.fromstring(data)
expected_class = 'com.github.albertocavalcante.gvy.gls.e2e.' + name
if root.tag != 'testsuite' or root.attrib.get('name') != expected_class: raise SystemExit('invalid root')
cases = root.findall('./testcase')
all_cases = list(root.iter('testcase'))
if len(cases) != len(all_cases): raise SystemExit('nested testcase')
counts = {k: int(root.attrib[k]) for k in ('tests','failures','errors','skipped')}
if counts['tests'] != len(cases): raise SystemExit('count mismatch')
seen=set(); failures=[]; errors=0; skipped=0
for case in cases:
    if case.attrib.get('classname') != expected_class: raise SystemExit('invalid class')
    key=case.attrib.get('name')
    if not key or key in seen: raise SystemExit('duplicate test')
    seen.add(key)
    fail=list(case.iter('failure')); err=list(case.iter('error')); skip=list(case.iter('skipped'))
    if len(fail)>1 or len(err)>1 or len(skip)>1 or sum(bool(x) for x in (fail,err,skip))>1: raise SystemExit('invalid outcome')
    if fail:
        typ=fail[0].attrib.get('type')
        if not typ: raise SystemExit('missing type')
        failures.append({'class':name,'name':key,'type':typ})
    errors+=len(err); skipped+=len(skip)
if len(list(root.iter('failure'))) != len(failures) or len(list(root.iter('error'))) != errors or len(list(root.iter('skipped'))) != skipped: raise SystemExit('outcome outside testcase')
if counts['failures']!=len(failures) or counts['errors']!=errors or counts['skipped']!=skipped: raise SystemExit('outcome count mismatch')
if errors or skipped: raise SystemExit('error or skip')
print(json.dumps({'count':len(cases),'failures':failures}))
`;
const actual=[];
for (const c of r.classes) {
  const file=`${dir}/TEST-com.github.albertocavalcante.gvy.gls.e2e.${c.name}.xml`;
  const result=JSON.parse(execFileSync('python3',['-c',parser,file,c.name],{encoding:'utf8',maxBuffer:1024*1024,timeout:10000}));
  if(result.count!==c.count) throw Error('suite mismatch'); actual.push(...result.failures);
}
const normalize=a=>a.map(x=>JSON.stringify(x)).sort();
if(JSON.stringify(normalize(actual))!==JSON.stringify(normalize(r.failures))) throw Error('unexpected failure');
console.log(`${phase} tests validated`);
