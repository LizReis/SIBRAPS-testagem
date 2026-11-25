package com.pet.buscaativa;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import com.pet.buscaativa.migration.AgendaMigrationTool;
import com.pet.buscaativa.migration.IdentidadeDocumentalPreflight;

class IdentidadeDocumentalMigrationTests {
    @TempDir Path temp;

    @Test
    void bloqueiaDocumentosRepetidosEInconclusivosSemAlterarEsquemaOuDados() throws Exception {
        var base=LegacyMigrationFixture.legacy(temp.resolve("bloqueio"));
        AgendaMigrationTool.migrar(base);
        var j=new JdbcTemplate(base);
        j.update("update tb_paciente set cpf=?, cns=? where id=1","52998224725","700000000000005");
        j.update("update tb_paciente set cpf=?, cns=? where id=2","529.982.247-25","70000 0000000005");
        var colunas=LegacyMigrationFixture.columns(base);
        var antes=LegacyMigrationFixture.snapshot(base,colunas);
        var diagnostico=IdentidadeDocumentalPreflight.analisar(base);
        assertEquals(1,diagnostico.cpfsDuplicados());
        assertEquals(1,diagnostico.cnsDuplicados());
        assertTrue(diagnostico.bloqueiaV17());
        assertFalse(diagnostico.toString().contains("52998224725"));
        assertThrows(IllegalStateException.class,()->AgendaMigrationTool.migrarIdentidade(base));
        assertEquals(colunas,LegacyMigrationFixture.columns(base));
        assertEquals(antes,LegacyMigrationFixture.snapshot(base,colunas));
        j.update("update tb_paciente set cpf=?, cns=null where id=2","abc");
        var invalido=IdentidadeDocumentalPreflight.analisar(base);
        assertEquals(1,invalido.cpfsInconclusivos());
        assertTrue(invalido.bloqueiaV17());
        assertThrows(IllegalStateException.class,()->AgendaMigrationTool.migrarIdentidade(base));
    }

    @Test
    void evoluiV16PreservandoIdentidadesOriginaisEImpedindoDuplicidadeNormalizada() throws Exception {
        var base=LegacyMigrationFixture.legacy(temp.resolve("evolucao"));
        assertEquals(16,AgendaMigrationTool.migrar(base).migrationsExecuted);
        var j=new JdbcTemplate(base);
        j.update("update tb_paciente set cpf=?, cns=? where id=1","529.982.247-25","70000 0000000005");
        var colunas=LegacyMigrationFixture.columns(base);
        colunas.remove("sibraps_agenda_schema_history");
        var antes=LegacyMigrationFixture.snapshot(base,colunas);
        assertFalse(IdentidadeDocumentalPreflight.analisar(base).bloqueiaV17());
        assertEquals(1,AgendaMigrationTool.migrarIdentidade(base).migrationsExecuted);
        assertEquals(antes,LegacyMigrationFixture.snapshot(base,colunas));
        assertEquals("52998224725",j.queryForObject("select cpf_normalizado from tb_paciente where id=1",String.class));
        assertEquals("700000000000005",j.queryForObject("select cns_normalizado from tb_paciente where id=1",String.class));
        assertThrows(DataAccessException.class,()->j.update("update tb_paciente set cpf='52998224725' where id=2"));
        assertThrows(DataAccessException.class,()->j.update("update tb_paciente set cns='700000000000005' where id=2"));
        assertNull(j.queryForObject("select cpf_normalizado from tb_paciente where id=2",String.class));
        j.update("update tb_paciente set cpf=null,cns=null");
        assertEquals(2,j.queryForObject("select count(*) from tb_paciente where cpf_normalizado is null and cns_normalizado is null",Integer.class));
        assertEquals(0,AgendaMigrationTool.migrarIdentidade(base).migrationsExecuted);
        var completo=LegacyMigrationFixture.columns(base);
        var depois=LegacyMigrationFixture.snapshot(base,completo);
        var backup=temp.resolve("v17.sql");
        LegacyMigrationFixture.backup(base,backup);
        var copia=LegacyMigrationFixture.restore(temp.resolve("restaurada"),backup);
        assertEquals(depois,LegacyMigrationFixture.snapshot(copia,completo));
        IdentidadeDocumentalPreflight.verificarRestricoes(copia);
        var jc=new JdbcTemplate(copia);
        jc.execute("alter table tb_paciente drop constraint uk_paciente_cpf_normalizado");
        assertThrows(IllegalStateException.class,()->IdentidadeDocumentalPreflight.verificarRestricoes(copia));
    }

    @Test
    void duasConexoesIndependentesNaoConseguemGravarMesmoCpfOuCns() throws Exception {
        var base=LegacyMigrationFixture.legacy(temp.resolve("concorrencia"));
        AgendaMigrationTool.migrar(base);
        AgendaMigrationTool.migrarIdentidade(base);
        disputar(base,"cpf","529.982.247-25","52998224725");
        disputar(base,"cns","70000 0000000005","700000000000005");
    }

    @Test
    void duasInsercoesEmConexoesIndependentesRespeitamUnicidade() throws Exception {
        var base=LegacyMigrationFixture.legacy(temp.resolve("insercoes"));
        AgendaMigrationTool.migrar(base);
        AgendaMigrationTool.migrarIdentidade(base);
        var inicio=new CountDownLatch(1);
        try(var executor=Executors.newFixedThreadPool(2)) {
            var a=executor.submit(()->inserir(base,inicio,"529.982.247-25","700000000000005"));
            var b=executor.submit(()->inserir(base,inicio,"52998224725","70000 0000000005"));
            inicio.countDown();
            assertNotEquals(a.get(20,TimeUnit.SECONDS),b.get(20,TimeUnit.SECONDS));
        }
        var j=new JdbcTemplate(base);
        assertEquals(3,j.queryForObject("select count(*) from tb_paciente",Integer.class));
        assertEquals(1,j.queryForObject("select count(*) from tb_paciente where cpf_normalizado='52998224725' and cns_normalizado='700000000000005'",Integer.class));
    }

    private static boolean inserir(javax.sql.DataSource fonte,CountDownLatch inicio,String cpf,String cns) throws Exception {
        try(var c=fonte.getConnection()) {
            c.setAutoCommit(false);
            if(!inicio.await(10,TimeUnit.SECONDS))throw new IllegalStateException("Timeout");
            try(var q=c.prepareStatement("insert into tb_paciente(id_publico,count_faltas,situacao_rua,cpf,cns) values (?,0,false,?,?)")) {
                q.setObject(1,java.util.UUID.randomUUID());q.setString(2,cpf);q.setString(3,cns);
                q.executeUpdate();c.commit();return true;
            } catch(java.sql.SQLException e) {
                c.rollback();
                if(!"23505".equals(e.getSQLState()))throw e;
                return false;
            }
        }
    }

    private static void disputar(javax.sql.DataSource base,String coluna,String primeiro,String segundo) throws Exception {
        var inicio=new CountDownLatch(1);
        try(var executor=Executors.newFixedThreadPool(2)) {
            var a=executor.submit(()->gravar(base,inicio,coluna,1,primeiro));
            var b=executor.submit(()->gravar(base,inicio,coluna,2,segundo));
            inicio.countDown();
            assertNotEquals(a.get(20,TimeUnit.SECONDS),b.get(20,TimeUnit.SECONDS));
        }
        assertEquals(1,new JdbcTemplate(base).queryForObject("select count(*) from tb_paciente where "+coluna+"_normalizado is not null",Integer.class));
    }

    private static boolean gravar(javax.sql.DataSource fonte,CountDownLatch inicio,String coluna,long id,String doc) throws Exception {
        try(var c=fonte.getConnection()) {
            c.setAutoCommit(false);
            if(!inicio.await(10,TimeUnit.SECONDS))throw new IllegalStateException("Timeout");
            try(var q=c.prepareStatement("update tb_paciente set "+coluna+"=? where id=?")) {
                q.setString(1,doc);q.setLong(2,id);q.executeUpdate();c.commit();return true;
            } catch(java.sql.SQLException e) {
                c.rollback();
                if(!"23505".equals(e.getSQLState()))throw e;
                return false;
            }
        }
    }
}
