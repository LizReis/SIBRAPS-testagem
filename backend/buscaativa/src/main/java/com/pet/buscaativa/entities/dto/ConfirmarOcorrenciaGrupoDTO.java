package com.pet.buscaativa.entities.dto;

import java.util.List;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ConfirmarOcorrenciaGrupoDTO(
    @NotNull 
    Boolean ocorreu, 
    
    @Valid 
    List<FrequenciaParticipanteGrupoDTO> frequencias,
        
    @Size(max=1000) 
    String motivoCancelamento, 
    Integer version
) {}