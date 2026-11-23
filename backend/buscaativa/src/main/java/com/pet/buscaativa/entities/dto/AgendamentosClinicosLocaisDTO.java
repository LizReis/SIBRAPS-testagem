package com.pet.buscaativa.entities.dto;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import com.pet.buscaativa.entities.enums.FrequenciaAssistencial;
import com.pet.buscaativa.entities.enums.SituacaoReserva;

/**
 * Projecao local de agendamentos com proveniencia comprovada na propria unidade.
 * Nao inclui risco, numero global de faltas, unidade estrangeira ou IDs de
 * remarcacoes cuja cadeia de proveniencia ainda nao foi verificada.
 */
public record AgendamentosClinicosLocaisDTO(
        String nomePaciente,
        long totalAgendamentos,
        List<AgendamentoLocalDTO> agendamentos) {

    public record AgendamentoLocalDTO(
            Long id,
            LocalDate dataAgendamento,
            LocalTime horaAtendimento,
            String nomeProfissional,
            SituacaoReserva situacaoReserva,
            FrequenciaAssistencial frequenciaAssistencial) {
    }
}
