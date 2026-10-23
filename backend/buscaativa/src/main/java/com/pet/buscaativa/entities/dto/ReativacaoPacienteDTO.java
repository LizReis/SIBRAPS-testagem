package com.pet.buscaativa.entities.dto;

import jakarta.validation.constraints.NotBlank;

public record ReativacaoPacienteDTO(
        @NotBlank(message = "Informe o motivo da reativação.")
        String motivo
) {
}