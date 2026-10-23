package com.pet.buscaativa.entities.dto;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;
import com.pet.buscaativa.entities.enums.RecorrenciaGrupo;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record AtualizarGrupoTerapeuticoDTO(
        @NotBlank String tema,
        @NotNull UUID coordenadorId,
        @NotNull LocalDate dataPrimeiraSessao,
        @NotNull LocalTime horario,
        @NotNull RecorrenciaGrupo recorrencia,
        LocalDate dataFimRecorrencia,
        @NotNull Integer version) {}