package com.pet.buscaativa.entities;

import com.pet.buscaativa.entities.enums.TipoUsuario;

import jakarta.persistence.AttributeConverter;

public class TipoUsuarioConverter implements AttributeConverter<TipoUsuario, Integer>{
    @Override
    public Integer convertToDatabaseColumn(TipoUsuario status) {
        if (status == null) {
            return null;
        }
        return status.getCodigo();
    }

    @Override
    public TipoUsuario convertToEntityAttribute(Integer codigo) {
        if (codigo == null) {
            return null;
        }
        return TipoUsuario.valueOf(codigo);
    }
}
