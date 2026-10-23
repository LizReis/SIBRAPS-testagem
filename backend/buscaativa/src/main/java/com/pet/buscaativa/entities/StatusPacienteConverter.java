package com.pet.buscaativa.entities;


import com.pet.buscaativa.entities.enums.StatusPaciente;

import jakarta.persistence.AttributeConverter;

public class StatusPacienteConverter implements AttributeConverter<StatusPaciente, Integer>{

    @Override
    public Integer convertToDatabaseColumn(StatusPaciente status) {
        if (status == null) {
            return null;
        }
        return status.getCodigo();
    }

    @Override
    public StatusPaciente convertToEntityAttribute(Integer codigo) {
        if (codigo == null) {
            return null;
        }
        return StatusPaciente.valueOf(codigo);
    }

}
    
