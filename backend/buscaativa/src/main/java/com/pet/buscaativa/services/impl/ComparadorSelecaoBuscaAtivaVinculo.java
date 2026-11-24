package com.pet.buscaativa.services.impl;

import java.util.Objects;
import java.util.Optional;

import com.pet.buscaativa.entities.enums.ClassificacaoRisco;
import com.pet.buscaativa.entities.enums.StatusPaciente;

/**
 * Caracterizacao PURA da selecao da busca ativa no contexto de um vinculo.
 *
 * Nenhuma API, scheduler, relatorio, endereco ou dado de paciente e acessado.
 * Todos os argumentos sao do VINCULO AUTORIZADO, nunca do Paciente global.
 * Uma conclusao INDETERMINADA nao pode ser interpretada como ausencia de risco
 * nem como autorizacao para entrar em lista/gerar acao clinica.
 */
public final class ComparadorSelecaoBuscaAtivaVinculo {
    private ComparadorSelecaoBuscaAtivaVinculo() {}

    public enum Decisao { INCLUIR, NAO_INCLUIR, INDETERMINADA }
    public enum Motivo {
        REVISAO_HISTORICA, RISCO_VERMELHO, VISITA_CONFIRMADA,
        ACOMPANHAMENTO_NAO_ATIVO, SEM_CRITERIO, DADOS_INCOMPLETOS
    }

    public record Resultado(Decisao decisao, Motivo motivo) {}

    /**
     * Reproduz a selecao do PacienteServiceImpl: apenas vinculos ATIVOS
     * com risco VERMELHO, indicador pendente de revisao OU visita confirmada.
     * A revisao historica tem prioridade. Com pendencia descartada, um
     * criterio comprovado basta para incluir o vinculo.
     *
     * A situacao de rua e a mensagem/endereco de busca pertencem a outra
     * camada de apresentacao segura e NAO sao tratados aqui.
     */
    public static Resultado comparar(StatusPaciente statusDoVinculo,
            Optional<ClassificacaoRisco> riscoDoVinculo,
            Boolean indicadoresPendentesDoVinculo,
            Optional<Boolean> visitaConfirmadaDoVinculo) {
        Objects.requireNonNull(riscoDoVinculo, "Resultado diagnostico de risco obrigatorio.");
        Objects.requireNonNull(visitaConfirmadaDoVinculo, "Resultado diagnostico de visita obrigatorio.");

        if (statusDoVinculo == null)
            return new Resultado(Decisao.INDETERMINADA, Motivo.DADOS_INCOMPLETOS);

        if (statusDoVinculo != StatusPaciente.ATIVO)
            return new Resultado(Decisao.NAO_INCLUIR, Motivo.ACOMPANHAMENTO_NAO_ATIVO);

        if (indicadoresPendentesDoVinculo == null)
            return new Resultado(Decisao.INDETERMINADA, Motivo.DADOS_INCOMPLETOS);

        if (indicadoresPendentesDoVinculo)
            return new Resultado(Decisao.INCLUIR, Motivo.REVISAO_HISTORICA);

        if (riscoDoVinculo.orElse(null) == ClassificacaoRisco.VERMELHO)
            return new Resultado(Decisao.INCLUIR, Motivo.RISCO_VERMELHO);

        if (visitaConfirmadaDoVinculo.orElse(false))
            return new Resultado(Decisao.INCLUIR, Motivo.VISITA_CONFIRMADA);

        if (riscoDoVinculo.isEmpty() || visitaConfirmadaDoVinculo.isEmpty())
            return new Resultado(Decisao.INDETERMINADA, Motivo.DADOS_INCOMPLETOS);

        return new Resultado(Decisao.NAO_INCLUIR, Motivo.SEM_CRITERIO);
    }
}
