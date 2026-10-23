package com.pet.buscaativa.entities.dto;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.NotNull;

public record NovaSessaoDTO(

        @NotNull(message = "Informe o grupo.")
        Long grupoId,

        @NotNull(message = "Defina a data da sessão.")
        LocalDate dataSessao,

        LocalTime horario,

        List<UUID> participantesIds

) {
}