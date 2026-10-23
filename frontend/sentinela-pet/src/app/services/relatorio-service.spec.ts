import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { RelatorioService } from './relatorio-service';

describe('RelatorioService', () => {
  let service: RelatorioService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    service = TestBed.inject(RelatorioService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('chama o endpoint de busca ativa com somente os filtros informados', () => {
    service.baixarBuscaAtivaPdf({ dataInicio: '2026-01-01', dataFim: '2026-01-31', profissionalId: 'uuid', tipoAcompanhamento: 'INDIVIDUAL' }).subscribe();
    const req = http.expectOne((request) => request.url === '/api/relatorios/busca-ativa/pdf');
    expect(req.request.responseType).toBe('blob');
    expect(req.request.params.get('profissionalId')).toBe('uuid');
    expect(req.request.params.get('tipoAcompanhamento')).toBe('INDIVIDUAL');
    req.flush(new Blob([], { type: 'application/pdf' }));
  });

  it('chama o endpoint de frequência com o id real do grupo', () => {
    service.baixarFrequenciaGruposPdf({ grupoId: 42 }).subscribe();
    const req = http.expectOne('/api/relatorios/frequencia-grupos/pdf?grupoId=42');
    expect(req.request.params.has('dataInicio')).toBe(false);
    req.flush(new Blob([], { type: 'application/pdf' }));
  });

  it('chama os endpoints Excel preservando os mesmos filtros', () => {
    service.baixarBuscaAtivaExcel({ profissionalId: 'uuid', tipoAcompanhamento: 'GRUPO_TERAPEUTICO' }).subscribe();
    const busca = http.expectOne((request) => request.url === '/api/relatorios/busca-ativa/excel');
    expect(busca.request.responseType).toBe('blob');
    expect(busca.request.params.get('profissionalId')).toBe('uuid');
    expect(busca.request.params.get('tipoAcompanhamento')).toBe('GRUPO_TERAPEUTICO');
    busca.flush(new Blob([], { type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet' }));

    service.baixarFrequenciaGruposExcel({ grupoId: 42 }).subscribe();
    const frequencia = http.expectOne('/api/relatorios/frequencia-grupos/excel?grupoId=42');
    expect(frequencia.request.responseType).toBe('blob');
    frequencia.flush(new Blob([], { type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet' }));
  });
});
