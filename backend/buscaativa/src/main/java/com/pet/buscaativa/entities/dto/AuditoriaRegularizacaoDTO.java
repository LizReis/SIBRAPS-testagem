package com.pet.buscaativa.entities.dto;
import java.time.Instant;
import java.util.UUID;
import com.pet.buscaativa.entities.AuditoriaRegularizacaoCancelamento;
import com.pet.buscaativa.entities.enums.*;
public record AuditoriaRegularizacaoDTO(UUID id,Long agendamentoId,UUID pacienteId,UUID usuarioId,TipoUsuario perfil,
        String unidade,UUID responsavelAtendimentoId,String tipoRegularizacao,SituacaoReserva reservaAnterior,
        SituacaoReserva reservaResultante,FrequenciaAssistencial frequenciaAnterior,FrequenciaAssistencial frequenciaResultante,
        String justificativa,Instant ocorridoEm,Integer versaoAnterior,Integer versaoResultante) {
    public AuditoriaRegularizacaoDTO(AuditoriaRegularizacaoCancelamento a){this(a.getId(),a.getAgendamento().getId(),a.getPaciente().getIdPublico(),
        a.getAtor().getIdPublico(),a.getPerfil(),a.getUnidade(),a.getResponsavel().getIdPublico(),a.getTipoRegularizacao(),a.getReservaAnterior(),
        a.getReservaResultante(),a.getFrequenciaAnterior(),a.getFrequenciaResultante(),a.getJustificativa(),a.getOcorridoEm(),a.getVersaoAnterior(),a.getVersaoResultante());}
}
