package com.pet.buscaativa.entities.dto;


import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.pet.buscaativa.entities.enums.ClassificacaoRisco;
import com.pet.buscaativa.entities.enums.StatusPaciente;
import com.pet.buscaativa.entities.enums.TipoAcompanhamento;

public record HistoricoPacienteDTO(
        String nomePaciente,
        StatusPaciente statusPaciente,
        ClassificacaoRisco classificacaoAtual,
        TipoAcompanhamento tipoAcompanhamento,
        LocalDate dataUltimaPresenca,
        Long diasSemComparecer,
        int quantidadeFaltasAtual,
        long totalConsultasAgendadas,
        long totalPresencas,
        long totalFaltas,
        long totalRemarcacoes,
        long totalGruposTerapeuticos,
        long totalRegistrosBuscaAtiva,
        List<HistoricoPacienteEventoDTO> eventos) {
}