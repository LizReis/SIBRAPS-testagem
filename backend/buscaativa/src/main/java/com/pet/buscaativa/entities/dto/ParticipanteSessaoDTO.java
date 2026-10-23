package com.pet.buscaativa.entities.dto;

import java.util.UUID;

import com.pet.buscaativa.entities.enums.StatusPresencaGrupo;

public record ParticipanteSessaoDTO(
        UUID pacienteId,
        String nomePaciente,
        StatusPresencaGrupo statusPresenca
) {
}