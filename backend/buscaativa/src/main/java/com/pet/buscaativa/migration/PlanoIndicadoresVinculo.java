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
        return preparar(projecao.vinculo(), projecao.unidade(), projecao.dataReferencia(),
                projecao.status(), projecao.acompanhamento(), projecao.pendencias(), projecao.faltas(),
                projecao.ultimaPresenca(), projecao.risco(), projecao.visita());
    }

    public static Plano preparar(UUID vinculo, String unidade, LocalDate data,
            Avaliacao<?> status, Avaliacao<?> acompanhamento, Avaliacao<Boolean> pendencia,
            Avaliacao<Integer> faltas, Avaliacao<LocalDate> ultimaPresenca,
            Avaliacao<ClassificacaoRisco> risco, Avaliacao<Boolean> visita) {
        var bloqueios = new EnumMap<Campo, Bloqueio>(Campo.class);
        conferir(bloqueios, Campo.STATUS, status, true);
        conferir(bloqueios, Campo.ACOMPANHAMENTO, acompanhamento, true);
        conferir(bloqueios, Campo.PENDENCIAS, pendencia, true);
        conferir(bloqueios, Campo.FALTAS, faltas, true);
        conferir(bloqueios, Campo.ULTIMA_PRESENCA, ultimaPresenca, false);
        conferir(bloqueios, Campo.RISCO, risco, true);
        conferir(bloqueios, Campo.VISITA, visita, true);

        if (pendencia.conclusiva() && pendencia.valor().orElse(false)) {
            bloqueios.put(Campo.PENDENCIAS, new Bloqueio(Set.of(Motivo.INDICADORES_PENDENTES), false));
        }

        Optional<Indicadores> candidato = bloqueios.isEmpty()
                ? Optional.of(new Indicadores(faltas.valor().orElseThrow(),
                        ultimaPresenca.valor(), risco.valor().orElseThrow(),
                        visita.valor().orElseThrow()))
                : Optional.empty();
        return new Plano(vinculo, unidade, data,
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
