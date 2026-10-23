package com.pet.buscaativa.entities.dto;

import java.util.List;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;

public record CorrigirFrequenciasGrupoDTO(
        @Valid @NotEmpty List<FrequenciaParticipanteGrupoDTO> frequencias,
        Integer version) {}