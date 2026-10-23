package com.pet.buscaativa.entities.dto;
import com.pet.buscaativa.entities.enums.ClassificacaoRisco;
public record PacienteDetalheDTO(PacienteDTO paciente, ClassificacaoRisco classificacaoRisco,
        ProximoAgendamentoDTO proximoAgendamento, String atencaoNecessaria) {}