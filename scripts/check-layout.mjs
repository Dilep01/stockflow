import fs from 'node:fs';
import assert from 'node:assert/strict';
for(const role of ['identity','catalog','inventory','orders','fulfillment','notification']){
 for(const file of fs.readdirSync(`backend/src/main/resources/db/${role}`)){
  assert.equal(fs.readFileSync(`backend/src/main/resources/db/${role}/${file}`,'utf8'),fs.readFileSync(`backend/src/main/resources/db/all/${file}`,'utf8'),`Lite migration differs: ${file}`);
 }
}
console.log('PASS service and lite migrations match');
