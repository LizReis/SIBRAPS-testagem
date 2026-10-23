package com.pet.buscaativa.entities.dto;

import com.pet.buscaativa.entities.enums.StatusPresencaGrupo;
import jakarta.validation.constraints.NotNull;

public record RegistrarPresencaGrupoDTO(
        @NotNull(message = "Informe o status da presença.") StatusPresencaGrupo statusPresenca
) {
}