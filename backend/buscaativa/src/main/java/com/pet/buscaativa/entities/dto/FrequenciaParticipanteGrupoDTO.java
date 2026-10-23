package com.pet.buscaativa.entities.dto;

import java.util.UUID;
import com.pet.buscaativa.entities.enums.StatusPresencaGrupo;
import jakarta.validation.constraints.NotNull;

public record FrequenciaParticipanteGrupoDTO(

    @NotNull UUID pacienteId, 
    @NotNull StatusPresencaGrupo statusPresenca
) {}