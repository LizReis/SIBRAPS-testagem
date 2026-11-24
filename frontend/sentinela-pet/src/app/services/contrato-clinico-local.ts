/** Contratos do ensaio local 5A/5B. Não conectados às rotas HTTP legadas. */
import type { ClassificacaoRisco, UnidadePainel } from './painel-service';

export type StatusLocal = 'ATIVO' | 'INATIVO' | 'ALTA_TERAPEUTICA' | 'TRANSFERIDO'
  | 'ABANDONO_TRATAMENTO' | 'OBITO' | 'OUTRO';
export type TipoLocal = 'INDIVIDUAL' | 'GRUPO_TERAPEUTICO' | 'AMBOS';
export interface IndicadoresLocais {
  statusPaciente: StatusLocal | null;
  tipoAcompanhamento: TipoLocal | null;
  countFaltas: number | null;
  dataUltimaPresenca: string | null;
  ultimaPresencaConclusiva: boolean;
  classificacaoRisco: ClassificacaoRisco | null;
  gatilhoVisitaAcionado: boolean | null;
  indicadoresPendentes: boolean | null;
  revisaoNecessaria: boolean;
  motivos: string[];
}
export interface IdentificacaoLocal {
  idPublico: string;
  vinculo: string;
  versao: number;
  nome: string;
}
export interface PacienteLocal extends IdentificacaoLocal {
  unidade: string;
  cpf: string | null;
  cns: string | null;
  indicadores: IndicadoresLocais;
}
export type AcaoBuscaLocal = 'REVISAO_HISTORICA' | 'VISITA_DOMICILIAR' | 'PONTO_REFERENCIA'
  | 'CONFERIR_REFERENCIA' | 'ACOMPANHAMENTO_PRIORITARIO';
export interface AlertaLocal extends IdentificacaoLocal {
  classificacaoRisco: ClassificacaoRisco | null;
  faltasConsecutivas: number | null;
  gatilhoVisitaAcionado: boolean | null;
  acao: AcaoBuscaLocal;
  alerta: string;
  localBusca: string | null;
  avisoAdicional: string | null;
}
export interface BuscaLocal {
  alertas: AlertaLocal[];
  estadoNaoConfirmado: number;
  selecaoIndeterminada: number;
}
export interface DistribuicaoLocal {
  verdes: number; amarelos: number; vermelhos: number; naoConfirmados: number;
  percentualVerdes: number; percentualAmarelos: number; percentualVermelhos: number;
  percentualNaoConfirmados: number;
}
export interface EvolucaoLocal {
  mes: string; rotulo: string; verdes: number | null; amarelos: number | null;
  vermelhos: number | null; disponivel: boolean;
}
export interface PrioritarioLocal extends IdentificacaoLocal {
  idade: number | null; classificacaoRisco: ClassificacaoRisco | null;
  quantidadeFaltas: number | null; acaoNecessaria: string;
}
export interface PainelLocal {
  totalPacientesAtivos: number;
  estadoNaoConfirmado: number;
  distribuicaoClassificacao: DistribuicaoLocal;
  evolucao: EvolucaoLocal[];
  pacientesPrioritarios: PrioritarioLocal[];
  unidadesDisponiveis: UnidadePainel[];
  historicoDisponivel: boolean;
  atividade: {
    reservasIndividuaisComprovadas: number; sessoesDistintasComprovadas: number;
    presencasComprovadas: number; faltasComprovadas: number; vinculosComRevisao: number;
    semPresencaRecenteConfirmada: number; semPresencaRecenteIndeterminada: number;
  };
}

export function rotuloRiscoLocal(risco: ClassificacaoRisco | null | undefined): string {
  switch (risco) {
    case 'VERDE': return 'Regular';
    case 'AMARELO': return 'Atenção';
    case 'VERMELHO': return 'Busca ativa';
    default: return 'Não confirmado';
  }
}
export function rotuloFaltasLocal(faltas: number | null | undefined): string {
  return faltas == null ? 'Não confirmado' : String(faltas);
}
export function rotuloUltimaPresencaLocal(i: Pick<IndicadoresLocais,'dataUltimaPresenca'|'ultimaPresencaConclusiva'>): string {
  if (!i.ultimaPresencaConclusiva) return 'Não confirmada';
  return i.dataUltimaPresenca ?? 'Nenhuma presença comprovada';
}
export function segmentosLocais(d: DistribuicaoLocal) {
  return [
    { titulo: 'Regular', classe: 'green', quantidade: d.verdes, percentual: d.percentualVerdes },
    { titulo: 'Atenção', classe: 'yellow', quantidade: d.amarelos, percentual: d.percentualAmarelos },
    { titulo: 'Busca ativa', classe: 'red', quantidade: d.vermelhos, percentual: d.percentualVermelhos },
    { titulo: 'Não confirmado', classe: 'unknown', quantidade: d.naoConfirmados, percentual: d.percentualNaoConfirmados },
  ];
}
/** Null interrompe a série; nunca converter ausência histórica em zero. */
export function valoresEvolucaoLocal(e: EvolucaoLocal[], campo: 'verdes'|'amarelos'|'vermelhos'): (number|null)[] {
  return e.map(m => m.disponivel ? m[campo] : null);
}
