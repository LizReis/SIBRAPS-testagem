package com.pet.buscaativa.entities.dto;

import java.time.LocalDateTime;

import com.pet.buscaativa.entities.enums.TipoEventoHistoricoPaciente;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record RegistroHistoricoPacienteDTO(
        @NotNull(message = "Informe o tipo do registro de histórico.")
        TipoEventoHistoricoPaciente tipo,
        LocalDateTime ocorridoEm,
        @Size(max = 2000, message = "A descrição deve ter no máximo 2000 caracteres.")
        String descricao) {
}