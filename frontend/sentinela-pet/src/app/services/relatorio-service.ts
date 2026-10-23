import { HttpClient, HttpParams, HttpResponse } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';

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
  private readonly apiUrl = '/api/relatorios';

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

  private baixarPdf(url: string, filtros: object): Observable<HttpResponse<Blob>> {
    return this.baixarArquivo(url, filtros);
  }

  private baixarArquivo(url: string, filtros: object): Observable<HttpResponse<Blob>> {
    let params = new HttpParams();
    Object.entries(filtros as Record<string, string | number | undefined>).forEach(([chave, valor]) => {
      if (valor !== undefined) params = params.set(chave, valor.toString());
    });
    return this.http.get(url, { params, responseType: 'blob', observe: 'response' });
  }
}
