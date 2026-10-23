package com.pet.buscaativa.entities.dto;

import com.pet.buscaativa.entities.enums.MotivoEncerramento;
import com.pet.buscaativa.entities.enums.StatusPaciente;

import jakarta.validation.constraints.NotNull;

public record EncerramentoPacienteDTO(
        @NotNull(message = "Informe o motivo do encerramento.")
        MotivoEncerramento motivo,
        @NotNull(message = "Informe o novo Status do paciente.")
        StatusPaciente statusPaciente,
        String descricao
) {
}