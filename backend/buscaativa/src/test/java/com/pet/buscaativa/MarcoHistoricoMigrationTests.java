package com.pet.buscaativa;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import com.pet.buscaativa.migration.AgendaMigrationTool;
import com.pet.buscaativa.migration.MarcoHistoricoOffline;
import com.pet.buscaativa.migration.MarcoHistoricoSchemaGuard;
import tools.jackson.databind.ObjectMapper;

class MarcoHistoricoMigrationTests {
    @TempDir Path temp;

    @Test
    void marcoExplicitoCapturaPopulacaoEPermaneceImutavelAposRestauracao() throws Exception {
        var fonte = LegacyMigrationFixture.legacy(temp.resolve("marco-v20"));
        AgendaMigrationTool.migrar(fonte);
        AgendaMigrationTool.migrarIdentidade(fonte);
        AgendaMigrationTool.migrarTransicao(fonte);
        AgendaMigrationTool.migrarCoberturaHistorica(fonte);
        var zona = ZoneId.of("America/Bahia");
        assertThrows(IllegalStateException.class, () -> MarcoHistoricoOffline.registrar(fonte, zona));
        var jdbc = new JdbcTemplate(fonte);
        inserir(jdbc, 2, "CAPS_AD");
        var colunas = LegacyMigrationFixture.columns(fonte);
        colunas.remove("sibraps_agenda_schema_history");
        var antes = LegacyMigrationFixture.snapshot(fonte, colunas);
        assertEquals(1, AgendaMigrationTool.migrarMarcoHistorico(fonte).migrationsExecuted);
        assertEquals(antes, LegacyMigrationFixture.snapshot(fonte, colunas));
        assertEquals(0, jdbc.queryForObject("select count(*) from tb_marco_tecnico_historico", Integer.class));

        var marco = MarcoHistoricoOffline.registrar(fonte, zona);
        assertEquals(1L, marco.vinculos());
        assertEquals(1L, marco.ultimoMovimento());
        assertEquals("America/Bahia", marco.zona());
        String inventario = jdbc.queryForObject("select inventario from tb_marco_tecnico_historico", String.class);
        var dados = new ObjectMapper().readTree(inventario);
        assertEquals(1, dados.path("CAPS_AD").size());
        assertEquals(1L, dados.path("CAPS_AD").get(0).path("paciente").asLong());
        assertEquals(marco.unidades(), dados.size());
        inserir(jdbc, 3, "CAPS_I");
        assertEquals(marco, MarcoHistoricoOffline.registrar(fonte, zona));
        assertEquals(inventario, jdbc.queryForObject("select inventario from tb_marco_tecnico_historico", String.class));
        assertThrows(IllegalStateException.class, () -> MarcoHistoricoOffline.registrar(fonte, ZoneId.of("UTC")));
        assertThrows(DataAccessException.class, () -> jdbc.update("delete from tb_marco_tecnico_historico"));
        assertThrows(DataAccessException.class, () -> jdbc.update("update tb_marco_tecnico_historico set total_vinculos=0"));
        assertEquals(0, jdbc.queryForObject("select count(*) from tb_fechamento_historico_vinculo", Integer.class));
        assertEquals(0, AgendaMigrationTool.migrarMarcoHistorico(fonte).migrationsExecuted);
        var backup = temp.resolve("marco.sql");
        LegacyMigrationFixture.backup(fonte, backup);
        var copia = LegacyMigrationFixture.restore(temp.resolve("copia-marco"), backup);
        assertEquals(marco, MarcoHistoricoOffline.registrar(copia, zona));
        assertEquals(inventario, new JdbcTemplate(copia).queryForObject("select inventario from tb_marco_tecnico_historico", String.class));
        new JdbcTemplate(copia).execute("drop trigger tr_marco_tecnico_historico_append_only");
        assertThrows(IllegalStateException.class, () -> MarcoHistoricoSchemaGuard.verificar(copia));
    }

    private static void inserir(JdbcTemplate jdbc, int tipo, String unidade) {
        jdbc.update("""
                insert into tb_vinculo_assistencial(id_publico,paciente_id,unidade_atuacao,unidade_chave,status_paciente,version)
                values (?,1,?,?,1,0)
                """, UUID.randomUUID(), tipo, unidade);
    }
}
