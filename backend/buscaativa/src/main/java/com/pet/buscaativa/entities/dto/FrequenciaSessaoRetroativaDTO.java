package com.pet.buscaativa.entities.dto;

import com.pet.buscaativa.entities.enums.StatusPresencaGrupo;
import jakarta.validation.constraints.NotNull;

public record FrequenciaSessaoRetroativaDTO(
    @NotNull Long sessaoId, 
    @NotNull StatusPresencaGrupo statusPresenca
) {}