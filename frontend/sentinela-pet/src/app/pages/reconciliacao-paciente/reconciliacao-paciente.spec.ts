import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { HttpErrorResponse } from '@angular/common/http';
import { ReconciliacaoPaciente } from './reconciliacao-paciente';
import { RevisaoClinicaService, CandidatoFrequencia, AuditoriaOrigem, RegistroResolucao } from '../../services/revisao-clinica-service';

describe('ReconciliacaoPaciente',()=>{
  const g:CandidatoFrequencia={ocorridoEm:'2026-11-01T08:00:00',tipo:'FREQUENCIAS_COINCIDENTES',fatos:[
    {referencia:{fonte:'ATENDIMENTO_INDIVIDUAL',id:1,versao:2},presente:true,podeDesconsiderar:false},
    {referencia:{fonte:'SESSAO_GRUPO',id:2,versao:3},presente:true,podeDesconsiderar:true}]};
  let origem=false;
  const evento={id:'e',ator:'a',perfil:'ADMINISTRADOR',justificativa:'Justificativa sintética',registradoEm:'2026-11-01T12:00:00Z'};
  const trilha:AuditoriaOrigem={versaoVinculo:5,historico:1,solicitacao:evento,decisao:{evento,resultado:'APROVAR'},
    prova:{fonte:'AGENDAMENTO',registroFonte:1,versaoFonte:2,participante:null,ocorridoEm:g.ocorridoEm,frequencia:'PRESENTE',tipoHistorico:'PRESENCA'},
    aplicacao:null,reversao:null,reaberturaAnterior:null,reaberturaPosterior:null,estadoAplicacao:'SEM_APLICACAO',integridadeEventos:true};
  let api:{candidatosFrequencia:ReturnType<typeof vi.fn>;auditoriaFrequencia:ReturnType<typeof vi.fn>;resolverFrequencia:ReturnType<typeof vi.fn>;desfazerFrequencia:ReturnType<typeof vi.fn>;auditoriaOrigem:ReturnType<typeof vi.fn>;comandoOrigem:ReturnType<typeof vi.fn>};
  beforeEach(async()=>{
    origem=false;
    api={candidatosFrequencia:vi.fn().mockReturnValue(of({versaoVinculo:5,revisaoNecessaria:true,candidatos:[g],total:1,pagina:0,tamanho:10})),
      auditoriaFrequencia:vi.fn().mockReturnValue(of({versaoVinculo:5,registros:[],total:0,pagina:0,tamanho:10})),resolverFrequencia:vi.fn().mockReturnValue(of({id:'r'})),
      desfazerFrequencia:vi.fn().mockReturnValue(of({id:'reversao'})),auditoriaOrigem:vi.fn().mockReturnValue(of(trilha)),comandoOrigem:vi.fn().mockReturnValue(of({id:'a'}))};
    await TestBed.configureTestingModule({imports:[ReconciliacaoPaciente],providers:[provideRouter([]),
      {provide:ActivatedRoute,useValue:{get snapshot(){return {data:{origem}};},get paramMap(){return of(convertToParamMap({id:'p',vinculo:'v',ticket:origem?'t':''}));}}},
      {provide:RevisaoClinicaService,useValue:api}]}).compileComponents();
  });
  function iniciar(){const f=TestBed.createComponent(ReconciliacaoPaciente);f.detectChanges();return f.componentInstance;}
  it('exige dois fatos distintos, confirmação, justificativa e proteção do fato válido',()=>{
    const c=iniciar();c.selecionar(g);c.valido=g.fatos[0];c.desconsiderado=g.fatos[0];c.justificativa='Justificativa sintética suficiente';c.confirmou=true;
    c.resolver();expect(api.resolverFrequencia).not.toHaveBeenCalled();
    c.desconsiderado=g.fatos[1];c.confirmou=false;c.resolver();expect(api.resolverFrequencia).not.toHaveBeenCalled();
    c.confirmou=true;c.resolver();c.resolver();
    expect(api.resolverFrequencia).toHaveBeenCalledExactlyOnceWith('p','v',5,g.fatos[0].referencia,g.fatos[1].referencia,'Justificativa sintética suficiente',undefined);
  });
  it('exige justificativa própria e seleção explícita para desfazer uma resolução',()=>{
    const r:RegistroResolucao={id:'r',fonteValida:'ATENDIMENTO_INDIVIDUAL',fatoValido:1,versaoValida:2,frequenciaValida:'PRESENTE',fonteDesconsiderada:'SESSAO_GRUPO',fatoDesconsiderado:2,versaoDesconsiderada:3,frequenciaDesconsiderada:'PRESENTE',horario:g.ocorridoEm,ator:'a',perfil:'ADMINISTRADOR',justificativa:'Motivo anterior',registradoEm:evento.registradoEm,ticket:null,decisao:null,reversao:null,atorReversao:null,perfilReversao:null,justificativaReversao:null,revertidoEm:null};
    api.auditoriaFrequencia.mockReturnValue(of({versaoVinculo:5,registros:[r],total:1,pagina:0,tamanho:10}));
    const c=iniciar();c.selecionar(g);c.justificativa='Justificativa de outra operação';
    c.desfazer(r);expect(api.desfazerFrequencia).not.toHaveBeenCalled();
    c.selecionarReversao(r);expect(c.justificativa).toBe('');expect(c.grupo).toBeUndefined();
    c.desfazer(r);expect(api.desfazerFrequencia).not.toHaveBeenCalled();
    c.justificativaReversao='Nova justificativa sintética';c.desfazer(r);c.desfazer(r);
    expect(api.desfazerFrequencia).toHaveBeenCalledExactlyOnceWith('p','v',5,'r','Nova justificativa sintética');
  });
  it('bloqueia resolução se consultas têm versões diferentes',()=>{
    api.auditoriaFrequencia.mockReturnValue(of({versaoVinculo:6,registros:[],total:0,pagina:0,tamanho:10}));
    const c=iniciar();expect(c.exigeConsulta).toBe(true);expect(c.podeResolver).toBe(false);
  });
  it('aplica origem somente após aprovação, prova e justificativa, sem reenvio',()=>{
    origem=true;const c=iniciar();c.justificativa='Justificativa sintética suficiente';c.comandoOrigem('aplicar');c.comandoOrigem('aplicar');
    expect(api.comandoOrigem).toHaveBeenCalledExactlyOnceWith('p','v',5,'aplicar','t','Justificativa sintética suficiente');
  });
  it('não aplica origem com integridade inconclusiva',()=>{
    origem=true;api.auditoriaOrigem.mockReturnValue(of({...trilha,integridadeEventos:false}));const c=iniciar();c.justificativa='Justificativa sintética suficiente';
    c.comandoOrigem('aplicar');expect(api.comandoOrigem).not.toHaveBeenCalled();
  });
  it('preserva justificativa na recusa e impede reenvio até consulta',()=>{
    origem=true;api.comandoOrigem.mockReturnValue(throwError(()=>new HttpErrorResponse({status:409,error:{code:'STALE_VERSION'}})));
    const c=iniciar();c.justificativa='Justificativa sintética suficiente';c.comandoOrigem('aplicar');c.comandoOrigem('aplicar');
    expect(api.comandoOrigem).toHaveBeenCalledTimes(1);expect(c.exigeConsulta).toBe(true);expect(c.justificativa).toContain('suficiente');
  });
});
