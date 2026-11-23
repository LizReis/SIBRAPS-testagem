import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import {
  HttpTestingController,
  provideHttpClientTesting,
} from '@angular/common/http/testing';

import {
  CadastroUnicoService,
  IdentidadeCadastralMinima,
} from './cadastro-unico-service';

/**
 * Contrato real do HttpClient: nenhum CPF/CNS na URL e nenhum acesso aos
 * endpoints legados de paciente, historico ou indicadores.
 */
describe('CadastroUnicoService', () => {
  let service: CadastroUnicoService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        CadastroUnicoService,
        provideHttpClient(),
        provideHttpClientTesting(),
      ],
    });
    service = TestBed.inject(CadastroUnicoService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('envia CPF e CNS exclusivamente no corpo POST e devolve somente dados cadastrais', () => {
    let resultado: IdentidadeCadastralMinima | undefined;
    service.conferirDocumentos({
      cpf: '123.123.123-12',
      cns: '100000000000123',
    }).subscribe(dados => { resultado = dados; });

    const req = http.expectOne('/api/pacientes/cadastro-unico/conferencia');
    expect(req.request.method).toBe('POST');
    expect(req.request.urlWithParams).toBe('/api/pacientes/cadastro-unico/conferencia');
    expect(req.request.body).toEqual({
      cpf: '123.123.123-12',
      cns: '100000000000123',
    });
    req.flush({
      nome: 'Paciente sintetico',
      nomeMae: 'Responsavel sintetica',
      dataNascimento: '1995-02-03',
    });

    expect(resultado).toEqual({
      nome: 'Paciente sintetico',
      nomeMae: 'Responsavel sintetica',
      dataNascimento: '1995-02-03',
    });
    expect(Object.keys(resultado!)).toEqual(['nome', 'nomeMae', 'dataNascimento']);
    http.expectNone('/api/pacientes');
  });

  it('permite consultar somente por CNS sem acrescentar IDs ou unidade no payload', () => {
    service.conferirDocumentos({ cns: '900000000000009' }).subscribe();
    const req = http.expectOne('/api/pacientes/cadastro-unico/conferencia');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ cns: '900000000000009' });
    req.flush({
      nome: 'Pessoa existente',
      nomeMae: 'Responsavel',
      dataNascimento: '2000-01-01',
    });
  });

  it('envia confirmacao explicita ao endpoint de vinculo local e aceita 204 sem corpo', () => {
    let sucesso = false;
    const dados = {
      cpf: '12312312312',
      nome: 'Paciente sintetico',
      nomeMae: 'Responsavel sintetica',
      dataNascimento: '1995-02-03',
    };
    service.confirmarVinculo(dados).subscribe(() => { sucesso = true; });
    const req = http.expectOne('/api/pacientes/cadastro-unico/confirmar-vinculo');
    expect(req.request.method).toBe('POST');
    expect(req.request.urlWithParams).not.toContain('12312312312');
    expect(req.request.body).toEqual(dados);
    req.flush(null, { status: 204, statusText: 'No Content' });
    expect(sucesso).toBe(true);
    http.expectNone('/api/pacientes');
  });

  it('propaga rejeicao de identidade divergente, sem segundo envio automatico', () => {
    let status: number | undefined;
    service.confirmarVinculo({
      cns: '100000000000123',
      nome: 'Nome divergente',
      nomeMae: 'Responsavel',
      dataNascimento: '1995-02-03',
    }).subscribe({
      next: () => { throw new Error('Nao deveria confirmar divergencia'); },
      error: erro => { status = erro.status; },
    });
    const req = http.expectOne('/api/pacientes/cadastro-unico/confirmar-vinculo');
    req.flush({ message: 'Confirmacao divergente' }, {
      status: 422,
      statusText: 'Unprocessable Entity',
    });
    expect(status).toBe(422);
    http.expectNone('/api/pacientes');
  });

  it('nao converte erro de consulta em cadastro automatico', () => {
    let status: number | undefined;
    service.conferirDocumentos({ cpf: '12312312312' }).subscribe({
      error: erro => { status = erro.status; },
    });
    const req = http.expectOne('/api/pacientes/cadastro-unico/conferencia');
    req.flush({ message: 'Cadastro nao localizado' }, {
      status: 404,
      statusText: 'Not Found',
    });
    expect(status).toBe(404);
    http.expectNone('/api/pacientes');
  });
});
