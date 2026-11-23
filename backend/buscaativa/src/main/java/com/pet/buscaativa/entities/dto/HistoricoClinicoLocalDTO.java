package com.pet.buscaativa.entities.dto;

import java.time.LocalDateTime;
import java.util.List;

import com.pet.buscaativa.entities.enums.SituacaoAtendimento;
import com.pet.buscaativa.entities.enums.TipoEventoHistoricoPaciente;

/**
 * Projecao estritamente LOCAL do historico com origem assistencial comprovada.
 * Contadores derivam APENAS dos eventos retornados, nunca de Paciente.countFaltas,
 * risco ou outros indicadores globais do modelo legado.
 */
public record HistoricoClinicoLocalDTO(
        String nomePaciente,
        long totalEventos,
        long totalConsultasAgendadas,
        long totalPresencas,
        long totalFaltas,
        long totalRemarcacoes,
        long totalGruposTerapeuticos,
        long totalRegistrosBuscaAtiva,
        List<EventoLocalDTO> eventos) {

    public record EventoLocalDTO(
            Long id,
            TipoEventoHistoricoPaciente tipo,
            SituacaoAtendimento situacaoAtendimento,
            LocalDateTime ocorridoEm,
            String descricao) {
    }
}
