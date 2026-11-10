package com.pet.buscaativa.entities.dto;

import java.util.List;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;

public record CorrigirFrequenciasGrupoDTO(
        @Valid @NotEmpty List<FrequenciaParticipanteGrupoDTO> frequencias,
        Integer version,
        @jakarta.validation.constraints.Size(max=2000,message="A justificativa deve ter no máximo 2000 caracteres.") String justificativa) {
    public CorrigirFrequenciasGrupoDTO(List<FrequenciaParticipanteGrupoDTO> frequencias,Integer version){this(frequencias,version,null);}
}