const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs'),path=require('node:path'),os=require('node:os');
const {spawnSync}=require('node:child_process');
const {registerBuild,updateBuild,plan,gradleArguments}=require('./launch');
test('standalone launcher has no build-lock dependency',()=>{
  const dir=process.env.LOOM_LOCK_PARTICIPANTS,token=process.env.LOOM_LOCK_TOKEN;
  delete process.env.LOOM_LOCK_PARTICIPANTS;delete process.env.LOOM_LOCK_TOKEN;
  try{assert.equal(registerBuild({}),null);}finally{if(dir!==undefined)process.env.LOOM_LOCK_PARTICIPANTS=dir;if(token!==undefined)process.env.LOOM_LOCK_TOKEN=token;}
});
test('each named launch gets distinct persistent child lease metadata',t=>{
  const root=fs.mkdtempSync(path.join(os.tmpdir(),'puppet-lease-test-'));
  const dir=process.env.LOOM_LOCK_PARTICIPANTS,token=process.env.LOOM_LOCK_TOKEN;
  process.env.LOOM_LOCK_PARTICIPANTS=root;process.env.LOOM_LOCK_TOKEN='lease-token';
  t.after(()=>{if(dir===undefined)delete process.env.LOOM_LOCK_PARTICIPANTS;else process.env.LOOM_LOCK_PARTICIPANTS=dir;if(token===undefined)delete process.env.LOOM_LOCK_TOKEN;else process.env.LOOM_LOCK_TOKEN=token;fs.rmSync(root,{recursive:true,force:true});});
  const a=registerBuild({project:root,gameDir:path.join(root,'a'),name:'a',task:':fabric:runClient'});
  const b=registerBuild({project:root,gameDir:path.join(root,'b'),name:'b',task:':fabric:runClient'});
  assert.notEqual(a.file,b.file);updateBuild(a,{pid:123,phase:'building'});
  assert.equal(JSON.parse(fs.readFileSync(a.file)).pid,123);
  assert.equal(JSON.parse(fs.readFileSync(b.file)).name,'b');
  fs.rmSync(a.file);assert.doesNotThrow(()=>updateBuild(a,{phase:'exited'}));
});

test('wrapper exit cannot overwrite the separate Gradle daemon build phase',t=>{
  const root=fs.mkdtempSync(path.join(os.tmpdir(),'puppet-daemon-test-'));
  t.after(()=>fs.rmSync(root,{recursive:true,force:true}));
  const file=path.join(root,'lease.json');
  const data={token:'t',pid:123,phase:'building',gradleRecord:'lease.json.gradle.json'};
  fs.writeFileSync(file,JSON.stringify(data));
  const daemonFile=path.join(root,data.gradleRecord);
  const daemon={token:'t',hostname:os.hostname(),lease:'lease.json',buildPid:456,phase:'building'};
  fs.writeFileSync(daemonFile,JSON.stringify(daemon));
  updateBuild({file,data},{phase:'exited',wrapperExited:true});
  const result=JSON.parse(fs.readFileSync(file));
  assert.equal(result.phase,'exited');assert.equal(result.gradleRecord,data.gradleRecord);
  assert.deepEqual(JSON.parse(fs.readFileSync(daemonFile)),daemon);
});

test('daemon registration between wrapper read and rename survives the stale wrapper exit write',t=>{
  const root=fs.mkdtempSync(path.join(os.tmpdir(),'puppet-interleaved-lease-'));
  t.after(()=>fs.rmSync(root,{recursive:true,force:true}));
  const file=path.join(root,'lease.json'),data={token:'t',pid:123,phase:'building',
    gradleRecord:'lease.json.gradle.json'};
  fs.writeFileSync(file,JSON.stringify(data));
  const daemonFile=path.join(root,data.gradleRecord),daemon={token:'t',hostname:os.hostname(),
    lease:'lease.json',buildPid:456,phase:'building'};
  const rename=fs.renameSync;
  let interleaved=false;
  t.mock.method(fs,'renameSync',(from,to)=>{
    // Deterministic original failure order: wrapper read, daemon registers,
    // then the stale wrapper terminal snapshot commits.
    assert.equal(to,file);assert.equal(fs.existsSync(daemonFile),false);
    fs.writeFileSync(daemonFile,JSON.stringify(daemon));interleaved=true;
    rename(from,to);
  });
  updateBuild({file,data},{phase:'exited',wrapperExited:true});
  assert.equal(interleaved,true);assert.equal(JSON.parse(fs.readFileSync(file)).phase,'exited');
  assert.deepEqual(JSON.parse(fs.readFileSync(daemonFile)),daemon);
});

test('daemon ready written before wrapper PID update remains a distinct authoritative record',t=>{
  const root=fs.mkdtempSync(path.join(os.tmpdir(),'puppet-early-ready-'));
  t.after(()=>fs.rmSync(root,{recursive:true,force:true}));
  const file=path.join(root,'lease.json'),data={token:'t',pid:null,phase:'spawning',
    gradleRecord:'lease.json.gradle.json'};
  fs.writeFileSync(file,JSON.stringify(data));
  const daemonFile=path.join(root,data.gradleRecord),daemon={token:'t',hostname:os.hostname(),
    lease:'lease.json',buildPid:456,phase:'ready'};
  fs.writeFileSync(daemonFile,JSON.stringify(daemon));
  updateBuild({file,data},{pid:123,phase:'building'});
  assert.equal(JSON.parse(fs.readFileSync(file)).pid,123);
  assert.deepEqual(JSON.parse(fs.readFileSync(daemonFile)),daemon);
});

test('explicit init scripts resolve before launch and preserve whitespace in exact argv order',t=>{
  const root=fs.mkdtempSync(path.join(os.tmpdir(),'puppet-init files-'));
  t.after(()=>fs.rmSync(root,{recursive:true,force:true}));
  fs.mkdirSync(path.join(root,'neoforge'));
  const first=path.join(root,'pack dependencies.gradle'),second=path.join(root,'second.gradle');
  fs.writeFileSync(first,'// pack dependencies');fs.writeFileSync(second,'// second');
  const planned=plan('client',{project:root,loader:'neoforge',name:'task149',username:'Recipe161',
    initScripts:[path.relative(process.cwd(),first),second]});
  assert.deepEqual(planned.initScripts,[first,second]);
  assert.deepEqual(gradleArguments(planned,{file:path.join(root,'lease.json')}),[
    '--init-script',path.join(__dirname,'launch.init.gradle'),
    '--init-script',first,'--init-script',second,':neoforge:runClient','--console=plain',
    '-Pmc_puppet.build_lease='+path.join(root,'lease.json'),'-Pmc_puppet.build_task=:neoforge:runClient',
    '-Pmc_puppet.run_dir=runs/task149','-Pmc_puppet.username=Recipe161'
  ]);
});

test('invalid explicit init scripts refuse before preparation or spawn',t=>{
  const root=fs.mkdtempSync(path.join(os.tmpdir(),'puppet-init-validation-'));
  t.after(()=>fs.rmSync(root,{recursive:true,force:true}));
  const directory=path.join(root,'directory.gradle');fs.mkdirSync(directory);
  const wrong=path.join(root,'plain.txt');fs.writeFileSync(wrong,'not an init script');
  for(const file of ['', '   ',path.join(root,'missing.gradle'),directory,wrong]) {
    assert.throws(()=>plan('client',{project:root,loader:'fabric',name:'newgame',initScripts:[file]}),/init-script/);
  }
  assert.throws(()=>plan('client',{project:root,initScripts:'not-a-list'}),/must be a list/);
  assert.equal(fs.existsSync(path.join(root,'runs','newgame')),false);
});

test('unreadable explicit init script is rejected',t=>{
  const root=fs.mkdtempSync(path.join(os.tmpdir(),'puppet-init-denied-'));
  t.after(()=>fs.rmSync(root,{recursive:true,force:true}));
  const file=path.join(root,'denied.gradle');fs.writeFileSync(file,'// unreadable');
  t.mock.method(fs,'accessSync',()=>{throw Object.assign(new Error('permission denied'),{code:'EACCES'});});
  assert.throws(()=>plan('client',{project:root,initScripts:[file]}),/cannot read --init-script.*permission denied/);
});

test('ordinary launcher argv remains unchanged without additional init scripts',()=>{
  const planned=plan('server',{project:process.cwd(),loader:'nonexistent-test-loader'});
  assert.deepEqual(gradleArguments(planned,null),[
    '--init-script',path.join(__dirname,'launch.init.gradle'),'runServer','--console=plain'
  ]);
});

test('CLI parses every repeated init flag and rejects a missing value without launching Gradle',t=>{
  const root=fs.mkdtempSync(path.join(os.tmpdir(),'puppet-cli-init-'));
  t.after(()=>fs.rmSync(root,{recursive:true,force:true}));
  const first=path.join(root,'first file.gradle'),missing=path.join(root,'missing.gradle');
  fs.writeFileSync(first,'// first');
  const cli=path.join(__dirname,'puppet.js');
  const result=spawnSync(process.execPath,[cli,'launch','client','--project',root,
    '--init-script',first,'--init-script',missing],{encoding:'utf8',windowsHide:true});
  assert.equal(result.status,2);assert.match(result.stderr,/cannot read --init-script/);
  assert.ok(result.stderr.includes(missing));assert.doesNotMatch(result.stderr,/no Gradle wrapper/);
  assert.equal(fs.existsSync(path.join(root,'build')),false);
  const noValue=spawnSync(process.execPath,[cli,'--init-script','--help'],{encoding:'utf8',windowsHide:true});
  assert.equal(noValue.status,2);assert.match(noValue.stderr,/needs a .gradle file path/);
});
