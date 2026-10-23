package com.pet.buscaativa.entities.dto;

import java.time.LocalDate;
import java.util.UUID;

import com.pet.buscaativa.entities.enums.ClassificacaoRisco;

public record PacientePrioritarioDTO(
        UUID idPublico,
        String nome,
        ClassificacaoRisco classificacaoRisco,
        Integer quantidadeFaltas,
        LocalDate dataUltimaPresenca) {
}