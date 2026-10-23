package com.pet.buscaativa.entities.dto;
import java.time.LocalDate;
import java.time.LocalTime;

import com.pet.buscaativa.entities.enums.SituacaoAtendimento;
public record ProximoAgendamentoDTO(Long id, LocalDate data, LocalTime hora, SituacaoAtendimento situacao) {}