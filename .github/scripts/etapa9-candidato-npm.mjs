import fs from 'node:fs';
import assert from 'node:assert/strict';
import {spawnSync} from 'node:child_process';
import {createHash} from 'node:crypto';

const dir='candidato-etapa9/';
fs.mkdirSync(dir,{recursive:true});
const originalManifest=fs.readFileSync('package.json');
const originalLock=fs.readFileSync('package-lock.json');
const before=JSON.parse(originalLock);
const sha=buf=>createHash('sha256').update(buf).digest('hex');
fs.writeFileSync(dir+'original.sha256.json',JSON.stringify({manifest:sha(originalManifest),lock:sha(originalLock)},null,2));
function run(name,args){
  const result=spawnSync('npm',args,{encoding:'utf8',maxBuffer:32*1024*1024,timeout:360000,env:{...process.env,npm_config_ignore_scripts:'true',npm_config_force:'false'}});
  fs.writeFileSync(dir+name+'.json',result.stdout||'');
  fs.writeFileSync(dir+name+'.stderr.txt',result.stderr||'');
  fs.writeFileSync(dir+name+'.exit.txt',String(result.status));
  if(result.error) throw result.error;
  assert([0,1].includes(result.status),'Exit npm inesperado: '+name+' '+result.status);
  const obj=JSON.parse(result.stdout);
  assert(!obj.error,'Erro npm: '+name);
  return {obj,status:result.status};
}
try {
  // Resolve exact, published stable patches in the current 21.2 line.
  // Keep the manifest candidate explicit rather than rely on npm update heuristics.
  const manifest=JSON.parse(originalManifest);
  const framework=['common','compiler','core','forms','platform-browser','router','compiler-cli'].map(n=>'@angular/'+n);
  const tooling=['@angular/build','@angular/cli'];
  const catalog={};
  for(const name of [...framework,...tooling]){
    const r=spawnSync('npm',['view',name,'versions','--json'],{
      encoding:'utf8',maxBuffer:16*1024*1024,timeout:45000,env:{...process.env,npm_config_ignore_scripts:'true',npm_config_force:'false'}
    });
    if(r.error)throw r.error;
    assert.equal(r.status,0,'Registry query failed: '+name+' '+r.stderr);
    catalog[name]=JSON.parse(r.stdout).filter(v=>/^21\.2\.\d+$/.test(v));
    assert(catalog[name].length,'No stable 21.2 versions: '+name);
    fs.writeFileSync(dir+'angular-catalog.json',JSON.stringify(catalog,null,2));
  }
  function commonPatch(names,min){
    const candidates=catalog[names[0]].filter(v=>Number(v.split('.')[2])>=min&&names.every(n=>catalog[n].includes(v)));
    candidates.sort((a,b)=>Number(b.split('.')[2])-Number(a.split('.')[2]));
    assert(candidates.length,'No common published patch meeting minimum '+min+' for '+names.join(','));
    return candidates[0];
  }
  const chosen={framework:commonPatch(framework,25),tooling:commonPatch(tooling,21)};
  for(const [names,version] of [[framework,chosen.framework],[tooling,chosen.tooling]]){
    for(const name of names){
      const section=Object.hasOwn(manifest.dependencies,name)?'dependencies':'devDependencies';
      assert(Object.hasOwn(manifest[section],name),'Unexpected direct dependency '+name);
      manifest[section][name]=version;
    }
  }
  console.log('ANGULAR_PINS '+JSON.stringify(chosen));
  fs.writeFileSync(dir+'angular-pins.json',JSON.stringify(chosen,null,2));
  const expectedManifest=Buffer.from(JSON.stringify(manifest,null,2)+'\n');
  fs.writeFileSync('package.json',expectedManifest);
  fs.copyFileSync('package.json',dir+'package.json');
  const install=spawnSync('npm',['install','--package-lock-only','--ignore-scripts','--no-audit','--no-fund','--strict-peer-deps'],{
    encoding:'utf8',maxBuffer:32*1024*1024,timeout:360000,env:{...process.env,npm_config_ignore_scripts:'true',npm_config_force:'false'}
  });
  fs.writeFileSync(dir+'angular-install.stdout.txt',install.stdout||'');
  fs.writeFileSync(dir+'angular-install.stderr.txt',install.stderr||'');
  if(install.error)throw install.error;
  assert.equal(install.status,0,'Falha na instalação das versões explícitas: '+install.stderr);
  assert(fs.readFileSync('package.json').equals(expectedManifest),'Resolvedor alterou manifesto candidato');
  fs.copyFileSync('package-lock.json',dir+'angular-update.lock.json');
  const fix=run('fix',['audit','fix','--package-lock-only','--ignore-scripts','--json','--no-fund','--audit-level=low','--strict-peer-deps']);
  const afterBytes=fs.readFileSync('package-lock.json');
  const after=JSON.parse(afterBytes);
  fs.copyFileSync('package-lock.json',dir+'package-lock.json');
  fs.copyFileSync('package.json',dir+'package.json');
  assert(fs.readFileSync('package.json').equals(expectedManifest),'Manifesto candidato sofreu alterações inesperadas');
  assert.deepEqual(after.packages[''].dependencies,manifest.dependencies);
  assert.deepEqual(after.packages[''].devDependencies,manifest.devDependencies);
  for(const [key,val] of Object.entries(before.packages['']))if(!['dependencies','devDependencies'].includes(key))assert.deepEqual(after.packages[''][key],val);
  assert.equal(after.lockfileVersion,before.lockfileVersion);
  const direct=Object.keys({...manifest.dependencies,...manifest.devDependencies});
  const versions={};
  for(const name of direct){
    const old=before.packages['node_modules/'+name]?.version;
    const updated=after.packages['node_modules/'+name]?.version;
    assert(old&&updated,'Dependência direta ausente: '+name);
    assert.equal(updated.split('.')[0],old.split('.')[0],'Mudança de major não autorizada: '+name);
    versions[name]={antes:old,depois:updated};
  }
  for(const name of framework)assert.equal(versions[name].depois,chosen.framework,'Framework e compiler-cli devem permanecer alinhados');
  for(const name of tooling)assert.equal(versions[name].depois,chosen.tooling,'CLI e Build devem manter patches selecionados');
  assert.equal(versions['@angular/build'].depois,versions['@angular/cli'].depois,'Build e CLI devem permanecer alinhados');
  assert(versions['@angular/core'].depois!==versions['@angular/core'].antes,'Framework Angular permaneceu na versão vulnerável original');
  assert(versions['@angular/build'].depois!==versions['@angular/build'].antes,'Angular Build permaneceu na versão vulnerável original');
  fs.writeFileSync(dir+'versoes.json',JSON.stringify(versions,null,2));
  for(const scope of ['completo','producao']){
    const args=['audit','--json','--package-lock-only','--audit-level=low'];
    args.push(...(scope==='producao'?['--omit=dev']:['--include=dev','--include=optional','--include=peer']));
    const {obj,status}=run(scope,args);
    assert.equal(obj.auditReportVersion,2);
    assert(Number.isInteger(obj.metadata?.vulnerabilities?.total));
    assert(obj.vulnerabilities&&typeof obj.vulnerabilities==='object');
    assert.equal(status,obj.metadata.vulnerabilities.total>0?1:0);
    console.log('AUDITORIA_'+scope+' '+JSON.stringify(obj.metadata.vulnerabilities));
  }
  assert(fs.readFileSync('package-lock.json').equals(afterBytes),'Auditoria modificou lockfile');
  const changed=Object.entries(after.packages).filter(([key,val])=>JSON.stringify(val)!==JSON.stringify(before.packages[key]));
  const removed=Object.keys(before.packages).filter(key=>!Object.hasOwn(after.packages,key));
  const header=Object.fromEntries(Object.entries(after).filter(([k])=>k!=='packages'));
  const delta={header,order:Object.keys(after.packages),changed,removed,sha256:sha(afterBytes)};
  fs.writeFileSync(dir+'delta.json',JSON.stringify(delta));
  console.log('VERSOES_DIRETAS '+JSON.stringify(versions));
  console.log('CANDIDATE_SHA256 '+delta.sha256);
  console.log('CANDIDATE_MANIFEST_SHA256 '+sha(expectedManifest));
  console.log('CANDIDATE_COUNTS '+JSON.stringify({changed:changed.length,removed:removed.length,fixExit:fix.status}));
  // Emit compact, machine-readable delta to recover the candidate without
  // a write token or automated push. Artifact remains the authoritative copy.
  console.log('CANDIDATE_HEADER '+JSON.stringify(header));
  console.log('CANDIDATE_ORDER '+JSON.stringify(delta.order));
  for(const item of changed) console.log('CANDIDATE_PACKAGE '+JSON.stringify(item));
  console.log('CANDIDATE_REMOVED '+JSON.stringify(removed));
  console.log('CANDIDATE_END '+delta.sha256);
  fs.appendFileSync(process.env.GITHUB_STEP_SUMMARY,
    '## Candidato npm (diagnóstico, não publicado)\n\nNenhuma alteração na branch; revisar auditorias, versões e delta antes de publicar. Testes de aplicação ainda pendentes.\n');
} finally {
  // Changes are confined to the ephemeral checkout; never stage or push.
  fs.writeFileSync('package.json',originalManifest);
  fs.writeFileSync('package-lock.json',originalLock);
}
