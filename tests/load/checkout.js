import http from 'k6/http';
import {check,sleep} from 'k6';
export const options={vus:20,iterations:100,thresholds:{http_req_failed:['rate<0.01'],checks:['rate==1']}};
export default function(){
 const base=__ENV.BASE_URL||'http://host.docker.internal:8089';
 const response=http.post(base+'/api/orders',JSON.stringify({address:'Synthetic load fixture',items:[{skuId:__ENV.SKU_ID,quantity:1}]}),{headers:{'Content-Type':'application/json',Authorization:'Bearer '+__ENV.ACCESS_TOKEN,'Idempotency-Key':`k6-${__ENV.RUN_ID}-${__VU}-${__ITER}`}});
 check(response,{'checkout accepted for asynchronous processing':r=>r.status===202});sleep(.1);
}
