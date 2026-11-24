package com.pet.buscaativa.migration;

import java.time.LocalDate;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.pet.buscaativa.entities.enums.ClassificacaoRisco;
import com.pet.buscaativa.services.impl.ProjecaoClinicaVinculoService.Avaliacao;
import com.pet.buscaativa.services.impl.ProjecaoClinicaVinculoService.Motivo;
import com.pet.buscaativa.services.impl.ProjecaoClinicaVinculoService.Projecao;

/**
 * Ensaio puro do conjunto de indicadores derivados de uma projeção local.
 * Recebe apenas a projeção produzida internamente, nunca dados do cliente.
 * Não é comando de escrita, autorização, endpoint ou política clínica nova.
 * Um conjunto incompleto não é candidato à atualização conjunta.
 */
public final class PlanoIndicadoresVinculo {
    private PlanoIndicadoresVinculo() {}

    public enum Campo { STATUS, ACOMPANHAMENTO, PENDENCIAS, FALTAS, ULTIMA_PRESENCA, RISCO, VISITA }

    /** Última presença vazia pode representar ausência comprovada, não desconhecimento. */
    public record Indicadores(int faltas, Optional<LocalDate> ultimaPresenca,
            ClassificacaoRisco risco, boolean gatilhoVisita) {}

    public record Bloqueio(Set<Motivo> motivos, boolean valorObrigatorioAusente) {}

    public record Plano(UUID vinculo, String unidade, LocalDate dataReferencia,
            Optional<Indicadores> candidato, Map<Campo, Bloqueio> bloqueios) {
        /** O ensaio não concede autorização para aplicar o candidato. */
        public boolean aplicacaoAutorizada() {
            return false;
        }
    }

    public static Plano preparar(Projecao projecao) {
        Objects.requireNonNull(projecao, "Projeção local obrigatória.");
        var bloqueios = new EnumMap<Campo, Bloqueio>(Campo.class);
        conferir(bloqueios, Campo.STATUS, projecao.status(), true);
        conferir(bloqueios, Campo.ACOMPANHAMENTO, projecao.acompanhamento(), true);
        conferir(bloqueios, Campo.PENDENCIAS, projecao.pendencias(), true);
        conferir(bloqueios, Campo.FALTAS, projecao.faltas(), true);
        conferir(bloqueios, Campo.ULTIMA_PRESENCA, projecao.ultimaPresenca(), false);
        conferir(bloqueios, Campo.RISCO, projecao.risco(), true);
        conferir(bloqueios, Campo.VISITA, projecao.visita(), true);

        if (projecao.pendencias().conclusiva() && projecao.pendencias().valor().orElse(false)) {
            bloqueios.put(Campo.PENDENCIAS, new Bloqueio(Set.of(Motivo.INDICADORES_PENDENTES), false));
        }

        Optional<Indicadores> candidato = bloqueios.isEmpty()
                ? Optional.of(new Indicadores(projecao.faltas().valor().orElseThrow(),
                        projecao.ultimaPresenca().valor(), projecao.risco().valor().orElseThrow(),
                        projecao.visita().valor().orElseThrow()))
                : Optional.empty();
        return new Plano(projecao.vinculo(), projecao.unidade(), projecao.dataReferencia(),
                candidato, Map.copyOf(bloqueios));
    }

    private static void conferir(Map<Campo, Bloqueio> bloqueios, Campo campo,
            Avaliacao<?> avaliacao, boolean exigeValor) {
        boolean ausente = exigeValor && avaliacao.valor().isEmpty();
        if (!avaliacao.conclusiva() || ausente) {
            bloqueios.put(campo, new Bloqueio(Set.copyOf(avaliacao.motivos()), ausente));
        }
    }
}
