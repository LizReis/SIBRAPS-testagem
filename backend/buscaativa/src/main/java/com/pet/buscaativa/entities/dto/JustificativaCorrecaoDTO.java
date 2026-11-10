package com.pet.buscaativa.entities.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Validated in the service too: legacy parameters and direct calls share the same policy. */
public record JustificativaCorrecaoDTO(
        @NotBlank(message="Informe a justificativa da correção.")
        @Size(min=10,max=2000,message="A justificativa deve ter entre 10 e 2000 caracteres.") String justificativa) {}
