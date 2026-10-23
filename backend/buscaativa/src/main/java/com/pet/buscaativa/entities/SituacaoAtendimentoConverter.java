package com.pet.buscaativa.entities;

import com.pet.buscaativa.entities.enums.SituacaoAtendimento;

import jakarta.persistence.AttributeConverter;

public class SituacaoAtendimentoConverter implements AttributeConverter<SituacaoAtendimento, Integer> {
    
    @Override
    public Integer convertToDatabaseColumn(SituacaoAtendimento status) {
        if (status == null) {
            return null;
        }
        return status.getCodigo();
    }

    @Override
    public SituacaoAtendimento convertToEntityAttribute(Integer codigo) {
        if (codigo == null) {
            return null;
        }
        return SituacaoAtendimento.valueOf(codigo);
    }
}
