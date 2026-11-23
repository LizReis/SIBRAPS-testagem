package com.pet.buscaativa.services.impl;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pet.buscaativa.services.impl.LinhaDoTempoFrequenciasVinculoService.Diagnostico;
import com.pet.buscaativa.services.impl.LinhaDoTempoFrequenciasVinculoService.Fonte;

import lombok.RequiredArgsConstructor;

/**
 * INVENTARIO de evidencias para a futura separacao dos indicadores clinicos.
 *
 * Nao calcula faltas CONSECUTIVAS, risco, ultima presenca, visita domiciliar
 * ou busca ativa; portanto, nao deve alimentar painéis nem APIs assistenciais.
 * Registros legados sem proveniencia nao sao atribuidos automaticamente.
 * Nenhum metodo modifica o banco.
 */
@Service
@RequiredArgsConstructor
public class InventarioFrequenciasVinculoService {
    private final LinhaDoTempoFrequenciasVinculoService linhaDoTempo;

    public record Inventario(
            long presencasIndividuais,
            long faltasIndividuais,
            long presencasEmGrupo,
            long faltasEmGrupo,
            long agendamentosLocaisPendentesDeEvidencia
    ) {
        /** Quantidades isoladas nao substituem cronologia nem classificacao de risco. */
        public long totalPresencasComprovadas() { return presencasIndividuais + presencasEmGrupo; }
        public long totalFaltasComprovadas() { return faltasIndividuais + faltasEmGrupo; }
    }

    @Transactional(readOnly = true)
    public Inventario inventariar(UUID pacienteId) {
        var diagnostico = linhaDoTempo.analisar(pacienteId);
        return new Inventario(
                contar(diagnostico, Fonte.ATENDIMENTO_INDIVIDUAL, true),
                contar(diagnostico, Fonte.ATENDIMENTO_INDIVIDUAL, false),
                contar(diagnostico, Fonte.SESSAO_GRUPO, true),
                contar(diagnostico, Fonte.SESSAO_GRUPO, false),
                diagnostico.agendamentosLocaisPendentesDeEvidencia());
    }

    private static long contar(Diagnostico diagnostico, Fonte fonte, boolean presente) {
        return diagnostico.eventosComOrigemLocal().stream()
                .filter(evento -> evento.fonte() == fonte && evento.presente() == presente)
                .count();
    }
}
