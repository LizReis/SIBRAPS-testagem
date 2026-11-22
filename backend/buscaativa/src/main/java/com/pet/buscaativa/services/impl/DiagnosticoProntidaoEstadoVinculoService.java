package com.pet.buscaativa.services.impl;

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

    public enum Situacao {
        REVISAR_PROVENIENCIA_HISTORICA,
        RECONCILIAR_ESTADO_LOCAL,
        REVISAR_DIVERGENCIA_DE_FALTAS,
        COERENTE_APENAS_PARA_TESTES
    }

    public record Relatorio(
            Situacao situacao,
            boolean temFatosSemUnidadeComprovada,
            boolean faltaTipoAcompanhamentoLocal,
            boolean faltaStatusLocal,
            boolean faltaIndicadorDePendenciaLocal,
            boolean faltaContagemLocal,
            boolean contagemLocalDivergenteDaSequencia,
            int eventosLocaisComprovados,
            long agendamentosHistoricosSemOrigem,
            long sessoesHistoricasSemOrigem
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
        return avaliar(vinculo, diagnostico);
    }

    /** Funcao pura, sem consultar o cadastro clinico global do Paciente. */
    public static Relatorio avaliar(
            VinculoAssistencial vinculo,
            DiagnosticoFaltasConsecutivasVinculoService.Resultado diagnostico) {
        Objects.requireNonNull(vinculo, "Vinculo explicito obrigatorio.");
        Objects.requireNonNull(diagnostico, "Diagnostico local obrigatorio.");

        boolean origemIncerta = diagnostico.revisaoHistoricaNecessaria()
                || diagnostico.faltasConsecutivasAnalisaveis().isEmpty();
        boolean tipoAusente = vinculo.getTipoAcompanhamento() == null;
        boolean statusAusente = vinculo.getStatusPaciente() == null;
        boolean pendenciaAusente = vinculo.getIndicadoresPendentes() == null;
        boolean contadorAusente = vinculo.getCountFaltas() == null;
        boolean divergencia = !origemIncerta && vinculo.getCountFaltas() != null
                && vinculo.getCountFaltas() != diagnostico.faltasConsecutivasAnalisaveis().getAsInt();

        Situacao situacao = origemIncerta ? Situacao.REVISAR_PROVENIENCIA_HISTORICA
                : (tipoAusente || statusAusente || pendenciaAusente || contadorAusente)
                    ? Situacao.RECONCILIAR_ESTADO_LOCAL
                : divergencia ? Situacao.REVISAR_DIVERGENCIA_DE_FALTAS
                : Situacao.COERENTE_APENAS_PARA_TESTES;

        return new Relatorio(
                situacao, origemIncerta, tipoAusente, statusAusente,
                pendenciaAusente, contadorAusente, divergencia, diagnostico.eventosLocaisComprovados(),
                diagnostico.agendamentosSemOrigemComFrequencia(),
                diagnostico.presencasGrupoSemOrigem());
    }
}
