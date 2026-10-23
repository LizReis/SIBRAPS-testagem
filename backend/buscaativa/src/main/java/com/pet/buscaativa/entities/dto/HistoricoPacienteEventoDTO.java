package com.pet.buscaativa.entities.dto;

import java.time.LocalDateTime;

import com.pet.buscaativa.entities.enums.SituacaoAtendimento;
import com.pet.buscaativa.entities.enums.TipoEventoHistoricoPaciente;

public record HistoricoPacienteEventoDTO(
        Long id,
        TipoEventoHistoricoPaciente tipo,
        SituacaoAtendimento situacaoAtendimento,
        LocalDateTime ocorridoEm,
        String descricao,
        String titulo,
        Long agendamentoId,
        String nomeResponsavel,
        String funcaoResponsavel,
        String nomeUnidade,
        Integer numeroFaltaConsecutiva) {
}