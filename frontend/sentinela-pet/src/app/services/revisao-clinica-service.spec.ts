import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { RevisaoClinicaService } from './revisao-clinica-service';
import { raizClinica } from './configuracao-contrato';

describe('RevisaoClinicaService', () => {
  let api: RevisaoClinicaService;
  let http: HttpTestingController;
  beforeEach(() => {
    TestBed.configureTestingModule({providers:[provideHttpClient(),provideHttpClientTesting()]});
    api=TestBed.inject(RevisaoClinicaService);
    http=TestBed.inject(HttpTestingController);
  });
  afterEach(() => http.verify());
  it('consulta registros e evidências por identidade e vínculo locais', () => {
    api.prontuario('p').subscribe();
    http.expectOne({method:'GET',url:raizClinica+'/pacientes/p/prontuario'}).flush({});
    api.candidatosOrigem('p','v').subscribe();
    http.expectOne({method:'GET',url:raizClinica+'/pacientes/p/vinculos/v/revisoes/candidatos-origem'}).flush({versaoVinculo:5,candidatos:[]});
  });
  it('registra somente solicitação com tipo, registro, versão e justificativa', () => {
    api.solicitar('p','v',5,'HISTORICO',12,'Conferência sintética suficiente').subscribe();
    const r=http.expectOne({method:'POST',url:raizClinica+'/pacientes/p/vinculos/v/revisoes?version=5'});
    expect(r.request.body).toEqual({tipo:'HISTORICO',registro:12,justificativa:'Conferência sintética suficiente'});
    r.flush('ticket-sintetico');
  });
  it('envia resolução com versões dos dois fatos e ticket explícito, sem inferir aplicação', () => {
    const a={fonte:'ATENDIMENTO_INDIVIDUAL' as const,id:8,versao:2}, b={fonte:'SESSAO_GRUPO' as const,id:9,versao:4};
    api.resolverFrequencia('p','v',3,a,b,'Justificativa sintética suficiente','t').subscribe();
    const req=http.expectOne({method:'POST',url:raizClinica+'/pacientes/p/vinculos/v/resolucoes-frequencia?version=3'});
    expect(req.request.body).toEqual({valido:a,desconsiderado:b,justificativa:'Justificativa sintética suficiente',ticket:'t'});
    req.flush({id:'resolucao'});
    api.desfazerFrequencia('p','v',4,'r','Nova justificativa sintética').subscribe();
    http.expectOne({method:'POST',url:raizClinica+'/pacientes/p/vinculos/v/resolucoes-frequencia/r/desfazer?version=4'}).flush({id:'reversao'});
  });
  it('separa auditoria, aplicação, reversão e reabertura de origem', () => {
    const raiz=raizClinica+'/pacientes/p/vinculos/v/revisoes';
    api.auditoriaOrigem('p','v','t').subscribe();
    http.expectOne({method:'GET',url:raiz+'/t/origem/auditoria'}).flush({});
    for(const acao of ['aplicar','reabrir','desfazer'] as const){
      api.comandoOrigem('p','v',5,acao,'id','Justificativa sintética suficiente').subscribe();
      const req=http.expectOne({method:'POST',url:raiz+(acao==='desfazer'?'/origem/aplicacoes/id/desfazer':'/id/origem/'+acao)+'?version=5'});
      expect(req.request.body).toEqual({justificativa:'Justificativa sintética suficiente'});
      req.flush({id:'novo'});
    }
  });
});
