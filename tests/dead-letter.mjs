import fs from 'node:fs';
import assert from 'node:assert/strict';
import {execFileSync} from 'node:child_process';
const env=Object.fromEntries(fs.readFileSync('.env','utf8').split(/\r?\n/).filter(l=>l.includes('=')&&!l.startsWith('#')).map(l=>[l.slice(0,l.indexOf('=')),l.slice(l.indexOf('=')+1)]));
const base=process.env.BASE_URL||'http://127.0.0.1:8089';
const docker=(args,input)=>execFileSync(process.platform==='win32'?'docker.exe':'docker',['compose',...args],{input,encoding:'utf8',timeout:45000});
const login=await fetch(base+'/api/auth/login',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({email:'admin@stockflow.local',password:env.DEMO_PASSWORD})}).then(r=>r.json());
const orders=await fetch(base+'/api/orders',{headers:{Authorization:'Bearer '+login.accessToken}}).then(r=>r.json());
const order=orders.find(o=>o.state==='CANCELLED');assert(order,'Run tests/system.mjs first to create a cancelled order fixture');
const event={eventId:crypto.randomUUID(),aggregateId:order.id,target:'inventory',kind:'UnsupportedVerificationCommand',payload:{},schemaVersion:1,occurredAt:new Date().toISOString()};
// A dedicated partition offset avoids reading an unrelated old dead-letter record.
const offsets=docker(['exec','-T','kafka','/opt/kafka/bin/kafka-get-offsets.sh','--bootstrap-server','kafka:9092','--topic','stockflow.inventory.DLT']);
const before=Object.fromEntries(offsets.trim().split('\n').filter(Boolean).map(l=>{const p=l.trim().split(':');return [p[1],Number(p[2])]}));
function publish(e){docker(['exec','-T','kafka','/opt/kafka/bin/kafka-console-producer.sh','--bootstrap-server','kafka:9092','--topic','stockflow.inventory','--property','parse.key=true','--property','key.separator=\t'],e.aggregateId+'\t'+JSON.stringify(e)+'\n');}
publish(event);
let dead,partition;
for(let i=0;i<15;i++){
 const now=docker(['exec','-T','kafka','/opt/kafka/bin/kafka-get-offsets.sh','--bootstrap-server','kafka:9092','--topic','stockflow.inventory.DLT']);
 for(const line of now.trim().split('\n')){const p=line.trim().split(':');if(Number(p[2])>(before[p[1]]||0)){partition=p[1];break;}}
 if(partition!==undefined){dead=docker(['exec','-T','kafka','/opt/kafka/bin/kafka-console-consumer.sh','--bootstrap-server','kafka:9092','--topic','stockflow.inventory.DLT','--partition',partition,'--offset',String(before[partition]||0),'--max-messages','1','--timeout-ms','10000']);break;}
 await new Promise(r=>setTimeout(r,1000));
}
assert(dead,'Expected failed event in DLT');assert.equal(JSON.parse(dead.trim()).eventId,event.eventId);
const count=()=>Number(docker(['exec','-T','postgres','psql','-U','postgres','-d','inventory','-tAc',`SELECT count(*) FROM processed_messages WHERE event_id='${event.eventId}'`]).trim());
assert.equal(count(),0,'Failed business transaction must not commit inbox entry');
const repaired={...event,kind:'Release'};fs.writeFileSync('artifacts/repaired-event.json',JSON.stringify(repaired,null,2));
execFileSync(process.platform==='win32'?'pwsh.exe':'pwsh',['-NoProfile','-File','scripts/replay-event.ps1','-File','artifacts/repaired-event.json','-Target','inventory'],{encoding:'utf8',timeout:45000});
for(let i=0;i<20&&count()===0;i++)await new Promise(r=>setTimeout(r,500));
assert.equal(count(),1);publish(repaired);await new Promise(r=>setTimeout(r,1000));assert.equal(count(),1);
fs.writeFileSync('artifacts/dead-letter-results.json',JSON.stringify({passed:true,eventId:event.eventId,checks:['invalid event reaches DLT','failed transaction does not commit inbox','repaired same-ID replay applies once','duplicate replay leaves one inbox entry']},null,2));
console.log('PASS dead-letter recovery and duplicate replay');
