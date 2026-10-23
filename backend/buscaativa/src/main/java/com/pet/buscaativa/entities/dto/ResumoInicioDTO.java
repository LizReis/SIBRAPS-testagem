package com.pet.buscaativa.entities.dto;

import java.util.List;

public record ResumoInicioDTO(
        Long totalPacientesAtivos,
        Long totalAgendamentosHoje,
        Long totalFaltasUltimosSeteDias,
        Long totalPacientesAtencao,
        Long totalNotificacoes,
        List<PacientePrioritarioDTO> pacientesPrioritarios) {
}