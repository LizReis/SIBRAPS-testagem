import { HttpClient, HttpParams } from "@angular/common/http";
import { Injectable } from "@angular/core";
import { Observable } from "rxjs";

export type RecorrenciaGrupo = "UNICA" | "SEMANAL" | "QUINZENAL" | "MENSAL";

export type StatusSessaoGrupo = "AGENDADA" | "REALIZADA" | "CANCELADA";
export type StatusPresencaGrupo = "NAO_REGISTRADA" | "PRESENTE" | "FALTOU";
export type StatusExibicaoSessaoGrupo =
  | "AGENDADO"
  | "EM_ANDAMENTO"
  | "REALIZADO"
  | "CANCELADO";

export interface ParticipanteSessaoPayload {
  pacienteId: string;
  nomePaciente: string;
  statusPresenca: StatusPresencaGrupo;
}

export interface GrupoTerapeuticoPayload {
  id: number;
  tema: string;
  coordenadorId: string;
  nomeCoordenador: string;
  recorrencia: RecorrenciaGrupo;
  horarioPadrao: string;
  dataFimRecorrencia: string | null;
  ativo: boolean;
  dataPrimeiraSessao: string;
  version: number;
  iniciado: boolean;
}

export interface SessaoGrupoPayload {
  id: number;
  grupoId: number;
  temaGrupo: string;
  nomeCoordenador: string;
  dataSessao: string;
  horario: string;
  status: StatusSessaoGrupo;
  statusExibicao: StatusExibicaoSessaoGrupo;
  motivoCancelamento: string | null;
  participantes: ParticipanteSessaoPayload[];
  quantidadeParticipantes: number;
  quantidadePresencasConfirmadas: number;
  version: number;
}

export interface ConfirmarOcorrenciaPayload {
  ocorreu: boolean;
  frequencias: { pacienteId: string; statusPresenca: StatusPresencaGrupo }[];
  motivoCancelamento: string | null;
  version: number;
}

export interface SessaoInscricaoRetroativaPayload {
  sessaoId: number;
  data: string;
  horario: string;
  status: StatusSessaoGrupo;
  statusExibicao: StatusExibicaoSessaoGrupo;
  necessitaFrequencia: boolean;
}

export interface InscricaoRetroativaPayload {
  pacienteId: string;
  frequenciasPassadas: { sessaoId: number; statusPresenca: StatusPresencaGrupo }[];
}

export interface AtualizarGrupoPayload {
  tema: string;
  coordenadorId: string;
  dataPrimeiraSessao: string;
  horario: string;
  recorrencia: RecorrenciaGrupo;
  dataFimRecorrencia: string | null;
  version: number;
}

export interface ParticipanteGrupoPayload {
  pacienteId: string;
  nomePaciente: string;
  inscritoDesde: string;
  quantidadeSessoesRegistradas: number;
  quantidadePresencas: number;
  quantidadeFaltas: number;
  percentualPresenca: number | null;
  possuiInscricaoFutura: boolean;
}

export interface CriarGrupoPayload {
  tema: string;
  coordenadorId: string;
  recorrencia: RecorrenciaGrupo;
  dataPrimeiraSessao: string;
  dataFimRecorrencia: string | null;
  horario: string;
  participantesIds: string[];
}

export interface NovaSessaoPayload {
  grupoId: number;
  dataSessao: string;
  horario?: string | null;
  participantesIds?: string[];
}

@Injectable({
  providedIn: "root",
})
export class GrupoTerapeuticoService {
  private readonly apiUrl = "/api/grupos";

  constructor(private readonly http: HttpClient) {}

  criar(payload: CriarGrupoPayload): Observable<GrupoTerapeuticoPayload> {
    return this.http.post<GrupoTerapeuticoPayload>(this.apiUrl, payload);
  }

  listarGrupos(): Observable<GrupoTerapeuticoPayload[]> {
    return this.http.get<GrupoTerapeuticoPayload[]>(this.apiUrl);
  }

  buscarGrupo(grupoId: number): Observable<GrupoTerapeuticoPayload> {
    return this.http.get<GrupoTerapeuticoPayload>(`${this.apiUrl}/${grupoId}`);
  }

  atualizarGrupo(grupoId: number, payload: AtualizarGrupoPayload): Observable<GrupoTerapeuticoPayload> {
    return this.http.put<GrupoTerapeuticoPayload>(`${this.apiUrl}/${grupoId}`, payload);
  }

  listarParticipantesDoGrupo(grupoId: number): Observable<ParticipanteGrupoPayload[]> {
    return this.http.get<ParticipanteGrupoPayload[]>(`${this.apiUrl}/${grupoId}/participantes`);
  }

  inscreverEmSessoesFuturas(grupoId: number, pacienteId: string): Observable<SessaoGrupoPayload> {
    return this.http.post<SessaoGrupoPayload>(`${this.apiUrl}/${grupoId}/inscricoes-futuras`, { pacienteId });
  }

  removerParticipanteDoGrupo(grupoId: number, pacienteId: string): Observable<void> {
    return this.http.delete<void>(`${this.apiUrl}/${grupoId}/participantes/${pacienteId}`);
  }

  corrigirFrequencias(
    sessaoId: number,
    frequencias: { pacienteId: string; statusPresenca: StatusPresencaGrupo }[],
    version: number,
  ): Observable<SessaoGrupoPayload> {
    return this.http.patch<SessaoGrupoPayload>(`${this.apiUrl}/sessoes/${sessaoId}/frequencias`, { frequencias, version });
  }

  sugerirProximaData(grupoId: number): Observable<string> {
    return this.http.get(`${this.apiUrl}/${grupoId}/proxima-data-sugerida`, {
      responseType: "text",
    });
  }

  criarProximaSessao(payload: NovaSessaoPayload): Observable<SessaoGrupoPayload> {
    return this.http.post<SessaoGrupoPayload>(`${this.apiUrl}/sessoes`, payload);
  }

  listarSessoes(
    dataInicio: string,
    dataFim: string,
  ): Observable<SessaoGrupoPayload[]> {
    const params = new HttpParams()
      .set("dataInicio", dataInicio)
      .set("dataFim", dataFim);

    return this.http.get<SessaoGrupoPayload[]>(`${this.apiUrl}/sessoes`, {
      params,
    });
  }

  confirmarOcorrencia(sessaoId: number, payload: ConfirmarOcorrenciaPayload): Observable<SessaoGrupoPayload> {
    return this.http.post<SessaoGrupoPayload>(`${this.apiUrl}/sessoes/${sessaoId}/confirmacao-ocorrencia`, payload);
  }

  listarSessoesParaInscricaoRetroativa(grupoId: number): Observable<SessaoInscricaoRetroativaPayload[]> {
    return this.http.get<SessaoInscricaoRetroativaPayload[]>(`${this.apiUrl}/${grupoId}/sessoes-para-inscricao-retroativa`);
  }

  inscreverRetroativamente(grupoId: number, payload: InscricaoRetroativaPayload): Observable<SessaoGrupoPayload> {
    return this.http.post<SessaoGrupoPayload>(`${this.apiUrl}/${grupoId}/inscricoes-retroativas`, payload);
  }

  adicionarParticipante(
    sessaoId: number,
    pacienteId: string,
  ): Observable<SessaoGrupoPayload> {
    return this.http.post<SessaoGrupoPayload>(
      `${this.apiUrl}/sessoes/${sessaoId}/participantes`,
      { pacienteId },
    );
  }

  removerParticipante(
    sessaoId: number,
    pacienteId: string,
  ): Observable<SessaoGrupoPayload> {
    return this.http.delete<SessaoGrupoPayload>(
      `${this.apiUrl}/sessoes/${sessaoId}/participantes/${pacienteId}`,
    );
  }

  atualizarStatus(
    sessaoId: number,
    novoStatus: StatusSessaoGrupo,
    version?: number,
  ): Observable<SessaoGrupoPayload> {
    let params = new HttpParams().set("novoStatus", novoStatus);

    if (version !== undefined) {
      params = params.set("version", version);
    }

    return this.http.patch<SessaoGrupoPayload>(
      `${this.apiUrl}/sessoes/${sessaoId}/status`,
      null,
      { params },
    );
  }
}
