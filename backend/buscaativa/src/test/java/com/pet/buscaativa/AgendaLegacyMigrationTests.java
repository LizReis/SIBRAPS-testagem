package com.pet.buscaativa;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.io.InputStreamReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.h2.tools.RunScript;
import org.springframework.jdbc.core.JdbcTemplate;
import com.pet.buscaativa.migration.*;

class AgendaLegacyMigrationTests {
    @TempDir Path temp;

    @Test void migratePersistedOldSchemaWithoutChangingAnyOriginalColumnOrRevision() throws Exception {
        var source = LegacyMigrationFixture.legacy(temp.resolve("legacy"));
        var jdbc = new JdbcTemplate(source);
        var columns = LegacyMigrationFixture.columns(source); var before = LegacyMigrationFixture.snapshot(source, columns);
        assertFalse(columns.get("TB_AGENDAMENTO").contains("SITUACAO_RESERVA"));
        assertEquals(12, jdbc.queryForObject("select count(*) from tb_agendamento", Integer.class));
        var result = AgendaMigrationTool.migrar(source);
        assertEquals(4, result.migrationsExecuted);
        assertEquals(before, LegacyMigrationFixture.snapshot(source, columns));
        assertEquals(4, jdbc.queryForObject("select count(*) from tb_agendamento where migracao_pendente=true", Integer.class));
        assertEquals(3, jdbc.queryForObject("select count(*) from tb_agendamento where ocupa_vaga=true", Integer.class));
        for (long id : new long[]{7, 8, 9}) {
            assertEquals("FALTOU", jdbc.queryForObject("select frequencia_assistencial from tb_agendamento where id=?", String.class, id));
        }
        assertEquals("REMARCADA", jdbc.queryForObject("select situacao_reserva from tb_agendamento where id=9", String.class));
        assertEquals("PRESENTE", jdbc.queryForObject("select frequencia_assistencial from tb_agendamento where id=10", String.class));
        assertEquals(7, jdbc.queryForObject("select version from tb_agendamento where id=4", Integer.class));
        for (long id : new long[]{2, 6, 12}) assertNull(jdbc.queryForObject("select frequencia_assistencial from tb_agendamento where id=?", String.class, id));
        assertEquals(0, jdbc.queryForObject("select count(*) from tb_agendamento_aud where frequencia_assistencial is not null", Integer.class));
        var allColumns = LegacyMigrationFixture.columns(source); var after = LegacyMigrationFixture.snapshot(source, allColumns);
        jdbc.execute("SHUTDOWN"); // Actual close/reopen of the file database, not a fresh Hibernate schema.
        assertEquals(0, AgendaMigrationTool.migrar(source).migrationsExecuted);
        assertEquals(after, LegacyMigrationFixture.snapshot(source, allColumns));
        System.out.println("AGENDA_MIGRATION legacy=12 after=12 resolved=8 pending=4 occupied=3 history=6 revisions=2 restartMigrations=0");
    }

    @Test void restorePreMigrationAndLatestPostMigrationSnapshotsIncludingNewWrites() throws Exception {
        var source = LegacyMigrationFixture.legacy(temp.resolve("source")); var jdbc = new JdbcTemplate(source);
        var oldColumns = LegacyMigrationFixture.columns(source); var before = LegacyMigrationFixture.snapshot(source, oldColumns);
        var oldBackup = temp.resolve("before.sql"); LegacyMigrationFixture.backup(source, oldBackup);
        AgendaMigrationTool.migrar(source);
        jdbc.update("update tb_agendamento set frequencia_assistencial='PRESENTE', situacao_atendimento=1, ocupa_vaga=true, version=version+1 where id=5");
        jdbc.update("update tb_historico_paciente set tipo='PRESENCA', situacao_atendimento=1, descricao='Correção sintética após migração' where agendamento_id=5");
        jdbc.update("""
            insert into tb_agendamento(id,paciente_id,usuario_id,data_agendamento,hora_atendimento,turno_agendamento,
            situacao_atendimento,remarcacao_apos_falta,version,situacao_reserva,frequencia_assistencial,migracao_pendente,ocupa_vaga)
            values(13,1,2,DATE '2026-01-10',TIME '14:00',2,2,false,0,'AGENDADA','NAO_REGISTRADA',false,true)
            """);
        CanonicalAgendaGuard.verificar(source);
        var newColumns = LegacyMigrationFixture.columns(source); var updated = LegacyMigrationFixture.snapshot(source, newColumns);
        var latestBackup = temp.resolve("latest.sql"); LegacyMigrationFixture.backup(source, latestBackup);
        var restoredLatest = LegacyMigrationFixture.restore(temp.resolve("latest-restored"), latestBackup);
        assertEquals(updated, LegacyMigrationFixture.snapshot(restoredLatest, newColumns));
        assertEquals(0, AgendaMigrationTool.migrar(restoredLatest).migrationsExecuted);
        var restoredOld = LegacyMigrationFixture.restore(temp.resolve("old-restored"), oldBackup);
        assertEquals(before, LegacyMigrationFixture.snapshot(restoredOld, oldColumns));
        assertFalse(LegacyMigrationFixture.columns(restoredOld).get("TB_AGENDAMENTO").contains("SITUACAO_RESERVA"));
        System.out.println("AGENDA_RESTORE beforeRows=12 latestRows=13 postMigrationCorrectionPreserved=true oldSnapshotRestored=true");
    }

    @Test void contradictionsMissingEvidenceAndBrokenChainsRemainPendingRatherThanInventingFacts() throws Exception {
        var source = LegacyMigrationFixture.legacy(temp.resolve("ambiguous")); var jdbc = new JdbcTemplate(source);
        jdbc.update("update tb_historico_paciente set tipo='FALTA', situacao_atendimento=3 where agendamento_id=4");
        jdbc.update("update tb_historico_paciente set tipo='PRESENCA', situacao_atendimento=1 where agendamento_id=5");
        jdbc.update("update tb_historico_paciente set agendamento_id=null where agendamento_id=7");
        jdbc.update("update tb_agendamento set agendamento_original_id=3 where id=2");
        var columns = LegacyMigrationFixture.columns(source); var before = LegacyMigrationFixture.snapshot(source, columns);
        AgendaMigrationTool.migrar(source);
        for (long id : new long[]{2, 3, 4, 5, 6, 7, 8, 9, 11, 12})
            if (id != 8 && id != 9) assertTrue(jdbc.queryForObject("select migracao_pendente from tb_agendamento where id=?", Boolean.class, id));
        assertNull(jdbc.queryForObject("select frequencia_assistencial from tb_agendamento where id=5", String.class));
        assertTrue(jdbc.queryForObject("select ocupa_vaga from tb_agendamento where id=4", Boolean.class));
        assertNull(jdbc.queryForObject("select frequencia_assistencial from tb_agendamento where id=7", String.class));
        assertEquals(before, LegacyMigrationFixture.snapshot(source, columns));
    }

    @Test void partiallyAddedColumnsBlockStartupUntilExplicitBackfillCompletes() throws Exception {
        var source = LegacyMigrationFixture.legacy(temp.resolve("partial"));
        try (var connection = source.getConnection(); var reader = new InputStreamReader(getClass().getResourceAsStream(
                "/agenda-migration/h2/V1__adicionar_estados_canonicos.sql"))) { RunScript.execute(connection, reader); }
        assertThrows(IllegalStateException.class, () -> CanonicalAgendaGuard.verificar(source));
        assertEquals(4, AgendaMigrationTool.migrar(source).migrationsExecuted);
        assertDoesNotThrow(() -> CanonicalAgendaGuard.verificar(source));
    }

    @Test void wrongOrEmptyDatabaseIsRejectedWithoutCreatingMigrationInfrastructure() throws Exception {
        var empty = LegacyMigrationFixture.empty(temp.resolve("empty"));
        assertThrows(java.sql.SQLException.class, () -> AgendaMigrationTool.migrar(empty));
        assertTrue(LegacyMigrationFixture.columns(empty).isEmpty());
        var memory = new org.springframework.jdbc.datasource.DriverManagerDataSource("jdbc:h2:mem:unsupported-migration", "sa", "synthetic-only");
        assertThrows(IllegalArgumentException.class, () -> AgendaMigrationTool.migrar(memory));
    }

    @Test void inconsistentProjectionsBlockStartupWithoutAutomaticRepair() throws Exception {
        var source = LegacyMigrationFixture.legacy(temp.resolve("incoherent")); var jdbc = new JdbcTemplate(source);
        AgendaMigrationTool.migrar(source);
        jdbc.update("update tb_agendamento set ocupa_vaga=false where id=4");
        var columns = LegacyMigrationFixture.columns(source); var changed = LegacyMigrationFixture.snapshot(source, columns);
        assertThrows(IllegalStateException.class, () -> CanonicalAgendaGuard.verificar(source));
        assertEquals(changed, LegacyMigrationFixture.snapshot(source, columns));
        jdbc.update("update tb_agendamento set ocupa_vaga=true where id=4");
        jdbc.update("update tb_agendamento set ocupa_vaga=true where id=6");
        assertThrows(IllegalStateException.class, () -> CanonicalAgendaGuard.verificar(source));
    }

    @Test void actualClinicalRevisionCanRecoverAFactWithoutCreatingHistory() throws Exception {
        var source = LegacyMigrationFixture.legacy(temp.resolve("audit-evidence")); var jdbc = new JdbcTemplate(source);
        jdbc.update("""
            insert into tb_agendamento_aud(id,rev,revtype,situacao_atendimento,paciente_id,usuario_id,
            data_agendamento,hora_atendimento,turno_agendamento,remarcacao_apos_falta)
            values(2,1,0,3,1,2,DATE '2025-12-30',TIME '07:30',1,false)
            """);
        var columns = LegacyMigrationFixture.columns(source); var before = LegacyMigrationFixture.snapshot(source, columns);
        AgendaMigrationTool.migrar(source);
        assertEquals("FALTOU", jdbc.queryForObject("select frequencia_assistencial from tb_agendamento where id=2", String.class));
        assertFalse(jdbc.queryForObject("select migracao_pendente from tb_agendamento where id=2", Boolean.class));
        assertTrue(jdbc.queryForObject("select migracao_pendente from tb_agendamento where id=3", Boolean.class));
        assertEquals("NAO_REGISTRADA", jdbc.queryForObject("select frequencia_assistencial from tb_agendamento where id=3", String.class));
        assertEquals(before, LegacyMigrationFixture.snapshot(source, columns));
    }

    @Test void unsupportedFileLifecycleIsRejectedBeforeChangingSchema() throws Exception {
        var source = LegacyMigrationFixture.empty(temp.resolve("lifecycle"));
        var jdbc = new JdbcTemplate(source); jdbc.execute("SET DB_CLOSE_DELAY -1");
        try {
            assertThrows(IllegalArgumentException.class, () -> AgendaMigrationTool.migrar(source));
            assertTrue(LegacyMigrationFixture.columns(source).isEmpty());
        } finally { jdbc.execute("SHUTDOWN"); }
    }

    @Test void existingCanonicalDataWithoutVersionedOriginAreNeverReinterpreted() throws Exception {
        var source = LegacyMigrationFixture.legacy(temp.resolve("already-canonical"));
        try (var connection = source.getConnection(); var reader = new InputStreamReader(getClass().getResourceAsStream(
                "/agenda-migration/h2/V1__adicionar_estados_canonicos.sql"))) { RunScript.execute(connection, reader); }
        var jdbc = new JdbcTemplate(source);
        jdbc.update("update tb_agendamento set situacao_reserva='SUBSTITUIDA',frequencia_assistencial='PRESENTE',migracao_pendente=false,ocupa_vaga=false where id=7");
        var columns = LegacyMigrationFixture.columns(source); var before = LegacyMigrationFixture.snapshot(source, columns);
        assertThrows(IllegalStateException.class, () -> AgendaMigrationTool.migrar(source));
        assertEquals(columns, LegacyMigrationFixture.columns(source));
        assertEquals(before, LegacyMigrationFixture.snapshot(source, columns));
    }
}
