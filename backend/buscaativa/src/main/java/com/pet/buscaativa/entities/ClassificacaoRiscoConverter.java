package com.pet.buscaativa.entities;

import com.pet.buscaativa.entities.enums.ClassificacaoRisco;
import com.pet.buscaativa.entities.enums.SituacaoAtendimento;

import jakarta.persistence.AttributeConverter;

public class ClassificacaoRiscoConverter implements AttributeConverter<ClassificacaoRisco, Integer> {
    
    @Override
    public Integer convertToDatabaseColumn(ClassificacaoRisco status) {
        if (status == null) {
            return null;
        }
        return status.getCodigo();
    }

    @Override
    public ClassificacaoRisco convertToEntityAttribute(Integer codigo) {
        if (codigo == null) {
            return null;
        }
        return ClassificacaoRisco.valueOf(codigo);
    }
}
