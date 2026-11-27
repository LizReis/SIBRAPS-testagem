import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { raizClinica } from './configuracao-contrato';
import { ProntuarioLocal } from './contrato-clinico-local';

export type TipoRegistroRevisao = 'AGENDAMENTO' | 'HISTORICO' | 'SESSAO_GRUPO';
export interface CandidatoOrigem {
  historico: number; fonte: TipoRegistroRevisao; registroFonte: number;
  ocorridoEm: string; frequencia: 'PRESENTE' | 'FALTOU';
}
export interface CandidatosOrigem {
  versaoVinculo: number;
  candidatos: CandidatoOrigem[];
}

@Injectable({ providedIn: 'root' })
export class RevisaoClinicaService {
  private readonly http = inject(HttpClient);
  prontuario(paciente: string) {
    return this.http.get<ProntuarioLocal>(`${raizClinica}/pacientes/${encodeURIComponent(paciente)}/prontuario`);
  }
  private raiz(paciente: string, vinculo: string): string {
    return `${raizClinica}/pacientes/${encodeURIComponent(paciente)}/vinculos/${encodeURIComponent(vinculo)}/revisoes`;
  }
  candidatosOrigem(paciente: string, vinculo: string) {
    return this.http.get<CandidatosOrigem>(`${this.raiz(paciente,vinculo)}/candidatos-origem`);
  }
  solicitar(paciente: string, vinculo: string, version: number, tipo: TipoRegistroRevisao, registro: number, justificativa: string) {
    return this.http.post<string>(this.raiz(paciente,vinculo), { tipo, registro, justificativa }, { params: { version } });
  }
}
