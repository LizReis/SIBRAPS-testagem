package com.pet.buscaativa.entities.dto;

import com.pet.buscaativa.entities.enums.FrequenciaAssistencial;
import jakarta.validation.constraints.*;

/** Actor, timestamps, booking state and ownership are never accepted from the client. */
public record CorrigirFrequenciaDTO(
        @NotNull FrequenciaAssistencial novaFrequencia,
        @NotNull @Min(0) Integer version,
        @NotBlank @Size(min=10,max=2000) String justificativa){}
