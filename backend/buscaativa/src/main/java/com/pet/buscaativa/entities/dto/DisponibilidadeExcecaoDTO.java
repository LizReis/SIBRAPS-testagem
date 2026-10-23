package com.pet.buscaativa.entities.dto;

import java.time.LocalDate;
import java.util.UUID;

import com.pet.buscaativa.entities.enums.TurnoEnum;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record DisponibilidadeExcecaoDTO(

        Long id,

        UUID usuarioId,

        @NotNull(message = "Informe a data.")
        LocalDate data,

        @NotNull(message = "Informe o turno.")
        TurnoEnum turno,

        @NotNull(message = "Informe a capacidade.")
        @Min(value = 0, message = "A capacidade não pode ser negativa.")
        Integer capacidade

) {

}