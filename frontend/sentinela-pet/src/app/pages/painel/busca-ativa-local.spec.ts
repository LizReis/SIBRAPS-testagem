import { TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { of, Subject, throwError } from 'rxjs';
import { BuscaAtivaLocal } from './busca-ativa-local';
import { UsuarioLogadoService } from '../../services/usuario-logado-service';
import { AlertaLocal } from '../../services/contrato-clinico-local';

describe('BuscaAtivaLocal RNF17',()=>{
  const alerta:AlertaLocal={idPublico:'p',vinculo:'v',versao:3,nome:'Sintético',classificacaoRisco:'VERMELHO',faltasConsecutivas:3,
    gatilhoVisitaAcionado:false,acao:'ACOMPANHAMENTO_PRIORITARIO',alerta:'Acompanhamento prioritário',localBusca:null,avisoAdicional:null};
  let perfil:string;
  let http:{get:ReturnType<typeof vi.fn>;post:ReturnType<typeof vi.fn>};
  beforeEach(async()=>{
    perfil='PROFISSIONAL';
    http={get:vi.fn().mockReturnValue(of({alertas:[alerta],estadoNaoConfirmado:0,selecaoIndeterminada:0})),post:vi.fn().mockReturnValue(of({}))};
    await TestBed.configureTestingModule({imports:[BuscaAtivaLocal],providers:[provideRouter([]),
      {provide:HttpClient,useValue:http},{provide:UsuarioLogadoService,useValue:{obterUsuarioLogado:()=>of({tipoUsuario:perfil})}}]}).compileComponents();
  });
  function iniciar(){const f=TestBed.createComponent(BuscaAtivaLocal);f.detectChanges();return f.componentInstance;}
  it('recepção não encaminha e não consulta auditoria administrativa',()=>{
    perfil='RECEPCAO';const c=iniciar();c.preparar(alerta);c.encaminhar();c.carregarAuditoria(0);
    expect(http.post).not.toHaveBeenCalled();expect(http.get).toHaveBeenCalledTimes(1);
  });
  it('recusa encaminhamento de alerta de revisão histórica',()=>{
    const c=iniciar();const pendente={...alerta,acao:'REVISAO_HISTORICA' as const};c.busca={alertas:[pendente],estadoNaoConfirmado:0,selecaoIndeterminada:0};
    c.preparar(pendente);expect(c.selecionado).toBeUndefined();expect(http.post).not.toHaveBeenCalled();
  });
  it('não repete após versão recusada; exige nova consulta',()=>{
    const c=iniciar();c.preparar(alerta);c.destino='Equipe local';c.descricao='Justificativa sintética suficiente';
    http.post.mockReturnValue(throwError(()=>new HttpErrorResponse({status:422,error:{code:'STALE_VERSION'}})));
    c.encaminhar();c.encaminhar();expect(http.post).toHaveBeenCalledTimes(1);expect(c.exigeConsulta).toBe(true);
    expect(c.descricao).toContain('suficiente');c.carregar();expect(c.selecionado).toBeUndefined();
  });
  it('recupera resultado incerto só por ação explícita e mesma chave e conteúdo',()=>{
    const c=iniciar();c.preparar(alerta);c.destino='Equipe local';c.descricao='Justificativa sintética suficiente';
    http.post.mockReturnValueOnce(throwError(()=>new HttpErrorResponse({status:0}))).mockReturnValueOnce(of({}));
    c.encaminhar();c.encaminhar();c.carregar();expect(http.post).toHaveBeenCalledTimes(1);expect(http.get).toHaveBeenCalledTimes(1);
    c.confirmarResultado();expect(http.post).toHaveBeenCalledTimes(2);
    expect(http.post.mock.calls[1]).toEqual(http.post.mock.calls[0]);expect(c.pendente).toBeUndefined();expect(c.mensagem).toContain('registrado');
  });
  it('só navega após confirmação da visualização e preserva o vínculo',()=>{
    perfil='RECEPCAO';const c=iniciar();
    const navegar=vi.spyOn(TestBed.inject(Router),'navigate').mockResolvedValue(true);
    const resposta=new Subject<object>();http.post.mockReturnValueOnce(resposta.asObservable());
    c.visualizar(alerta);expect(navegar).not.toHaveBeenCalled();
    expect(http.post.mock.calls[0][0]).toContain('/pacientes/p/vinculos/v/busca-ativa/visualizacao');
    expect(http.post.mock.calls[0][1]).toEqual(expect.objectContaining({version:3,requisicao:expect.any(String)}));
    resposta.next({});resposta.complete();
    expect(navegar).toHaveBeenCalledWith(['/pacientes/detalhes','p'],{queryParams:{vinculo:'v'}});
  });
  it('não navega após recusa nem aceita alerta fora da lista',()=>{
    const c=iniciar();const navegar=vi.spyOn(TestBed.inject(Router),'navigate').mockResolvedValue(true);
    c.visualizar({...alerta,vinculo:'externo'});expect(http.post).not.toHaveBeenCalled();
    http.post.mockReturnValueOnce(throwError(()=>new HttpErrorResponse({status:404})));
    c.visualizar(alerta);expect(navegar).not.toHaveBeenCalled();expect(c.exigeConsulta).toBe(true);
  });
  it('não reapresenta auditoria superada depois de encaminhar',()=>{
    perfil='ADMINISTRADOR';const c=iniciar();const antiga=new Subject<object>();
    http.get.mockReturnValueOnce(antiga.asObservable());c.carregarAuditoria(0);
    c.preparar(alerta);c.destino='Equipe local';c.descricao='Justificativa sintética suficiente';c.encaminhar();
    antiga.next({content:[{id:'antigo'}],totalPages:1});antiga.complete();
    expect(c.auditoria).toBeUndefined();expect(c.consultandoAuditoria).toBe(false);
    http.get.mockReturnValueOnce(of({content:[],totalPages:1}));c.carregarAuditoria(0);
    expect(c.auditoria?.content).toEqual([]);
  });
  it('reutiliza chave da lista somente após falha incerta e renova após recusa definitiva',()=>{
    http.get.mockReturnValueOnce(throwError(()=>new HttpErrorResponse({status:0})));const c=iniciar();
    http.get.mockReturnValueOnce(throwError(()=>new HttpErrorResponse({status:409})));c.carregar();
    expect(http.get.mock.calls[1]).toEqual(http.get.mock.calls[0]);
    c.carregar();expect(http.get.mock.calls[2][1].params.requisicao).not.toBe(http.get.mock.calls[0][1].params.requisicao);
    expect(c.busca?.alertas).toEqual([alerta]);
  });
  it('limpa auditoria anterior quando consulta administrativa falha',()=>{
    perfil='ADMINISTRADOR';const c=iniciar();
    http.get.mockReturnValueOnce(of({content:[],totalPages:1}));c.carregarAuditoria(0);expect(c.auditoria).toBeDefined();
    http.get.mockReturnValueOnce(throwError(()=>new HttpErrorResponse({status:403})));c.carregarAuditoria(0);
    expect(c.auditoria).toBeUndefined();expect(c.erroAuditoria).toBeTruthy();
  });
});
