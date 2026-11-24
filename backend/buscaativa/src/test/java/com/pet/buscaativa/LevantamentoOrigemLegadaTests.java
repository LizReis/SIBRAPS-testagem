package com.pet.buscaativa;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Map;
import java.util.TreeMap;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import com.pet.buscaativa.migration.AgendaMigrationTool;
import com.pet.buscaativa.migration.CadastroUnicoPreflight;
import com.pet.buscaativa.migration.OrigemClinicaPreflight;
import com.pet.buscaativa.migration.V2__identificar_frequencias_legadas;
import com.pet.buscaativa.migration.V6__identificar_responsabilidade_historica;
import com.pet.buscaativa.services.impl.DiagnosticoConsolidadoMigracaoClinicaService;
import com.pet.buscaativa.services.impl.DiagnosticoConsolidadoMigracaoClinicaService.Proveniencia;
import com.pet.buscaativa.services.impl.DiagnosticoConsolidadoMigracaoClinicaService.TipoRegistro;

class LevantamentoOrigemLegadaTests {
    @TempDir Path temp;

    private static final Map<TipoRegistro, String> TABELAS = Map.of(
            TipoRegistro.AGENDAMENTO, "tb_agendamento",
            TipoRegistro.HISTORICO, "tb_historico_paciente",
            TipoRegistro.GRUPO, "tb_grupo_terapeutico",
            TipoRegistro.SESSAO, "tb_sessao_grupo",
            TipoRegistro.PARTICIPACAO, "tb_sessao_grupo_participante");

    @ParameterizedTest
    @CsvSource({
            "legacy-agenda-v0.sql, 6, 0, 0, 0, 0",
            "group-responsibility-v4.sql, 7, 1, 3, 2, 1"
    })
    void inventariaCadaFixtureRestauradaEmV8EEmV13(String fixture, long historicos, long grupos,
            long sessoes, long participacoes, long responsabilidadesPendentes) throws Exception {
        var original = LegacyMigrationFixture.fromSql(temp.resolve("original"), fixture);
        var esperado = Map.of(TipoRegistro.AGENDAMENTO, 12L, TipoRegistro.HISTORICO, historicos,
                TipoRegistro.GRUPO, grupos, TipoRegistro.SESSAO, sessoes, TipoRegistro.PARTICIPACAO, participacoes);
        assertThat(totais(original)).isEqualTo(esperado);

        var v8 = restaurar(original, "restaurada");
        Flyway.configure().dataSource(v8).table("sibraps_agenda_schema_history").locations("classpath:agenda-migration/h2")
                .javaMigrations(new V2__identificar_frequencias_legadas(), new V6__identificar_responsabilidade_historica())
                .baselineOnMigrate(true).baselineVersion("0").target("8").load().migrate();
        assertThat(versao(v8)).isEqualTo("8");
        assertThat(totais(v8)).isEqualTo(esperado);
        var leituraV8 = somenteLeitura(v8);
        var colunasV8 = LegacyMigrationFixture.columns(leituraV8);
        var antesV8 = LegacyMigrationFixture.snapshot(leituraV8, colunasV8);
        assertThat(colunasV8.get("TB_AGENDAMENTO")).doesNotContain("UNIDADE_ORIGEM_CHAVE");
        var cadastroV8 = CadastroUnicoPreflight.analisar(leituraV8);
        var origemV8 = OrigemClinicaPreflight.analisar(leituraV8);
        assertThat(cadastroV8.vinculosAssistenciaisRegistrados()).isZero();
        assertThat(origemV8.agendamentosComIndicioNaUnidadeAtualDoUsuario()).isEqualTo(12);
        assertThat(origemV8.historicosAssociadosAoAgendamentoDoMesmoPaciente()).isEqualTo(6);
        assertThat(origemV8.historicosAssociadosASessaoRealizadaComResponsavelRegistrado()).isEqualTo(grupos);
        assertThat(origemV8.historicosSemFonteDireta()).isZero();
        assertThat(origemV8.historicosComFonteDePacienteInconsistente()).isZero();
        assertThat(origemV8.sessoesRealizadasSemResponsavelConfirmado()).isEqualTo(responsabilidadesPendentes);
        assertThat(origemV8.necessitaRevisaoHistorica()).isTrue();
        var jdbcV8 = new JdbcTemplate(leituraV8);
        assertThat(contar(jdbcV8, """
                select count(*) from tb_agendamento where migracao_pendente = false
                    and frequencia_assistencial in ('PRESENTE', 'FALTOU')
                """)).isEqualTo(6);
        assertThat(contar(jdbcV8, "select count(*) from tb_agendamento where migracao_pendente = true"))
                .isEqualTo(4);
        assertThat(LegacyMigrationFixture.columns(leituraV8)).isEqualTo(colunasV8);
        assertThat(LegacyMigrationFixture.snapshot(leituraV8, colunasV8)).isEqualTo(antesV8);

        var copia = restaurar(v8, "copia-v13");
        assertThat(LegacyMigrationFixture.migrarAte(copia, "13").migrationsExecuted).isEqualTo(5);
        var leituraV13 = somenteLeitura(copia);
        assertThat(versao(leituraV13)).isEqualTo("13");
        var colunasV13 = LegacyMigrationFixture.columns(leituraV13);
        var antesV13 = LegacyMigrationFixture.snapshot(leituraV13, colunasV13);
        var colunasClinicasV8 = new TreeMap<>(colunasV8);
        colunasClinicasV8.remove("sibraps_agenda_schema_history");
        var dadosV8 = new TreeMap<>(antesV8);
        dadosV8.remove("sibraps_agenda_schema_history");
        assertThat(LegacyMigrationFixture.snapshot(leituraV13, colunasClinicasV8)).isEqualTo(dadosV8);
        assertThat(antesV13).containsKeys("TB_PENDENCIA_REVISAO_PROVENIENCIA", "TB_PENDENCIA_REVISAO_PROVENIENCIA_AUD",
                "TB_DECISAO_REVISAO_PROVENIENCIA", "TB_DECISAO_REVISAO_PROVENIENCIA_AUD");
        antesV13.forEach((tabela, linhas) -> {
            if (tabela.contains("REVISAO_PROVENIENCIA")) assertThat(linhas).isEmpty();
        });

        var copiaV16 = restaurar(leituraV13, "copia-v16");
        assertThat(AgendaMigrationTool.migrar(copiaV16).migrationsExecuted).isEqualTo(3);
        var leituraV16 = somenteLeitura(copiaV16);
        var colunasV16 = LegacyMigrationFixture.columns(leituraV16);
        var antesV16 = LegacyMigrationFixture.snapshot(leituraV16, colunasV16);
        CorrectiveMigrationApplication.application(leituraV16)
                .withPropertyValues("spring.profiles.active=test,diagnostico-migracao")
                .run(contexto -> {
                    assertThat(contexto).hasNotFailed();
                    var relatorio = contexto.getBean(DiagnosticoConsolidadoMigracaoClinicaService.class).analisar();
                    esperado.forEach((tipo, total) ->
                            assertThat(relatorio.proveniencia()).containsEntry(tipo, new Proveniencia(0, total, 0)));
                    assertThat(relatorio.cadastro()).isEqualTo(cadastroV8);
                    assertThat(relatorio.prontidaoPorVinculo()).isEmpty();
                    assertThat(relatorio.migracaoAutorizada()).isFalse();
                    assertThat(relatorio.frequenciasExcluidasDoEnsaio()).isEqualTo(10 + participacoes);
                });

        assertThat(LegacyMigrationFixture.snapshot(leituraV16, colunasV16)).isEqualTo(antesV16);
        conferirPistasDeAuditoria(leituraV13, grupos);
        assertThat(OrigemClinicaPreflight.analisar(leituraV13)).isEqualTo(origemV8);
        assertThat(LegacyMigrationFixture.columns(leituraV13)).isEqualTo(colunasV13);
        assertThat(LegacyMigrationFixture.snapshot(leituraV13, colunasV13)).isEqualTo(antesV13);
        assertThat(LegacyMigrationFixture.snapshot(leituraV8, colunasV8)).isEqualTo(antesV8);
    }

    private void conferirPistasDeAuditoria(DataSource fonte, long grupos) {
        var jdbc = new JdbcTemplate(fonte);
        assertThat(contar(jdbc, """
                select count(*) from tb_auditoria_correcao_frequencia a
                join tb_sessao_grupo s on s.id = a.sessao_id
                where a.unidade is not null and a.ator_id <> a.responsavel_id
                  and cast(a.ocorrido_em as date) > s.data_sessao
                """)).isEqualTo(2 * grupos);
        assertThat(contar(jdbc, "select count(*) from tb_usuario_aud")).isEqualTo(3);
        assertThat(contar(jdbc, """
                select count(*) from tb_usuario u
                where (select count(*) from tb_usuario_aud a where a.id = u.id and a.revtype = 0) = 1
                """)).isEqualTo(3);
        var agendasComRevisaoAnteriorOuNoDia = contar(jdbc, """
                select count(*) from tb_agendamento a
                where exists (
                    select 1 from tb_usuario_aud u join revinfo r on r.rev = u.rev
                    where u.id = a.usuario_id and u.revtype = 0
                      and cast(dateadd('MILLISECOND', r.revtstmp, timestamp '1969-12-31 21:00:00') as date)
                          <= a.data_agendamento
                )
                """);
        var historicosColetivosComAutorDistinto = contar(jdbc, """
                select count(*) from tb_historico_paciente h
                join tb_sessao_grupo s on s.id = h.sessao_grupo_id
                where h.profissional_id <> s.responsavel_atendimento_id
                """);
        assertThat(historicosColetivosComAutorDistinto).isEqualTo(grupos);
        assertThat(contar(jdbc, """
                select count(*) from tb_sessao_grupo s join tb_grupo_terapeutico g on g.id = s.grupo_id
                where s.origem_responsabilidade = 'ENVERS_TRANSICAO_REALIZADA'
                    and s.responsavel_atendimento_id <> g.coordenador_id
                """)).isEqualTo(grupos);
        assertThat(agendasComRevisaoAnteriorOuNoDia).isZero();
    }

    private DriverManagerDataSource restaurar(DriverManagerDataSource origem, String nome) throws Exception {
        var arquivoSql = temp.resolve(nome + ".sql");
        LegacyMigrationFixture.backup(origem, arquivoSql);
        var copia = LegacyMigrationFixture.restore(temp.resolve(nome), arquivoSql);
        var colunas = LegacyMigrationFixture.columns(origem);
        assertThat(LegacyMigrationFixture.columns(copia)).isEqualTo(colunas);
        assertThat(LegacyMigrationFixture.snapshot(copia, colunas))
                .isEqualTo(LegacyMigrationFixture.snapshot(origem, colunas));
        return copia;
    }

    private static DriverManagerDataSource somenteLeitura(DriverManagerDataSource banco) {
        return new DriverManagerDataSource(banco.getUrl() + ";ACCESS_MODE_DATA=r", banco.getUsername(), banco.getPassword());
    }

    private static String versao(DataSource banco) {
        return new JdbcTemplate(banco).queryForObject("""
                select "version" from "sibraps_agenda_schema_history"
                where "success" = true and "version" is not null order by "installed_rank" desc limit 1
                """, String.class);
    }

    private static Map<TipoRegistro, Long> totais(DataSource banco) {
        var jdbc = new JdbcTemplate(banco);
        var resultado = new EnumMap<TipoRegistro, Long>(TipoRegistro.class);
        TABELAS.forEach((tipo, tabela) -> resultado.put(tipo, contar(jdbc, "select count(*) from " + tabela)));
        return resultado;
    }

    private static long contar(JdbcTemplate jdbc, String consulta) {
        return jdbc.queryForObject(consulta, Long.class);
    }
}
