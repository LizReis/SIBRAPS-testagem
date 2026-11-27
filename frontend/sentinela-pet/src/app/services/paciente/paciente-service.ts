import type { AgendamentoDTO } from '../agendamento-service';
import { contratoExperimental, raizClinica } from '../configuracao-contrato';
import { IndicadoresLocais, PainelLocal, PacienteLocal, PacienteLocalDetalhe, ProntuarioLocal, VersoesPacienteLocal } from '../contrato-clinico-local';
import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { forkJoin, map, Observable, of, switchMap, throwError } from 'rxjs';

export interface EnderecoPayload {
  cidade: string;
  estado: string;
  bairro: string;
  logradouro: string;
  numero: string;
  complemento: string;
  cep: string;
}

export interface UsfReferencia {
  id: number;
  cnes: string;
  nomeUsf: string;
  bairro: string;
  logradouro: string;
  latitude: string;
  longitude: string;
}

export interface CadastroLocal {
  paciente: string;
  vinculo: string;
  versaoCadastro: number;
  versaoVinculo: number;
}

export interface PacientePayload {
  idPublico?: string;

  nome: string;
  nomeMae: string;
  dataNascimento: string;

  dataUltimaPresenca?: string;

  sexo: string;
  racacor: string;

  cns: string;
  cpf: string;
  telefone: string;

  endereco: EnderecoPayload | null;

  situacaoRua: boolean;
  tipoAcompanhamento: string | null;
  unidade?: string;
  local?: VersoesPacienteLocal;
  indicadoresLocais?: IndicadoresLocais;

  countFaltas?: number | null;
  statusPaciente?: string | null;

  usfReferencia: UsfReferencia | null;

  capsReferencia: string;
  profissionalReferenciaId?: string | null;
  profissionalReferencia?: UsuarioReferencia | null;
}

export interface UsuarioReferencia {
  idPublico: string;
  nome: string;
  unidadeAtuacao?: string;
}

export type PacienteSelecao = Pick<PacientePayload, 'idPublico' | 'nome' | 'cpf' | 'cns' | 'dataNascimento' | 'statusPaciente' | 'tipoAcompanhamento'>;
export interface PacienteLista extends Pick<PacientePayload, 'idPublico'|'nome'|'cpf'|'cns'|'dataNascimento'|'countFaltas'|'dataUltimaPresenca'|'statusPaciente'|'tipoAcompanhamento'> { unidade?: string; classificacaoRisco: string | null; }
export interface PacientePesquisa { resumo: { pacientesAtivos:number; emAtencao:number; buscaAtiva:number; semPresencaRecente:number; estadoNaoConfirmado?:number; semPresencaIndeterminada?:number }; pacientes:PacienteLista[]; paginaAtual:number; tamanhoPagina:number; totalRegistros:number; totalPaginas:number; }
export interface ProximoAgendamento { id:number; data:string; hora:string; situacao:string }
export interface PacienteDetalhe { paciente:PacientePayload; referenciaInconclusiva?: boolean; classificacaoRisco:string|null; proximoAgendamento?:ProximoAgendamento; atencaoNecessaria:string }
export type AgendamentoPaciente = Pick<AgendamentoDTO, 'id' | 'dataAgendamento' | 'horaAtendimento' | 'nomeProfissional'
  | 'tipoAcompanhamento' | 'situacaoAtendimento' | 'situacaoReserva' | 'frequenciaAssistencial' | 'migracaoPendente'>;

export interface FieldMessage {
  fieldName: string;
  message: string;
}

export interface StandardError {
  timestamp: string;
  status: number;
  error: string;
  message: string;
  path: string;
}

export interface ValidationError extends StandardError {
  errors: FieldMessage[];
}

export interface HistoricoPacienteEvento {
  id: number;
  tipo: string;
  situacaoAtendimento?: string;
  ocorridoEm: string;
  titulo: string;
  descricao?: string;
  agendamentoId?: number;
  nomeResponsavel?: string;
  funcaoResponsavel?: string;
  nomeUnidade?: string;
  numeroFaltaConsecutiva?: number;
}

export interface HistoricoPacientePayload {
  idPublico: string;
  nomePaciente: string;
  statusPaciente: string;
  classificacaoAtual: string | null;
  tipoAcompanhamento: string;
  dataUltimaPresenca?: string;
  diasSemComparecer?: number;
  quantidadeFaltasAtual: number | null;
  historicoLocalRequerConferencia?: boolean;
  sessoesLocais?: ProntuarioLocal['sessoes'];
  totalConsultasAgendadas: number;
  totalPresencas: number;
  totalFaltas: number;
  totalRemarcacoes: number | null;
  totalGruposTerapeuticos: number;
  totalRegistrosBuscaAtiva: number;
  eventos: HistoricoPacienteEvento[];
}

interface PesquisaLocal extends Omit<PacientePesquisa, 'pacientes' | 'resumo'> {
  pacientes: PacienteLocal[];
}

function linhaLocal(paciente: PacienteLocal): PacienteLista {
  return {
    ...paciente.indicadores,
    idPublico: paciente.idPublico,
    nome: paciente.nome,
    cpf: paciente.cpf ?? '',
    cns: paciente.cns ?? '',
    dataNascimento: paciente.dataNascimento,
    unidade: paciente.unidade,
    dataUltimaPresenca: paciente.indicadores.dataUltimaPresenca ?? undefined,
  };
}

function detalheLocal(local: PacienteLocalDetalhe): PacienteDetalhe {
  const { paciente, cadastro } = local;
  return {
    paciente: {
      ...cadastro,
      ...linhaLocal(paciente),
      tipoAcompanhamento: paciente.indicadores.tipoAcompanhamento,
      usfReferencia: cadastro.usfReferenciaId === null ? null : {
        id: cadastro.usfReferenciaId, nomeUsf: cadastro.nomeUsfReferencia ?? '',
        cnes: '', bairro: '', logradouro: '', latitude: '', longitude: '',
      },
      profissionalReferencia: local.profissionalReferencia,
      local: { vinculo: paciente.vinculo, versao: paciente.versao, versaoCadastro: paciente.versaoCadastro },
      indicadoresLocais: paciente.indicadores,
    },
    classificacaoRisco: paciente.indicadores.classificacaoRisco,
    referenciaInconclusiva: local.referenciaInconclusiva,
    proximoAgendamento: local.proximoAgendamento ?? undefined,
    atencaoNecessaria: paciente.indicadores.revisaoNecessaria ? 'Requer conferência'
      : paciente.indicadores.classificacaoRisco === 'VERMELHO' ? 'Busca ativa'
      : paciente.indicadores.classificacaoRisco === 'AMARELO' ? 'Acompanhar frequência'
      : paciente.indicadores.classificacaoRisco === 'VERDE' ? 'Regular' : 'Não confirmado',
  };
}

@Injectable({
  providedIn: 'root',
})
export class PacienteService {
  private readonly apiUrl = `${raizClinica}/pacientes`;

  constructor(private readonly http: HttpClient) {}

  listar(): Observable<PacienteSelecao[]> {
    if (contratoExperimental) return this.selecionarLocal('');
    return this.http.get<PacientePayload[]>(this.apiUrl);
  }

  pesquisar(params: Record<string, string | number | undefined>): Observable<PacientePesquisa> {
    const aliases: Record<string, string> = { busca: 'q', pagina: 'page', tamanho: 'size' };
    const limpos: Record<string, string | number> = {};
    Object.entries(params).forEach(([chave, valor]) => {
      if (valor !== undefined && valor !== '') limpos[aliases[chave] ?? chave] = valor;
    });
    if (contratoExperimental) return forkJoin({
      pagina: this.http.get<PesquisaLocal>(`${this.apiUrl}/pesquisa`, {params: limpos}),
      painel: this.http.get<PainelLocal>(`${raizClinica}/painel/resumo`),
    }).pipe(map(({ pagina, painel }) => ({
      ...pagina,
      pacientes: pagina.pacientes.map(linhaLocal),
      resumo: { pacientesAtivos: painel.totalPacientesAtivos, emAtencao: painel.distribuicaoClassificacao.amarelos,
        buscaAtiva: painel.distribuicaoClassificacao.vermelhos,
        semPresencaRecente: painel.atividade.semPresencaRecenteConfirmada,
        estadoNaoConfirmado: painel.estadoNaoConfirmado,
        semPresencaIndeterminada: painel.atividade.semPresencaRecenteIndeterminada },
    })));
    return this.http.get<PacientePesquisa>(`${this.apiUrl}/pesquisa`, {params: limpos});
  }
  buscarDetalhe(id:string):Observable<PacienteDetalhe>{
    if (contratoExperimental) return this.http.get<PacienteLocalDetalhe>(`${this.apiUrl}/${id}/detalhe`).pipe(map(detalheLocal));
    return this.http.get<PacienteDetalhe>(`${this.apiUrl}/${id}/detalhe`); }
  listarAgendamentos(id:string):Observable<AgendamentoPaciente[]>{ return this.http.get<AgendamentoPaciente[]>(`${this.apiUrl}/${id}/agendamentos`); }
  encerrar(id:string, motivo:string, descricao?:string, local?: VersoesPacienteLocal):Observable<void>{
    const status:Record<string,string>={ALTA_TERAPEUTICA:'ALTA_TERAPEUTICA',TRANSFERENCIA_OUTRA_UNIDADE:'TRANSFERIDO',ABANDONO_TRATAMENTO:'ABANDONO_TRATAMENTO',OBITO:'OBITO',OUTRO:'OUTRO'};
    const dados = {motivo,statusPaciente:status[motivo],descricao};
    return contratoExperimental
      ? this.comandoLocal(id, local, (url, params) => this.http.patch<void>(`${url}/encerrar`, dados, {params}))
      : this.http.patch<void>(`${this.apiUrl}/${id}/encerrar`, dados);
  }
  configurarAcompanhamento(id: string, tipoAcompanhamento: string, referencia: string | null,
      local?: VersoesPacienteLocal): Observable<void> {
    return this.comandoLocal(id, local, (url, params) =>
      this.http.patch<void>(`${url}/acompanhamento`, {tipoAcompanhamento, referencia}, {params}));
  }

  reativarAcompanhamento(id: string, motivo: string, local?: VersoesPacienteLocal): Observable<void> {
    return this.comandoLocal(id, local, (url, params) =>
      this.http.patch<void>(`${url}/reativar`, {motivo}, {params}));
  }

  registrarBuscaAtiva(id:string, descricao:string, local?: VersoesPacienteLocal):Observable<void>{
    const dados = {tipo:'BUSCA_ATIVA',descricao};
    return contratoExperimental
      ? this.comandoLocal(id, local, (url, params) => this.http.post<void>(`${url}/historico`, dados, {params}))
      : this.http.post<void>(`${this.apiUrl}/${id}/historico`, dados);
  }

  buscarPorNome(nome: string): Observable<PacienteSelecao[]> {
    if (contratoExperimental) return this.selecionarLocal(nome);
    return this.http.get<PacientePayload[]>(
      `${this.apiUrl}/busca/nome?q=${encodeURIComponent(nome)}`,
    );
  }

  buscarPorCpf(cpf: string): Observable<PacienteSelecao> {
    if (contratoExperimental) return this.selecionarLocal(cpf).pipe(switchMap(pessoas => pessoas.length === 1
      ? of(pessoas[0])
      : throwError(() => new HttpErrorResponse({status: pessoas.length ? 409 : 404}))));
    return this.http.get<PacientePayload>(
      `${this.apiUrl}/busca/cpf/${cpf}`,
    );
  }

  buscarPorCns(cns: string): Observable<PacienteSelecao> {
    if (contratoExperimental) return this.selecionarLocal(cns).pipe(switchMap(pessoas => pessoas.length === 1
      ? of(pessoas[0])
      : throwError(() => new HttpErrorResponse({status: pessoas.length ? 409 : 404}))));
    return this.http.get<PacientePayload>(
      `${this.apiUrl}/busca/cns/${cns}`,
    );
  }

  cadastrarPaciente(
    paciente: PacientePayload,
  ): Observable<CadastroLocal | PacientePayload> {
    return contratoExperimental
      ? this.http.post<CadastroLocal>(this.apiUrl, paciente)
      : this.http.post<PacientePayload>(this.apiUrl, paciente);
  }

  atualizarPaciente(
    idPublico: string,
    paciente: PacientePayload,
    local?: VersoesPacienteLocal,
  ): Observable<CadastroLocal | PacientePayload> {
    if (contratoExperimental) {
      if (!local) return this.semVersao();
      const { nome, nomeMae, dataNascimento, sexo, racacor, cpf, cns, telefone,
        endereco, situacaoRua, usfReferencia, capsReferencia } = paciente;
      const cadastro = { nome, nomeMae, dataNascimento, sexo, racacor, cpf, cns, telefone,
        endereco, situacaoRua, usfReferencia, capsReferencia };
      return this.http.put<CadastroLocal>(`${this.apiUrl}/${idPublico}/vinculos/${local.vinculo}/cadastro`,
        cadastro, { params: { versaoCadastro: local.versaoCadastro } });
    }
    return this.http.put<PacientePayload>(
      `${this.apiUrl}/${idPublico}`,
      paciente,
    );
  }

  buscarPorId(idPublico: string): Observable<PacientePayload> {
    if (contratoExperimental) return this.buscarDetalhe(idPublico).pipe(map(d => d.paciente));
    return this.http.get<PacientePayload>(
      `${this.apiUrl}/${idPublico}`,
    );
  }

  buscarHistorico(
    idPublico: string,
  ): Observable<HistoricoPacientePayload> {
    if (contratoExperimental) return this.http.get<ProntuarioLocal>(`${this.apiUrl}/${idPublico}/prontuario`).pipe(map(p => ({
      idPublico, nomePaciente: p.paciente.nome, statusPaciente: p.paciente.indicadores.statusPaciente ?? 'Não confirmado',
      classificacaoAtual: p.paciente.indicadores.classificacaoRisco,
      tipoAcompanhamento: p.paciente.indicadores.tipoAcompanhamento ?? 'Não confirmado',
      dataUltimaPresenca: p.paciente.indicadores.ultimaPresencaConclusiva
        ? p.paciente.indicadores.dataUltimaPresenca ?? undefined : undefined,
      quantidadeFaltasAtual: p.paciente.indicadores.countFaltas,
      totalConsultasAgendadas: p.atendimentos.length, totalPresencas: p.presencasComprovadas, totalFaltas: p.faltasComprovadas,
      totalRemarcacoes: p.atendimentos.filter(a => a.situacaoAtendimento === 'REMARCADO_ORIGEM').length,
      totalGruposTerapeuticos: p.sessoes.length,
      totalRegistrosBuscaAtiva: p.historico.filter(h => h.tipo === 'BUSCA_ATIVA').length,
      historicoLocalRequerConferencia: p.historicoLocalRequerConferencia,
      sessoesLocais: p.sessoes,
      eventos: p.historico.map(evento => ({ ...evento, titulo: evento.tipo.replaceAll('_', ' '),
        situacaoAtendimento: evento.situacao ?? undefined, nomeUnidade: evento.unidadeOrigem })),
    })));
    return this.http.get<HistoricoPacientePayload>(
      `${this.apiUrl}/${idPublico}/historico`,
    );
  }

  inativarPaciente(idPublico: string, local?: VersoesPacienteLocal): Observable<void> {
    if (contratoExperimental) return this.comandoLocal(idPublico, local, (url, params) => this.http.delete<void>(url, {params}));
    return this.http.delete<void>(
      `${this.apiUrl}/${idPublico}`,
    );
  }

  private selecionarLocal(q: string): Observable<PacienteSelecao[]> {
    return this.http.get<PesquisaLocal>(`${this.apiUrl}/pesquisa`, {params: {q, size: 50}})
      .pipe(map(pagina => pagina.pacientes.map(linhaLocal)));
  }

  private comandoLocal(id: string, local: VersoesPacienteLocal | undefined,
      acao: (url: string, params: {version: number}) => Observable<void>): Observable<void> {
    if (!local) return this.semVersao();
    return acao(`${this.apiUrl}/${id}/vinculos/${local.vinculo}`, {version: local.versao});
  }

  private semVersao(): Observable<never> {
    return throwError(() => new HttpErrorResponse({status: 409,
      error: {message: 'Recarregue o cadastro antes de salvar.'}}));
  }
}