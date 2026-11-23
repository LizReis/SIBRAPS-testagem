package com.pet.buscaativa.services.impl;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.pet.buscaativa.entities.Agendamento;
import com.pet.buscaativa.services.PoliticaVisitaDomiciliar;

/**
 * Comparacao de VISITA DOMICILIAR por vinculo assistencial, sem efeitos colaterais.
 *
 * PREPARATORIO: nao alimenta alertas, paineis, historico ou endpoints.
 * A chave de unidade deve vir do vinculo explicitamente autorizado, jamais
 * do cliente ou de campo capsReferencia/usfReferencia.
 *
 * Reaproveita a politica clinica vigente, mas so apos verificar proveniencia
 * imutavel da remarcacao e de sua origem. Em caso de incerteza retorna vazio,
 * em vez de ativar ou limpar um gatilho sem evidencia.
 */
public final class ComparadorPoliticaVisitaVinculo {
    private ComparadorPoliticaVisitaVinculo() {}

    public static Optional<Boolean> comparar(
            List<Agendamento> agendamentosDaIdentidade,
            String unidadeDoVinculoAutorizado,
            DiagnosticoFaltasConsecutivasVinculoService.Resultado diagnostico,
            PoliticaVisitaDomiciliar politicaExistente) {
        Objects.requireNonNull(agendamentosDaIdentidade, "Agendamentos obrigatorios.");
        Objects.requireNonNull(diagnostico, "Diagnostico local obrigatorio.");
        Objects.requireNonNull(politicaExistente, "Politica vigente obrigatoria.");
        if (unidadeDoVinculoAutorizado == null || unidadeDoVinculoAutorizado.isBlank())
            throw new IllegalArgumentException("Unidade local comprovada obrigatoria.");

        if (!diagnostico.podeAvaliarSequencia()) return Optional.empty();

        if (agendamentosDaIdentidade.stream().anyMatch(a -> a == null
                || (AvaliacaoProvenienciaClinica.origemAusente(a.getUnidadeOrigemChave())
                    && AvaliacaoProvenienciaClinica.frequenciaIndividualEmAnalise(a)))) {
            return Optional.empty();
        }

        List<Agendamento> locais = agendamentosDaIdentidade.stream()
                .filter(a -> unidadeDoVinculoAutorizado.equals(a.getUnidadeOrigemChave()))
                .toList();

        for (Agendamento agenda : locais) {
            if (agenda.getPaciente() == null || AvaliacaoProvenienciaClinica.cadeiaAgendamento(
                    agenda, agenda.getPaciente().getId(), unidadeDoVinculoAutorizado)
                    != AvaliacaoProvenienciaClinica.Situacao.COMPROVADA) {
                return Optional.empty();
            }
        }

        boolean necessario = politicaExistente.necessaria(
                locais, diagnostico.ultimaPresencaComprovada().orElse(null));
        return Optional.of(necessario);
    }
}
