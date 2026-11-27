import { raizClinica } from './configuracao-contrato';
import { HttpClient, HttpParams, HttpResponse } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { EstadoRevisaoLocal, PaginaPendenciasLocal, PendenciaLocal } from './contrato-clinico-local';

export interface PacienteRelatorioLocal {
  nomeCompleto: string; cnsCpf: string; dataUltimoAtendimentoFormatada: string;
  faltasConsecutivas: number | null; tipoAcompanhamento: string; profissionalReferencia: string; telefone: string;
}
export interface SessaoRelatorioLocal {
  dataFormatada: string; grupo: string; coordenador: string; taxaPresenca: number | null;
  nomesPresentes: string[]; nomesAusentes: string[];
}

export type TipoAcompanhamentoRelatorio = 'INDIVIDUAL' | 'GRUPO_TERAPEUTICO';

export interface FiltroPeriodo {
  dataInicio?: string;
  dataFim?: string;
}

export interface FiltrosBuscaAtiva extends FiltroPeriodo {
  profissionalId?: string;
  tipoAcompanhamento?: TipoAcompanhamentoRelatorio;
}

export interface FiltrosFrequenciaGrupos extends FiltroPeriodo {
  grupoId?: number;
}

@Injectable({ providedIn: 'root' })
export class RelatorioService {
  private readonly apiUrl = `${raizClinica}/relatorios`;

  constructor(private readonly http: HttpClient) {}

  baixarBuscaAtivaPdf(filtros: FiltrosBuscaAtiva): Observable<HttpResponse<Blob>> {
    return this.baixarPdf(`${this.apiUrl}/busca-ativa/pdf`, filtros);
  }

  baixarFrequenciaGruposPdf(filtros: FiltrosFrequenciaGrupos): Observable<HttpResponse<Blob>> {
    return this.baixarPdf(`${this.apiUrl}/frequencia-grupos/pdf`, filtros);
  }

  baixarBuscaAtivaExcel(filtros: FiltrosBuscaAtiva): Observable<HttpResponse<Blob>> {
    return this.baixarArquivo(`${this.apiUrl}/busca-ativa/excel`, filtros);
  }

  baixarFrequenciaGruposExcel(filtros: FiltrosFrequenciaGrupos): Observable<HttpResponse<Blob>> {
    return this.baixarArquivo(`${this.apiUrl}/frequencia-grupos/excel`, filtros);
  }

  consultarBusca(filtros: FiltrosBuscaAtiva) {
    return this.http.get<{dados: PacienteRelatorioLocal[]; revisaoOuSelecaoInconclusiva: number; periodo?: string}>(
      `${this.apiUrl}/busca-ativa`, {params: this.parametros(filtros)});
  }

  consultarGrupos(filtros: FiltrosFrequenciaGrupos) {
    return this.http.get<{dados: SessaoRelatorioLocal[]; sessoesPendentes: number; media: number | null; participacoesDesconsideradas?: number; periodo?: string}>(
      `${this.apiUrl}/frequencia-grupos`, {params: this.parametros(filtros)});
  }

  pendencias(pagina: number, estado?: EstadoRevisaoLocal): Observable<PaginaPendenciasLocal> {
    return this.http.get<PaginaPendenciasLocal>(`${this.apiUrl}/pendencias`,
      {params: this.parametros({pagina, tamanho: 10, estado})});
  }

  decidir(p: PendenciaLocal, decisao: 'APROVAR' | 'REJEITAR', justificativa: string) {
    return this.http.post<string>(
      `${raizClinica}/pacientes/${p.paciente}/vinculos/${p.vinculo}/revisoes/${p.ticket}/decisao`,
      {decisao, justificativa}, {params: {version: p.versaoVinculo}});
  }

  private baixarPdf(url: string, filtros: object): Observable<HttpResponse<Blob>> {
    return this.baixarArquivo(url, filtros);
  }

  private baixarArquivo(url: string, filtros: object): Observable<HttpResponse<Blob>> {
    return this.http.get(url, { params: this.parametros(filtros), responseType: 'blob', observe: 'response' });
  }

  private parametros(filtros: object): HttpParams {
    let params = new HttpParams();
    Object.entries(filtros as Record<string, string | number | undefined>).forEach(([chave, valor]) => {
      if (valor !== undefined) params = params.set(chave, valor.toString());
    });
    return params;
  }
}
