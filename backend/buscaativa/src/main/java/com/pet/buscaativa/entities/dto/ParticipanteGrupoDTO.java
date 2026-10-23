package com.pet.buscaativa.entities.dto;

import java.time.LocalDate;
import java.util.UUID;

public record ParticipanteGrupoDTO(UUID pacienteId, String nomePaciente, LocalDate inscritoDesde,
        long quantidadeSessoesRegistradas, long quantidadePresencas, long quantidadeFaltas,
        Double percentualPresenca, boolean possuiInscricaoFutura) {}