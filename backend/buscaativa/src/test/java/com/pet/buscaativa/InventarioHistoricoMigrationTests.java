package com.pet.buscaativa;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.pet.buscaativa.migration.AgendaMigrationTool;
import com.pet.buscaativa.migration.InventarioHistoricoSchemaGuard;

class InventarioHistoricoMigrationTests {
    @TempDir Path temp;

    @Test
    void instalaTrilhaSemMarcoERegistraSomenteComposicaoConfirmada() throws Exception {
        var fonte = LegacyMigrationFixture.legacy(temp.resolve("inventario-v19"));
        AgendaMigrationTool.migrar(fonte);
        AgendaMigrationTool.migrarIdentidade(fonte);
        AgendaMigrationTool.migrarTransicao(fonte);
        var jdbc = new JdbcTemplate(fonte);
        var anterior = UUID.randomUUID();
        inserir(jdbc, anterior, 2, "CAPS_AD");
        var colunas = LegacyMigrationFixture.columns(fonte);
        colunas.remove("sibraps_agenda_schema_history");
        var dados = LegacyMigrationFixture.snapshot(fonte, colunas);
        assertEquals(1, AgendaMigrationTool.migrarCoberturaHistorica(fonte).migrationsExecuted);
        assertEquals(dados, LegacyMigrationFixture.snapshot(fonte, colunas));
        assertEquals(0, contar(jdbc));
        assertEquals(0, jdbc.queryForObject("select count(*) from tb_marco_historico_vinculo", Integer.class));
        assertEquals(0, jdbc.queryForObject("select count(*) from tb_fechamento_historico_vinculo", Integer.class));

        var novo = UUID.randomUUID();
        inserir(jdbc, novo, 3, "CAPS_I");
        assertEquals(1, contar(jdbc));
        assertEquals("CAPS_I", jdbc.queryForObject("select unidade_atual from tb_movimento_inventario_historico", String.class));
        jdbc.update("update tb_vinculo_assistencial set version=1 where id_publico=?", novo);
        assertEquals(1, contar(jdbc));
        new TransactionTemplate(new DataSourceTransactionManager(fonte)).executeWithoutResult(status -> {
            jdbc.update("delete from tb_vinculo_assistencial where id_publico=?", novo);
            assertEquals(2, contar(jdbc));
            status.setRollbackOnly();
        });
        assertEquals(1, contar(jdbc));
        jdbc.update("delete from tb_vinculo_assistencial where id_publico=?", novo);
        assertEquals(2, contar(jdbc));
        assertEquals("CAPS_I", jdbc.queryForObject("""
                select unidade_anterior from tb_movimento_inventario_historico where operacao='EXCLUSAO'
                """, String.class));
        assertThrows(DataAccessException.class, () -> jdbc.update("delete from tb_movimento_inventario_historico"));
        assertThrows(DataAccessException.class, () -> jdbc.update("update tb_movimento_inventario_historico set operacao='INCLUSAO'"));
        assertEquals(0, AgendaMigrationTool.migrarCoberturaHistorica(fonte).migrationsExecuted);
        var backup = temp.resolve("v19.sql");
        LegacyMigrationFixture.backup(fonte, backup);
        var copia = LegacyMigrationFixture.restore(temp.resolve("restaurado"), backup);
        InventarioHistoricoSchemaGuard.verificar(copia);
        assertEquals(2, contar(new JdbcTemplate(copia)));
        var restaurado = new JdbcTemplate(copia);
        inserir(restaurado, UUID.randomUUID(), 3, "CAPS_I");
        assertEquals(3, contar(restaurado));
        restaurado.execute("drop trigger tr_inventario_historico_movimento");
        assertThrows(IllegalStateException.class, () -> InventarioHistoricoSchemaGuard.verificar(copia));
    }

    private static void inserir(JdbcTemplate jdbc, UUID id, int unidade, String chave) {
        jdbc.update("""
                insert into tb_vinculo_assistencial(id_publico,paciente_id,unidade_atuacao,unidade_chave,status_paciente,version)
                values (?,1,?,?,1,0)
                """, id, unidade, chave);
    }

    private static int contar(JdbcTemplate jdbc) {
        return jdbc.queryForObject("select count(*) from tb_movimento_inventario_historico", Integer.class);
    }
}
