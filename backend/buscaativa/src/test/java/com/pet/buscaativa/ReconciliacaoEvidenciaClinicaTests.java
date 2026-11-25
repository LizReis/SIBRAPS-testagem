package com.pet.buscaativa;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import com.pet.buscaativa.migration.AgendaMigrationTool;
import com.pet.buscaativa.migration.MarcoHistoricoOffline;
import com.pet.buscaativa.migration.ReconciliacaoEvidenciaClinica;

class ReconciliacaoEvidenciaClinicaTests {
    @TempDir Path temp;

    @Test
    void distingueBaseAusenteMudancaSemAuditoriaERevisaoCorrespondente() throws Exception {
        var banco=LegacyMigrationFixture.legacy(temp.resolve("reconciliacao"));
        AgendaMigrationTool.migrar(banco);
        AgendaMigrationTool.migrarIdentidade(banco);
        AgendaMigrationTool.migrarTransicao(banco);
        AgendaMigrationTool.migrarCoberturaHistorica(banco);
        AgendaMigrationTool.migrarMarcoHistorico(banco);
        AgendaMigrationTool.migrarEvidenciaClinica(banco);
        assertFalse(ReconciliacaoEvidenciaClinica.diagnosticar(banco).baseDisponivel());
        var marco=MarcoHistoricoOffline.registrar(banco,ZoneId.of("America/Bahia"));
        var jdbc=new JdbcTemplate(banco);
        var inicial=ReconciliacaoEvidenciaClinica.diagnosticar(banco);
        assertTrue(inicial.baseDisponivel());
        assertTrue(inicial.cardinalidadeConfere());
        assertEquals(7,inicial.fontes().size());
        assertTrue(inicial.fontes().get("TB_PACIENTE").consistente());

        // Escrita sem Envers: a captura independente não pode certificar a alteração.
        jdbc.update("update tb_paciente set situacao_rua=true where id=1");
        var semAuditoria=ReconciliacaoEvidenciaClinica.diagnosticar(banco).fontes().get("TB_PACIENTE");
        assertEquals(1,semAuditoria.auditoriaDivergente());
        assertEquals(0,semAuditoria.estadoAtualDivergente());

        // Revisão sintética controlada para isolar a comparação das duas trilhas.
        long instante=marco.iniciadoEm().toInstant().toEpochMilli()+1;
        int rev=jdbc.queryForObject("select max(rev)+1 from revinfo",Integer.class);
        jdbc.update("insert into revinfo(rev,revtstmp) values (?,?)",rev,instante);
        jdbc.update("insert into tb_paciente_aud(id,rev,revtype,situacao_rua) values (?,?,1,true)",1L,rev);
        assertTrue(ReconciliacaoEvidenciaClinica.diagnosticar(banco).fontes().get("TB_PACIENTE").consistente());

        // Uma revisão extra, sem alteração na fonte independente, também diverge.
        jdbc.update("insert into revinfo(rev,revtstmp) values (?,?)",rev+1,instante+1);
        jdbc.update("insert into tb_paciente_aud(id,rev,revtype,situacao_rua) values (?,?,1,false)",1L,rev+1);
        assertEquals(1,ReconciliacaoEvidenciaClinica.diagnosticar(banco).fontes().get("TB_PACIENTE").auditoriaDivergente());
        assertEquals(0,jdbc.queryForObject("select count(*) from tb_fechamento_historico_vinculo",Integer.class));
    }
}
