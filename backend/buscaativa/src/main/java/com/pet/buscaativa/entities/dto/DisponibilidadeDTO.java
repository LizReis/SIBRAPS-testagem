package com.pet.buscaativa.entities.dto;

import java.time.DayOfWeek;
import java.util.UUID;

import com.pet.buscaativa.entities.enums.TurnoEnum;
import com.pet.buscaativa.validation.UniqueDisponibilidade;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

@UniqueDisponibilidade
public record DisponibilidadeDTO(

        Long id,

        UUID usuarioId,

        @NotNull
        DayOfWeek diaSemana,

        @NotNull
        TurnoEnum turno,

        @NotNull
        @Positive(message = "A capacidade deve ser maior que zero.")
        Integer capacidade

) {

}