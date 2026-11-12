package com.pet.buscaativa.entities.dto;
import jakarta.validation.constraints.*;
public record RecuperarReservaCanceladaDTO(@NotNull @Min(0) Integer version,
        @NotBlank @Size(min=10,max=2000) String justificativa){}
