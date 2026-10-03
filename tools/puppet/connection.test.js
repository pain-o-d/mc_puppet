const {test}=require('node:test');
const assert=require('node:assert/strict');
const net=require('node:net'),fs=require('node:fs'),path=require('node:path'),os=require('node:os');
const {Connection,Puppet}=require('./lib');

const closed=socket=>new Promise(resolve=>socket.once('close',resolve));
async function dispose(connection) {
  const socket=connection.socket;
  const done=socket?closed(socket):Promise.resolve();
  connection.close();
  await done;
}
async function peer(t,handle) {
  const sockets=new Set(),requests=[];
  let accepted=0;
  const server=net.createServer(socket=>{
    accepted++;sockets.add(socket);socket.setEncoding('utf8');
    socket.on('close',()=>sockets.delete(socket));
    socket.on('error',()=>{}); // A test deliberately closes/refuses transports.
    let buffer='';
    socket.on('data',data=>{
      buffer+=data;let end;
      while((end=buffer.indexOf('\n'))>=0) {
        const request=JSON.parse(buffer.slice(0,end));buffer=buffer.slice(end+1);
        requests.push(request);
        const reply=result=>socket.write(JSON.stringify({id:request.id,ok:true,result})+'\n');
        if(handle)handle(request,reply,socket);else reply(request);
      }
    });
  });
  await new Promise((resolve,reject)=>{server.once('error',reject);server.listen(0,'127.0.0.1',resolve);});
  t.after(async()=>{
    const waits=[...sockets].map(socket=>{const done=closed(socket);socket.destroy();return done;});
    await Promise.all(waits);
    if(server.listening)await new Promise((resolve,reject)=>server.close(error=>error?reject(error):resolve()));
  });
  return {server,requests,get accepted(){return accepted;},endpoint:{side:'server',port:server.address().port,
    protocol:1,pid:process.pid,token:'unit-test-token',dir:os.tmpdir()}};
}

test('cold parallel Puppet calls share one exact endpoint transport and preserve every response',async t=>{
  const remote=await peer(t),dir=fs.mkdtempSync(path.join(os.tmpdir(),'puppet-connection-'));
  t.after(()=>fs.rmSync(dir,{recursive:true,force:true}));
  fs.mkdirSync(path.join(dir,'mc_puppet'));
  fs.writeFileSync(path.join(dir,'mc_puppet','endpoint-server.json'),JSON.stringify(remote.endpoint));
  const puppet=new Puppet(['local='+dir]);
  t.after(async()=>{await Promise.all([...puppet.connections.values()].map(dispose));puppet.close();});
  const replies=await Promise.all(Array.from({length:16},(_,n)=>puppet.call('server@local','echo',{n},2000)));
  assert.equal(remote.accepted,1);assert.equal(remote.requests.length,16);
  assert.equal(new Set(replies.map(r=>r.id)).size,16);
  assert.deepEqual(replies.map(r=>r.args.n),Array.from({length:16},(_,n)=>n));
  assert.ok(replies.every(r=>r.token===remote.endpoint.token));
});

test('direct concurrent connect and call retain one socket/pending promise',async t=>{
  const remote=await peer(t),connection=new Connection(remote.endpoint);
  t.after(()=>dispose(connection));
  const first=connection.connect();
  assert.ok(connection.socket);assert.equal(connection.connect(),first);
  const replies=await Promise.all(Array.from({length:32},(_,n)=>connection.call('echo',{n},2000)));
  await first;
  assert.equal(remote.accepted,1);assert.equal(remote.requests.length,32);
  assert.deepEqual(replies.map(r=>r.args.n),Array.from({length:32},(_,n)=>n));
});

test('close before connect rejects every waiter, destroys pending socket and permits a fresh attempt',async t=>{
  const remote=await peer(t),connection=new Connection(remote.endpoint);
  t.after(()=>dispose(connection));
  const connecting=connection.connect(),pendingSocket=connection.socket,done=closed(pendingSocket);
  const calls=[connection.call('never',{n:1},2000),connection.call('never',{n:2},2000)];
  const settled=Promise.allSettled([connecting,...calls]);
  connection.close();
  const results=await settled;await done;
  assert.ok(results.every(r=>r.status==='rejected'&&/closed/.test(r.reason.message)));
  assert.equal(pendingSocket.destroyed,true);assert.equal(connection.waiting.size,0);
  assert.equal(connection.socket,null);assert.equal(connection.connecting,null);
  const reply=await connection.call('fresh',{n:3},2000);
  assert.equal(reply.op,'fresh');assert.equal(remote.requests.length,1);
});

test('close does not migrate a just-connected request onto the next attempt',async t=>{
  const remote=await peer(t),connection=new Connection(remote.endpoint);
  t.after(()=>dispose(connection));
  await connection.connect();
  const oldSocket=connection.socket,done=closed(oldSocket);
  const pending=connection.call('must-not-send',{},2000),settled=Promise.allSettled([pending]);
  connection.close();
  const fresh=connection.call('fresh',{},2000);
  assert.equal((await settled)[0].status,'rejected');
  assert.equal((await fresh).op,'fresh');await done;
  assert.deepEqual(remote.requests.map(r=>r.op),['fresh']);
});

test('late retired socket events cannot clear a current request or its response buffer',async t=>{
  let answer,received;
  const incoming=new Promise(resolve=>received=resolve);
  const remote=await peer(t,(request,reply)=>{if(request.op==='later'){answer=()=>reply('new-response');received();}else reply('old-response');});
  const connection=new Connection(remote.endpoint);t.after(()=>dispose(connection));
  await connection.call('first',{},2000);
  const old=connection.socket,done=closed(old);connection.close();
  await done;await connection.connect();
  const current=connection.socket;
  const request=connection.call('later',{},2000);
  // The real new TCP peer receiving this request is the gate, not a sleep.
  await Promise.race([incoming,request.then(()=>{throw new Error('response before test gate');})]);
  old.emit('data','{"id":2');old.emit('close');old.emit('error',new Error('late retired error'));
  assert.equal(connection.socket,current);assert.equal(connection.waiting.size,1);
  answer();assert.equal(await request,'new-response');
});

test('destroyed socket rejects old pending requests before a delayed close reaches the next attempt',async t=>{
  let observed;
  const incoming=new Promise(resolve=>observed=resolve);
  const remote=await peer(t,(request,reply)=>{if(request.op==='old-pending')observed();else reply(request);});
  const connection=new Connection(remote.endpoint);t.after(()=>dispose(connection));
  const old=connection.call('old-pending',{},2000),oldResult=Promise.allSettled([old]);
  await Promise.race([incoming,old.then(()=>{throw new Error('unexpected old response');})]);
  const retired=connection.socket,done=closed(retired);retired.destroy();
  const next=connection.call('fresh',{},2000);
  assert.equal(connection.waiting.size,0);
  assert.equal((await oldResult)[0].status,'rejected');
  assert.equal((await next).op,'fresh');await done;
  assert.equal(remote.accepted,2);
});

test('real refused connect clears pending state and allows explicit subsequent reconnect',async t=>{
  const remote=await peer(t),port=remote.endpoint.port;
  await new Promise((resolve,reject)=>remote.server.close(error=>error?reject(error):resolve()));
  const connection=new Connection(remote.endpoint);t.after(()=>dispose(connection));
  await assert.rejects(connection.connect(),e=>e.code==='ECONNREFUSED');
  assert.equal(connection.socket,null);assert.equal(connection.connecting,null);
  await new Promise((resolve,reject)=>{remote.server.once('error',reject);remote.server.listen(port,'127.0.0.1',resolve);});
  assert.equal((await connection.call('fresh',{},2000)).op,'fresh');
});

test('Puppet close between resolved side and request commitment does not reconnect old work',async t=>{
  const remote=await peer(t),dir=fs.mkdtempSync(path.join(os.tmpdir(),'puppet-close-boundary-'));
  t.after(()=>fs.rmSync(dir,{recursive:true,force:true}));fs.mkdirSync(path.join(dir,'mc_puppet'));
  fs.writeFileSync(path.join(dir,'mc_puppet','endpoint-server.json'),JSON.stringify(remote.endpoint));
  const puppet=new Puppet(['local='+dir]);
  t.after(async()=>{await Promise.all([...puppet.connections.values()].map(dispose));puppet.close();});
  const held=await puppet.side('server@local');
  const done=closed(held.socket);
  const pending=puppet.call('server@local','must-not-send',{},2000);
  const refusal=assert.rejects(pending,/closed before/);
  puppet.close();
  await refusal;
  await done;assert.equal(remote.requests.length,0);assert.equal(puppet.connections.size,0);
  assert.equal((await puppet.call('server@local','fresh',{},2000)).op,'fresh');
  assert.equal(remote.accepted,2);
});

test('real connection failure rejects pending work and a later caller can reconnect',async t=>{
  let first=true;
  const remote=await peer(t,(request,reply,socket)=>{if(first){first=false;socket.destroy();}else reply(request);});
  const connection=new Connection(remote.endpoint);t.after(()=>dispose(connection));
  await assert.rejects(connection.call('fail',{},2000),/closed|ECONNRESET/);
  assert.equal(connection.waiting.size,0);assert.equal(connection.connecting,null);
  assert.equal((await connection.call('fresh',{},2000)).op,'fresh');
  assert.equal(remote.accepted,2);
});

test('endpoint token restart cancels old work and port identity cannot reuse an older transport',async t=>{
  let oldReceived;
  let expectedToken='old-token';
  const received=new Promise(resolve=>oldReceived=resolve);
  const remote=await peer(t,(request,reply,socket)=>{
    if(request.token!==expectedToken)socket.write(JSON.stringify({id:request.id,ok:false,error:'test peer refused token'})+'\n');
    else if(request.op==='old-pending')oldReceived();else reply(request);
  });
  const next=await peer(t),dir=fs.mkdtempSync(path.join(os.tmpdir(),'puppet-restart-'));
  t.after(()=>fs.rmSync(dir,{recursive:true,force:true}));fs.mkdirSync(path.join(dir,'mc_puppet'));
  const endpointFile=path.join(dir,'mc_puppet','endpoint-server.json');
  const write=endpoint=>fs.writeFileSync(endpointFile,JSON.stringify(endpoint));
  write({...remote.endpoint,token:'old-token'});
  const puppet=new Puppet(['local='+dir]);
  t.after(async()=>{await Promise.all([...puppet.connections.values()].map(dispose));puppet.close();});
  const old=puppet.call('server@local','old-pending',{},2000),oldResult=Promise.allSettled([old]);
  await received;
  const retired=puppet.connections.get('server@local'),retiredDone=closed(retired.socket);
  expectedToken='new-token';
  write({...remote.endpoint,token:'new-token'});
  const fresh=await puppet.call('server@local','new-request',{},2000);
  assert.equal(fresh.token,'new-token');assert.equal((await oldResult)[0].status,'rejected');await retiredDone;
  assert.equal(remote.accepted,2);assert.equal(retired.endpoint.token,'old-token');
  const staleCredential=new Connection({...remote.endpoint,token:'old-token'});
  t.after(()=>dispose(staleCredential));
  await assert.rejects(staleCredential.call('stale-token',{},2000),/test peer refused token/);
  assert.equal(staleCredential.waiting.size,0);
  write({...next.endpoint,token:'new-token'});
  const moved=await puppet.call('server@local','different-port',{},2000);
  assert.equal(moved.token,'new-token');assert.equal(next.accepted,1);
  assert.equal(remote.requests.filter(r=>r.op==='new-request').length,1);
});
