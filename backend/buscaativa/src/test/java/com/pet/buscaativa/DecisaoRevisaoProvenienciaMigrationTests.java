package com.pet.buscaativa;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;


/**
 * V13 em H2 persistido SINTETICO e isolado; nenhuma tabela em producao
 * e acessada. Verifica ausencia de backfill, FKs locais, decisao unica e
 * imutabilidade da trilha. NAO autoriza API, tela nem correcao clinica.
 */
class DecisaoRevisaoProvenienciaMigrationTests {
    @TempDir Path temp;

    @Test
    void v13NaoAlteraFatosLegadosNemCriaDecisoesArtificiais() throws Exception {
        var fonte = LegacyMigrationFixture.legacy(temp.resolve("decisao-v13-sem-backfill"));
        var colunasAnteriores = LegacyMigrationFixture.columns(fonte);
        var snapshot = LegacyMigrationFixture.snapshot(fonte, colunasAnteriores);
        var jdbc = new JdbcTemplate(fonte);

        assertEquals(13, LegacyMigrationFixture.migrarAte(fonte, "13").migrationsExecuted);
        assertEquals(snapshot, LegacyMigrationFixture.snapshot(fonte, colunasAnteriores));
        assertEquals(0, jdbc.queryForObject(
                "select count(*) from tb_decisao_revisao_proveniencia", Integer.class));
        assertEquals(0, jdbc.queryForObject(
                "select count(*) from tb_decisao_revisao_proveniencia_aud", Integer.class));
        assertEquals(0, jdbc.queryForObject(
                "select count(*) from tb_pendencia_revisao_proveniencia", Integer.class));
        assertEquals(0, LegacyMigrationFixture.migrarAte(fonte, "13").migrationsExecuted);
    }

    @Test
    void decisaoV13ExigeMesmoTicketPacienteUnidadePerfilEImutabilidade() throws Exception {
        var fonte = LegacyMigrationFixture.legacy(temp.resolve("decisao-v13-integridade"));
        LegacyMigrationFixture.migrarAte(fonte, "13");
        var jdbc = new JdbcTemplate(fonte);
        jdbc.update("""
            insert into tb_vinculo_assistencial(
                id_publico, paciente_id, unidade_atuacao, unidade_chave,
                status_paciente, version)
            values (?, 1, 2, 'CAPS_AD', 1, 0)
            """, UUID.randomUUID());
        Long vinculoId = jdbc.queryForObject(
                "select id from tb_vinculo_assistencial where paciente_id=1 and unidade_chave='CAPS_AD'",
                Long.class);

        UUID ticket = UUID.randomUUID();
        jdbc.update("""
            insert into tb_pendencia_revisao_proveniencia(
                id, vinculo_id, paciente_id, unidade_chave, tipo_registro,
                registro_id, situacao_evidencia, justificativa, solicitante_id,
                perfil_solicitante, solicitado_em, estado)
            values (?, ?, 1, 'CAPS_AD', 'AGENDAMENTO', 4,
                'COMPATIVEL_SOMENTE_PARA_CONFERENCIA',
                'Solicitacao sintetica para revisao humana da unidade',
                1, 'ADMINISTRADOR', CURRENT_TIMESTAMP, 'PENDENTE')
            """, ticket, vinculoId);

        String inserir = """
            insert into tb_decisao_revisao_proveniencia(
                id, pendencia_id, paciente_id, unidade_chave, decisao,
                justificativa, revisor_id, perfil_revisor, decidida_em)
            values (?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
            """;
        String justificativa = "Revisao humana sintetica apos conferencia de evidencias locais.";

        assertThrows(DataAccessException.class, () -> jdbc.update(inserir,
                UUID.randomUUID(), ticket, 1L, "CAPS_II", "APROVAR",
                justificativa, 1L, "ADMINISTRADOR"));
        assertThrows(DataAccessException.class, () -> jdbc.update(inserir,
                UUID.randomUUID(), ticket, 2L, "CAPS_AD", "REJEITAR",
                justificativa, 1L, "ADMINISTRADOR"));
        assertThrows(DataAccessException.class, () -> jdbc.update(inserir,
                UUID.randomUUID(), ticket, 1L, "CAPS_AD", "APROVAR",
                justificativa, 1L, "PROFISSIONAL"));
        assertThrows(DataAccessException.class, () -> jdbc.update(inserir,
                UUID.randomUUID(), ticket, 1L, "CAPS_AD", "ALTERAR_ORIGEM",
                justificativa, 1L, "ADMINISTRADOR"));
        assertThrows(DataAccessException.class, () -> jdbc.update(inserir,
                UUID.randomUUID(), ticket, 1L, "CAPS_AD", "REJEITAR",
                "sem motivo", 1L, "ADMINISTRADOR"));
        assertThrows(DataAccessException.class, () -> jdbc.update(inserir,
                UUID.randomUUID(), ticket, 1L, "CAPS_AD", "REJEITAR",
                "NAO INFORMADO", 1L, "ADMINISTRADOR"));
        assertThrows(DataAccessException.class, () -> jdbc.update(inserir,
                UUID.randomUUID(), ticket, 1L, "CAPS_AD", "APROVAR",
                "Não informado", 1L, "ADMINISTRADOR"));
        assertThrows(DataAccessException.class, () -> jdbc.update(inserir,
                UUID.randomUUID(), UUID.randomUUID(), 1L, "CAPS_AD", "APROVAR",
                justificativa, 1L, "ADMINISTRADOR"));

        UUID decisao = UUID.randomUUID();
        jdbc.update(inserir, decisao, ticket, 1L, "CAPS_AD", "REJEITAR",
                justificativa, 1L, "ADMINISTRADOR");
        assertThrows(DataAccessException.class, () -> jdbc.update(inserir,
                UUID.randomUUID(), ticket, 1L, "CAPS_AD", "APROVAR",
                justificativa, 1L, "ADMINISTRADOR"));
        assertThrows(DataAccessException.class, () -> jdbc.update(
                "update tb_decisao_revisao_proveniencia set justificativa=? where id=?",
                "Justificativa adulterada depois da decisao", decisao));
        assertThrows(DataAccessException.class, () -> jdbc.update(
                "delete from tb_decisao_revisao_proveniencia where id=?", decisao));

        assertEquals(1, jdbc.queryForObject(
                "select count(*) from tb_decisao_revisao_proveniencia where pendencia_id=?",
                Integer.class, ticket));
        assertEquals("REJEITAR", jdbc.queryForObject(
                "select decisao from tb_decisao_revisao_proveniencia where id=?",
                String.class, decisao));
        assertEquals("PENDENTE", jdbc.queryForObject(
                "select estado from tb_pendencia_revisao_proveniencia where id=?",
                String.class, ticket));
        assertEquals(0, jdbc.queryForObject(
                "select count(*) from tb_decisao_revisao_proveniencia_aud", Integer.class));
    }
}
