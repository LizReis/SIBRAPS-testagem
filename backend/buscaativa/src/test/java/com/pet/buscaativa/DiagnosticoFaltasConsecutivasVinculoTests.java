package com.pet.buscaativa;

import static org.junit.jupiter.api.Assertions.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.OptionalInt;

import org.junit.jupiter.api.Test;

import com.pet.buscaativa.services.impl.DiagnosticoFaltasConsecutivasVinculoService;
import com.pet.buscaativa.services.impl.LinhaDoTempoFrequenciasVinculoService;

/**
 * Caracterizacao pura, sem alterar persistencia nem calculos assistenciais.
 * A proveniencia local e previamente garantida pela linha do tempo de vinculo.
 */
class DiagnosticoFaltasConsecutivasVinculoTests {
    private static LinhaDoTempoFrequenciasVinculoService.Evento evento(
            int dia, int hora, boolean presente,
            LinhaDoTempoFrequenciasVinculoService.Fonte fonte, long id) {
        return new LinhaDoTempoFrequenciasVinculoService.Evento(
                LocalDateTime.of(2026, 11, dia, hora, 0), presente, fonte, id);
    }

    @Test
    void tresFaltasEmSequenciaGeramContagemTresSemDispararRisco() {
        var origem = LinhaDoTempoFrequenciasVinculoService.Fonte.ATENDIMENTO_INDIVIDUAL;
        var fatos = List.of(evento(10, 9, false, origem, 10),
                evento(12, 9, false, origem, 12),
                evento(14, 9, false, origem, 14));
        var resultado = DiagnosticoFaltasConsecutivasVinculoService.calcular(
                new LinhaDoTempoFrequenciasVinculoService.Diagnostico(fatos, 0, 0));
        assertEquals(OptionalInt.of(3), resultado.faltasConsecutivasAnalisaveis());
        assertTrue(resultado.ultimaPresencaComprovada().isEmpty());
        assertTrue(resultado.podeAvaliarSequencia());
    }

    @Test
    void presencaRetroativaEntreFaltasRecompoeSequenciaCronologica() {
        var origem = LinhaDoTempoFrequenciasVinculoService.Fonte.ATENDIMENTO_INDIVIDUAL;
        var fatos = List.of(evento(15, 8, false, origem, 15),
                evento(11, 8, false, origem, 11),
                evento(12, 8, true, origem, 12),
                evento(13, 8, false, origem, 13));
        var resultado = DiagnosticoFaltasConsecutivasVinculoService.calcular(
                new LinhaDoTempoFrequenciasVinculoService.Diagnostico(fatos, 0, 0));
        assertEquals(OptionalInt.of(2), resultado.faltasConsecutivasAnalisaveis());
        assertEquals(LocalDateTime.of(2026, 11, 12, 8, 0),
                resultado.ultimaPresencaComprovada().orElseThrow());
    }

    @Test
    void modalidadesDoMesmoVinculoRespeitamDesempateLegado() {
        var individual = LinhaDoTempoFrequenciasVinculoService.Fonte.ATENDIMENTO_INDIVIDUAL;
        var grupo = LinhaDoTempoFrequenciasVinculoService.Fonte.SESSAO_GRUPO;
        var fatos = List.of(evento(20, 10, true, grupo, 2),
                evento(20, 10, false, individual, 1),
                evento(21, 10, false, individual, 3));
        var resultado = DiagnosticoFaltasConsecutivasVinculoService.calcular(
                new LinhaDoTempoFrequenciasVinculoService.Diagnostico(fatos, 0, 0));
        assertEquals(OptionalInt.of(1), resultado.faltasConsecutivasAnalisaveis());
    }

    @Test
    void evidenciaSemOrigemBloqueiaContagemConclusiva() {
        var individual = LinhaDoTempoFrequenciasVinculoService.Fonte.ATENDIMENTO_INDIVIDUAL;
        var fatos = List.of(evento(22, 9, false, individual, 22));
        var resultado = DiagnosticoFaltasConsecutivasVinculoService.calcular(
                new LinhaDoTempoFrequenciasVinculoService.Diagnostico(fatos, 1, 0));
        assertTrue(resultado.faltasConsecutivasAnalisaveis().isEmpty());
        assertTrue(resultado.revisaoHistoricaNecessaria());
        assertFalse(resultado.podeAvaliarSequencia());
        assertEquals(1, resultado.eventosLocaisComprovados());
        assertEquals(1, resultado.agendamentosSemOrigemComFrequencia());
    }

    @Test
    void semFatosNemPendenciasContagemZeroNaoInventaPresenca() {
        var resultado = DiagnosticoFaltasConsecutivasVinculoService.calcular(
                new LinhaDoTempoFrequenciasVinculoService.Diagnostico(List.of(), 0, 0));
        assertEquals(OptionalInt.of(0), resultado.faltasConsecutivasAnalisaveis());
        assertTrue(resultado.ultimaPresencaComprovada().isEmpty());
    }
}
