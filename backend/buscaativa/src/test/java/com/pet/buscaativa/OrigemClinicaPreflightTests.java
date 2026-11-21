package com.pet.buscaativa;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

import com.pet.buscaativa.migration.AgendaMigrationTool;
import com.pet.buscaativa.migration.OrigemClinicaPreflight;

/** Triagem de proveniencia read-only; jamais reconstrui unidade por referencia. */
class OrigemClinicaPreflightTests {
    @TempDir Path temp;

    @Test
    void relatorioNaoAlteraAgendaHistoricoOuAuditoriasAntigas() throws Exception {
        var source = LegacyMigrationFixture.legacy(temp.resolve("base"));
        AgendaMigrationTool.migrar(source);
        var columns = LegacyMigrationFixture.columns(source);
        var before = LegacyMigrationFixture.snapshot(source, columns);

        var report = OrigemClinicaPreflight.analisar(source);

        assertEquals(12, report.agendamentosTotais());
        assertEquals(12, report.agendamentosComIndicioNaUnidadeAtualDoUsuario());
        assertEquals(0, report.agendamentosSemIndicioDaUnidade());
        assertEquals(6, report.historicosTotais());
        assertEquals(6, report.historicosAssociadosAoAgendamentoDoMesmoPaciente());
        assertEquals(0, report.historicosAssociadosASessaoRealizadaComResponsavelRegistrado());
        assertEquals(0, report.historicosComFonteDePacienteInconsistente());
        assertEquals(before, LegacyMigrationFixture.snapshot(source, columns));
        assertFalse(report.toString().contains("41f5f5c9-89f2-496d-ae8e-b049dfae1428"));
    }

    @Test
    void divergenciaEntrePacienteDoHistoricoEAgendamentoNaoEReatribuida() throws Exception {
        var source = LegacyMigrationFixture.legacy(temp.resolve("inconsistente"));
        AgendaMigrationTool.migrar(source);
        var jdbc = new JdbcTemplate(source);
        jdbc.update("update tb_historico_paciente set paciente_id = 2 where id = 1");
        var columns = LegacyMigrationFixture.columns(source);
        var before = LegacyMigrationFixture.snapshot(source, columns);

        var report = OrigemClinicaPreflight.analisar(source);

        assertEquals(1, report.historicosComFonteDePacienteInconsistente());
        assertEquals(5, report.historicosAssociadosAoAgendamentoDoMesmoPaciente());
        assertTrue(report.necessitaRevisaoHistorica());
        assertEquals(before, LegacyMigrationFixture.snapshot(source, columns));
    }

    @Test
    void usuarioSemUnidadeNaoTransformaReferenciaEmProvaHistorica() throws Exception {
        var source = LegacyMigrationFixture.legacy(temp.resolve("sem-unidade"));
        AgendaMigrationTool.migrar(source);
        var jdbc = new JdbcTemplate(source);
        jdbc.update("update tb_usuario set unidade_atuacao=null where id=2");
        var columns = LegacyMigrationFixture.columns(source);
        var before = LegacyMigrationFixture.snapshot(source, columns);

        var report = OrigemClinicaPreflight.analisar(source);

        assertTrue(report.agendamentosSemIndicioDaUnidade() > 0);
        assertTrue(report.necessitaRevisaoHistorica());
        assertEquals(before, LegacyMigrationFixture.snapshot(source, columns));
    }

    @Test
    void historicoSemFontePermanecePendenteSemMudancas() throws Exception {
        var source = LegacyMigrationFixture.legacy(temp.resolve("sem-fonte"));
        AgendaMigrationTool.migrar(source);
        var jdbc = new JdbcTemplate(source);
        jdbc.update("update tb_historico_paciente set agendamento_id=null where id=1");
        var columns = LegacyMigrationFixture.columns(source);
        var before = LegacyMigrationFixture.snapshot(source, columns);

        var report = OrigemClinicaPreflight.analisar(source);

        assertTrue(report.historicosSemFonteDireta() > 0);
        assertTrue(report.necessitaRevisaoHistorica());
        assertEquals(before, LegacyMigrationFixture.snapshot(source, columns));
    }
}
