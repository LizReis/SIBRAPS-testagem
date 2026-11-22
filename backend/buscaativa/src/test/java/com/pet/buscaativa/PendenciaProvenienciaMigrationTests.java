package com.pet.buscaativa;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import com.pet.buscaativa.migration.AgendaMigrationTool;

/**
 * Executa V12 num H2 EM ARQUIVO com fixture legada real; nao executa em
 * ambiente assistencial, nao cria tickets por endpoint e nao muda prontuarios.
 */
class PendenciaProvenienciaMigrationTests {
    @TempDir Path temp;

    @Test
    void v12NaoMigraFatosAntigosNemCriaPendenciasAutomaticas() throws Exception {
        var source = LegacyMigrationFixture.legacy(temp.resolve("pendencia-v12"));
        var beforeColumns = LegacyMigrationFixture.columns(source);
        var before = LegacyMigrationFixture.snapshot(source, beforeColumns);
        var jdbc = new JdbcTemplate(source);

        assertEquals(13, AgendaMigrationTool.migrar(source).migrationsExecuted);
        assertEquals(before, LegacyMigrationFixture.snapshot(source, beforeColumns));
        assertEquals(0, jdbc.queryForObject(
                "select count(*) from tb_pendencia_revisao_proveniencia", Integer.class));
        assertEquals(0, jdbc.queryForObject(
                "select count(*) from tb_pendencia_revisao_proveniencia_aud", Integer.class));
        assertEquals(0, jdbc.queryForObject(
                "select count(*) from tb_vinculo_assistencial", Integer.class));
        assertEquals(0, AgendaMigrationTool.migrar(source).migrationsExecuted);
    }

    @Test
    void chaveDeUnidadePacientePapelEImutabilidadeProtegidosPeloBanco() throws Exception {
        var source = LegacyMigrationFixture.legacy(temp.resolve("constraints-v12"));
        AgendaMigrationTool.migrar(source);
        var jdbc = new JdbcTemplate(source);

        jdbc.update("""
                insert into tb_vinculo_assistencial(
                    id_publico, paciente_id, unidade_atuacao, unidade_chave,
                    status_paciente, version)
                values (?, 1, 2, 'CAPS_AD', 1, 0)
                """, UUID.randomUUID());
        Long vinculo = jdbc.queryForObject(
                "select id from tb_vinculo_assistencial where paciente_id=1 and unidade_chave='CAPS_AD'",
                Long.class);
        assertNotNull(vinculo);
        var sql = """
                insert into tb_pendencia_revisao_proveniencia(
                    id, vinculo_id, paciente_id, unidade_chave, tipo_registro, registro_id,
                    situacao_evidencia, justificativa, solicitante_id,
                    perfil_solicitante, solicitado_em, estado)
                values(?, ?, ?, ?, 'AGENDAMENTO', 4,
                    'REVISAR_ORIGEM_DESCONHECIDA',
                    'Solicitação sintética para conferência documental',
                    1, ?, CURRENT_TIMESTAMP, ?)
                """;
        assertThrows(DataAccessException.class, () -> jdbc.update(sql,
                UUID.randomUUID(), vinculo, 1, "CAPS_II", "ADMINISTRADOR", "PENDENTE"));
        assertThrows(DataAccessException.class, () -> jdbc.update(sql,
                UUID.randomUUID(), vinculo, 2, "CAPS_AD", "ADMINISTRADOR", "PENDENTE"));
        assertThrows(DataAccessException.class, () -> jdbc.update(sql,
                UUID.randomUUID(), vinculo, 1, "CAPS_AD", "RECEPCAO", "PENDENTE"));
        assertThrows(DataAccessException.class, () -> jdbc.update(sql,
                UUID.randomUUID(), vinculo, 1, "CAPS_AD", "ADMINISTRADOR", "APROVADA"));

        UUID ticket = UUID.randomUUID();
        jdbc.update(sql, ticket, vinculo, 1, "CAPS_AD", "ADMINISTRADOR", "PENDENTE");
        assertEquals(1, jdbc.queryForObject(
                "select count(*) from tb_pendencia_revisao_proveniencia", Integer.class));
        assertThrows(DataAccessException.class, () -> jdbc.update(
                "update tb_pendencia_revisao_proveniencia set estado='PENDENTE' where id=?", ticket));
        assertThrows(DataAccessException.class, () -> jdbc.update(
                "delete from tb_pendencia_revisao_proveniencia where id=?", ticket));
        assertEquals("PENDENTE", jdbc.queryForObject(
                "select estado from tb_pendencia_revisao_proveniencia where id=?", String.class, ticket));
    }
}
