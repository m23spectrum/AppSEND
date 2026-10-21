const fs=require('node:fs'),path=require('node:path'),crypto=require('node:crypto');
const {Engine}=require('../engine.cjs');
const {Discovery}=require('../discovery.cjs');
const root=path.resolve(process.argv[2]||'device-check');fs.mkdirSync(root,{recursive:true});
const events=[];let discovery;
const engine=new Engine({name:'AppSEND hardware check',folder:path.join(root,'received'),event:(type,data)=>{
 events.push({type,data});
 if(type==='consent')engine.approve();
 if(type==='done'){
  const files=fs.readdirSync(path.join(root,'received')).filter(x=>!x.endsWith('.part'));
  const file=files.find(x=>fs.readFileSync(path.join(root,'received',x),'utf8')==='AppSEND 1.5 phone-to-PC LAN check — generated test data');
  fs.writeFileSync(path.join(root,'report.json'),JSON.stringify({received:!!file,file,sha256:file&&crypto.createHash('sha256').update(fs.readFileSync(path.join(root,'received',file))).digest('hex'),events},null,2));
 }
 if(type==='error')fs.writeFileSync(path.join(root,'error.txt'),String(data));
}});
engine.start().then(port=>{
 discovery=new Discovery(crypto.randomUUID(),'AppSEND hardware check',port,()=>{},e=>fs.writeFileSync(path.join(root,'discovery-error.txt'),String(e)));
 fs.writeFileSync(path.join(root,'ready.json'),JSON.stringify({port,started:new Date().toISOString()}));
});
const close=()=>{discovery?.close();engine.close();process.exit()};
process.on('SIGINT',close);process.on('SIGTERM',close);setTimeout(close,300000);

