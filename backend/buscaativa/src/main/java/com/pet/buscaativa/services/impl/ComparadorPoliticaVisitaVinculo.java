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

        if (!diagnostico.podeAvaliarSequencia()
                || diagnostico.faltasConsecutivasAnalisaveis().isEmpty()) return Optional.empty();

        // Nao atribuir fatos de paciente global com unidade desconhecida
        // a qualquer vinculo. Sem certeza, nao existe decisao segura.
        if (agendamentosDaIdentidade.stream().anyMatch(a -> a == null
                || (a.getUnidadeOrigemChave() == null
                    && (a.isMigracaoPendente()
                        || a.getFrequenciaAssistencial()
                           == com.pet.buscaativa.entities.enums.FrequenciaAssistencial.FALTOU
                        || a.getFrequenciaAssistencial()
                           == com.pet.buscaativa.entities.enums.FrequenciaAssistencial.PRESENTE))))
            return Optional.empty();

        List<Agendamento> locais = agendamentosDaIdentidade.stream()
                .filter(a -> unidadeDoVinculoAutorizado.equals(a.getUnidadeOrigemChave()))
                .toList();

        // Nao tratar migracao inconclusiva como falta nem como ausencia de gatilho.
        if (locais.stream().anyMatch(Agendamento::isMigracaoPendente)) return Optional.empty();

        for (Agendamento remarcacao : locais) {
            if (!remarcacao.isRemarcacaoAposFalta()) continue;
            Agendamento original = remarcacao.getAgendamentoOriginal();
            if (original == null) {
                // Uma remarcacao declarada sem fato original nao e avaliavel.
                return Optional.empty();
            }
            if (!unidadeDoVinculoAutorizado.equals(original.getUnidadeOrigemChave())
                    || original.isMigracaoPendente()
                    || original.getPaciente() == null || remarcacao.getPaciente() == null
                    || !Objects.equals(original.getPaciente().getId(), remarcacao.getPaciente().getId())) {
                // Bloquear ligações cruzadas e historico sem proveniencia.
                return Optional.empty();
            }
        }

        boolean necessario = politicaExistente.necessaria(
                locais, diagnostico.ultimaPresencaComprovada().orElse(null));
        return Optional.of(necessario);
    }
}
