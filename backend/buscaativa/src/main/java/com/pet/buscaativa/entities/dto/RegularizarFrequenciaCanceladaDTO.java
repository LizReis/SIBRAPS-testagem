package com.pet.buscaativa.entities.dto;
import jakarta.validation.constraints.*;
import com.pet.buscaativa.entities.enums.FrequenciaAssistencial;
public record RegularizarFrequenciaCanceladaDTO(@NotNull FrequenciaAssistencial novaFrequencia,
        @NotNull @Min(0) Integer version,@NotBlank @Size(min=10,max=2000) String justificativa){}
