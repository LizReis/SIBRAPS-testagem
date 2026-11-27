import { HttpErrorResponse } from '@angular/common/http';

export interface ErroCampo {
  fieldName: string;
  message: string;
}

export interface CorpoErroHttp {
  timestamp?: string;
  status?: number;
  error?: string;
  message?: string;
  path?: string;
  code?: string;
  correlationId?: string;
  errors?: ErroCampo[];
  codigo?: string;
  tipoConflito?: string | null;
  agendamentoId?: number | null;
  sessaoId?: number | null;
}

const mensagens = {
  AUTHENTICATION_REQUIRED: 'Sua sessão não está válida. Entre novamente.',
  ACCESS_DENIED: 'Você não tem permissão para realizar esta ação.',
  RESOURCE_NOT_FOUND: 'O recurso solicitado não foi encontrado.',
  MALFORMED_REQUEST: 'Confira o formato e os campos da solicitação.',
  VALIDATION_FAILED: 'Confira os dados informados.',
  PATIENT_SIMILARITY_CONFIRMATION: 'Há registros similares. Confira o cadastro antes de confirmar uma nova inclusão.',
  BUSINESS_CONFLICT: 'A operação conflita com os dados atuais.',
  DUPLICATE_RESOURCE: 'Já existe um registro com esses dados.',
  STALE_VERSION: 'Os dados estão desatualizados. Atualize a consulta, confira os dados e decida novamente.',
  CONCURRENT_UPDATE: 'Há outra operação concorrente. Atualize os dados antes de tentar novamente.',
  CONCURRENT_DEADLOCK: 'A operação não pôde ser concluída por concorrência. Atualize os dados antes de tentar novamente.',
  LOCK_ACQUISITION_TIMEOUT: 'Não foi possível concluir a operação neste momento. Atualize os dados antes de tentar novamente.',
  SERVICE_UNAVAILABLE: 'O serviço está temporariamente indisponível.',
  DATABASE_FAILURE: 'Ocorreu uma falha técnica no serviço.',
  REPORT_GENERATION_FAILED: 'Não foi possível gerar o relatório solicitado.',
  INTERNAL_ERROR: 'Ocorreu uma falha interna.',
  NETWORK_ERROR: 'Não foi possível confirmar a resposta do servidor. Verifique sua conexão.',
  UNKNOWN_ERROR: 'Não foi possível concluir a operação.',
};

export type CategoriaErroHttp = keyof typeof mensagens;

const mensagemDeDominio = new Set<CategoriaErroHttp>(['VALIDATION_FAILED', 'BUSINESS_CONFLICT', 'DUPLICATE_RESOURCE']);
const tecnicos = new Set<CategoriaErroHttp>([
  'SERVICE_UNAVAILABLE', 'DATABASE_FAILURE', 'REPORT_GENERATION_FAILED', 'INTERNAL_ERROR',
]);
const uuid = /^[\da-f]{8}(?:-[\da-f]{4}){3}-[\da-f]{12}$/i;

function objeto(valor: unknown): Record<string, unknown> {
  return valor !== null && typeof valor === 'object' ? valor as Record<string, unknown> : {};
}

function textoSeguro(valor: unknown): string | undefined {
  if (typeof valor !== 'string') return undefined;
  const texto = valor.trim();
  // Respostas antigas podiam conter mensagens do driver ou dados serializados.
  if (!texto || texto.length > 500 || /[\r\n<>{}\[\]]/.test(texto)
      || /\b(?:exception|stack\s*trace|sql(?:state)?|jdbc|hibernate|bearer|token|password|credencia\w*|authorization)\b/i.test(texto)
      || /(?:\bsenha\s*[:=]|^Conflito para (?:o paciente )?)/i.test(texto)
      || /\b(?:select\s+.+\s+from|insert\s+into|update\s+.+\s+set|delete\s+from)\b/i.test(texto)
      || /\b[\da-f]{8}(?:-[\da-f]{4}){3}-[\da-f]{12}\b|[\w.+-]+@[\w.-]+\.[a-z]{2,}|\b\d{3}\.\d{3}\.\d{3}-\d{2}\b|\b\d{11,}\b/i.test(texto)) {
    return undefined;
  }
  return texto;
}

function categoria(status: unknown, corpo: CorpoErroHttp): CategoriaErroHttp {
  if (status === 0) return 'NETWORK_ERROR';
  if (status === 401) return 'AUTHENTICATION_REQUIRED';
  if (status === 403) return 'ACCESS_DENIED';
  if (status === 404) return 'RESOURCE_NOT_FOUND';
  const code = corpo.code;
  // Falhas técnicas podem vir também de proxies e versões que não publicam code.
  if (typeof status === 'number' && status >= 500) {
    if (code === 'REPORT_GENERATION_FAILED') return code;
    if (code?.startsWith('DATABASE_')) return 'DATABASE_FAILURE';
    return status === 503 || code === 'SERVICE_UNAVAILABLE' ? 'SERVICE_UNAVAILABLE' : 'INTERNAL_ERROR';
  }
  if (code && Object.hasOwn(mensagens, code)) return code as CategoriaErroHttp;
  switch (status) {
    case 400:
      return !code && /^Registros similares encontrados\b/i.test(corpo.message ?? '')
        ? 'PATIENT_SIMILARITY_CONFIRMATION' : 'MALFORMED_REQUEST';
    case 409:
    case 422:
      if (!code && /^(?:Versão local divergente\.|Recarregue o cadastro antes de salvar\.|O atendimento foi alterado\. Atualize|(?:O atendimento|O grupo|A sessão) foi alterad[oa] por outro usuári[oa]\.?$)/i
        .test((corpo.message ?? '').trim())) return 'STALE_VERSION';
      if (status === 422) return 'VALIDATION_FAILED';
      if (corpo.error === 'Recurso duplicado.') return 'DUPLICATE_RESOURCE';
      if (corpo.error === 'Conflito de atualização.') return 'CONCURRENT_UPDATE';
      return 'BUSINESS_CONFLICT';
    default: return 'UNKNOWN_ERROR';
  }
}

function camposInvalidos(corpo: CorpoErroHttp): ErroCampo[] {
  if (!Array.isArray(corpo.errors)) return [];
  return corpo.errors.flatMap(item => {
    const campo = objeto(item);
    if (typeof campo['fieldName'] !== 'string' || !/^[a-z]\w*(?:\.\w+|\[\d+\])*$/.test(campo['fieldName'])) return [];
    const nome = campo['fieldName'];
    if (nome.split('.').some(parte => ['constructor', 'prototype', '__proto__'].includes(parte))) return [];
    return [{ fieldName: nome, message: textoSeguro(campo['message']) ?? 'Valor inválido.' }];
  });
}

export function interpretarErroHttp(erro: unknown, opcoes: { escrita?: boolean; padrao?: string } = {}) {
  const resposta = objeto(erro);
  const bruto = objeto(resposta['error']);
  const corpo: CorpoErroHttp = {
    ...bruto,
    code: typeof bruto['code'] === 'string' ? bruto['code'] : undefined,
    message: typeof bruto['message'] === 'string' ? bruto['message'] : undefined,
  };
  const tipo = categoria(resposta['status'], corpo);
  const tecnico = tecnicos.has(tipo);
  let mensagem = (mensagemDeDominio.has(tipo) ? textoSeguro(corpo.message) : undefined)
    ?? (tipo === 'UNKNOWN_ERROR' ? opcoes.padrao : undefined)
    ?? mensagens[tipo];
  if (opcoes.escrita && (tecnico || tipo === 'NETWORK_ERROR')) {
    mensagem += ' Confira o estado atual antes de tentar a operação novamente.';
  }

  const idCabecalho = erro instanceof HttpErrorResponse ? erro.headers.get('X-Correlation-ID') : null;
  const correlationId = [corpo.correlationId, idCabecalho].find(id => typeof id === 'string' && uuid.test(id));
  if (tecnico && correlationId) mensagem += ` Referência para o suporte: ${correlationId}.`;

  return {
    tipo,
    mensagem,
    campos: tipo === 'VALIDATION_FAILED' ? camposInvalidos(corpo) : [],
    correlationId,
    codigo: corpo.codigo,
    tipoConflito: corpo.tipoConflito,
    agendamentoId: corpo.agendamentoId,
    sessaoId: corpo.sessaoId,
  };
}
