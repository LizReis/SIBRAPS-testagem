import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import { podeEditarPaciente } from '../auth/permissoes';
import { GrupoTerapeuticoService } from './grupo-terapeutico-service';
import { AgendamentoService } from './agendamento-service';
import { contratoExperimental, raizClinica } from './configuracao-contrato';
import { PacientePayload, PacienteService } from './paciente/paciente-service';
import { RelatorioService } from './relatorio-service';

describe('Contratos HTTP do pacote 6B.2', () => {
  let http: HttpTestingController;
  beforeEach(() => {
    TestBed.configureTestingModule({providers: [provideHttpClient(), provideHttpClientTesting()]});
    http = TestBed.inject(HttpTestingController);
  });
  afterEach(() => http.verify());

  it('habilita o contrato por vínculo nos dois builds', () => {
    expect(podeEditarPaciente('ADMINISTRADOR')).toBe(true);
    expect(podeEditarPaciente('RECEPCAO')).toBe(true);
    expect(podeEditarPaciente('PROFISSIONAL')).toBe(contratoExperimental);
    expect(contratoExperimental).toBe(true);
    expect(['/api', '/api/ensaio-escritores']).toContain(raizClinica);
  });

  it('envia a versão do cadastro e omite campos clínicos na edição local', () => {
    const cadastro = {telefone: '81900000000', tipoAcompanhamento: 'AMBOS'} as PacientePayload;
    TestBed.inject(PacienteService).atualizarPaciente('pessoa', cadastro,
      {vinculo: 'vinculo', versaoCadastro: 2, versao: 6}).subscribe();
    const req = http.expectOne(r => r.method === 'PUT');
    expect(req.request.url).toBe(`${raizClinica}/pacientes/pessoa/vinculos/vinculo/cadastro`);
    expect(req.request.params.get('versaoCadastro')).toBe('2');
    expect(req.request.body.tipoAcompanhamento).toBeUndefined();
    req.flush({});
  });

  it('usa a versão do atendimento e mantém a justificativa da correção', () => {
    TestBed.inject(AgendamentoService).corrigirFrequencia(14, 'FALTOU', 3, 'Correção sintética confirmada.').subscribe();
    const req = http.expectOne(`${raizClinica}/agendamentos/14/frequencia/correcao`);
    expect(req.request.body).toEqual({novaFrequencia: 'FALTOU', version: 3, justificativa: 'Correção sintética confirmada.'});
    req.flush({});
  });

  it('usa a versão do vínculo ao registrar somente a decisão de revisão', () => {
    TestBed.inject(RelatorioService).decidir({
      paciente: 'pessoa', vinculo: 'vinculo', ticket: 'ticket', versaoVinculo: 7,
      nome: 'Sintético', tipoRegistro: 'AGENDAMENTO', registro: 14, estado: 'PENDENTE',
      solicitadoEm: '', decididaEm: null, decisao: null, justificativaSolicitacao: null, justificativaDecisao: null,
    }, 'APROVAR', 'Justificativa sintética.').subscribe();
    const req = http.expectOne(r => r.method === 'POST');
    expect(req.request.url).toBe(`${raizClinica}/pacientes/pessoa/vinculos/vinculo/revisoes/ticket/decisao`);
    expect(req.request.params.get('version')).toBe('7');
    expect(req.request.body).toEqual({decisao: 'APROVAR', justificativa: 'Justificativa sintética.'});
    req.flush('decisao');
  });

  it('usa os recursos operacionais de sessão adicional, composição e presença provisória', () => {
    const grupos = TestBed.inject(GrupoTerapeuticoService);
    grupos.criarProximaSessao({ grupoId: 4, dataSessao: '2030-06-20', horario: null }).subscribe();
    const criar = http.expectOne(`${raizClinica}/grupos/sessoes`);
    expect(criar.request.method).toBe('POST');
    expect(criar.request.body).toEqual({ grupoId: 4, dataSessao: '2030-06-20', horario: null });
    criar.flush({ id: 5, version: 0 });

    grupos.adicionarParticipante(5, 'paciente-sintetico', 0).subscribe();
    const adicionar = http.expectOne(req => req.method === 'POST');
    expect(adicionar.request.url).toBe(`${raizClinica}/grupos/sessoes/5/participantes`);
    expect(adicionar.request.params.get('version')).toBe('0');
    adicionar.flush({ id: 5, version: 1 });

    grupos.registrarProvisoria(5, 'paciente-sintetico', 'PRESENTE', 1).subscribe();
    const provisoria = http.expectOne(req => req.method === 'PATCH');
    expect(provisoria.request.url).toBe(`${raizClinica}/grupos/sessoes/5/participantes/paciente-sintetico/presenca`);
    expect(provisoria.request.params.get('version')).toBe('1');
    expect(provisoria.request.body).toEqual({ statusPresenca: 'PRESENTE' });
    provisoria.flush({ id: 5, version: 2 });
  });
});
