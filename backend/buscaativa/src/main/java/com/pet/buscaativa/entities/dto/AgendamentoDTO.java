package com.pet.buscaativa.entities.dto;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

import com.pet.buscaativa.entities.enums.SituacaoAtendimento;
import com.pet.buscaativa.entities.enums.TipoAcompanhamento;
import com.pet.buscaativa.entities.enums.TurnoEnum;

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

        Integer version
) {

}