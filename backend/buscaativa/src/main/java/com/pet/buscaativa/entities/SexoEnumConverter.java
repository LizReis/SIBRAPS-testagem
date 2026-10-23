package com.pet.buscaativa.entities ;

import com.pet.buscaativa.entities.enums.SexoEnum;

import jakarta.persistence.AttributeConverter;

public class SexoEnumConverter implements AttributeConverter<SexoEnum, Integer>{
    
    @Override
    public Integer convertToDatabaseColumn(SexoEnum status) {
        if (status == null) {
            return null;
        }
        return status.getCodigo();
    }

    @Override
    public SexoEnum convertToEntityAttribute(Integer codigo) {
        if (codigo == null) {
            return null;
        }
        return SexoEnum.valueOf(codigo);
    }
}
