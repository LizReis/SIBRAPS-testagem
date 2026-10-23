package com.pet.buscaativa.entities;

import com.pet.buscaativa.entities.enums.UnidadeAtuacao;

import jakarta.persistence.AttributeConverter;

public class UnidadeAtuacaoConverter implements AttributeConverter<UnidadeAtuacao, Integer>{
    
    @Override
    public Integer convertToDatabaseColumn(UnidadeAtuacao status) {
        if (status == null) {
            return null;
        }
        return status.getCodigo();
    }

    @Override
    public UnidadeAtuacao convertToEntityAttribute(Integer codigo) {
        if (codigo == null) {
            return null;
        }
        return UnidadeAtuacao.valueOf(codigo);
    }
}
