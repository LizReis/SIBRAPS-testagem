package com.pet.buscaativa.entities.dto;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

import com.pet.buscaativa.entities.enums.SituacaoAtendimento;
import com.pet.buscaativa.entities.enums.TipoAcompanhamento;
import com.pet.buscaativa.entities.enums.TurnoEnum;
import com.pet.buscaativa.entities.enums.SituacaoReserva;
import com.pet.buscaativa.entities.enums.FrequenciaAssistencial;
import com.fasterxml.jackson.annotation.JsonProperty;

import jakarta.validation.constraints.NotNull;

public record AgendamentoDTO(

        Long id,

        @NotNull(message = "Selecione o profissional.")
        UUID usuarioId,

        String nomeProfissional,

        @NotNull(message = "Selecione o paciente.")
        UUID pacienteId,

        String nomePaciente,

        TipoAcompanhamento tipoAcompanhamento,

        @NotNull(message = "Defina uma data para o Atendimento.")
        LocalDate dataAgendamento,

        @NotNull(message = "Defina o turno do Atendimento.")
        TurnoEnum turnoAgendamento,

        @NotNull(message = "Defina a hora do Atendimento.")
        LocalTime horaAtendimento,

        SituacaoAtendimento situacaoAtendimento,

        Long agendamentoOriginalId,

        Integer version,
        @JsonProperty(access = JsonProperty.Access.READ_ONLY) SituacaoReserva situacaoReserva,
        @JsonProperty(access = JsonProperty.Access.READ_ONLY) FrequenciaAssistencial frequenciaAssistencial,
        @JsonProperty(access = JsonProperty.Access.READ_ONLY) Boolean migracaoPendente,
        @JsonProperty(access = JsonProperty.Access.READ_ONLY) String motivoMigracaoPendente,
        @JsonProperty(access = JsonProperty.Access.READ_ONLY) Boolean ocupaVaga
) {
    public AgendamentoDTO(Long id, UUID usuarioId, String nomeProfissional, UUID pacienteId, String nomePaciente,
            TipoAcompanhamento tipoAcompanhamento, LocalDate dataAgendamento, TurnoEnum turnoAgendamento,
            LocalTime horaAtendimento, SituacaoAtendimento situacaoAtendimento, Long agendamentoOriginalId, Integer version) {
        this(id, usuarioId, nomeProfissional, pacienteId, nomePaciente, tipoAcompanhamento, dataAgendamento,
                turnoAgendamento, horaAtendimento, situacaoAtendimento, agendamentoOriginalId, version,
                null, null, null, null, null);
    }
}
