const fs = require('node:fs/promises');
const path = require('node:path');
const os = require('node:os');
const crypto = require('node:crypto');
const { performance } = require('node:perf_hooks');
const { Engine } = require(process.argv[2] ? path.resolve(process.argv[2]) : path.join(__dirname, '..', 'engine.cjs'));
const runs = Number(process.argv[3] || 3);
const size = 128 * 1024 * 1024;
(async () => {
  const root = await fs.mkdtemp(path.join(os.tmpdir(), 'appsend-bench-'));
  const source = path.join(root, 'source.bin');
  const block = crypto.randomBytes(1024 * 1024);
  const digest = crypto.createHash('sha256');
  const file = await fs.open(source, 'wx');
  try { for (let i=0; i<size/block.length; i++) { await file.write(block); digest.update(block); } } finally { await file.close(); }
  const expected = digest.digest('hex');
  const results = [];
  try {
    for (let i=0; i<runs; i++) {
      let a,b,received,transferMs;
      let resolve,reject;
      const done=new Promise((r,j)=>{resolve=r;reject=j;});
      const timeout=setTimeout(()=>reject(Error('Benchmark timed out')),60000);
      const event = side => (type,data) => {
        if(type==='consent') (side==='a'?a:b).approve();
        if(type==='error') reject(Error(String(data)));
        if(type==='done'&&side==='b') received=data.path;
        if(type==='done'&&side==='a') resolve();
      };
      a=new Engine({name:'Benchmark sender',folder:path.join(root,'a'+i),event:event('a')});
      b=new Engine({name:'Benchmark receiver',folder:path.join(root,'b'+i),event:event('b')});
      const original=a.sendData.bind(a);
      a.sendData=async s=>{const start=performance.now();await original(s);transferMs=performance.now()-start;};
      try {
        const ports=await Promise.all([a.start(),b.start()]);
        await a.stageFile(source);a.connect({host:'127.0.0.1',port:ports[1]});
        await done;
        const hash=crypto.createHash('sha256');
        for await (const data of require('node:fs').createReadStream(received)) hash.update(data);
        if(hash.digest('hex')!==expected) throw Error('Received hash mismatch');
        const row={run:i+1,bytes:size,transferMs,MiBps:size/1048576/(transferMs/1000),sha256Match:true};
        results.push(row); console.log(JSON.stringify(row));
      } finally {clearTimeout(timeout);a.close();b.close();}
    }
    console.log(JSON.stringify({medianMiBps:results.map(x=>x.MiBps).sort((a,b)=>a-b)[Math.floor(results.length/2)]}));
  } finally {await fs.rm(root,{recursive:true,force:true});}
})().catch(e=>{console.error(e);process.exitCode=1;});
