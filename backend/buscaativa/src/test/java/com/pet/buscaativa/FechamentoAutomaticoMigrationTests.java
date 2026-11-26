package com.pet.buscaativa;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DataAccessException;
import com.pet.buscaativa.migration.AgendaMigrationTool;
import com.pet.buscaativa.migration.FechamentoAutomaticoSchemaGuard;

class FechamentoAutomaticoMigrationTests {
    @TempDir Path temp;
    @Test
    void instalaSemBackfillEProtegeFechamentosTecnicos() throws Exception {
        var banco=LegacyMigrationFixture.legacy(temp.resolve("v22"));
        AgendaMigrationTool.migrar(banco);
        AgendaMigrationTool.migrarIdentidade(banco);
        AgendaMigrationTool.migrarTransicao(banco);
        AgendaMigrationTool.migrarCoberturaHistorica(banco);
        AgendaMigrationTool.migrarMarcoHistorico(banco);
        AgendaMigrationTool.migrarEvidenciaClinica(banco);
        var colunas=LegacyMigrationFixture.columns(banco);
        colunas.remove("sibraps_agenda_schema_history");
        var antes=LegacyMigrationFixture.snapshot(banco,colunas);
        assertEquals(1,AgendaMigrationTool.migrarFechamentoAutomatico(banco).migrationsExecuted);
        assertEquals(antes,LegacyMigrationFixture.snapshot(banco,colunas));
        var jdbc=new JdbcTemplate(banco);
        assertEquals(0,jdbc.queryForObject("select count(*) from tb_observacao_historico_mensal",Integer.class));
        jdbc.update("""
                insert into tb_observacao_historico_mensal(unidade,mes,fase,observado_em,assinatura,inventario,indicadores)
                values ('CAPS_I',date '2027-01-01','PRE',current_timestamp,?,'[]',null)
                ""","a".repeat(64));
        assertThrows(DataAccessException.class,()->jdbc.update("delete from tb_observacao_historico_mensal"));
        assertEquals(0,AgendaMigrationTool.migrarFechamentoAutomatico(banco).migrationsExecuted);
        var colunasV22=LegacyMigrationFixture.columns(banco);
        colunasV22.remove("sibraps_agenda_schema_history");
        var antesV23=LegacyMigrationFixture.snapshot(banco,colunasV22);
        assertEquals(1,AgendaMigrationTool.migrarResolucaoFrequencia(banco).migrationsExecuted);
        assertEquals(antesV23,LegacyMigrationFixture.snapshot(banco,colunasV22));
        assertEquals(0,jdbc.queryForObject("select count(*) from tb_resolucao_frequencia_local",Integer.class));
        assertEquals(0,AgendaMigrationTool.migrarResolucaoFrequencia(banco).migrationsExecuted);
        jdbc.execute("drop trigger tr_resolucao_frequencia_append_only");
        assertThrows(IllegalStateException.class,()->com.pet.buscaativa.migration.ResolucaoFrequenciaSchemaGuard.verificar(banco));
        jdbc.execute("drop trigger tr_observacao_historico_append_only");
        assertThrows(IllegalStateException.class,()->FechamentoAutomaticoSchemaGuard.verificar(banco));
    }
}
