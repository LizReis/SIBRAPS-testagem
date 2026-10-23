package com.pet.buscaativa.entities;

import com.pet.buscaativa.entities.enums.MotivoEncerramento;

import jakarta.persistence.AttributeConverter;

public class MotivoEncerramentoConverter implements AttributeConverter<MotivoEncerramento, Integer>{
    @Override
    public Integer convertToDatabaseColumn(MotivoEncerramento status) {
        if (status == null) {
            return null;
        }
        return status.getCodigo();
    }

    @Override
    public MotivoEncerramento convertToEntityAttribute(Integer codigo) {
        if (codigo == null) {
            return null;
        }
        return MotivoEncerramento.valueOf(codigo);
    }
}
