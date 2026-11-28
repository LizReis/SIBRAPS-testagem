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
  // npm audit fix alone kept the framework and build at their old patches.
  // Resolve the declared Angular ranges together before remediating transitives.
  const angular=Object.keys({...JSON.parse(originalManifest).dependencies,...JSON.parse(originalManifest).devDependencies})
    .filter(name=>name.startsWith('@angular/'));
  const update=spawnSync('npm',['update',...angular,'--package-lock-only','--ignore-scripts','--no-save','--no-audit','--no-fund','--strict-peer-deps'],{
    encoding:'utf8',maxBuffer:32*1024*1024,timeout:360000,env:{...process.env,npm_config_ignore_scripts:'true',npm_config_force:'false'}
  });
  fs.writeFileSync(dir+'angular-update.stdout.txt',update.stdout||'');
  fs.writeFileSync(dir+'angular-update.stderr.txt',update.stderr||'');
  if(update.error)throw update.error;
  assert.equal(update.status,0,'Falha na resolução coordenada do Angular');
  assert(fs.readFileSync('package.json').equals(originalManifest),'Atualização Angular modificou manifesto');
  fs.copyFileSync('package-lock.json',dir+'angular-update.lock.json');
  const fix=run('fix',['audit','fix','--package-lock-only','--ignore-scripts','--json','--no-fund','--audit-level=low','--strict-peer-deps']);
  const afterBytes=fs.readFileSync('package-lock.json');
  const after=JSON.parse(afterBytes);
  fs.copyFileSync('package-lock.json',dir+'package-lock.json');
  fs.copyFileSync('package.json',dir+'package.json');
  assert(fs.readFileSync('package.json').equals(originalManifest),'Manifesto alterado: requer análise específica, não publicar');
  assert.deepEqual(after.packages[''],before.packages[''],'Dependências declaradas não devem mudar');
  assert.equal(after.lockfileVersion,before.lockfileVersion);
  const manifest=JSON.parse(originalManifest);
  const direct=Object.keys({...manifest.dependencies,...manifest.devDependencies});
  const versions={};
  for(const name of direct){
    const old=before.packages['node_modules/'+name]?.version;
    const updated=after.packages['node_modules/'+name]?.version;
    assert(old&&updated,'Dependência direta ausente: '+name);
    assert.equal(updated.split('.')[0],old.split('.')[0],'Mudança de major não autorizada: '+name);
    versions[name]={antes:old,depois:updated};
  }
  const framework=['common','compiler','core','forms','platform-browser','router','compiler-cli'];
  assert.equal(new Set(framework.map(n=>versions['@angular/'+n].depois)).size,1,'Framework e compiler-cli devem permanecer alinhados');
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
