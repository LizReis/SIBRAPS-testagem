package com.pet.buscaativa.entities.dto;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import com.pet.buscaativa.entities.enums.StatusSessaoGrupo;
import com.pet.buscaativa.entities.enums.StatusExibicaoSessaoGrupo;

public record SessaoGrupoDTO(
        Long id,
        Long grupoId,
        String temaGrupo,
        String nomeCoordenador,
        LocalDate dataSessao,
        LocalTime horario,
        StatusSessaoGrupo status,
        StatusExibicaoSessaoGrupo statusExibicao,
        String motivoCancelamento,
        List<ParticipanteSessaoDTO> participantes,
        Integer quantidadeParticipantes,
        Integer quantidadePresencasConfirmadas,
        Integer version,
        java.util.UUID responsavelAtendimentoId,
        String nomeResponsavelAtendimento,
        boolean responsabilidadePendente,
        String motivoResponsabilidadePendente,
        String origemResponsabilidade
) {
    public SessaoGrupoDTO(Long id,Long grupoId,String temaGrupo,String nomeCoordenador,LocalDate dataSessao,LocalTime horario,
            StatusSessaoGrupo status,StatusExibicaoSessaoGrupo statusExibicao,String motivoCancelamento,List<ParticipanteSessaoDTO> participantes,
            Integer quantidadeParticipantes,Integer quantidadePresencasConfirmadas,Integer version) {
        this(id,grupoId,temaGrupo,nomeCoordenador,dataSessao,horario,status,statusExibicao,motivoCancelamento,participantes,
            quantidadeParticipantes,quantidadePresencasConfirmadas,version,null,null,false,null,null);
    }
}