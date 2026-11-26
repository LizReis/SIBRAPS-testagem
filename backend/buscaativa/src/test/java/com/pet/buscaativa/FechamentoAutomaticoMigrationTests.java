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
        var colunasV23=LegacyMigrationFixture.columns(banco);
        colunasV23.remove("sibraps_agenda_schema_history");
        var antesV24=LegacyMigrationFixture.snapshot(banco,colunasV23);
        assertEquals(1,AgendaMigrationTool.migrarReversaoResolucao(banco).migrationsExecuted);
        assertEquals(antesV24,LegacyMigrationFixture.snapshot(banco,colunasV23));
        assertEquals(0,AgendaMigrationTool.migrarReversaoResolucao(banco).migrationsExecuted);
        assertEquals(0,jdbc.queryForObject("select count(*) from tb_reversao_resolucao_frequencia",Integer.class));
        validarReversaoSintetica(jdbc);
        jdbc.execute("drop trigger tr_reversao_resolucao_append_only");
        assertThrows(IllegalStateException.class,()->com.pet.buscaativa.migration.ReversaoResolucaoSchemaGuard.verificar(banco));
        jdbc.execute("drop trigger tr_resolucao_frequencia_append_only");
        assertThrows(IllegalStateException.class,()->com.pet.buscaativa.migration.ResolucaoFrequenciaSchemaGuard.verificar(banco));
        jdbc.execute("drop trigger tr_observacao_historico_append_only");
        assertThrows(IllegalStateException.class,()->FechamentoAutomaticoSchemaGuard.verificar(banco));
    }

    private void validarReversaoSintetica(JdbcTemplate jdbc) {
        jdbc.update("""
                insert into tb_vinculo_assistencial(id_publico,paciente_id,unidade_atuacao,unidade_chave,status_paciente,version)
                values (?,1,2,'CAPS_AD',1,0)
                """,java.util.UUID.randomUUID());
        long vinculo=jdbc.queryForObject("select id from tb_vinculo_assistencial where paciente_id=1 and unidade_chave='CAPS_AD'",Long.class);
        var resolucao=java.util.UUID.randomUUID();
        var insert="""
                insert into tb_resolucao_frequencia_local
                (id,vinculo_id,paciente_id,unidade_chave,fonte_valida,fato_valido,versao_valida,frequencia_valida,responsavel_valido,
                fonte_desconsiderada,fato_desconsiderado,versao_desconsiderada,frequencia_desconsiderada,responsavel_desconsiderado,
                horario,ator_id,perfil,justificativa,registrado_em)
                values (?, ?,1,'CAPS_AD','ATENDIMENTO_INDIVIDUAL',4,7,'PRESENTE',2,
                'ATENDIMENTO_INDIVIDUAL',5,0,'FALTOU',2,TIMESTAMP '2026-01-01 09:00:00',1,'ADMINISTRADOR',
                'Resolução sintética para teste de estrutura.',CURRENT_TIMESTAMP)
                """;
        jdbc.update(insert,resolucao,vinculo);
        assertEquals(1,jdbc.queryForObject("select count(*) from vw_resolucao_frequencia_ativa",Integer.class));
        var reversao=java.util.UUID.randomUUID();
        var desfazer="""
                insert into tb_reversao_resolucao_frequencia
                (id,resolucao_id,vinculo_id,paciente_id,unidade_chave,responsavel_valido,responsavel_desconsiderado,
                ator_id,perfil,justificativa,registrado_em)
                values (?,?,?,1,?,2,2,?,?,'Justificativa sintética de reversão.',CURRENT_TIMESTAMP)
                """;
        assertThrows(DataAccessException.class,()->jdbc.update(desfazer,reversao,resolucao,vinculo,"CAPS_I",1,"ADMINISTRADOR"));
        assertThrows(DataAccessException.class,()->jdbc.update(desfazer,reversao,resolucao,vinculo,"CAPS_AD",1,"PROFISSIONAL"));
        jdbc.update(desfazer,reversao,resolucao,vinculo,"CAPS_AD",2,"PROFISSIONAL");
        assertEquals(0,jdbc.queryForObject("select count(*) from vw_resolucao_frequencia_ativa",Integer.class));
        assertEquals(1,jdbc.queryForObject("select count(*) from tb_resolucao_frequencia_local",Integer.class));
        assertThrows(DataAccessException.class,()->jdbc.update(desfazer,java.util.UUID.randomUUID(),resolucao,vinculo,"CAPS_AD",2,"PROFISSIONAL"));
        assertThrows(DataAccessException.class,()->jdbc.update("delete from tb_reversao_resolucao_frequencia where id=?",reversao));
        assertThrows(DataAccessException.class,()->jdbc.update("update tb_reversao_resolucao_frequencia set justificativa='Tentativa de alteração' where id=?",reversao));
        jdbc.update(insert,java.util.UUID.randomUUID(),vinculo);
        assertEquals(1,jdbc.queryForObject("select count(*) from vw_resolucao_frequencia_ativa",Integer.class));
        assertEquals(2,jdbc.queryForObject("select count(*) from tb_resolucao_frequencia_local",Integer.class));
    }
}
