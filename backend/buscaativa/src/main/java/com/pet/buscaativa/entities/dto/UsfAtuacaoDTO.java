package com.pet.buscaativa.entities.dto;

import com.pet.buscaativa.entities.UsfReferencia;

public record UsfAtuacaoDTO(Long id, String cnes, String nomeUsf) {
    public UsfAtuacaoDTO(UsfReferencia usf) {
        this(usf.getId(), usf.getCnes(), usf.getNomeUsf());
    }
}
