package com.pet.buscaativa.mapping;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;

import com.pet.buscaativa.entities.Paciente;
import com.pet.buscaativa.entities.dto.PacienteDTO;

@Mapper(componentModel = "spring")
public interface PacienteMapper {
    default PacienteDTO toPacienteDTO(Paciente pacienteEntity) {
        return new PacienteDTO(pacienteEntity);
    }

    @Mapping(target = "idPublico", ignore = true)
    @Mapping(target = "profissionalReferencia", ignore = true)
    @Mapping(target = "countFaltas", source = "countFaltas", defaultValue = "0")
    public Paciente toPacienteEntity(PacienteDTO pacienteDTO);

    @Mapping(target = "idPublico", ignore = true)
    @Mapping(target = "countFaltas", ignore = true)
    @Mapping(target = "statusPaciente", ignore = true)
    @Mapping(target = "classificacaoRisco", ignore = true)
    @Mapping(target = "gatilhoVisitaAcionado", ignore = true)
    @Mapping(target = "profissionalReferencia", ignore = true)
    void updatePacienteFromDTO(PacienteDTO pacienteDTO, @MappingTarget Paciente pacienteEntity);
}