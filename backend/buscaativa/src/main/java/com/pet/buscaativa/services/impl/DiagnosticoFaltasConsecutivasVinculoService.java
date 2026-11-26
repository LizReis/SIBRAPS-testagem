package com.pet.buscaativa.services.impl;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

/**
 * Diagnostico PREPARATORIO da sequencia de frequencias de um vinculo local.
 *
 * Nao escreve no Paciente/Vinculo, nao classifica risco e nao dispara visita
 * domiciliar, busca ativa nem auditoria. A linha do tempo ja exige vinculo
 * explicito da unidade autenticada e nao mistura os atendimentos de outras
 * unidades. Dados historicos sem origem comprovada bloqueiam o resultado
 * conclusivo, em vez de serem atribuidos ou ignorados.
 */
@Service
@RequiredArgsConstructor
public class DiagnosticoFaltasConsecutivasVinculoService {
    private final LinhaDoTempoFrequenciasVinculoService linhaDoTempo;

    public record Resultado(
            OptionalInt faltasNaOrdemLegada,
            Optional<LocalDateTime> ultimaPresencaComprovada,
            int eventosLocaisComprovados,
            boolean revisaoHistoricaNecessaria,
            long agendamentosSemOrigemComFrequencia,
            long presencasGrupoSemOrigem,
            long frequenciasInconsistentes,
            boolean empateComFrequenciasDiferentes
    ) {
        public OptionalInt faltasConsecutivasAnalisaveis() {
            return revisaoHistoricaNecessaria ? OptionalInt.empty() : faltasNaOrdemLegada;
        }

        public boolean podeAvaliarSequencia() {
            return faltasConsecutivasAnalisaveis().isPresent();
        }
    }

    /**
     * Candidatos à conferência da Etapa 6.4. A coincidência temporal, mesmo
     * com frequências iguais, não demonstra que dois compromissos sejam o
     * mesmo atendimento. Não excluir, recontar ou alterar a pendência aqui.
     * A origem local é pré-condição do Diagnostico; não consultar fatos externos.
     */
    public enum TipoCandidato { FREQUENCIAS_CONTRADITORIAS, FREQUENCIAS_COINCIDENTES }

    public record Candidato(LocalDateTime ocorridoEm, TipoCandidato tipo,
            List<LinhaDoTempoFrequenciasVinculoService.Evento> fatos) {}

    public static List<Candidato> candidatosReconciliacao(
            LinhaDoTempoFrequenciasVinculoService.Diagnostico diagnostico) {
        Objects.requireNonNull(diagnostico, "Diagnostico de frequencias obrigatorio.");
        var porInstante = new java.util.TreeMap<LocalDateTime,
                java.util.List<LinhaDoTempoFrequenciasVinculoService.Evento>>();
        for (var fato : diagnostico.eventosComOrigemLocal())
            porInstante.computeIfAbsent(fato.ocorridoEm(), k -> new java.util.ArrayList<>()).add(fato);
        var candidatos = new java.util.ArrayList<Candidato>();
        for (var entrada : porInstante.entrySet()) {
            // A identidade do fato inclui a fonte: IDs numéricos de tabelas
            // distintas podem coincidir. Ordenação técnica não prova cronologia.
            var fatos = entrada.getValue().stream().distinct()
                    .sorted(Comparator.comparing(LinhaDoTempoFrequenciasVinculoService.Evento::fonte)
                            .thenComparing(LinhaDoTempoFrequenciasVinculoService.Evento::registroId)
                            .thenComparing(LinhaDoTempoFrequenciasVinculoService.Evento::presente))
                    .toList();
            if (fatos.size() < 2) continue;
            boolean contraditorio = fatos.stream().anyMatch(f -> f.presente() != fatos.getFirst().presente());
            candidatos.add(new Candidato(entrada.getKey(), contraditorio
                    ? TipoCandidato.FREQUENCIAS_CONTRADITORIAS : TipoCandidato.FREQUENCIAS_COINCIDENTES, fatos));
        }
        return List.copyOf(candidatos);
    }

    @Transactional(readOnly = true)
    public Resultado analisar(UUID pacienteId) {
        return calcular(linhaDoTempo.analisar(pacienteId));
    }

    /**
     * Calculo puro para CARACTERIZAR a futura logica local. Presenca zera a
     * sequencia; falta incrementa. Empates reproduzem a prioridade historica
     * individual antes de grupo, sem mudar esta regra na atual entrega.
     */
    public static Resultado calcular(LinhaDoTempoFrequenciasVinculoService.Diagnostico diagnostico) {
        Objects.requireNonNull(diagnostico, "Diagnostico de frequencias obrigatorio.");

        List<LinhaDoTempoFrequenciasVinculoService.Evento> eventos =
                diagnostico.eventosComOrigemLocal().stream()
                .sorted(Comparator
                        .comparing(LinhaDoTempoFrequenciasVinculoService.Evento::ocorridoEm)
                        .thenComparingInt(e -> e.fonte()
                                == LinhaDoTempoFrequenciasVinculoService.Fonte.ATENDIMENTO_INDIVIDUAL ? 0 : 1)
                        .thenComparing(LinhaDoTempoFrequenciasVinculoService.Evento::registroId))
                .toList();

        int faltas = 0;
        LocalDateTime ultimaPresenca = null;
        boolean empateInconclusivo = false;
        LinhaDoTempoFrequenciasVinculoService.Evento anterior = null;
        for (var evento : eventos) {
            if (anterior != null && anterior.ocorridoEm().equals(evento.ocorridoEm())
                    && anterior.presente() != evento.presente()) {
                empateInconclusivo = true;
            }
            anterior = evento;
            if (evento.presente()) {
                faltas = 0;
                ultimaPresenca = evento.ocorridoEm();
            } else {
                faltas++;
            }
        }
        boolean incerto = diagnostico.haEvidenciaHistoricaSemUnidadeComprovada()
                || diagnostico.frequenciasInconsistentes() > 0;
        return new Resultado(incerto ? OptionalInt.empty() : OptionalInt.of(faltas),
                Optional.ofNullable(ultimaPresenca), eventos.size(), incerto || empateInconclusivo,
                diagnostico.agendamentosSemOrigemComFrequencia(),
                diagnostico.presencasGrupoSemOrigem(), diagnostico.frequenciasInconsistentes(), empateInconclusivo);
    }
}
