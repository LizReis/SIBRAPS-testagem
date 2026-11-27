import { HttpErrorResponse, HttpHeaders } from '@angular/common/http';
import { interpretarErroHttp } from './erro-http';

function erro(status: number, corpo: object = {}) {
  return new HttpErrorResponse({ status, error: corpo });
}

describe('Contrato de erros HTTP', () => {
  it.each([409, 422])('reconhece STALE_VERSION no HTTP %d sem depender do texto', (status) => {
    const resultado = interpretarErroHttp(erro(status, {
      code: 'STALE_VERSION', message: 'Texto novo.', errors: [{ fieldName: 'version', message: 'Inválida' }],
    }));
    expect(resultado.tipo).toBe('STALE_VERSION');
    expect(resultado.mensagem).toContain('Atualize a consulta');
    expect(resultado.campos).toEqual([]);
  });

  it('preserva o código específico e as referências de reconciliação separadamente do código geral', () => {
    const corpo = {
      code: 'BUSINESS_CONFLICT', codigo: 'CONFLITO_ASSISTENCIAL_PENDENTE_RECONCILIACAO',
      tipoConflito: 'PROFISSIONAL', agendamentoId: 7, sessaoId: null, message: 'Solicite revisão autorizada.',
    };
    expect(interpretarErroHttp(erro(409, corpo))).toMatchObject({
      tipo: 'BUSINESS_CONFLICT', codigo: corpo.codigo, tipoConflito: 'PROFISSIONAL', agendamentoId: 7,
      sessaoId: null, mensagem: corpo.message,
    });
  });

  it.each([
    [401, {}, 'AUTHENTICATION_REQUIRED'],
    [403, {}, 'ACCESS_DENIED'],
    [404, {}, 'RESOURCE_NOT_FOUND'],
    [400, {}, 'MALFORMED_REQUEST'],
    [422, { code: 'MALFORMED_REQUEST' }, 'MALFORMED_REQUEST'],
    [409, { error: 'Recurso duplicado.' }, 'DUPLICATE_RESOURCE'],
    [409, { message: 'Horário indisponível.' }, 'BUSINESS_CONFLICT'],
    [409, { error: 'Conflito de atualização.' }, 'CONCURRENT_UPDATE'],
    [422, { message: 'Versão local divergente. Consulte o vínculo novamente.' }, 'STALE_VERSION'],
    [400, { message: 'Registros similares encontrados. [dados antigos]' }, 'PATIENT_SIMILARITY_CONFIRMATION'],
    [400, { code: 'PATIENT_SIMILARITY_CONFIRMATION' }, 'PATIENT_SIMILARITY_CONFIRMATION'],
    [409, { code: 'CONCURRENT_DEADLOCK' }, 'CONCURRENT_DEADLOCK'],
    [409, { code: 'LOCK_ACQUISITION_TIMEOUT' }, 'LOCK_ACQUISITION_TIMEOUT'],
    [500, { code: 'DATABASE_QUERY_TIMEOUT' }, 'DATABASE_FAILURE'],
    [500, { code: 'REPORT_GENERATION_FAILED' }, 'REPORT_GENERATION_FAILED'],
    [500, {}, 'INTERNAL_ERROR'],
    [503, {}, 'SERVICE_UNAVAILABLE'],
    [0, {}, 'NETWORK_ERROR'],
  ])('classifica HTTP %i com compatibilidade de formato', (status, corpo, tipo) => {
    expect(interpretarErroHttp(erro(status, corpo)).tipo).toBe(tipo);
  });

  it('associa validações seguras e descarta conteúdo técnico ou nomes de campo inválidos', () => {
    const resultado = interpretarErroHttp(erro(422, {
      code: 'VALIDATION_FAILED', message: 'Confira os dados.',
      errors: [
        { fieldName: 'endereco.cep', message: 'Informe o CEP.' },
        { fieldName: 'cpf', message: 'SQLException: CPF 123.456.789-00' },
        { fieldName: 'constructor', message: 'ignorar' },
      ],
    }));
    expect(resultado.campos).toEqual([
      { fieldName: 'endereco.cep', message: 'Informe o CEP.' }, { fieldName: 'cpf', message: 'Valor inválido.' },
    ]);
  });

  it('apresenta o mesmo 404 para ausência e recurso de outra unidade', () => {
    const inexistente = interpretarErroHttp(erro(404, { message: 'Registro não localizado.' }));
    const externo = interpretarErroHttp(erro(404, { code: 'RESOURCE_NOT_FOUND', message: 'Paciente de outra unidade.' }));
    expect(externo.mensagem).toBe(inexistente.mensagem);
    expect(externo.mensagem).not.toContain('Paciente');
    expect(interpretarErroHttp(erro(404, { code: 'ACCESS_DENIED' })).mensagem).toBe(inexistente.mensagem);
    expect(interpretarErroHttp(erro(403, { code: 'AUTHENTICATION_REQUIRED' })).tipo).toBe('ACCESS_DENIED');
  });

  it('apresenta correlação válida e orientação de escrita sem expor detalhes de 5xx ou rede', () => {
    const id = 'dd0c35c4-6a1f-4f14-9c99-6cc209788722';
    const resultado = interpretarErroHttp(new HttpErrorResponse({
      status: 503,
      error: { correlationId: 'conteúdo confidencial', message: 'SELECT cns FROM paciente' },
      headers: new HttpHeaders({ 'X-Correlation-ID': id }),
    }), { escrita: true });
    expect(resultado.mensagem).toContain(id);
    expect(resultado.mensagem).toContain('Confira o estado atual');
    expect(resultado.mensagem).not.toMatch(/SELECT|confidencial/);
    expect(interpretarErroHttp(erro(0), { escrita: true }).mensagem).toContain('Confira o estado atual');
    expect(interpretarErroHttp(erro(500, { correlationId: 'Bearer credencial' })).mensagem).not.toContain('Bearer');
  });

  it('não exibe SQL do contrato legado e não transforma qualquer 409 em versão desatualizada', () => {
    const resultado = interpretarErroHttp(erro(409, { message: 'INSERT INTO pacientes (cpf) VALUES (12345678901)' }));
    expect(resultado.tipo).toBe('BUSINESS_CONFLICT');
    expect(resultado.mensagem).not.toContain('INSERT');
  });
});
