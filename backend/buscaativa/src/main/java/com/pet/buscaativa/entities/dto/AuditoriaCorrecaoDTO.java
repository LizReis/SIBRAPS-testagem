package com.pet.buscaativa.entities.dto;

import java.time.Instant;
import java.util.UUID;
import com.pet.buscaativa.entities.AuditoriaCorrecaoFrequencia;
import com.pet.buscaativa.entities.enums.*;

public record AuditoriaCorrecaoDTO(UUID id,Long agendamentoId,Long sessaoId,Long participanteId,UUID pacienteId,
        UUID usuarioId,TipoUsuario perfil,String unidade,UUID responsavelAtendimentoId,String tipoCorrecao,
        SituacaoReserva situacaoReserva,StatusSessaoGrupo situacaoSessao,FrequenciaAssistencial frequenciaAnterior,
        FrequenciaAssistencial frequenciaNova,String justificativa,Instant ocorridoEm,Integer versaoAnterior,Integer versaoResultante) {
    public AuditoriaCorrecaoDTO(AuditoriaCorrecaoFrequencia a){this(a.getId(),a.getAgendamento()==null?null:a.getAgendamento().getId(),
        a.getSessao()==null?null:a.getSessao().getId(),a.getParticipante()==null?null:a.getParticipante().getId(),a.getPaciente().getIdPublico(),
        a.getAtor().getIdPublico(),a.getPerfil(),a.getUnidade(),a.getResponsavel().getIdPublico(),a.getTipoCorrecao(),a.getSituacaoReserva(),a.getSituacaoSessao(),
        a.getFrequenciaAnterior(),a.getFrequenciaNova(),a.getJustificativa(),a.getOcorridoEm(),a.getVersaoAnterior(),a.getVersaoResultante());}
}
