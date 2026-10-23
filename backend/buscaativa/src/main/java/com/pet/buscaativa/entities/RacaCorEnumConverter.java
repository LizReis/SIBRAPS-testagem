package com.pet.buscaativa.entities;

import com.pet.buscaativa.entities.enums.RacaCorEnum;

import jakarta.persistence.AttributeConverter;

public class RacaCorEnumConverter implements AttributeConverter<RacaCorEnum, Integer>{
    @Override
    public Integer convertToDatabaseColumn(RacaCorEnum status) {
        if (status == null) {
            return null;
        }
        return status.getCodigo();
    }

    @Override
    public RacaCorEnum convertToEntityAttribute(Integer codigo) {
        if (codigo == null) {
            return null;
        }
        return RacaCorEnum.valueOf(codigo);
    }
}
