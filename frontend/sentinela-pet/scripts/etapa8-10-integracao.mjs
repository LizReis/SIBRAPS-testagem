// Browser + actual Spring Boot HTTP + isolated in-memory H2. No API fixtures.
import assert from 'node:assert/strict';
import { createServer, request } from 'node:http';
import { readFile, mkdir, writeFile } from 'node:fs/promises';
import { resolve, extname, sep } from 'node:path';
import { createRequire } from 'node:module';
import { once } from 'node:events';

const root=resolve(process.argv[2]||'dist/sentinela-pet/browser');
const output=resolve(process.argv[3]||'integracao-evidence');
const password=process.env.SIBRAPS_ENSAIO_SENHA;
assert(password && password.length>=16,'Senha efêmera obrigatória');
const {chromium}=createRequire(resolve(process.env.PLAYWRIGHT_PREFIX||'.','package.json'))('playwright');
const api='/api/ensaio-escritores';
const mime={'.html':'text/html','.js':'text/javascript','.css':'text/css','.json':'application/json','.svg':'image/svg+xml','.png':'image/png','.jpg':'image/jpeg','.webp':'image/webp','.woff2':'font/woff2','.woff':'font/woff','.ico':'image/x-icon'};
await mkdir(output,{recursive:true});
const server=createServer(async(req,res)=>{
  try {
    const url=new URL(req.url,'http://127.0.0.1');
    if(url.pathname.startsWith('/api/')){
      const upstream=request({hostname:'127.0.0.1',port:8080,path:url.pathname+url.search,method:req.method,headers:{...req.headers,host:'127.0.0.1:8080'}},r=>{res.writeHead(r.statusCode,r.headers);r.pipe(res);});
      upstream.on('error',()=>{if(!res.headersSent)res.writeHead(502);res.end();});
      req.pipe(upstream);return;
    }
    const file=resolve(root,'.'+decodeURIComponent(url.pathname));
    if(file!==root&&!file.startsWith(root+sep)){res.writeHead(403);res.end();return;}
    const target=extname(file)?file:resolve(root,'index.html');
    const data=await readFile(target);
    res.writeHead(200,{'Content-Type':mime[extname(target)]||'application/octet-stream'});res.end(data);
  } catch {res.writeHead(404);res.end();}
});
server.listen(0,'127.0.0.1');await once(server,'listening');
const base='http://127.0.0.1:'+server.address().port;
const cpf='52998224725',cns='700000000000005',nome='Paciente Sintético Integração';
const results=[],http=[],pageErrors=[],contexts=[];
let browser,phase='inicialização';
function expectResponse(page,method,path,status,filter=()=>true){
  return page.waitForResponse(r=>new URL(r.url()).pathname===path&&r.request().method()===method&&filter(new URL(r.url()))).then(r=>{
    assert.equal(r.status(),status,method+' '+path+' deve retornar '+status);return r;
  });
}
async function step(name,fn){
  phase=name;await fn();results.push({name,status:'PASS'});console.log('PASS '+name);
}
async function login(perfil,unidade){
  const ctx=await browser.newContext({viewport:{width:1366,height:768},locale:'pt-BR',timezoneId:'America/Bahia'});
  contexts.push(ctx);
  await ctx.route('**/*',r=>new URL(r.request().url()).origin===base?r.continue():r.abort());
  const page=await ctx.newPage();page.setDefaultTimeout(15000);
  page.on('pageerror',e=>pageErrors.push(e.message));
  page.on('response',r=>{const u=new URL(r.url());if(u.pathname.startsWith('/api/'))http.push({perfil,unidade,method:r.request().method(),path:u.pathname,status:r.status()});});
  await page.goto(base+'/login');
  await page.locator('#email').fill(perfil.toLowerCase()+'.'+unidade+'@test.local');
  await page.locator('#password').fill(password);
  const resp=expectResponse(page,'POST','/api/auth/login',200);
  await page.locator('button[type=submit]').click();await resp;
  await page.waitForURL('**/inicio');
  await page.getByRole('heading',{name:'Visão geral da unidade'}).waitFor();
  await page.locator('.quick-action[href="/pacientes/novo"]').waitFor();
  const token=await page.evaluate(()=>localStorage.getItem('token'));
  assert(token,'Login deve gerar sessão');
  return {page,token};
}
async function get(actor,path,status=200){
  const r=await actor.page.request.get(base+path,{headers:{Authorization:'Bearer '+actor.token}});
  assert.equal(r.status(),status,'GET '+path);return status===200?r.json():undefined;
}
async function detail(actor,paciente){
  return get(actor,api+'/pacientes/'+paciente+'/detalhe');
}
async function shot(page,name){await page.screenshot({path:resolve(output,name+'.png'),animations:'disabled'});}
async function openForm(page){
  const unidades=expectResponse(page,'GET','/api/unidades-saude',200);
  await page.goto(base+'/pacientes/novo');await page.getByRole('heading',{name:'Cadastrar paciente',exact:true}).waitFor();
  const dados=await (await unidades).json();
  assert(Array.isArray(dados)&&dados.some(u=>u.nomeUsf==='USF sintética A'),'API deve retornar a USF sintética A');
  await page.locator('#usfReferencia option').filter({hasText:'USF sintética A'}).waitFor({state:'attached'});
}
try {
  browser=await chromium.launch({headless:true});
  let a,b,admin,pessoa,localA,localB;
  await step('login real dos três perfis em contextos independentes',async()=>{
    a=await login('PROFISSIONAL','a');b=await login('RECEPCAO','b');admin=await login('ADMINISTRADOR','a');
    for(const [actor,perfil] of [[a,'PROFISSIONAL'],[b,'RECEPCAO'],[admin,'ADMINISTRADOR']]){
      const me=await get(actor,'/api/usuarios/me');assert.equal(me.tipoUsuario,perfil);
    }
  });
  await step('profissional cria paciente pelo formulário real',async()=>{
    const p=a.page;await openForm(p);
    for(const [id,value] of Object.entries({nome,nomeMae:'Responsável Sintética Integração',dataNascimento:'1984-12-05',cpf,cns,telefone:'81999990001'}))await p.locator('#'+id).fill(value);
    for(const [id,value] of Object.entries({sexo:'FEMININO',racacor:'PARDA',capsReferencia:'CAPS_AD',tipoAcompanhamento:'AMBOS'}))await p.locator('#'+id).selectOption(value);
    await p.locator('#situacaoRua').selectOption({label:'Sim'});
    await p.locator('#usfReferencia').selectOption({label:'USF sintética A'});
    const resp=expectResponse(p,'POST',api+'/pacientes',201);
    await p.locator('form').filter({has:p.locator('#nome')}).locator('button[type=submit]').click();
    const created=await (await resp).json();pessoa=created.paciente;localA=created.vinculo;
    assert(pessoa&&localA,'Cadastro retorna identidade e vínculo');
    await p.waitForURL('**/pacientes/detalhes/'+pessoa+'?*');
    await p.getByRole('heading',{name:nome,exact:true}).waitFor();
    const d=await detail(a,pessoa);assert.equal(d.paciente.vinculo,localA);
    assert.equal(d.paciente.indicadores.tipoAcompanhamento,'AMBOS');await shot(p,'cadastro-usf-a');
  });
  await step('unidade B não consulta prontuário antes de possuir vínculo',async()=>{
    await get(b,api+'/pacientes/'+pessoa+'/detalhe',404);
    const p=b.page;await p.goto(base+'/pacientes');await p.getByRole('heading',{name:'Pacientes',exact:true}).waitFor();
    const consulta=expectResponse(p,'GET',api+'/pacientes/pesquisa',200,u=>u.searchParams.get('q')===cpf);
    await p.getByPlaceholder('Nome, CPF ou CNS').fill(cpf);
    const r=await consulta;assert.equal((await r.json()).pacientes.length,0,'Sem paciente local na USF B');
    await p.getByText('Nenhum paciente encontrado.',{exact:true}).waitFor();
  });
  await step('recepção B confirma identidade e cria somente vínculo local',async()=>{
    const p=b.page;await openForm(p);await p.locator('#cpf-existente').fill(cpf);
    const consulta=expectResponse(p,'POST',api+'/pacientes/cadastro-unico/conferencia',200);
    await p.getByRole('button',{name:'Conferir cadastro existente'}).click();
    const identity=await (await consulta).json();assert.equal(identity.nome,nome);
    assert.equal(identity.idPublico,undefined,'Conferência não devolve identidade técnica');
    await p.getByRole('heading',{name:'Confira com o paciente antes de continuar'}).waitFor();
    const confirmar=p.getByRole('button',{name:'Confirmar e vincular à minha unidade'});
    assert(await confirmar.isDisabled());await p.locator('app-cadastro-existente input[type=checkbox]').check();
    await p.locator('#modalidade-vinculo').selectOption('GRUPO_TERAPEUTICO');
    const vinculo=expectResponse(p,'POST',api+'/pacientes/cadastro-unico/confirmar-vinculo',204);
    await confirmar.click();await vinculo;
    await p.getByText('Vínculo com a sua unidade confirmado. Não foi criado outro cadastro.',{exact:true}).waitFor();
    const d=await detail(b,pessoa);localB=d.paciente.vinculo;
    assert(localB&&localB!==localA);assert.equal(d.paciente.idPublico,pessoa);
    assert.equal(d.paciente.indicadores.tipoAcompanhamento,'GRUPO_TERAPEUTICO');
    await shot(p,'vinculacao-usf-b');
  });
  await step('recepção edita cadastro compartilhado sem alterar modalidade local',async()=>{
    const p=b.page;await p.goto(base+'/pacientes/detalhes/'+pessoa+'?vinculo='+localB);
    await p.getByRole('link',{name:'Editar cadastro compartilhado'}).click();
    await p.waitForURL('**/pacientes/editar/'+pessoa);
    await p.waitForFunction(n=>document.querySelector('#nome')?.value===n,nome);
    assert.equal(await p.locator('#tipoAcompanhamento').count(),0);
    await p.locator('#telefone').fill('81999990002');
    const resp=expectResponse(p,'PUT',api+'/pacientes/'+pessoa+'/vinculos/'+localB+'/cadastro',200);
    await p.locator('form').filter({has:p.locator('#nome')}).locator('button[type=submit]').click();await resp;
    await p.waitForURL('**/pacientes/detalhes/'+pessoa+'?*');
    const da=await detail(a,pessoa),db=await detail(b,pessoa);
    assert.equal(da.cadastro.telefone,'81999990002');assert.equal(db.cadastro.telefone,'81999990002');
    assert.equal(da.paciente.indicadores.tipoAcompanhamento,'AMBOS');
    assert.equal(db.paciente.indicadores.tipoAcompanhamento,'GRUPO_TERAPEUTICO');
    assert.equal(da.paciente.versaoCadastro,db.paciente.versaoCadastro);
    assert.equal((await detail(admin,pessoa)).paciente.vinculo,localA);
    const denied=await b.page.request.put(base+api+'/pacientes/'+pessoa+'/vinculos/'+localA+'/cadastro?versaoCadastro='+db.paciente.versaoCadastro,{headers:{Authorization:'Bearer '+b.token},data:{nome,nomeMae:'Responsável Sintética Integração',dataNascimento:'1984-12-05',cpf,cns,telefone:'81999990003',sexo:'FEMININO',racacor:'PARDA',situacaoRua:true,usfReferencia:{id:da.cadastro.usfReferenciaId},capsReferencia:'CAPS_AD'}});
    assert.equal(denied.status(),404,'Escrita com vínculo de outra unidade deve ser recusada');
    assert.equal((await detail(a,pessoa)).cadastro.telefone,'81999990002');
    await shot(p,'edicao-compartilhada');
  });
  assert.deepEqual(pageErrors,[],'Erros JavaScript não tratados');
  assert(!http.some(r=>r.status>=500),'Não deve haver erro técnico nas chamadas da interface');
} catch(e){
  results.push({name:phase,status:'FAIL',message:String(e),stack:e instanceof Error?e.stack:undefined});
  for(const [i,c] of contexts.entries())for(const p of c.pages())await shot(p,'falha-'+i).catch(()=>{});
  process.exitCode=1;
} finally {
  await browser?.close();server.close();
  await writeFile(resolve(output,'resultados.json'),JSON.stringify({mode:'Spring Boot real, H2 em memória, sem mocks HTTP',results,http,pageErrors},null,2));
}
console.log(JSON.stringify({results,http,pageErrors},null,2));
assert.equal(results.filter(r=>r.status==='PASS').length,5,'Todas as cinco fases devem passar');
