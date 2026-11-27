import { raizClinica } from './configuracao-contrato';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

export type ClassificacaoRisco = 'VERDE' | 'AMARELO' | 'VERMELHO';
export interface DistribuicaoPainel { naoConfirmados?: number; percentualNaoConfirmados?: number; verdes:number; amarelos:number; vermelhos:number; percentualVerdes:number; percentualAmarelos:number; percentualVermelhos:number }
export interface EvolucaoPainel { mes:string; rotulo:string; verdes:number|null; amarelos:number|null; vermelhos:number|null; disponivel:boolean; situacao?: 'SEM_COBERTURA' | 'INDISPONIVEL' | 'COMPROVADO' }
export interface PacientePainel { idPublico:string; vinculo?:string; nome:string; idade:number|null; classificacaoRisco:ClassificacaoRisco|null; quantidadeFaltas:number|null; acaoNecessaria:string }
export interface UnidadePainel { valor:string; nome:string }
export interface PainelPayload { estadoNaoConfirmado?: number; totalPacientesAtivos:number; distribuicaoClassificacao:DistribuicaoPainel; evolucao:EvolucaoPainel[]; pacientesPrioritarios:PacientePainel[]; unidadesDisponiveis:UnidadePainel[]; historicoDisponivel:boolean }
export interface FiltrosPainel { periodoMeses:number; unidade?:string; tipoAcompanhamento?:string; situacaoRua?:boolean }

@Injectable({ providedIn: 'root' })
export class PainelService {
  private readonly http = inject(HttpClient);
  buscarResumo(filtros: FiltrosPainel): Observable<PainelPayload> {
    let params = new HttpParams().set('periodoMeses', filtros.periodoMeses);
    if (filtros.unidade) params = params.set('unidade', filtros.unidade);
    if (filtros.tipoAcompanhamento) params = params.set('tipoAcompanhamento', filtros.tipoAcompanhamento);
    if (filtros.situacaoRua !== undefined) params = params.set('situacaoRua', filtros.situacaoRua);
    return this.http.get<PainelPayload>(`${raizClinica}/painel/resumo`, { params });
  }
}
