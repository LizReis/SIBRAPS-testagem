package com.pet.buscaativa.entities.dto;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

import com.pet.buscaativa.entities.enums.RecorrenciaGrupo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record CriarGrupoDTO(

        @NotBlank(message = "Informe o tema do grupo.")
        String tema,

        @NotNull(message = "Selecione o coordenador do grupo.")
        UUID coordenadorId,

        @NotNull(message = "Defina a recorrência do grupo.")
        RecorrenciaGrupo recorrencia,

        @NotNull(message = "Defina a data da primeira sessão.")
        LocalDate dataPrimeiraSessao,

        LocalDate dataFimRecorrencia,

        @NotNull(message = "Defina o horário do grupo.")
        LocalTime horario,

        List<UUID> participantesIds

) {
}