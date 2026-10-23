package com.pet.buscaativa.mapping;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;

import com.pet.buscaativa.entities.Agendamento;
import com.pet.buscaativa.entities.dto.AgendamentoDTO;

@Mapper(componentModel = "spring")
public interface AgendamentoMapper {

    @Mapping(target = "usuarioId", source = "usuario.idPublico")
    @Mapping(target = "nomeProfissional", source = "usuario.nome")
    @Mapping(target = "pacienteId", source = "paciente.idPublico")
    @Mapping(target = "nomePaciente", source = "paciente.nome")
    @Mapping(target = "tipoAcompanhamento", source = "paciente.tipoAcompanhamento")
    @Mapping(target = "agendamentoOriginalId", source = "agendamentoOriginal.id")
    public AgendamentoDTO toAgendamentoDTO(Agendamento agendamentoEntity);

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "usuario", ignore = true)
    @Mapping(target = "paciente", ignore = true)
    @Mapping(target = "agendamentoOriginal", ignore = true)
    public Agendamento toAgendamentoEntity(AgendamentoDTO agendamentoDTO);

    @Mapping(target = "usuario", ignore = true)
    @Mapping(target = "paciente", ignore = true)
    @Mapping(target = "agendamentoOriginal", ignore = true)
    void updateAgendamentoFromDTO(AgendamentoDTO agendamentoDTO, @MappingTarget Agendamento agendamentoEntity);
}