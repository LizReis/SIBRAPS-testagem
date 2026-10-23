import { HttpClient } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';

export interface PacientePrioritarioPayload {
  idPublico: string;
  nome: string;
  classificacaoRisco: 'VERDE' | 'AMARELO' | 'VERMELHO';
  quantidadeFaltas: number;
  dataUltimaPresenca?: string;
}

export interface ResumoInicioPayload {
  totalPacientesAtivos: number;
  totalAgendamentosHoje: number;
  totalFaltasUltimosSeteDias: number;
  totalPacientesAtencao: number;
  totalNotificacoes: number;
  pacientesPrioritarios: PacientePrioritarioPayload[];
}

@Injectable({ providedIn: 'root' })
export class InicioService {
  private readonly apiUrl = '/api/inicio/resumo';

  constructor(private readonly http: HttpClient) {}

  buscarResumo(): Observable<ResumoInicioPayload> {
    return this.http.get<ResumoInicioPayload>(this.apiUrl);
  }
}