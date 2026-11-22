package com.pet.buscaativa.services.impl;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Optional;

import com.pet.buscaativa.entities.enums.ClassificacaoRisco;
import com.pet.buscaativa.entities.enums.StatusPaciente;
import com.pet.buscaativa.entities.enums.TipoAcompanhamento;
import com.pet.buscaativa.services.PoliticaClassificacaoPaciente;

/**
 * Comparador PURO da politica de risco legada em contexto de vinculo.
 *
 * Nao e servico assistencial, nao atualiza banco, nao gera alertas e nao
 * pode ser ligado a endpoints/painel antes da migracao coordenada.
 *
 * O tipo e status precisam VIR DO VINCULO LOCAL, jamais do cadastro global
 * Paciente. Contexto incompleto, pendente ou origem incerta nao permite
 * conclusao — nao se assume INDIVIDUAL, ATIVO nem ausencia de pendencia.
 */
public final class ComparadorPoliticaRiscoVinculo {
    private static final int FALTAS_AMARELO = 2;
    private static final int FALTAS_VERMELHO = 3;

    private ComparadorPoliticaRiscoVinculo() {}

    /**
     * Calcula classificacao teorica com os MESMOS limiares do algoritmo
     * atual, somente quando os dados sao suficientes e confirmados.
     */
    public static Optional<ClassificacaoRisco> comparar(
            DiagnosticoFaltasConsecutivasVinculoService.Resultado diagnostico,
            StatusPaciente statusDoVinculo,
            TipoAcompanhamento tipoDoVinculo,
            Boolean indicadoresPendentesDoVinculo,
            LocalDate hoje) {
        Objects.requireNonNull(diagnostico, "Diagnostico local obrigatorio.");
        Objects.requireNonNull(hoje, "Data de referencia obrigatoria.");

        if (statusDoVinculo == null || indicadoresPendentesDoVinculo == null
                || indicadoresPendentesDoVinculo || !diagnostico.podeAvaliarSequencia()
                || diagnostico.faltasConsecutivasAnalisaveis().isEmpty())
            return Optional.empty();

        // O legado trata INATIVO antes de avaliar qualquer outro criterio.
        if (statusDoVinculo == StatusPaciente.INATIVO) return Optional.of(ClassificacaoRisco.VERDE);

        // Nao inferir tipo local a partir do tipo do cadastro global.
        if (tipoDoVinculo == null) return Optional.empty();

        boolean grupo = tipoDoVinculo == TipoAcompanhamento.GRUPO_TERAPEUTICO
                || tipoDoVinculo == TipoAcompanhamento.AMBOS;
        int amareloDias = grupo ? PoliticaClassificacaoPaciente.DIAS_AMARELO_GRUPO
                : PoliticaClassificacaoPaciente.DIAS_AMARELO_INDIVIDUAL;
        int vermelhoDias = grupo ? PoliticaClassificacaoPaciente.DIAS_VERMELHO_GRUPO
                : PoliticaClassificacaoPaciente.DIAS_VERMELHO_INDIVIDUAL;

        long diasAusente = diagnostico.ultimaPresencaComprovada()
                .map(p -> ChronoUnit.DAYS.between(p.toLocalDate(), hoje))
                .orElse(0L);
        int faltas = diagnostico.faltasConsecutivasAnalisaveis().getAsInt();

        if (faltas >= FALTAS_VERMELHO || diasAusente > vermelhoDias)
            return Optional.of(ClassificacaoRisco.VERMELHO);
        if (faltas >= FALTAS_AMARELO || diasAusente > amareloDias)
            return Optional.of(ClassificacaoRisco.AMARELO);
        return Optional.of(ClassificacaoRisco.VERDE);
    }
}
