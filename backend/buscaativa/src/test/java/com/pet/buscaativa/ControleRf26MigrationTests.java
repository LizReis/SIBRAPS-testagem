package com.pet.buscaativa;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import com.pet.buscaativa.migration.AgendaMigrationTool;

class ControleRf26MigrationTests {
    @TempDir Path temp;

    @Test
    void evoluiV15SemBackfillEPreservaReservaAoRestaurar() throws Exception {
        var base = LegacyMigrationFixture.legacy(temp.resolve("base-v15"));
        assertEquals(15, LegacyMigrationFixture.migrarAte(base, "15").migrationsExecuted);
        var colunas = LegacyMigrationFixture.columns(base);
        colunas.remove("sibraps_agenda_schema_history");
        var antes = LegacyMigrationFixture.snapshot(base, colunas);
        assertEquals(1, AgendaMigrationTool.migrar(base).migrationsExecuted);
        assertEquals(antes, LegacyMigrationFixture.snapshot(base, colunas));
        var jdbc = new JdbcTemplate(base);
        assertEquals(0, jdbc.queryForObject("select count(*) from tb_entrega_rf26", Integer.class));
        String reservar = """
                insert into tb_entrega_rf26(dia,destinatario,unidade,estado,reservada_em)
                values (date '2026-11-24',?,?,'RESERVADA',current_timestamp)
                """;
        var destinatario = UUID.randomUUID();
        jdbc.update(reservar, destinatario, "CAPS_II");
        assertThrows(DataAccessException.class, () -> jdbc.update(reservar, destinatario, "CAPS_II"));
        assertThrows(DataAccessException.class, () -> jdbc.update(reservar, destinatario, "CAPS:CAPS_II"));
        assertThrows(DataAccessException.class, () -> jdbc.update("update tb_entrega_rf26 set estado='SIMULADA'"));
        jdbc.update(reservar, destinatario, "CAPS_I");
        var completo = LegacyMigrationFixture.columns(base);
        var comReservas = LegacyMigrationFixture.snapshot(base, completo);
        var backup = temp.resolve("v16.sql");
        LegacyMigrationFixture.backup(base, backup);
        var copia = LegacyMigrationFixture.restore(temp.resolve("copia-v16"), backup);
        assertEquals(comReservas, LegacyMigrationFixture.snapshot(copia, completo));
        assertEquals(0, AgendaMigrationTool.migrar(copia).migrationsExecuted);
        assertEquals(comReservas, LegacyMigrationFixture.snapshot(copia, completo));
    }
}
