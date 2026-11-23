package com.pet.buscaativa;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.LocalDate;
import java.util.Optional;
import java.util.OptionalInt;

import org.junit.jupiter.api.Test;

import com.pet.buscaativa.entities.VinculoAssistencial;
import com.pet.buscaativa.entities.enums.ClassificacaoRisco;
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
        when(v.getClassificacaoRisco()).thenReturn(ClassificacaoRisco.VERDE);
        when(v.getGatilhoVisitaAcionado()).thenReturn(false);
        when(v.getCountFaltas()).thenReturn(faltas);
        when(v.getIndicadoresPendentes()).thenReturn(pendente);
        if (temTipo) when(v.getTipoAcompanhamento()).thenReturn(TipoAcompanhamento.INDIVIDUAL);
        return v;
    }

    private DiagnosticoFaltasConsecutivasVinculoService.Resultado diagnostico(
            Integer faltas, long semOrigem) {
        return new DiagnosticoFaltasConsecutivasVinculoService.Resultado(
                faltas == null ? OptionalInt.empty() : OptionalInt.of(faltas),
                Optional.empty(), 3, semOrigem > 0, semOrigem, 0, 0, false);
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

    @Test
    void indicadorLocalMarcadoComoPendenteImpedeProntidaoComDemaisCamposCoerentes() {
        var relatorio = DiagnosticoProntidaoEstadoVinculoService.avaliar(
                vinculo(1, true, true), diagnostico(1, 0));
        assertEquals(Situacao.REVISAR_INDICADORES_LOCAIS_PENDENTES, relatorio.situacao());
        assertFalse(relatorio.faltaIndicadorDePendenciaLocal());
        assertTrue(relatorio.requerRevisao());
    }

    @Test
    void ultimaPresencaDivergenteImpedeCoerenciaMesmoComContagemIgual() {
        var vinculo = vinculo(1, false, true);
        when(vinculo.getDataUltimaPresenca()).thenReturn(LocalDate.of(2026, 11, 1));
        var comprovada = LocalDate.of(2026, 11, 2);
        var frequencias = new DiagnosticoFaltasConsecutivasVinculoService.Resultado(
                OptionalInt.of(1), Optional.of(comprovada.atStartOfDay()), 2, false, 0, 0, 0, false);

        var relatorio = DiagnosticoProntidaoEstadoVinculoService.avaliar(vinculo, frequencias);

        assertEquals(Situacao.REVISAR_DIVERGENCIA_DE_ULTIMA_PRESENCA, relatorio.situacao());
        assertTrue(relatorio.ultimaPresencaLocalDivergente());
        assertFalse(relatorio.contagemLocalDivergenteDaSequencia());
    }
}
