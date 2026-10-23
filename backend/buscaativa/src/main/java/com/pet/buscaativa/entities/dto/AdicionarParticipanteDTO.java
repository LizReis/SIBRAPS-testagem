package com.pet.buscaativa.entities.dto;

import java.util.UUID;

import jakarta.validation.constraints.NotNull;

public record AdicionarParticipanteDTO(
        @NotNull(message = "Informe o paciente.")
        UUID pacienteId
) {
}