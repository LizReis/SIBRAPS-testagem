package com.pet.buscaativa;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.pet.buscaativa.migration.AgendaMigrationTool;
import com.pet.buscaativa.migration.EvidenciaClinicaSchemaGuard;
import com.pet.buscaativa.migration.MarcoHistoricoOffline;

class EvidenciaClinicaMigrationTests {
    @TempDir Path temp;

    @Test
    void capturaBaseEAlteracoesSemModificarLegadoEConservaRollback() throws Exception {
        var fonte = LegacyMigrationFixture.legacy(temp.resolve("evidencia-v21"));
        AgendaMigrationTool.migrar(fonte);
        AgendaMigrationTool.migrarIdentidade(fonte);
        AgendaMigrationTool.migrarTransicao(fonte);
        AgendaMigrationTool.migrarCoberturaHistorica(fonte);
        AgendaMigrationTool.migrarMarcoHistorico(fonte);
        var jdbc = new JdbcTemplate(fonte);
        var colunas=LegacyMigrationFixture.columns(fonte);
        colunas.remove("sibraps_agenda_schema_history");
        var antes=LegacyMigrationFixture.snapshot(fonte,colunas);
        assertEquals(1,AgendaMigrationTool.migrarEvidenciaClinica(fonte).migrationsExecuted);
        assertEquals(antes,LegacyMigrationFixture.snapshot(fonte,colunas));
        assertEquals(0,contar(jdbc));
        assertEquals(0,jdbc.queryForObject("select count(*) from tb_base_evidencia_clinica",Integer.class));

        var marco=MarcoHistoricoOffline.registrar(fonte,ZoneId.of("America/Bahia"));
        var assinatura=jdbc.queryForObject("""
                select assinatura from tb_item_base_evidencia_clinica where fonte='TB_PACIENTE' and registro=1
                """,String.class);
        assertNotNull(assinatura);
        assertEquals(64,assinatura.length());
        jdbc.update("update tb_paciente set nome='Nome sintético alterado' where id=1");
        assertEquals(0,contar(jdbc));
        jdbc.update("update tb_paciente set situacao_rua=not situacao_rua where id=1");
        assertEquals(1,contar(jdbc));
        assertEquals(assinatura,jdbc.queryForObject("select assinatura_anterior from tb_movimento_evidencia_clinica",String.class));
        var depois=jdbc.queryForObject("select assinatura_atual from tb_movimento_evidencia_clinica",String.class);
        assertNotEquals(assinatura,depois);
        new TransactionTemplate(new DataSourceTransactionManager(fonte)).executeWithoutResult(status -> {
            jdbc.update("update tb_paciente set situacao_rua=not situacao_rua where id=1");
            assertEquals(2,contar(jdbc));
            status.setRollbackOnly();
        });
        assertEquals(1,contar(jdbc));
        jdbc.update("update tb_paciente set situacao_rua=situacao_rua where id=1");
        assertEquals(1,contar(jdbc));
        jdbc.update("update tb_paciente set situacao_rua=not situacao_rua where id=1");
        assertEquals(2,contar(jdbc));
        assertThrows(DataAccessException.class,()->jdbc.update("delete from tb_item_base_evidencia_clinica"));
        assertThrows(DataAccessException.class,()->jdbc.update("delete from tb_movimento_evidencia_clinica"));
        assertEquals(marco,MarcoHistoricoOffline.registrar(fonte,ZoneId.of("America/Bahia")));
        assertEquals(0,AgendaMigrationTool.migrarEvidenciaClinica(fonte).migrationsExecuted);
        int movimentos=contar(jdbc);
        var backup=temp.resolve("evidencia.sql");
        LegacyMigrationFixture.backup(fonte,backup);
        var copia=LegacyMigrationFixture.restore(temp.resolve("copia-evidencia"),backup);
        EvidenciaClinicaSchemaGuard.verificar(copia);
        var restaurado=new JdbcTemplate(copia);
        assertEquals(movimentos,contar(restaurado));
        assertEquals(marco,MarcoHistoricoOffline.registrar(copia,ZoneId.of("America/Bahia")));
        restaurado.update("update tb_paciente set situacao_rua=not situacao_rua where id=1");
        assertEquals(movimentos+1,contar(restaurado));
        restaurado.execute("drop trigger tr_evidencia_clinica_f1");
        assertThrows(IllegalStateException.class,()->EvidenciaClinicaSchemaGuard.verificar(copia));
    }

    private static int contar(JdbcTemplate jdbc) {
        return jdbc.queryForObject("select count(*) from tb_movimento_evidencia_clinica",Integer.class);
    }
}
