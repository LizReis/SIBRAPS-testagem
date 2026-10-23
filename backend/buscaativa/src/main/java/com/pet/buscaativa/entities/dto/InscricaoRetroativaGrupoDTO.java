package com.pet.buscaativa.entities.dto;

import java.util.List;
import java.util.UUID;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

public record InscricaoRetroativaGrupoDTO(
    @NotNull UUID pacienteId, 
    
    @Valid 
    @NotNull 
    List<FrequenciaSessaoRetroativaDTO> frequenciasPassadas
) {}