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
});
