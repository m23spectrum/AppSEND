const test=require('node:test'),assert=require('node:assert/strict');
const {sample}=require('../ui/motion.js');
test('handoff crosses the edge before the receiving card appears',()=>{
  for(const plane of [true,false]) {
    for(const ms of [-200,0,150,349,350])assert.equal(sample(ms,true,plane).alpha,0);
    assert.equal(sample(350,false,plane).alpha,0);
    assert.ok(sample(450,true,plane).alpha>0);
    assert.equal(sample(350,true,plane).glow,1);
    assert.equal(sample(1600,true,plane).alpha,0);
  }
});
test('the thrown file settles at the landing point',()=>{
  assert.equal(sample(400,true,true).file,1);
  assert.equal(sample(1000,true,true).file,1);
  assert.equal(sample(1000,true,true).progress,1);
  assert.ok(sample(850,true,true).progress>1);
  for(let ms=-100;ms<=1700;ms+=10)for(const incoming of [true,false])for(const plane of [true,false]) {
    const f=sample(ms,incoming,plane);for(const v of Object.values(f))assert.ok(Number.isFinite(v));
    assert.ok(f.alpha>=0 && f.alpha<=1);
  }
});
