package com.pet.buscaativa;

import static org.junit.jupiter.api.Assertions.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

import org.junit.jupiter.api.Test;

import com.pet.buscaativa.entities.enums.ClassificacaoRisco;
import com.pet.buscaativa.entities.enums.StatusPaciente;
import com.pet.buscaativa.entities.enums.TipoAcompanhamento;
import com.pet.buscaativa.services.impl.ComparadorPoliticaRiscoVinculo;
import com.pet.buscaativa.services.impl.DiagnosticoFaltasConsecutivasVinculoService;
import com.pet.buscaativa.services.impl.LinhaDoTempoFrequenciasVinculoService;

/** Caracterizacao dos limites vigentes, sem escrita de estado clinico. */
class ComparadorPoliticaRiscoVinculoTests {
    private static final LocalDate HOJE = LocalDate.of(2026, 11, 22);

    private DiagnosticoFaltasConsecutivasVinculoService.Resultado diag(
            int faltas, LocalDate ultima, boolean incerto) {
        return new DiagnosticoFaltasConsecutivasVinculoService.Resultado(
                incerto ? OptionalInt.empty() : OptionalInt.of(faltas),
                Optional.ofNullable(ultima).map(d -> d.atStartOfDay()),
                faltas, incerto, incerto ? 1 : 0, 0, 0, false);
    }

    private Optional<ClassificacaoRisco> risco(int faltas, LocalDate ultima,
            TipoAcompanhamento tipo) {
        return ComparadorPoliticaRiscoVinculo.comparar(
                diag(faltas, ultima, false), StatusPaciente.ATIVO, tipo, false, HOJE);
    }

    @Test
    void doisFaltasAmareloTresVermelhoSemAlterarLimiar() {
        assertEquals(Optional.of(ClassificacaoRisco.VERDE), risco(1, null, TipoAcompanhamento.INDIVIDUAL));
        assertEquals(Optional.of(ClassificacaoRisco.AMARELO), risco(2, null, TipoAcompanhamento.INDIVIDUAL));
        assertEquals(Optional.of(ClassificacaoRisco.VERMELHO), risco(3, null, TipoAcompanhamento.INDIVIDUAL));
    }

    @Test
    void grupoTemLimiaresDeDiasEstritosEm15E30() {
        assertEquals(Optional.of(ClassificacaoRisco.VERDE),
                risco(0, HOJE.minusDays(15), TipoAcompanhamento.GRUPO_TERAPEUTICO));
        assertEquals(Optional.of(ClassificacaoRisco.AMARELO),
                risco(0, HOJE.minusDays(16), TipoAcompanhamento.GRUPO_TERAPEUTICO));
        assertEquals(Optional.of(ClassificacaoRisco.AMARELO),
                risco(0, HOJE.minusDays(30), TipoAcompanhamento.GRUPO_TERAPEUTICO));
        assertEquals(Optional.of(ClassificacaoRisco.VERMELHO),
                risco(0, HOJE.minusDays(31), TipoAcompanhamento.GRUPO_TERAPEUTICO));
        assertEquals(Optional.of(ClassificacaoRisco.VERMELHO),
                risco(0, HOJE.minusDays(31), TipoAcompanhamento.AMBOS));
    }

    @Test
    void acompanhamentoIndividualTemLimiaresDe60E120DiasEstritos() {
        assertEquals(Optional.of(ClassificacaoRisco.VERDE),
                risco(0, HOJE.minusDays(60), TipoAcompanhamento.INDIVIDUAL));
        assertEquals(Optional.of(ClassificacaoRisco.AMARELO),
                risco(0, HOJE.minusDays(61), TipoAcompanhamento.INDIVIDUAL));
        assertEquals(Optional.of(ClassificacaoRisco.AMARELO),
                risco(0, HOJE.minusDays(120), TipoAcompanhamento.INDIVIDUAL));
        assertEquals(Optional.of(ClassificacaoRisco.VERMELHO),
                risco(0, HOJE.minusDays(121), TipoAcompanhamento.INDIVIDUAL));
    }

    @Test
    void dadosDoVinculoIncompletosOuHistoricoIncertoNaoCriamRisco() {
        var d = diag(3, HOJE.minusDays(121), true);
        assertEquals(Optional.empty(), ComparadorPoliticaRiscoVinculo.comparar(
                d, StatusPaciente.ATIVO, TipoAcompanhamento.INDIVIDUAL, false, HOJE));
        assertEquals(Optional.empty(), ComparadorPoliticaRiscoVinculo.comparar(
                diag(3, null, false), StatusPaciente.ATIVO, null, false, HOJE));
        assertEquals(Optional.empty(), ComparadorPoliticaRiscoVinculo.comparar(
                diag(3, null, false), StatusPaciente.ATIVO, TipoAcompanhamento.INDIVIDUAL, null, HOJE));
        assertEquals(Optional.empty(), ComparadorPoliticaRiscoVinculo.comparar(
                diag(3, null, false), StatusPaciente.ATIVO, TipoAcompanhamento.INDIVIDUAL, true, HOJE));
    }

    @Test
    void acompanhamentoInativoMantemVerdeComDadosComprovados() {
        assertEquals(Optional.of(ClassificacaoRisco.VERDE),
                ComparadorPoliticaRiscoVinculo.comparar(
                        diag(3, HOJE.minusDays(200), false),
                        StatusPaciente.INATIVO, TipoAcompanhamento.INDIVIDUAL, false, HOJE));
    }

    @Test
    void correcaoRetroativaDaPresencaDiminuiFaltasSemCriarEventoNoBanco() {
        var individual = LinhaDoTempoFrequenciasVinculoService.Fonte.ATENDIMENTO_INDIVIDUAL;
        var antes = List.of(
                new LinhaDoTempoFrequenciasVinculoService.Evento(LocalDateTime.of(2026, 11, 10, 9, 0), false, individual, 1L),
                new LinhaDoTempoFrequenciasVinculoService.Evento(LocalDateTime.of(2026, 11, 11, 9, 0), false, individual, 2L),
                new LinhaDoTempoFrequenciasVinculoService.Evento(LocalDateTime.of(2026, 11, 12, 9, 0), false, individual, 3L));
        var depois = List.of(antes.get(0),
                new LinhaDoTempoFrequenciasVinculoService.Evento(LocalDateTime.of(2026, 11, 11, 9, 0), true, individual, 2L),
                antes.get(2));
        var a = DiagnosticoFaltasConsecutivasVinculoService.calcular(
                new LinhaDoTempoFrequenciasVinculoService.Diagnostico(antes, 0, 0, 0, 0));
        var b = DiagnosticoFaltasConsecutivasVinculoService.calcular(
                new LinhaDoTempoFrequenciasVinculoService.Diagnostico(depois, 0, 0, 0, 0));
        assertEquals(Optional.of(ClassificacaoRisco.VERMELHO),
                ComparadorPoliticaRiscoVinculo.comparar(a, StatusPaciente.ATIVO,
                        TipoAcompanhamento.INDIVIDUAL, false, HOJE));
        assertEquals(Optional.of(ClassificacaoRisco.VERDE),
                ComparadorPoliticaRiscoVinculo.comparar(b, StatusPaciente.ATIVO,
                        TipoAcompanhamento.INDIVIDUAL, false, HOJE));
    }
}
