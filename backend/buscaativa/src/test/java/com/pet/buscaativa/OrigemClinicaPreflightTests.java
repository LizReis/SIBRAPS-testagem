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
        assertEquals(0, report.sessoesRealizadasTotais());
        assertEquals(0, report.sessoesRealizadasSemParticipantes());
        assertEquals(0, report.participacoesComFrequenciaNaoRegistrada());
        // Todos os profissionais possuem indicio de unidade atual, mas
        // nenhum desses metadados V8 comprova unidade historica.
        assertTrue(report.necessitaRevisaoHistorica());
        assertEquals(before, LegacyMigrationFixture.snapshot(source, columns));
        assertFalse(report.toString().contains("41f5f5c9-89f2-496d-ae8e-b049dfae1428"));
    }

    @Test
    void metadadosLegadosSemAlertasNaoSignificamOrigemHistoricaComprovada() {
        var legadoComIndicios = new OrigemClinicaPreflight.Relatorio(
                12, 12, 0, 6, 6, 0, 0, 0, 0, 0, 0, 0);
        assertTrue(legadoComIndicios.necessitaRevisaoHistorica(),
                "Origem historica de fatos V8 nao e provada pela unidade atual.");

        var vazio = new OrigemClinicaPreflight.Relatorio(
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
        assertFalse(vazio.necessitaRevisaoHistorica(),
                "Sem fatos legados, nenhum registro demanda atribuicao.");
    }

    @Test
    void sessaoLegadaRealizadaComResponsavelNaoComprovaUnidadeHistorica() throws Exception {
        // Um fato coletivo realizado existe sem historico individual
        // associado; conhecer o responsavel nao comprova a unidade na data.
        var relatorioSomenteSessao = new OrigemClinicaPreflight.Relatorio(
                0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 0);
        assertTrue(relatorioSomenteSessao.necessitaRevisaoHistorica());

        var fonte = LegacyMigrationFixture.legacy(temp.resolve("sessao-realizada"));
        AgendaMigrationTool.migrar(fonte);
        var jdbc = new JdbcTemplate(fonte);
        jdbc.update("""
                insert into tb_grupo_terapeutico(id, ativo, coordenador_id, tema, recorrencia)
                values (9001, true, 2, 'Grupo legado sintetico', 'UNICA')
                """);
        jdbc.update("""
                insert into tb_sessao_grupo(
                    id, grupo_id, status, responsavel_atendimento_id,
                    responsabilidade_pendente)
                values (9002, 9001, 'REALIZADA', 2, false)
                """);

        var colunas = LegacyMigrationFixture.columns(fonte);
        var antes = LegacyMigrationFixture.snapshot(fonte, colunas);
        var relatorio = OrigemClinicaPreflight.analisar(fonte);
        assertEquals(1, relatorio.sessoesRealizadasTotais());
        assertEquals(1, relatorio.sessoesRealizadasSemParticipantes());
        assertEquals(0, relatorio.participacoesComFrequenciaNaoRegistrada());
        assertEquals(0, relatorio.sessoesRealizadasSemResponsavelConfirmado());
        assertTrue(relatorio.necessitaRevisaoHistorica());
        assertEquals(antes, LegacyMigrationFixture.snapshot(fonte, colunas));
    }

    @Test
    void frequenciaNaoRegistradaEmSessaoRealizadaExigeConferenciaSemEscrita() throws Exception {
        var fonte = LegacyMigrationFixture.legacy(temp.resolve("frequencia-coletiva"));
        AgendaMigrationTool.migrar(fonte);
        var jdbc = new JdbcTemplate(fonte);
        jdbc.update("""
                insert into tb_grupo_terapeutico(id, ativo, coordenador_id, tema, recorrencia)
                values (9101, true, 2, 'Grupo sintetico de conferencia', 'UNICA')
                """);
        jdbc.update("""
                insert into tb_sessao_grupo(
                    id, grupo_id, status, responsavel_atendimento_id,
                    responsabilidade_pendente)
                values (9102, 9101, 'REALIZADA', 2, false)
                """);
        jdbc.update("""
                insert into tb_sessao_grupo_participante(
                    sessao_grupo_id, paciente_id, status_presenca)
                values (9102, 1, 'NAO_REGISTRADA')
                """);
        // O registro NAO_REGISTRADA de sessao ainda AGENDADA nao deve
        // contar como participacao de uma sessao realizada.
        jdbc.update("""
                insert into tb_sessao_grupo(id, grupo_id, status)
                values (9103, 9101, 'AGENDADA')
                """);
        jdbc.update("""
                insert into tb_sessao_grupo_participante(
                    sessao_grupo_id, paciente_id, status_presenca)
                values (9103, 1, 'NAO_REGISTRADA')
                """);
        var colunas = LegacyMigrationFixture.columns(fonte);
        var antes = LegacyMigrationFixture.snapshot(fonte, colunas);
        var relatorio = OrigemClinicaPreflight.analisar(fonte);
        assertEquals(1, relatorio.sessoesRealizadasTotais());
        assertEquals(0, relatorio.sessoesRealizadasSemParticipantes());
        assertEquals(1, relatorio.participacoesComFrequenciaNaoRegistrada());
        assertTrue(relatorio.necessitaRevisaoHistorica());
        assertEquals(antes, LegacyMigrationFixture.snapshot(fonte, colunas));
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
