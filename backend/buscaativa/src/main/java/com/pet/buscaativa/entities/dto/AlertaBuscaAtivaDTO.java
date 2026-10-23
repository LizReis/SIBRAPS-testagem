package com.pet.buscaativa.entities.dto;

import java.util.UUID;

import com.pet.buscaativa.entities.enums.ClassificacaoRisco;

public record AlertaBuscaAtivaDTO(
        UUID idPublico,
        String nome,
        ClassificacaoRisco classificacaoRisco,
        Integer faltasConsecutivas,
        String alerta,
        String localBusca,
        String avisoAdicional
) {
}
