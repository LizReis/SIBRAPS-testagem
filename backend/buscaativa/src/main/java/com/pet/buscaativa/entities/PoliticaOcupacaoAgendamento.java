package com.pet.buscaativa.entities;

import com.pet.buscaativa.entities.enums.*;

/** Canonical rule; ocupaVaga and the legacy column are transactional query projections only. */
public final class PoliticaOcupacaoAgendamento {
    private PoliticaOcupacaoAgendamento() {}
    public static boolean ocupa(SituacaoReserva reserva, FrequenciaAssistencial frequencia) {
        if (reserva == null || frequencia == null) throw new IllegalStateException("Estado canônico não identificado.");
        return (reserva == SituacaoReserva.AGENDADA || reserva == SituacaoReserva.REMARCADA)
                && frequencia != FrequenciaAssistencial.FALTOU;
    }
    public static boolean ocupavaLegado(Integer codigo) {
        return codigo != null && (codigo == 1 || codigo == 2 || codigo == 4);
    }
    public static SituacaoAtendimento projetar(SituacaoReserva reserva, FrequenciaAssistencial frequencia) {
        if (reserva == null || frequencia == null) throw new IllegalStateException("Estado canônico não identificado.");
        if (reserva == SituacaoReserva.CANCELADA) return SituacaoAtendimento.CANCELADO;
        if (reserva == SituacaoReserva.SUBSTITUIDA) return SituacaoAtendimento.REMARCADO_ORIGEM;
        if (frequencia == FrequenciaAssistencial.PRESENTE) return SituacaoAtendimento.PRESENTE;
        if (frequencia == FrequenciaAssistencial.FALTOU) return SituacaoAtendimento.FALTOU;
        return reserva == SituacaoReserva.REMARCADA ? SituacaoAtendimento.REMARCADO : SituacaoAtendimento.AGENDADO;
    }
}
