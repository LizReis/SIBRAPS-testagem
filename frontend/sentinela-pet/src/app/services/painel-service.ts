import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

export type ClassificacaoRisco = 'VERDE' | 'AMARELO' | 'VERMELHO';
export interface DistribuicaoPainel { verdes:number; amarelos:number; vermelhos:number; percentualVerdes:number; percentualAmarelos:number; percentualVermelhos:number }
export interface EvolucaoPainel { mes:string; rotulo:string; verdes:number; amarelos:number; vermelhos:number; disponivel:boolean }
export interface PacientePainel { idPublico:string; nome:string; idade:number|null; classificacaoRisco:ClassificacaoRisco; quantidadeFaltas:number; acaoNecessaria:string }
export interface UnidadePainel { valor:string; nome:string }
export interface PainelPayload { totalPacientesAtivos:number; distribuicaoClassificacao:DistribuicaoPainel; evolucao:EvolucaoPainel[]; pacientesPrioritarios:PacientePainel[]; unidadesDisponiveis:UnidadePainel[]; historicoDisponivel:boolean }
export interface FiltrosPainel { periodoMeses:number; unidade?:string; tipoAcompanhamento?:string; situacaoRua?:boolean }

@Injectable({ providedIn: 'root' })
export class PainelService {
  private readonly http = inject(HttpClient);
  buscarResumo(filtros: FiltrosPainel): Observable<PainelPayload> {
    let params = new HttpParams().set('periodoMeses', filtros.periodoMeses);
    if (filtros.unidade) params = params.set('unidade', filtros.unidade);
    if (filtros.tipoAcompanhamento) params = params.set('tipoAcompanhamento', filtros.tipoAcompanhamento);
    if (filtros.situacaoRua !== undefined) params = params.set('situacaoRua', filtros.situacaoRua);
    return this.http.get<PainelPayload>('/api/painel/resumo', { params });
  }
}
