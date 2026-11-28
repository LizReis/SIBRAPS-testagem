// Browser + actual Spring Boot HTTP + isolated in-memory H2. No API fixtures.
import assert from 'node:assert/strict';
import { createServer, request } from 'node:http';
import { readFile, mkdir, writeFile, rename } from 'node:fs/promises';
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
const results=[],http=[],pageErrors=[],contexts=[],groupRequests=[],groupForms=[];
let browser,phase='inicialização';
function expectResponse(page,method,path,status,filter=()=>true){
  const pending=page.waitForResponse(r=>new URL(r.url()).pathname===path&&r.request().method()===method&&filter(new URL(r.url()))).then(r=>{
    assert.equal(r.status(),status,method+' '+path+' deve retornar '+status);return r;
  }).catch(e=>{throw new Error('Falha aguardando '+method+' '+path+': '+e.message,{cause:e});});
  // Preserve the rejection for await, but never abort Node before the scenario's catch.
  void pending.catch(()=>{});
  return pending;
}
async function step(name,fn){
  phase=name;await fn();results.push({name,status:'PASS'});console.log('PASS '+name);
}
async function login(perfil,unidade){
  const ctx=await browser.newContext({viewport:{width:1366,height:768},locale:'pt-BR',timezoneId:'America/Bahia'});
  contexts.push(ctx);
  await ctx.route('**/*',r=>new URL(r.request().url()).origin===base?r.continue():r.abort());
  const page=await ctx.newPage();page.setDefaultTimeout(15000);
  // Keep the clinical date deterministic without freezing Date.now(): RxJS debounceTime uses it.
  await page.clock.install({time:new Date('2026-11-24T22:00:00Z')});
  page.on('pageerror',e=>pageErrors.push(e.message));
  page.on('request',r=>{const u=new URL(r.url());if(u.pathname.startsWith(api+'/grupos'))groupRequests.push({perfil,unidade,method:r.method(),path:u.pathname});});
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
  let recepcaoA,primeiro,segundo,sucessor;
  const hoje='2026-11-24',amanha='2026-11-25';
  const agendaPath=api+'/agendamentos';
  async function abrirAgenda(actor,data=hoje){
    const p=actor.page;
    await p.goto(base+'/agenda');
    const dia=p.getByRole('button',{name:new RegExp('^(Selecionar|Data selecionada) '+data+'$')});
    await dia.waitFor();
    if((await dia.getAttribute('aria-label'))==='Selecionar '+data) await dia.click();
    return p;
  }
  function cartao(p,hora){
    return p.locator('.appointment-card').filter({has:p.locator('.appointment-time').filter({hasText:hora})}).filter({hasText:nome});
  }
  async function escolherHorario(p,data,hora){
    const vagas=expectResponse(p,'GET',agendaPath+'/vagas',200,u=>u.searchParams.get('data')===data);
    await p.locator('#data').fill(data);
    assert((await (await vagas).json()).MANHA>0,'Oferta sintética deve ter vagas pela manhã');
    const horarios=expectResponse(p,'GET',agendaPath+'/horarios-disponiveis',200,u=>u.searchParams.get('data')===data&&u.searchParams.get('turno')==='MANHA');
    await p.locator('.turns button').filter({hasText:/Manhã|Manha/i}).click();
    const disponiveis=await (await horarios).json();
    assert(disponiveis.horarios.some(h=>h.hora.slice(0,5)===hora&&h.disponivel),'API deve ofertar o horário escolhido: '+hora);
    await p.getByRole('button',{name:hora+' disponível',exact:true}).click();
  }
  async function criar(hora){
    const p=recepcaoA.page;
    await p.goto(base+'/agenda/novo');
    await p.getByRole('heading',{name:'Novo Agendamento',exact:true}).waitFor();
    const inicioRelogio=await p.evaluate(()=>Date.now());
    await p.waitForFunction(inicio=>Date.now()>inicio,inicioRelogio);
    const pesquisa=expectResponse(p,'GET',api+'/pacientes/pesquisa',200,u=>u.searchParams.get('q')===cpf);
    await p.locator('#busca').fill(cpf);
    const resultado=await (await pesquisa).json();
    assert(resultado.pacientes.some(x=>x.idPublico===pessoa&&x.vinculo===localA),'Pesquisa da recepção A deve retornar o paciente e o vínculo local correto');
    await p.getByRole('option').filter({hasText:nome}).click();
    const profissional=(await get(a,'/api/usuarios/me')).idPublico;
    await p.locator('#prof').selectOption(profissional);
    await escolherHorario(p,hoje,hora);
    const resp=expectResponse(p,'POST',agendaPath,201);
    await p.getByRole('button',{name:'Confirmar agendamento',exact:true}).click();
    const dados=await (await resp).json();
    await p.waitForURL('**/agenda?*');
    assert.equal(dados.situacaoReserva,'AGENDADA');
    assert.equal(dados.frequenciaAssistencial,'NAO_REGISTRADA');
    return dados;
  }
  async function falta(ag,hora){
    const p=await abrirAgenda(recepcaoA);
    const card=cartao(p,hora);
    await card.getByText('Frequência: Não registrada',{exact:true}).waitFor();
    const resp=expectResponse(p,'PATCH',agendaPath+'/'+ag.id+'/status',200,u=>u.searchParams.get('novoStatus')==='FALTOU');
    await card.getByRole('button',{name:'Registrar falta de '+nome,exact:true}).click();
    const dados=await (await resp).json();
    assert.equal(dados.situacaoReserva,'AGENDADA');
    assert.equal(dados.frequenciaAssistencial,'FALTOU');
    await card.getByText('Frequência: Faltou',{exact:true}).waitFor();
    assert.equal(await card.getByRole('button',{name:'Corrigir para Presente'}).count(),0);
    return dados;
  }
  await step('recepção cria reserva individual e registra primeira falta',async()=>{
    recepcaoA=await login('RECEPCAO','a');
    primeiro=await criar('09:00');
    primeiro=await falta(primeiro,'09:00');
    const fora=await get(b,agendaPath+'/agenda/'+hoje);
    assert(!fora.some(x=>x.id===primeiro.id),'USF B não enxerga reserva A mesmo com identidade compartilhada');
    await get(b,agendaPath+'/'+primeiro.id,404);
    const d=await detail(a,pessoa);assert.equal(d.paciente.indicadores.countFaltas,1);
  });
  await step('recepção não corrige e profissional responsável corrige com justificativa',async()=>{
    const rota=agendaPath+'/'+primeiro.id+'/frequencia/correcao';
    const motivo='Correção de frequência comprovada no ensaio sintético.';
    const payload={novaFrequencia:'PRESENTE',version:primeiro.version,justificativa:motivo};
    const denied=await recepcaoA.page.request.patch(base+rota,{headers:{Authorization:'Bearer '+recepcaoA.token},data:payload});
    assert.equal(denied.status(),403);
    const p=await abrirAgenda(a);
    await cartao(p,'09:00').getByRole('button',{name:'Corrigir para Presente'}).click();
    const confirmar=p.getByRole('button',{name:'Confirmar operação'});
    assert(await confirmar.isDisabled());
    await p.getByLabel('Justificativa').fill(motivo);
    const resp=expectResponse(p,'PATCH',rota,200);
    await confirmar.click();const corrigido=await (await resp).json();
    assert.equal(corrigido.frequenciaAssistencial,'PRESENTE');
    assert.equal(corrigido.situacaoReserva,'AGENDADA');
    await cartao(p,'09:00').getByText('Frequência: Presente',{exact:true}).waitFor();
    assert.equal((await detail(a,pessoa)).paciente.indicadores.countFaltas,0);
    assert.equal((await detail(b,pessoa)).paciente.indicadores.countFaltas,0);
    await shot(p,'agenda-correcao');
  });
  await step('remarcação mantém falta original e sucessor sem frequência registrada',async()=>{
    segundo=await criar('09:30');
    segundo=await falta(segundo,'09:30');
    const p=recepcaoA.page;
    await cartao(p,'09:30').getByRole('button',{name:'Remarcar atendimento de '+nome}).click();
    await p.waitForURL('**/agenda/novo?*');
    await p.getByRole('heading',{name:'Remarcar Atendimento'}).waitFor();
    await p.locator('.selected').filter({hasText:nome}).waitFor();
    await escolherHorario(p,amanha,'09:30');
    const resp=expectResponse(p,'POST',agendaPath,201);
    await p.getByRole('button',{name:'Confirmar remarcação',exact:true}).click();
    sucessor=await (await resp).json();
    assert.equal(sucessor.agendamentoOriginalId,segundo.id);
    assert.equal(sucessor.situacaoReserva,'REMARCADA');
    assert.equal(sucessor.frequenciaAssistencial,'NAO_REGISTRADA');
    const original=await get(a,agendaPath+'/'+segundo.id);
    assert.equal(original.situacaoReserva,'SUBSTITUIDA');
    assert.equal(original.frequenciaAssistencial,'FALTOU');
    assert.equal((await detail(a,pessoa)).paciente.indicadores.countFaltas,1);
    assert.equal((await detail(b,pessoa)).paciente.indicadores.countFaltas,0);
    await p.waitForURL('**/agenda?*');
    await abrirAgenda(recepcaoA,amanha);
    await cartao(p,'09:30').getByText('Frequência: Não registrada',{exact:true}).waitFor();
    await shot(p,'agenda-remarcacao');
  });
  let alertaConfirmado;
  const buscaPath=api+'/pacientes/busca-ativa';
  async function buscarAlertas(actor){
    return get(actor,buscaPath+'?requisicao='+crypto.randomUUID());
  }
  async function avancarDia(){
    const arquivo=process.env.SIBRAPS_ENSAIO_RELOGIO;
    assert(arquivo,'Arquivo temporário de relógio obrigatório');
    assert.equal((await readFile(arquivo,'utf8')).trim(),'2026-11-24T22:00:00Z');
    const instante='2026-11-25T22:00:00Z';
    await writeFile(arquivo+'.next',instante+'\n',{mode:0o600});
    await rename(arquivo+'.next',arquivo);
    for(const c of contexts) for(const p of c.pages()) await p.clock.setSystemTime(new Date(instante));
  }
  await step('falta na remarcação aciona RF22 apenas no vínculo local',async()=>{
    assert.equal((await detail(a,pessoa)).paciente.indicadores.gatilhoVisitaAcionado,false);
    await avancarDia();
    const p=await abrirAgenda(recepcaoA,amanha);
    const card=cartao(p,'09:30');
    await card.getByText('Frequência: Não registrada',{exact:true}).waitFor();
    const resp=expectResponse(p,'PATCH',agendaPath+'/'+sucessor.id+'/status',200,u=>u.searchParams.get('novoStatus')==='FALTOU');
    await card.getByRole('button',{name:'Registrar falta de '+nome,exact:true}).click();
    const faltaSucessor=await (await resp).json();
    assert.equal(faltaSucessor.situacaoReserva,'REMARCADA');
    assert.equal(faltaSucessor.frequenciaAssistencial,'FALTOU');
    await card.getByText('Frequência: Faltou',{exact:true}).waitFor();
    const da=await detail(a,pessoa),db=await detail(b,pessoa);
    assert.equal(da.paciente.indicadores.countFaltas,2);
    assert.equal(da.paciente.indicadores.gatilhoVisitaAcionado,true);
    assert.equal(db.paciente.indicadores.countFaltas,0);
    assert.equal(db.paciente.indicadores.gatilhoVisitaAcionado,false);
    alertaConfirmado=(await buscarAlertas(a)).alertas.find(x=>x.vinculo===localA&&x.idPublico===pessoa);
    assert(alertaConfirmado,'Paciente deve estar na busca ativa A');
    assert.equal(alertaConfirmado.gatilhoVisitaAcionado,true);
    assert.equal(alertaConfirmado.acao,'CONFERIR_REFERENCIA');
    assert.equal(alertaConfirmado.alerta,'Busca em ponto de referência necessária');
    assert.equal(alertaConfirmado.avisoAdicional,'Nenhum ponto de referência cadastrado — verificar com a equipe antes de acionar a busca.');
    assert(!(await buscarAlertas(b)).alertas.some(x=>x.idPublico===pessoa),'Unidade B não recebe alerta A');
    await shot(p,'agenda-segunda-falta');
  });
  await step('busca ativa exibe RF22 e registra visualização auditável RNF17',async()=>{
    const p=a.page;
    await p.goto(base+'/painel');
    const item=p.locator('app-busca-ativa-local li').filter({has:p.getByText(nome,{exact:true})});
    await item.getByText(alertaConfirmado.alerta,{exact:true}).waitFor();
    await item.getByText(alertaConfirmado.avisoAdicional,{exact:true}).waitFor();
    await shot(p,'busca-ativa-rf22');
    const resp=expectResponse(p,'POST',api+'/pacientes/'+pessoa+'/vinculos/'+localA+'/busca-ativa/visualizacao',200);
    await item.getByRole('button',{name:'Ver paciente',exact:true}).click();
    const registro=await (await resp).json();
    assert.equal(registro.evento.tipoEvento,'VISUALIZACAO_ALERTA');
    assert.equal(registro.evento.pacienteId,pessoa);
    assert.equal(registro.evento.gatilhoConfirmado,true);
    await p.waitForURL(u=>u.pathname==='/pacientes/detalhes/'+pessoa&&u.searchParams.get('vinculo')===localA);
    const audit=await get(admin,api+'/auditoria/busca-ativa');
    assert(audit.content.some(x=>x.id===registro.evento.id),'Visualização deve persistir na auditoria local');
    await get(recepcaoA,api+'/auditoria/busca-ativa',403);
  });
  await step('correção de dia anterior desativa gatilho sem apagar sucessor nem auditoria',async()=>{
    const p=await abrirAgenda(a,hoje);
    const card=cartao(p,'09:30');
    await card.getByText('Frequência: Faltou',{exact:true}).waitFor();
    await card.getByRole('button',{name:'Corrigir para Presente'}).click();
    const confirmar=p.getByRole('button',{name:'Confirmar operação'});
    assert(await confirmar.isDisabled());
    await p.getByLabel('Justificativa').fill('Correção retroativa comprovada em ensaio sintético.');
    const resp=expectResponse(p,'PATCH',agendaPath+'/'+segundo.id+'/frequencia/correcao',200);
    await confirmar.click();
    const corrigido=await (await resp).json();
    assert.equal(corrigido.situacaoReserva,'SUBSTITUIDA');
    assert.equal(corrigido.frequenciaAssistencial,'PRESENTE');
    await card.getByText('Frequência: Presente',{exact:true}).waitFor();
    const mantido=await get(a,agendaPath+'/'+sucessor.id);
    assert.equal(mantido.agendamentoOriginalId,segundo.id);
    assert.equal(mantido.situacaoReserva,'REMARCADA');
    assert.equal(mantido.frequenciaAssistencial,'FALTOU');
    const da=await detail(a,pessoa),db=await detail(b,pessoa);
    assert.equal(da.paciente.indicadores.countFaltas,1);
    assert.equal(da.paciente.indicadores.gatilhoVisitaAcionado,false);
    assert.equal(db.paciente.indicadores.countFaltas,0);
    assert.equal(db.paciente.indicadores.gatilhoVisitaAcionado,false);
    assert(!(await buscarAlertas(a)).alertas.some(x=>x.idPublico===pessoa),'Alerta deixa a lista quando não atende mais os critérios');
    const audit=await get(admin,api+'/auditoria/busca-ativa');
    assert(audit.content.some(x=>x.pacienteId===pessoa&&x.tipoEvento==='VISUALIZACAO_ALERTA'&&x.gatilhoConfirmado),'Auditoria preserva contexto histórico');
    await p.goto(base+'/painel');
    await p.locator('app-busca-ativa-local').getByText('Nenhum alerta confirmado.',{exact:true}).waitFor();
    await shot(p,'busca-ativa-apos-correcao');
  });
  const gruposPath=api+'/grupos',tema='Grupo sintético integração';
  const dataGrupo=amanha,horarioGrupo='19:30';
  let grupoId,sessaoId,sessaoRealizada,coordenador,indicadoresAntes;
  async function sessaoAtual(actor){
    const lista=await get(actor,gruposPath+'/sessoes?dataInicio='+dataGrupo+'&dataFim='+dataGrupo);
    const sessao=lista.find(x=>x.id===sessaoId&&x.grupoId===grupoId);
    assert(sessao,'Sessão local deve existir');return sessao;
  }
  async function abrirSessao(actor){
    const p=actor.page;
    await p.goto(base+'/grupos-terapeuticos?data='+dataGrupo);
    await p.getByRole('heading',{name:'Grupos Terapêuticos',exact:true}).waitFor();
    const row=p.getByRole('row').filter({hasText:tema});
    await row.getByRole('button',{name:/^(Gerenciar|Ver detalhes)$/}).click();
    await p.waitForURL(u=>u.pathname==='/grupos-terapeuticos/detalhes/'+grupoId+'/sessao/'+sessaoId);
    await p.getByRole('heading',{name:'Gerenciar grupo terapêutico'}).waitFor();
    await p.getByRole('tab',{name:'Ocorrência da sessão'}).click();
    return p;
  }
  const participante=s=>s.participantes.find(x=>x.pacienteId===pessoa);
  await step('recepção agenda grupo e inscreve paciente no vínculo da unidade A',async()=>{
    indicadoresAntes=(await detail(a,pessoa)).paciente.indicadores;
    assert.equal(indicadoresAntes.countFaltas,1);
    coordenador=(await get(a,'/api/usuarios/me')).idPublico;
    const p=recepcaoA.page;
    await p.goto(base+'/grupos-terapeuticos/novo');
    await p.getByRole('heading',{name:'Agendar grupo terapêutico',exact:true}).waitFor();
    await p.locator('#tema').fill(tema);
    await p.locator('#data').fill(dataGrupo);
    await p.locator('#horario').fill(horarioGrupo);
    await p.locator('#recorrencia').selectOption('UNICA');
    const busca=expectResponse(p,'GET',api+'/pacientes/pesquisa',200,u=>u.searchParams.get('q')===cpf);
    await p.locator('#paciente').fill(cpf);
    const retorno=await (await busca).json();
    assert(retorno.pacientes.some(x=>x.idPublico===pessoa&&x.vinculo===localA));
    await p.locator('ul.results button').filter({hasText:nome}).click();
    await p.getByRole('button',{name:'Adicionar paciente',exact:true}).click();
    await p.locator('ul.selected').getByText(nome,{exact:true}).waitFor();
    // Select the dynamic option after the other fields and async search have settled.
    await p.locator('#coordenador').selectOption(coordenador);
    const form=p.locator('form');
    await p.locator('.summary dd').filter({hasText:'Não selecionado'}).waitFor({state:'hidden'});
    for(const [id,valor] of Object.entries({tema,coordenador,data:dataGrupo,horario:horarioGrupo,recorrencia:'UNICA'})){
      assert.equal(await p.locator('#'+id).inputValue(),valor,'Valor do campo '+id+' antes do envio');
    }
    assert.equal(await p.locator('#data').getAttribute('min'),dataGrupo,'Data mínima do formulário deve acompanhar o relógio clínico');
    await form.and(p.locator('.ng-valid')).waitFor({timeout:5000});
    const botaoSubmit=form.locator('button[type="submit"]');
    assert.equal(await botaoSubmit.count(),1,'Formulário deve possuir exatamente um botão de envio');
    assert.equal((await botaoSubmit.innerText()).trim(),'Agendar grupo','Rótulo visível do envio');
    assert.equal(await botaoSubmit.locator('i').getAttribute('aria-hidden'),'true','Ícone decorativo não deve compor o nome acessível');
    const nomeAcessivel=await botaoSubmit.ariaSnapshot();
    assert.equal(await form.getByRole('button',{name:'Agendar grupo',exact:true}).count(),1,'Nome acessível esperado; árvore: '+nomeAcessivel);
    const enviar=form.getByRole('button',{name:'Agendar grupo',exact:true});
    await enviar.scrollIntoViewIfNeeded();
    await enviar.click({trial:true,timeout:5000});
    await shot(p,'grupo-antes-envio');
    const resp=expectResponse(p,'POST',gruposPath,200);
    const erroFormulario=p.locator('.submit-error').waitFor({state:'visible',timeout:15000}).then(async()=>{
      throw new Error('Formulário recusou o envio: '+await p.locator('.submit-error').innerText());
    });
    const [resposta]=await Promise.all([Promise.race([resp,erroFormulario]),enviar.click({timeout:8000})]);
    const criado=await resposta.json();grupoId=criado.grupo;
    assert.equal(criado.sessoes.length,1);sessaoId=criado.sessoes[0];
    await p.waitForURL('**/grupos-terapeuticos?*');
    const sessao=await sessaoAtual(a);
    assert.equal(sessao.status,'AGENDADA');assert.equal(sessao.statusExibicao,'AGENDADO');
    assert.equal(sessao.quantidadeParticipantes,1);
    assert.equal(participante(sessao).statusPresenca,'NAO_REGISTRADA');
    assert.equal(sessao.profissionalDesignadoId,coordenador);
    await get(b,gruposPath+'/'+grupoId,404);
    const externas=await get(b,gruposPath+'/sessoes?dataInicio='+dataGrupo+'&dataFim='+dataGrupo);
    assert(!externas.some(x=>x.id===sessaoId),'USF B não consulta sessão de A');
    await p.getByRole('row').filter({hasText:tema}).waitFor();
    await shot(p,'grupo-agendado');
  });
  await step('frequência coletiva provisória não altera indicadores até confirmação',async()=>{
    const arquivo=process.env.SIBRAPS_ENSAIO_RELOGIO;
    assert.equal((await readFile(arquivo,'utf8')).trim(),'2026-11-25T22:00:00Z');
    const instante='2026-11-25T23:00:00Z';
    await writeFile(arquivo+'.next',instante+'\n',{mode:0o600});
    await rename(arquivo+'.next',arquivo);
    for(const c of contexts) for(const p of c.pages()) await p.clock.setSystemTime(new Date(instante));
    const inicial=await sessaoAtual(a);assert.equal(inicial.statusExibicao,'EM_ANDAMENTO');
    const rota=gruposPath+'/sessoes/'+sessaoId;
    const negada=await recepcaoA.page.request.patch(base+rota+'/participantes/'+pessoa+'/presenca?version='+inicial.version,{headers:{Authorization:'Bearer '+recepcaoA.token},data:{statusPresenca:'FALTOU'}});
    assert.equal(negada.status(),403);
    const p=await abrirSessao(a);
    const row=p.locator('section.occurrence .person').filter({hasText:nome});
    await row.getByRole('button',{name:'Faltou',exact:true}).click();
    const provisoria=expectResponse(p,'PATCH',rota+'/participantes/'+pessoa+'/presenca',200);
    await row.getByRole('button',{name:'Salvar provisória'}).click();
    const provis=await (await provisoria).json();
    assert.equal(provis.status,'AGENDADA');assert.equal(participante(provis).statusPresenca,'FALTOU');
    await row.getByText('Provisória salva: Faltou',{exact:true}).waitFor();
    assert.equal((await detail(a,pessoa)).paciente.indicadores.countFaltas,indicadoresAntes.countFaltas);
    assert.equal((await detail(b,pessoa)).paciente.indicadores.countFaltas,0);
    await shot(p,'grupo-provisoria');
    const confirmacao=expectResponse(p,'POST',rota+'/confirmacao-ocorrencia',200);
    await p.getByRole('button',{name:'Confirmar realização',exact:true}).click();
    sessaoRealizada=await (await confirmacao).json();
    assert.equal(sessaoRealizada.status,'REALIZADA');
    assert.equal(sessaoRealizada.responsavelAtendimentoId,coordenador);
    assert.equal(participante(sessaoRealizada).statusPresenca,'FALTOU');
    await row.getByText('Confirmada: Faltou',{exact:true}).waitFor();
    assert.equal((await detail(a,pessoa)).paciente.indicadores.countFaltas,2);
    assert.equal((await detail(b,pessoa)).paciente.indicadores.countFaltas,0);
    await shot(p,'grupo-realizado');
  });
  await step('correção coletiva exige justificativa e preserva autoria e isolamento',async()=>{
    const p=await abrirSessao(a),rota=gruposPath+'/sessoes/'+sessaoId;
    const motivo='Correção coletiva comprovada no ensaio sintético.';
    const negada=await recepcaoA.page.request.patch(base+rota+'/frequencias',{headers:{Authorization:'Bearer '+recepcaoA.token},data:{version:sessaoRealizada.version,frequencias:[{pacienteId:pessoa,statusPresenca:'PRESENTE'}],justificativa:motivo}});
    assert.equal(negada.status(),403);
    await p.getByRole('button',{name:'Corrigir frequência',exact:true}).click();
    const row=p.locator('section.occurrence .person').filter({hasText:nome});
    await row.getByRole('button',{name:'Presente',exact:true}).click();
    await p.getByRole('button',{name:'Revisar correções'}).click();
    const dialog=p.getByRole('dialog',{name:'Confirmar correção de frequência?'});
    const confirmar=dialog.getByRole('button',{name:'Confirmar correção',exact:true});
    assert(await confirmar.isDisabled());
    await dialog.getByLabel('Justificativa').fill(motivo);
    const resp=expectResponse(p,'PATCH',rota+'/frequencias',200);
    await confirmar.click();const corrigida=await (await resp).json();
    assert.equal(corrigida.status,'REALIZADA');
    assert.equal(corrigida.responsavelAtendimentoId,coordenador);
    assert.equal(participante(corrigida).statusPresenca,'PRESENTE');
    assert.equal(corrigida.quantidadePresencasConfirmadas,1);
    await row.getByText('Confirmada: Presente',{exact:true}).waitFor();
    assert.equal((await detail(a,pessoa)).paciente.indicadores.countFaltas,0);
    assert.equal((await detail(b,pessoa)).paciente.indicadores.countFaltas,0);
    const auditoria=await get(admin,rota+'/auditoria-correcoes');
    const registro=auditoria.content.find(x=>x.justificativa===motivo);
    assert(registro,'Justificativa coletiva deve persistir');
    assert.equal(registro.pacienteId,pessoa);
    assert.equal(registro.sessaoId,sessaoId);
    assert.equal(registro.tipoCorrecao,'GRUPO');
    assert.equal(registro.frequenciaAnterior,'FALTOU');
    assert.equal(registro.frequenciaNova,'PRESENTE');
    assert.equal(registro.responsavelAtendimentoId,coordenador);
    await get(recepcaoA,rota+'/auditoria-correcoes',403);
    await shot(p,'grupo-correcao');
    const consulta=expectResponse(p,'GET',rota+'/auditoria-correcoes',200);
    await p.getByRole('link',{name:'Consultar auditoria de correções',exact:true}).click();
    const trilha=await (await consulta).json();
    assert(trilha.content.some(x=>x.id===registro.id),'Responsável deve consultar a mesma trilha');
    await p.getByRole('heading',{name:'Auditoria de correções de frequência'}).waitFor();
    await p.getByText(motivo,{exact:true}).waitFor();
    await shot(p,'grupo-auditoria');
  });
  const temaSerie='Série semanal sintética',datasSerie=['2026-11-25','2026-12-02','2026-12-09'];
  const nomeRetro='Participante Retroativo Sintético',cpfRetro='39053344705',cnsRetro='800000000000028';
  let serieId,idsSerie,retroId;
  async function lerSerie(actor){
    const dados=await get(actor,gruposPath+'/sessoes?dataInicio='+datasSerie[0]+'&dataFim='+datasSerie[2]);
    return dados.filter(x=>x.grupoId===serieId).sort((x,y)=>x.dataSessao.localeCompare(y.dataSessao));
  }
  async function abrirSerie(actor,i,aba){
    const p=actor.page;
    await p.goto(base+'/grupos-terapeuticos?data='+datasSerie[i]);
    await p.getByRole('heading',{name:'Grupos Terapêuticos',exact:true}).waitFor();
    await p.getByRole('row').filter({hasText:temaSerie}).getByRole('button',{name:/^(Gerenciar|Ver detalhes)$/}).click();
    await p.waitForURL(u=>u.pathname==='/grupos-terapeuticos/detalhes/'+serieId+'/sessao/'+idsSerie[i]);
    await p.getByRole('heading',{name:'Gerenciar grupo terapêutico'}).waitFor();
    await p.getByRole('tab',{name:aba,exact:true}).click();
    return p;
  }
  await step('recorrência semanal replica participantes até a data limite',async()=>{
    const p=recepcaoA.page;
    await p.goto(base+'/grupos-terapeuticos/novo');
    await p.getByRole('heading',{name:'Agendar grupo terapêutico',exact:true}).waitFor();
    await p.locator('#tema').fill(temaSerie);
    await p.locator('#data').fill(datasSerie[0]);
    await p.locator('#horario').fill('21:00');
    await p.locator('#recorrencia').selectOption('SEMANAL');
    await p.locator('#dataFimRecorrencia').fill(datasSerie[2]);
    const busca=expectResponse(p,'GET',api+'/pacientes/pesquisa',200,u=>u.searchParams.get('q')===cpf);
    await p.locator('#paciente').fill(cpf);
    assert((await (await busca).json()).pacientes.some(x=>x.idPublico===pessoa&&x.vinculo===localA));
    await p.locator('ul.results button').filter({hasText:nome}).click();
    await p.getByRole('button',{name:'Adicionar paciente',exact:true}).click();
    await p.locator('ul.selected').getByText(nome,{exact:true}).waitFor();
    await p.locator('#coordenador').selectOption(coordenador);
    await p.locator('form.ng-valid').waitFor();
    const enviar=p.getByRole('button',{name:'Agendar grupo',exact:true});
    const resp=expectResponse(p,'POST',gruposPath,200);
    await enviar.click();const criada=await (await resp).json();
    serieId=criada.grupo;
    assert.equal(criada.sessoes.length,3);
    await p.waitForURL('**/grupos-terapeuticos?*');
    const sessoes=await lerSerie(a);
    assert.deepEqual(sessoes.map(x=>x.dataSessao),datasSerie);
    idsSerie=sessoes.map(x=>x.id);
    assert.deepEqual(new Set(idsSerie),new Set(criada.sessoes));
    for(const sessao of sessoes){
      assert.equal(sessao.status,'AGENDADA');
      assert.equal(sessao.profissionalDesignadoId,coordenador);
      assert.equal(sessao.quantidadeParticipantes,1);
      assert.equal(participante(sessao).statusPresenca,'NAO_REGISTRADA');
    }
    assert.equal((await lerSerie(b)).length,0,'Série não pode aparecer em outra unidade');
    assert.equal((await detail(a,pessoa)).paciente.indicadores.countFaltas,0);
    await p.getByRole('row').filter({hasText:temaSerie}).waitFor();
    await shot(p,'serie-semanal');
  });
  await step('cancelamento de sessão futura não gera falta nem cancela a série',async()=>{
    const p=await abrirSerie(a,1,'Ocorrência da sessão');
    const motivo='Cancelamento sintético por indisponibilidade da sala.';
    const anterior=(await lerSerie(a))[1];
    const rota=gruposPath+'/sessoes/'+idsSerie[1]+'/confirmacao-ocorrencia';
    const negada=await recepcaoA.page.request.post(base+rota,{headers:{Authorization:'Bearer '+recepcaoA.token},data:{ocorreu:false,frequencias:[],motivoCancelamento:motivo,version:anterior.version}});
    assert.equal(negada.status(),403);
    await p.getByRole('radio',{name:'Cancelar sessão',exact:true}).check();
    await p.getByLabel('Motivo do cancelamento (opcional)').fill(motivo);
    const resp=expectResponse(p,'POST',rota,200);
    await p.getByRole('button',{name:'Confirmar cancelamento',exact:true}).click();
    const cancelada=await (await resp).json();
    assert.equal(cancelada.status,'CANCELADA');
    assert.equal(cancelada.motivoCancelamento,motivo);
    assert.equal(participante(cancelada).statusPresenca,'NAO_REGISTRADA');
    await p.getByText('Sessão cancelada. Nenhuma falta foi atribuída.',{exact:true}).waitFor();
    assert.deepEqual((await lerSerie(a)).map(x=>x.status),['AGENDADA','CANCELADA','AGENDADA']);
    assert.equal((await detail(a,pessoa)).paciente.indicadores.countFaltas,0);
    assert.equal((await detail(b,pessoa)).paciente.indicadores.countFaltas,0);
    await shot(p,'serie-cancelamento');
  });
  await step('inscrição retroativa exige frequência realizada e ignora sessão cancelada',async()=>{
    const arquivo=process.env.SIBRAPS_ENSAIO_RELOGIO;
    assert.equal((await readFile(arquivo,'utf8')).trim(),'2026-11-25T23:00:00Z');
    const instante='2026-11-26T00:30:00Z';
    await writeFile(arquivo+'.next',instante+'\n',{mode:0o600});await rename(arquivo+'.next',arquivo);
    for(const c of contexts)for(const p of c.pages())await p.clock.setSystemTime(new Date(instante));
    let p=await abrirSerie(a,0,'Ocorrência da sessão');
    const card=p.locator('section.occurrence .person').filter({hasText:nome});
    await card.getByRole('button',{name:'Presente',exact:true}).click();
    const confirmacao=expectResponse(p,'POST',gruposPath+'/sessoes/'+idsSerie[0]+'/confirmacao-ocorrencia',200);
    await p.getByRole('button',{name:'Confirmar realização',exact:true}).click();
    const realizada=await (await confirmacao).json();
    assert.equal(realizada.status,'REALIZADA');assert.equal(participante(realizada).statusPresenca,'PRESENTE');
    await card.getByText('Confirmada: Presente',{exact:true}).waitFor();
    // Second synthetic identity, created through the same real registration form.
    await openForm(p);
    for(const [id,value] of Object.entries({nome:nomeRetro,nomeMae:'Responsável Retroativa Sintética',dataNascimento:'1996-03-12',cpf:cpfRetro,cns:cnsRetro,telefone:'81999990004'}))await p.locator('#'+id).fill(value);
    for(const [id,value] of Object.entries({sexo:'FEMININO',racacor:'PARDA',capsReferencia:'CAPS_AD',tipoAcompanhamento:'AMBOS'}))await p.locator('#'+id).selectOption(value);
    await p.locator('#situacaoRua').selectOption({label:'Sim'});
    await p.locator('#usfReferencia').selectOption({label:'USF sintética A'});
    const cadastro=expectResponse(p,'POST',api+'/pacientes',201);
    await p.locator('form').filter({has:p.locator('#nome')}).locator('button[type=submit]').click();
    const criada=await (await cadastro).json();retroId=criada.paciente;
    assert(retroId&&retroId!==pessoa);
    await p.waitForURL('**/pacientes/detalhes/'+retroId+'?*');
    await p.getByRole('heading',{name:nomeRetro,exact:true}).waitFor();
    assert.equal((await detail(a,retroId)).paciente.indicadores.countFaltas,0);
    const rota=gruposPath+'/'+serieId+'/inscricoes-retroativas';
    const negada=await recepcaoA.page.request.post(base+rota,{headers:{Authorization:'Bearer '+recepcaoA.token},data:{pacienteId:retroId,frequenciasPassadas:[{sessaoId:idsSerie[0],statusPresenca:'FALTOU'}]}});
    assert.equal(negada.status(),403);
    p=await abrirSerie(a,0,'Participantes');
    await p.getByLabel('Alcance da inscrição').selectOption('RETROATIVA');
    await p.getByPlaceholder('Nome, CPF ou CNS').fill(cpfRetro);
    const pesquisa=expectResponse(p,'GET',api+'/pacientes/pesquisa',200,u=>u.searchParams.get('q')===cpfRetro);
    await p.getByRole('button',{name:'Buscar',exact:true}).click();
    assert((await (await pesquisa).json()).pacientes.some(x=>x.idPublico===retroId));
    await p.getByRole('button',{name:nomeRetro,exact:true}).click();
    const confirmar=p.getByRole('button',{name:'Confirmar inscrição',exact:true});
    assert(await confirmar.isDisabled(),'Frequência realizada obrigatória');
    const passadas=p.locator('.past-session');
    assert.equal(await passadas.count(),3);
    await passadas.filter({hasText:'02/12/2026'}).getByText('Sessão cancelada • sem falta',{exact:true}).waitFor();
    assert.equal(await passadas.filter({hasText:'02/12/2026'}).getByRole('button').count(),0);
    await passadas.filter({hasText:'25/11/2026'}).getByRole('button',{name:'Faltou',exact:true}).click();
    await shot(p,'serie-inscricao-retroativa');
    const resp=expectResponse(p,'POST',rota,200);
    await confirmar.click();
    const alteradas=await (await resp).json();
    assert.deepEqual(new Set(alteradas),new Set([idsSerie[0],idsSerie[2]]));
    await p.getByText('Inscrição na série registrada.',{exact:true}).waitFor();
    const sessoes=await lerSerie(a),retro=x=>x.participantes.find(y=>y.pacienteId===retroId);
    assert.equal(retro(sessoes[0]).statusPresenca,'FALTOU');
    assert.equal(retro(sessoes[1]),undefined);
    assert.equal(retro(sessoes[2]).statusPresenca,'NAO_REGISTRADA');
    assert.equal(participante(sessoes[0]).statusPresenca,'PRESENTE');
    assert.equal((await detail(a,retroId)).paciente.indicadores.countFaltas,1);
    assert.equal((await detail(a,pessoa)).paciente.indicadores.countFaltas,0);
    assert.equal((await detail(b,pessoa)).paciente.indicadores.countFaltas,0);
    await get(b,api+'/pacientes/'+retroId+'/detalhe',404);
    await shot(p,'serie-inscricao-confirmada');
  });
  assert.deepEqual(pageErrors,[],'Erros JavaScript não tratados');
  assert(!http.some(r=>r.status>=500),'Não deve haver erro técnico nas chamadas da interface');
} catch(e){
  results.push({name:phase,status:'FAIL',message:String(e),stack:e instanceof Error?e.stack:undefined,cause:e?.cause?.stack});
  for(const [i,c] of contexts.entries())for(const p of c.pages()){
    if(new URL(p.url()).pathname==='/grupos-terapeuticos/novo'){
      const estado=await p.evaluate(()=>({
        form:document.querySelector('form')?.className,
        campos:['tema','coordenador','data','horario','recorrencia','dataFimRecorrencia'].map(id=>{
          const el=document.getElementById(id);
          return {id,value:el?.value,classes:el?.className,min:el?.getAttribute('min'),validation:el?.validationMessage};
        }),
        erros:[...document.querySelectorAll('form small, .submit-error')].map(el=>el.textContent.trim()),
        dataNavegador:new Date().toISOString()
      })).catch(()=>null);
      groupForms.push({...estado,submit:await p.locator('form button[type="submit"]').ariaSnapshot({timeout:2000}).catch(e=>String(e))});
    }
    await shot(p,'falha-'+i).catch(()=>{});
  }
  process.exitCode=1;
} finally {
  await browser?.close();server.close();
  await writeFile(resolve(output,'resultados.json'),JSON.stringify({mode:'Spring Boot real, H2 em memória, sem mocks HTTP',results,http,pageErrors,groupRequests,groupForms},null,2));
}
console.log(JSON.stringify({results,http,pageErrors,groupRequests,groupForms},null,2));
if(!process.exitCode) assert.equal(results.filter(r=>r.status==='PASS').length,17,'Todas as dezessete fases devem passar');
