package com.pet.buscaativa;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.Optional;
import java.util.OptionalInt;

import org.junit.jupiter.api.Test;

import com.pet.buscaativa.entities.VinculoAssistencial;
import com.pet.buscaativa.entities.enums.StatusPaciente;
import com.pet.buscaativa.entities.enums.TipoAcompanhamento;
import com.pet.buscaativa.services.impl.DiagnosticoFaltasConsecutivasVinculoService;
import com.pet.buscaativa.services.impl.DiagnosticoProntidaoEstadoVinculoService;
import com.pet.buscaativa.services.impl.DiagnosticoProntidaoEstadoVinculoService.Situacao;

/** Caracteriza preparacao de estado local sem mutacoes nem backfill. */
class DiagnosticoProntidaoEstadoVinculoTests {
    private VinculoAssistencial vinculo(Integer faltas, Boolean pendente, boolean temTipo) {
        VinculoAssistencial v = mock(VinculoAssistencial.class);
        when(v.getStatusPaciente()).thenReturn(StatusPaciente.ATIVO);
        when(v.getCountFaltas()).thenReturn(faltas);
        when(v.getIndicadoresPendentes()).thenReturn(pendente);
        if (temTipo) when(v.getTipoAcompanhamento()).thenReturn(TipoAcompanhamento.INDIVIDUAL);
        return v;
    }

    private DiagnosticoFaltasConsecutivasVinculoService.Resultado diagnostico(
            Integer faltas, long semOrigem) {
        return new DiagnosticoFaltasConsecutivasVinculoService.Resultado(
                faltas == null ? OptionalInt.empty() : OptionalInt.of(faltas),
                Optional.empty(), 3, semOrigem > 0, semOrigem, 0);
    }

    @Test
    void origemIncertaImpedeProntidaoMesmoComValoresLocaisPresentes() {
        var rel = DiagnosticoProntidaoEstadoVinculoService.avaliar(
                vinculo(3, false, true), diagnostico(null, 2));
        assertEquals(Situacao.REVISAR_PROVENIENCIA_HISTORICA, rel.situacao());
        assertTrue(rel.requerRevisao());
        assertEquals(2, rel.agendamentosHistoricosSemOrigem());
    }

    @Test
    void vinculoNovoSemEstadoClinicoNaoCopiaContadorGlobalDoPaciente() {
        var rel = DiagnosticoProntidaoEstadoVinculoService.avaliar(
                vinculo(null, null, false), diagnostico(0, 0));
        assertEquals(Situacao.RECONCILIAR_ESTADO_LOCAL, rel.situacao());
        assertTrue(rel.faltaContagemLocal());
        assertTrue(rel.faltaTipoAcompanhamentoLocal());
        assertTrue(rel.faltaIndicadorDePendenciaLocal());
    }

    @Test
    void contadorDivergenteRequerRevisaoAntesDeQualquerCorte() {
        var rel = DiagnosticoProntidaoEstadoVinculoService.avaliar(
                vinculo(3, false, true), diagnostico(1, 0));
        assertEquals(Situacao.REVISAR_DIVERGENCIA_DE_FALTAS, rel.situacao());
        assertTrue(rel.contagemLocalDivergenteDaSequencia());
    }

    @Test
    void estadosCoerentesAindaSaoSomenteParaTestes() {
        var rel = DiagnosticoProntidaoEstadoVinculoService.avaliar(
                vinculo(1, false, true), diagnostico(1, 0));
        assertEquals(Situacao.COERENTE_APENAS_PARA_TESTES, rel.situacao());
        assertFalse(rel.requerRevisao());
        assertEquals(3, rel.eventosLocaisComprovados());
    }
}
