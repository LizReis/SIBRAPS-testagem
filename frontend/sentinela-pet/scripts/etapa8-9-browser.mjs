// Chromium smoke tests against the compiled production frontend.
// All API responses are synthetic; no backend or real credentials are used.
import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { readFile, mkdir, writeFile } from 'node:fs/promises';
import { resolve, extname, sep } from 'node:path';
import { createRequire } from 'node:module';
import { once } from 'node:events';

const root = resolve(process.argv[2] || 'dist/sentinela-pet/browser');
const output = resolve(process.argv[3] || 'browser-evidence');
const { chromium } = createRequire(resolve(process.env.PLAYWRIGHT_PREFIX || '.', 'package.json'))('playwright');
await mkdir(output, { recursive: true });
const mime = { '.html':'text/html', '.js':'text/javascript', '.css':'text/css', '.json':'application/json',
  '.svg':'image/svg+xml', '.png':'image/png', '.jpg':'image/jpeg', '.webp':'image/webp', '.woff2':'font/woff2', '.woff':'font/woff', '.ico':'image/x-icon' };
const server = createServer(async (req, res) => {
  try {
    const pathname = decodeURIComponent(new URL(req.url, 'http://127.0.0.1').pathname);
    if (pathname.startsWith('/api/')) { res.writeHead(500); res.end('Unmocked API'); return; }
    const file = resolve(root, '.' + pathname);
    if (file !== root && !file.startsWith(root + sep)) { res.writeHead(403); res.end(); return; }
    const target = extname(file) ? file : resolve(root, 'index.html');
    const data = await readFile(target);
    res.writeHead(200, { 'Content-Type': mime[extname(target)] || 'application/octet-stream' }); res.end(data);
  } catch { res.writeHead(404); res.end(); }
});
server.listen(0, '127.0.0.1');
await once(server, 'listening');
const base = 'http://127.0.0.1:' + server.address().port;
const results = [];
const item = {
  ticket:'ticket-sintetico', paciente:'paciente-sintetico', vinculo:'vinculo-sintetico',
  nome:'Paciente sintético para teste de interface', tipoRegistro:'HISTORICO', registro:1,
  solicitadoEm:'2026-11-01T12:00:00Z', estado:'PENDENTE', decisao:null, decididaEm:null,
  versaoVinculo:5, justificativaSolicitacao:'Revisão sintética solicitada',
  justificativaDecisao:null, revisaoOrigem:true, aplicacaoOrigem:'SEM_APLICACAO'
};
const resumo = { totalPacientesAtivos:12,totalAgendamentosHoje:2,totalFaltasUltimosSeteDias:null,
  totalPacientesAtencao:1,totalNotificacoes:0,pacientesPrioritarios:[{idPublico:'paciente-sintetico',
  vinculo:'vinculo-sintetico',nome:'Paciente sintético',classificacaoRisco:'VERMELHO',quantidadeFaltas:3}] };
const viewportCases = [{width:1366,height:768},{width:820,height:1180},{width:390,height:844}];
let browser;
async function visible(locator) { await locator.waitFor({state:'visible'}); }
async function fit(page, locator) {
  await visible(locator);
  await locator.scrollIntoViewIfNeeded();
  const b = await locator.boundingBox(), vp = page.viewportSize();
  assert(b && b.x >= -1 && b.x + b.width <= vp.width + 1, 'Elemento ultrapassa largura visível: ' + JSON.stringify(b));
}
async function shot(page, name) {
  await page.screenshot({path:resolve(output,name+'.png'),animations:'disabled',timeout:10000});
}
async function scenario(name, viewport, perfil, run) {
  const context = await browser.newContext({viewport,locale:'pt-BR',timezoneId:'America/Bahia',reducedMotion:'reduce'});
  const page = await context.newPage();
  page.setDefaultTimeout(10000);
  const errors = [], unexpected = [], writes = [];
  let filaFalha = false;
  page.on('pageerror', e => errors.push(e.message));
  await context.route('**/*', async route => {
    const request = route.request(), url = new URL(request.url()), method = request.method();
    if (url.origin !== base) return route.abort();
    if (!url.pathname.startsWith('/api/')) return route.continue();
    const send = (json, status=200) => route.fulfill({status,json});
    if (method==='POST' && url.pathname==='/api/auth/login') return send({token:'token-exclusivamente-sintetico'});
    if (method==='GET' && url.pathname==='/api/usuarios/me') return send({idPublico:'usuario-sintetico',nome:'Usuário sintético',email:'teste@example.invalid',tipoUsuario:perfil,unidadeAtuacao:'CAPS_I'});
    if (method==='GET' && url.pathname==='/api/inicio/resumo') return send(resumo);
    if (method==='GET' && url.pathname==='/api/relatorios/pendencias' && perfil==='ADMINISTRADOR') {
      return filaFalha ? send({code:'SERVICE_UNAVAILABLE'},503) : send({pendentes:1,aprovadas:0,rejeitadas:0,itens:[item],total:1,pagina:0,tamanho:10});
    }
    if (method==='POST' && url.pathname==='/api/pacientes/'+item.paciente+'/vinculos/'+item.vinculo+'/revisoes/'+item.ticket+'/decisao' && perfil==='ADMINISTRADOR') {
      writes.push(request.postDataJSON()); return send({code:'STALE_VERSION'},422);
    }
    unexpected.push(method+' '+url.pathname); return send({code:'RESOURCE_NOT_FOUND'},404);
  });
  try {
    await run(page,{writes,failQueue:()=>{filaFalha=true;}});
    assert.deepEqual(unexpected,[],'Chamadas de API sem fixture');
    assert.deepEqual(errors,[],'Erros JavaScript não tratados');
    results.push({name,viewport,perfil,status:'PASS'});
  } catch (e) {
    results.push({name,viewport,perfil,status:'FAIL',message:String(e),errors,unexpected});
    await shot(page,name+'-failure').catch(()=>{});
  } finally { await context.close(); }
}
async function login(page) {
  await page.goto(base+'/login');
  const email=page.getByLabel('Email',{exact:true}), password=page.getByLabel('Senha',{exact:true});
  await visible(email); await fit(page,email);
  await email.focus(); await page.keyboard.press('Tab');
  assert(await password.evaluate(e=>document.activeElement===e),'Ordem de foco email/senha');
  await email.fill('teste@example.invalid'); await password.fill('senha-sintetica');
  await fit(page,page.locator('button[type=submit]'));
}
try {
  browser = await chromium.launch({headless:true});
  for (const vp of viewportCases) {
    const n = 'admin-'+vp.width;
    await scenario(n,vp,'ADMINISTRADOR',async (page, state)=>{
      await login(page); await shot(page,n+'-login');
      await page.locator('button[type=submit]').press('Enter');
      await page.waitForURL('**/inicio');
      const titulo=page.getByRole('heading',{name:'Visão geral da unidade'});
      await fit(page,titulo);
      await visible(page.getByText('Não confirmado',{exact:true}).first());
      await visible(page.locator('.quick-action[href="/agenda/novo"]'));
      const menu=page.locator('.menu-button');
      if (vp.width <= 768) {
        assert.equal(await menu.getAttribute('aria-expanded'),'false');
        await menu.focus(); await menu.press('Enter');
        await page.waitForFunction(()=>document.querySelector('.menu-button')?.getAttribute('aria-expanded')==='true');
        await shot(page,n+'-menu');
        await page.keyboard.press('Escape');
        await page.waitForFunction(()=>document.querySelector('.menu-button')?.getAttribute('aria-expanded')==='false');
        assert(await page.locator('app-sidebar').getAttribute('inert')!==null);
      }
      await shot(page,n+'-inicio');
      await page.goto(base+'/pendencias-clinicas');
      await fit(page,page.getByRole('heading',{name:'Pendências clínicas',exact:true}));
      const detalhes=page.getByRole('button',{name:'Detalhes',exact:true});
      await visible(detalhes);
      await detalhes.click();
      const campo=page.getByLabel(/Justificativa da decisão/);
      const aprovar=page.getByRole('button',{name:'Aprovar',exact:true});
      await fit(page,campo);
      assert(await aprovar.isDisabled(),'Decisão sem justificativa deve estar bloqueada');
      await campo.fill('Justificativa sintética suficiente');
      await page.waitForFunction(()=>Array.from(document.querySelectorAll('button')).some(b=>b.textContent.trim()==='Aprovar'&&!b.disabled));
      await fit(page,aprovar);
      await shot(page,n+'-pendencias');
      if (vp.width===1366) {
        await aprovar.press('Enter');
        await visible(page.getByRole('alert').filter({hasText:'desatualizados'}));
        assert.equal(state.writes.length,1);
        assert(await aprovar.isDisabled());
        assert.equal(await campo.inputValue(),'Justificativa sintética suficiente');
        state.failQueue();
        await page.getByRole('button',{name:'Atualizar consulta'}).click();
        await visible(page.getByRole('alert'));
        assert.equal(await page.getByRole('heading',{name:'Pendentes',exact:true}).count(),0);
        await shot(page,n+'-erro');
      }
    });
  }
  for (const perfil of ['PROFISSIONAL','RECEPCAO']) {
    await scenario(perfil.toLowerCase(),viewportCases[0],perfil,async (page)=>{
      await login(page); await page.locator('button[type=submit]').press('Enter');
      await page.waitForURL('**/inicio');
      await visible(page.locator('.quick-action[href="/pacientes/novo"]'));
      assert.equal(await page.locator('.quick-action[href="/agenda/novo"]').count(),perfil==='RECEPCAO'?1:0);
      assert.equal(await page.locator('app-sidebar a[href="/pendencias-clinicas"]').count(),0);
      await page.goto(base+'/pendencias-clinicas'); await page.waitForURL('**/inicio');
      await visible(page.getByRole('heading',{name:'Visão geral da unidade'}));
      await shot(page,perfil.toLowerCase()+'-inicio');
    });
  }
} finally {
  await browser?.close();
  server.close();
  await writeFile(resolve(output,'resultados.json'),JSON.stringify({modo:'API sintética; recursos externos bloqueados',results},null,2));
}
console.log(JSON.stringify(results,null,2));
assert.equal(results.length,5);
assert(results.every(r=>r.status==='PASS'),'Há cenários de navegador reprovados.');
