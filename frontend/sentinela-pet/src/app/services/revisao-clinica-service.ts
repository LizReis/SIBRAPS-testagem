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

export interface ReferenciaFrequencia {
  fonte: 'ATENDIMENTO_INDIVIDUAL' | 'SESSAO_GRUPO'; id: number; versao: number;
}
export interface FatoFrequencia { referencia: ReferenciaFrequencia; presente: boolean; podeDesconsiderar: boolean; }
export interface CandidatoFrequencia {
  ocorridoEm: string; tipo: 'FREQUENCIAS_CONTRADITORIAS' | 'FREQUENCIAS_COINCIDENTES'; fatos: FatoFrequencia[];
}
export interface CandidatosFrequencia {
  versaoVinculo: number; revisaoNecessaria: boolean; candidatos: CandidatoFrequencia[]; total: number; pagina: number; tamanho: number;
}
export interface RegistroResolucao {
  id: string; fonteValida: ReferenciaFrequencia['fonte']; fatoValido: number; versaoValida: number; frequenciaValida: string;
  fonteDesconsiderada: ReferenciaFrequencia['fonte']; fatoDesconsiderado: number; versaoDesconsiderada: number; frequenciaDesconsiderada: string;
  horario: string; ator: string; perfil: string; justificativa: string; registradoEm: string;
  ticket: string | null; decisao: string | null; reversao: string | null; atorReversao: string | null;
  perfilReversao: string | null; justificativaReversao: string | null; revertidoEm: string | null;
}
export interface AuditoriaFrequencia { versaoVinculo: number; registros: RegistroResolucao[]; total: number; pagina: number; tamanho: number; }
export interface EventoOrigem { id: string; ator: string; perfil: string; justificativa: string; registradoEm: string; }
export interface ReaberturaOrigem { anterior: string; nova: string; motivo: string; }
export interface AuditoriaOrigem {
  versaoVinculo: number; historico: number; solicitacao: EventoOrigem;
  decisao: { evento: EventoOrigem; resultado: string } | null;
  prova: { fonte: string; registroFonte: number; versaoFonte: number; participante: number | null; ocorridoEm: string; frequencia: string; tipoHistorico: string } | null;
  aplicacao: EventoOrigem | null; reversao: EventoOrigem | null; reaberturaAnterior: ReaberturaOrigem | null; reaberturaPosterior: ReaberturaOrigem | null;
  estadoAplicacao: string; integridadeEventos: boolean;
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
  private raizFrequencia(paciente: string, vinculo: string) {
    return `${raizClinica}/pacientes/${encodeURIComponent(paciente)}/vinculos/${encodeURIComponent(vinculo)}/resolucoes-frequencia`;
  }
  candidatosFrequencia(paciente: string, vinculo: string, pagina: number) {
    return this.http.get<CandidatosFrequencia>(this.raizFrequencia(paciente,vinculo)+'/candidatos',{params:{pagina,tamanho:10}});
  }
  auditoriaFrequencia(paciente: string, vinculo: string, pagina: number) {
    return this.http.get<AuditoriaFrequencia>(this.raizFrequencia(paciente,vinculo),{params:{pagina,tamanho:10}});
  }
  resolverFrequencia(paciente: string,vinculo: string,version: number,valido: ReferenciaFrequencia,desconsiderado: ReferenciaFrequencia,justificativa: string,ticket?: string) {
    return this.http.post<{id:string}>(this.raizFrequencia(paciente,vinculo),{valido,desconsiderado,justificativa,ticket:ticket || null},{params:{version}});
  }
  desfazerFrequencia(paciente: string,vinculo: string,version: number,resolucao: string,justificativa: string) {
    return this.http.post<{id:string}>(this.raizFrequencia(paciente,vinculo)+'/'+encodeURIComponent(resolucao)+'/desfazer',{justificativa},{params:{version}});
  }
  auditoriaOrigem(paciente: string,vinculo: string,ticket: string) {
    return this.http.get<AuditoriaOrigem>(this.raiz(paciente,vinculo)+'/'+encodeURIComponent(ticket)+'/origem/auditoria');
  }
  comandoOrigem(paciente: string,vinculo: string,version: number,acao: 'aplicar' | 'reabrir' | 'desfazer',id: string,justificativa: string) {
    const caminho=acao==='desfazer' ? '/origem/aplicacoes/'+encodeURIComponent(id)+'/desfazer' : '/'+encodeURIComponent(id)+'/origem/'+acao;
    return this.http.post<{id:string}>(this.raiz(paciente,vinculo)+caminho,{justificativa},{params:{version}});
  }
  solicitar(paciente: string, vinculo: string, version: number, tipo: TipoRegistroRevisao, registro: number, justificativa: string) {
    return this.http.post<string>(this.raiz(paciente,vinculo), { tipo, registro, justificativa }, { params: { version } });
  }
}
