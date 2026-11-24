package com.pet.buscaativa.services.impl;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pet.buscaativa.entities.VinculoAssistencial;

import lombok.RequiredArgsConstructor;

/**
 * Diagnostico SOMENTE LEITURA de prontidao para a futura migracao do estado
 * clinico por unidade. Nao move estados de Paciente para VinculoAssistencial,
 * nao reconcilia registros historicos e nao altera permissoes ou indicadores.
 *
 * IMPORTANTE: status ATIVO preenchido pela fabrica de vinculo novo nao e prova
 * de status historico. Mesmo uma avaliacao "COERENTE_PARA_TESTES" nunca
 * equivale a autorizacao para ativar regras clinicas ou endpoints.
 */
@Service
@RequiredArgsConstructor
public class DiagnosticoProntidaoEstadoVinculoService {
    private final AutorizacaoVinculoAssistencialService autorizacao;
    private final DiagnosticoFaltasConsecutivasVinculoService faltas;
    private final EvidenciaStatusVinculo evidenciaStatus;

    public enum Situacao {
        REVISAR_PROVENIENCIA_HISTORICA,
        REVISAR_INCONSISTENCIA_DE_FREQUENCIA,
        REVISAR_ORDEM_DE_FREQUENCIAS,
        RECONCILIAR_ESTADO_LOCAL,
        REVISAR_INDICADORES_LOCAIS_PENDENTES,
        REVISAR_DIVERGENCIA_DE_FALTAS,
        REVISAR_DIVERGENCIA_DE_ULTIMA_PRESENCA,
        COERENTE_APENAS_PARA_TESTES
    }

    public record Relatorio(
            Situacao situacao,
            boolean temFatosSemUnidadeComprovada,
            boolean faltaTipoAcompanhamentoLocal,
            boolean faltaStatusLocal,
            boolean statusLocalComprovado,
            boolean faltaIndicadorDePendenciaLocal,
            boolean faltaContagemLocal,
            boolean faltaRiscoLocal,
            boolean faltaGatilhoVisitaLocal,
            boolean contagemLocalDivergenteDaSequencia,
            int eventosLocaisComprovados,
            long agendamentosHistoricosSemOrigem,
            long sessoesHistoricasSemOrigem,
            long frequenciasInconsistentes,
            boolean ultimaPresencaLocalDivergente
    ) {
        /** Nunca usar como indicador de elegibilidade para migracao automatica. */
        public boolean requerRevisao() {
            return situacao != Situacao.COERENTE_APENAS_PARA_TESTES;
        }
    }

    @Transactional(readOnly = true)
    public Relatorio analisar(UUID pacienteId) {
        // O identificador clinico da unidade e obtido somente do contexto
        // autenticado, por meio de um vinculo explicito.
        VinculoAssistencial vinculo = autorizacao.exigirVinculoLocal(pacienteId);
        var diagnostico = faltas.analisar(pacienteId);
        return avaliar(vinculo, diagnostico, evidenciaStatus.analisar(vinculo).comprovada());
    }

    /** Função pura sobre os campos locais e a comprovação obtida pelo chamador. */
    public static Relatorio avaliar(VinculoAssistencial vinculo,
            DiagnosticoFaltasConsecutivasVinculoService.Resultado diagnostico, boolean statusComprovado) {
        Objects.requireNonNull(vinculo, "Vinculo explicito obrigatorio.");
        Objects.requireNonNull(diagnostico, "Diagnostico local obrigatorio.");

        boolean sequenciaBloqueada = !diagnostico.podeAvaliarSequencia();
        boolean origemIncerta = diagnostico.agendamentosSemOrigemComFrequencia() > 0
                || diagnostico.presencasGrupoSemOrigem() > 0;
        boolean tipoAusente = vinculo.getTipoAcompanhamento() == null;
        boolean statusAusente = vinculo.getStatusPaciente() == null;
        boolean pendenciaAusente = vinculo.getIndicadoresPendentes() == null;
        boolean contadorAusente = vinculo.getCountFaltas() == null;
        boolean riscoAusente = vinculo.getClassificacaoRisco() == null;
        boolean gatilhoAusente = vinculo.getGatilhoVisitaAcionado() == null;
        boolean divergencia = !sequenciaBloqueada && vinculo.getCountFaltas() != null
                && vinculo.getCountFaltas() != diagnostico.faltasConsecutivasAnalisaveis().getAsInt();

        boolean presencaDivergente = !sequenciaBloqueada
                && !Objects.equals(vinculo.getDataUltimaPresenca(),
                        diagnostico.ultimaPresencaComprovada().map(LocalDateTime::toLocalDate).orElse(null));

        boolean estadoIncompleto = tipoAusente || statusAusente || !statusComprovado || pendenciaAusente
                || contadorAusente || riscoAusente || gatilhoAusente;
        Situacao situacao = classificar(origemIncerta, sequenciaBloqueada, diagnostico.empateComFrequenciasDiferentes(),
                estadoIncompleto,
                Boolean.TRUE.equals(vinculo.getIndicadoresPendentes()), divergencia, presencaDivergente);

        return new Relatorio(
                situacao, origemIncerta, tipoAusente, statusAusente, statusComprovado,
                pendenciaAusente, contadorAusente, riscoAusente, gatilhoAusente, divergencia, diagnostico.eventosLocaisComprovados(),
                diagnostico.agendamentosSemOrigemComFrequencia(),
                diagnostico.presencasGrupoSemOrigem(), diagnostico.frequenciasInconsistentes(),
                presencaDivergente);
    }

    private static Situacao classificar(boolean origemIncerta, boolean sequenciaBloqueada,
            boolean ordemInconclusiva, boolean estadoIncompleto, boolean indicadoresPendentes, boolean faltasDivergentes,
            boolean presencaDivergente) {
        if (origemIncerta) return Situacao.REVISAR_PROVENIENCIA_HISTORICA;
        if (ordemInconclusiva) return Situacao.REVISAR_ORDEM_DE_FREQUENCIAS;
        if (sequenciaBloqueada) return Situacao.REVISAR_INCONSISTENCIA_DE_FREQUENCIA;
        if (estadoIncompleto) return Situacao.RECONCILIAR_ESTADO_LOCAL;
        if (indicadoresPendentes) return Situacao.REVISAR_INDICADORES_LOCAIS_PENDENTES;
        if (faltasDivergentes) return Situacao.REVISAR_DIVERGENCIA_DE_FALTAS;
        if (presencaDivergente) return Situacao.REVISAR_DIVERGENCIA_DE_ULTIMA_PRESENCA;
        return Situacao.COERENTE_APENAS_PARA_TESTES;
    }
}
