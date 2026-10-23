import { HttpClient } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';

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
  tipoAcompanhamento: string;

  countFaltas?: number;
  statusPaciente?: string;

  usfReferencia: UsfReferencia;

  capsReferencia: string;
  profissionalReferenciaId?: string | null;
  profissionalReferencia?: UsuarioReferencia | null;
}

export interface UsuarioReferencia {
  idPublico: string;
  nome: string;
  unidadeAtuacao?: string;
}

export interface PacienteLista extends Pick<PacientePayload, 'idPublico'|'nome'|'cpf'|'cns'|'countFaltas'|'dataUltimaPresenca'|'statusPaciente'|'tipoAcompanhamento'> { unidade?: string; classificacaoRisco: string; }
export interface PacientePesquisa { resumo: { pacientesAtivos:number; emAtencao:number; buscaAtiva:number; semPresencaRecente:number }; pacientes:PacienteLista[]; paginaAtual:number; tamanhoPagina:number; totalRegistros:number; totalPaginas:number; }
export interface ProximoAgendamento { id:number; data:string; hora:string; situacao:string }
export interface PacienteDetalhe { paciente:PacientePayload; classificacaoRisco:string; proximoAgendamento?:ProximoAgendamento; atencaoNecessaria:string }
export interface AgendamentoPaciente { id:number; dataAgendamento:string; horaAtendimento:string; nomeProfissional:string; tipoAcompanhamento:string; situacaoAtendimento:string }

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
  classificacaoAtual: string;
  tipoAcompanhamento: string;
  dataUltimaPresenca?: string;
  diasSemComparecer?: number;
  quantidadeFaltasAtual: number;
  totalConsultasAgendadas: number;
  totalPresencas: number;
  totalFaltas: number;
  totalRemarcacoes: number;
  totalGruposTerapeuticos: number;
  totalRegistrosBuscaAtiva: number;
  eventos: HistoricoPacienteEvento[];
}

@Injectable({
  providedIn: 'root',
})
export class PacienteService {
  private readonly apiUrl = '/api/pacientes';

  constructor(private readonly http: HttpClient) {}

  listar(): Observable<PacientePayload[]> {
    return this.http.get<PacientePayload[]>(this.apiUrl);
  }

  pesquisar(params: Record<string,string|number|undefined>): Observable<PacientePesquisa> {
    const limpos=Object.fromEntries(Object.entries(params).filter(([,v])=>v!==undefined && v!==''));
    return this.http.get<PacientePesquisa>(`${this.apiUrl}/pesquisa`, {params: limpos as any});
  }
  buscarDetalhe(id:string):Observable<PacienteDetalhe>{ return this.http.get<PacienteDetalhe>(`${this.apiUrl}/${id}/detalhe`); }
  listarAgendamentos(id:string):Observable<AgendamentoPaciente[]>{ return this.http.get<AgendamentoPaciente[]>(`${this.apiUrl}/${id}/agendamentos`); }
  encerrar(id:string, motivo:string, descricao?:string):Observable<void>{
    const status:Record<string,string>={ALTA_TERAPEUTICA:'ALTA_TERAPEUTICA',TRANSFERENCIA_OUTRA_UNIDADE:'TRANSFERIDO',ABANDONO_TRATAMENTO:'ABANDONO_TRATAMENTO',OBITO:'OBITO',OUTRO:'OUTRO'};
    return this.http.patch<void>(`${this.apiUrl}/${id}/encerrar`,{motivo,statusPaciente:status[motivo],descricao});
  }
  registrarBuscaAtiva(id:string, descricao:string):Observable<void>{ return this.http.post<void>(`${this.apiUrl}/${id}/historico`,{tipo:'BUSCA_ATIVA',descricao}); }

  buscarPorNome(nome: string): Observable<PacientePayload[]> {
    return this.http.get<PacientePayload[]>(
      `${this.apiUrl}/busca/nome?q=${encodeURIComponent(nome)}`,
    );
  }

  buscarPorCpf(cpf: string): Observable<PacientePayload> {
    return this.http.get<PacientePayload>(
      `${this.apiUrl}/busca/cpf/${cpf}`,
    );
  }

  buscarPorCns(cns: string): Observable<PacientePayload> {
    return this.http.get<PacientePayload>(
      `${this.apiUrl}/busca/cns/${cns}`,
    );
  }

  cadastrarPaciente(
    paciente: PacientePayload,
  ): Observable<PacientePayload> {
    return this.http.post<PacientePayload>(
      this.apiUrl,
      paciente,
    );
  }

  atualizarPaciente(
    idPublico: string,
    paciente: PacientePayload,
  ): Observable<PacientePayload> {
    return this.http.put<PacientePayload>(
      `${this.apiUrl}/${idPublico}`,
      paciente,
    );
  }

  buscarPorId(idPublico: string): Observable<PacientePayload> {
    return this.http.get<PacientePayload>(
      `${this.apiUrl}/${idPublico}`,
    );
  }

  buscarHistorico(
    idPublico: string,
  ): Observable<HistoricoPacientePayload> {
    return this.http.get<HistoricoPacientePayload>(
      `${this.apiUrl}/${idPublico}/historico`,
    );
  }

  inativarPaciente(idPublico: string): Observable<void> {
    return this.http.delete<void>(
      `${this.apiUrl}/${idPublico}`,
    );
  }
}