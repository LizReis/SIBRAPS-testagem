package com.pet.buscaativa.entities;

import com.pet.buscaativa.entities.enums.TipoAcompanhamento;

import jakarta.persistence.AttributeConverter;

public class TipoAcompanhamentoConverter implements AttributeConverter<TipoAcompanhamento, Integer>{

    @Override
    public Integer convertToDatabaseColumn(TipoAcompanhamento status) {
        if (status == null) {
            return null;
        }
        return status.getCodigo();
    }

    @Override
    public TipoAcompanhamento convertToEntityAttribute(Integer codigo) {
        if (codigo == null) {
            return null;
        }
        return TipoAcompanhamento.valueOf(codigo);
    }

}
