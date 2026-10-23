package com.pet.buscaativa.entities;

import com.pet.buscaativa.entities.enums.TurnoEnum;

import jakarta.persistence.AttributeConverter;

public class TurnoEnumConverter implements AttributeConverter<TurnoEnum, Integer>{
 
    
    @Override
    public Integer convertToDatabaseColumn(TurnoEnum status) {
        if (status == null) {
            return null;
        }
        return status.getCodigo();
    }

    @Override
    public TurnoEnum convertToEntityAttribute(Integer codigo) {
        if (codigo == null) {
            return null;
        }
        return TurnoEnum.valueOf(codigo);
    }
}
