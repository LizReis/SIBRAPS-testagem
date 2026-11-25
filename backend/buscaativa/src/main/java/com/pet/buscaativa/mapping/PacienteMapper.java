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

    /** Campos compartilhados no modelo local. Estado e referência clínica não passam por este mapa. */
    @org.mapstruct.BeanMapping(ignoreByDefault = true)
    @Mapping(target = "nome", source = "nome")
    @Mapping(target = "nomeMae", source = "nomeMae")
    @Mapping(target = "dataNascimento", source = "dataNascimento")
    @Mapping(target = "sexo", source = "sexo")
    @Mapping(target = "racacor", source = "racacor")
    @Mapping(target = "cns", source = "cns")
    @Mapping(target = "cpf", source = "cpf")
    @Mapping(target = "telefone", source = "telefone")
    @Mapping(target = "situacaoRua", source = "situacaoRua")
    @Mapping(target = "capsReferencia", source = "capsReferencia")
    @Mapping(target = "usfReferencia", source = "usfReferencia")
    void aplicarCadastroCompartilhado(PacienteDTO dados, @MappingTarget Paciente pessoa);

    @Mapping(target = "idPublico", ignore = true)
    @Mapping(target = "indicadoresPendentes", ignore = true)
    @Mapping(target = "profissionalReferencia", ignore = true)
    @Mapping(target = "countFaltas", source = "countFaltas", defaultValue = "0")
    public Paciente toPacienteEntity(PacienteDTO pacienteDTO);

    @Mapping(target = "idPublico", ignore = true)
    @Mapping(target = "countFaltas", ignore = true)
    @Mapping(target = "statusPaciente", ignore = true)
    @Mapping(target = "classificacaoRisco", ignore = true)
    @Mapping(target = "gatilhoVisitaAcionado", ignore = true)
    @Mapping(target = "indicadoresPendentes", ignore = true)
    @Mapping(target = "profissionalReferencia", ignore = true)
    void updatePacienteFromDTO(PacienteDTO pacienteDTO, @MappingTarget Paciente pacienteEntity);
}
