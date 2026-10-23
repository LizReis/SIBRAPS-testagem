package com.pet.buscaativa.entities;

import com.pet.buscaativa.entities.enums.CapsEnum;
import com.pet.buscaativa.entities.enums.ClassificacaoRisco;
import com.pet.buscaativa.entities.enums.SituacaoAtendimento;

import jakarta.persistence.AttributeConverter;

public class CapsEnumConverter implements AttributeConverter<CapsEnum, Integer> {
    
    @Override
    public Integer convertToDatabaseColumn(CapsEnum status) {
        if (status == null) {
            return null;
        }
        return status.getCodigo();
    }

    @Override
    public CapsEnum convertToEntityAttribute(Integer codigo) {
        if (codigo == null) {
            return null;
        }
        return CapsEnum.valueOf(codigo);
    }
}
