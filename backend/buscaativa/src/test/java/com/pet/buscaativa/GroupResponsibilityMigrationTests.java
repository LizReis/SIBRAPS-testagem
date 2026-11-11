package com.pet.buscaativa;

import static org.junit.jupiter.api.Assertions.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import java.nio.file.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import javax.sql.DataSource;
import org.h2.tools.RunScript;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import com.pet.buscaativa.entities.enums.*;
import com.pet.buscaativa.entities.dto.*;
import com.pet.buscaativa.migration.*;
import com.pet.buscaativa.repositories.*;
import com.pet.buscaativa.services.*;
import tools.jackson.databind.ObjectMapper;

class GroupResponsibilityMigrationTests {
    @TempDir Path temp;
    private DataSource v4(String name) throws Exception {
        var source=LegacyMigrationFixture.empty(temp.resolve(name));
        try(var c=source.getConnection();var reader=new InputStreamReader(Objects.requireNonNull(getClass().getResourceAsStream("/fixtures/group-responsibility-v4.sql")),StandardCharsets.UTF_8)){RunScript.execute(c,reader);}return source;
    }
    @Test void actualV4EnversRecoversAWithoutRewritingOldBPolicyAudit() throws Exception {
        var source=v4("actual-v4");var jdbc=new JdbcTemplate(source);var columns=LegacyMigrationFixture.columns(source);columns.remove("sibraps_agenda_schema_history");var before=LegacyMigrationFixture.snapshot(source,columns);
        assertEquals(2,AgendaMigrationTool.migrar(source).migrationsExecuted);assertEquals(before,LegacyMigrationFixture.snapshot(source,columns));
        assertEquals(2L,jdbc.queryForObject("select responsavel_atendimento_id from tb_sessao_grupo where id=1",Long.class));assertEquals(3L,jdbc.queryForObject("select coordenador_id from tb_grupo_terapeutico where id=1",Long.class));
        assertEquals("ENVERS_TRANSICAO_REALIZADA",jdbc.queryForObject("select origem_responsabilidade from tb_sessao_grupo where id=1",String.class));assertEquals(List.of(2L,3L),jdbc.queryForList("select responsavel_id from tb_auditoria_correcao_frequencia order by ocorrido_em",Long.class));
        assertNull(jdbc.queryForObject("select responsavel_atendimento_id from tb_sessao_grupo where id=2",Long.class));assertTrue(jdbc.queryForObject("select responsabilidade_pendente from tb_sessao_grupo where id=2",Boolean.class));
        assertNull(jdbc.queryForObject("select responsavel_atendimento_id from tb_sessao_grupo where id=3",Long.class));assertFalse(jdbc.queryForObject("select responsabilidade_pendente from tb_sessao_grupo where id=3",Boolean.class));
        assertEquals(0,jdbc.queryForObject("select count(*) from tb_sessao_grupo_aud where responsavel_atendimento_id is not null or responsabilidade_pendente is not null",Integer.class));
        ResponsabilidadeSessaoGuard.verificar(source);jdbc.execute("SHUTDOWN");var latestColumns=LegacyMigrationFixture.columns(source);var latest=LegacyMigrationFixture.snapshot(source,latestColumns);assertEquals(0,AgendaMigrationTool.migrar(source).migrationsExecuted);assertEquals(latest,LegacyMigrationFixture.snapshot(source,latestColumns));
        assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("update tb_sessao_grupo set responsavel_atendimento_id=999999 where id=1"));
        assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("update tb_sessao_grupo set responsabilidade_pendente=false where id=2"));
    }
    @ParameterizedTest @ValueSource(strings={"missing_session_creation","first_real_only","wrong_group","missing_group_creation","deleted_group_evidence","cancel_then_real"})
    void incompleteOrContradictoryEvidenceNeverAssignsCurrentCoordinator(String defect) throws Exception {
        var source=v4(defect);var jdbc=new JdbcTemplate(source);
        switch(defect){
            case "missing_session_creation"->jdbc.update("update tb_sessao_grupo_aud set revtype=1 where id=1 and revtype=0");
            case "first_real_only"->jdbc.update("update tb_sessao_grupo_aud set status='REALIZADA' where id=1 and revtype=0");
            case "wrong_group"->jdbc.update("update tb_sessao_grupo_aud set grupo_id=999999 where id=1 and revtype=0");
            case "missing_group_creation"->jdbc.update("update tb_grupo_terapeutico_aud set revtype=1 where id=1 and revtype=0");
            case "deleted_group_evidence"->jdbc.update("update tb_grupo_terapeutico_aud set revtype=2 where id=1 and revtype=0");
            case "cancel_then_real"->jdbc.update("update tb_sessao_grupo_aud set status='CANCELADA' where id=1 and status='AGENDADA'");
            default->throw new IllegalArgumentException();
        }
        var cols=LegacyMigrationFixture.columns(source);cols.remove("sibraps_agenda_schema_history");var before=LegacyMigrationFixture.snapshot(source,cols);AgendaMigrationTool.migrar(source);assertEquals(before,LegacyMigrationFixture.snapshot(source,cols));
        assertNull(jdbc.queryForObject("select responsavel_atendimento_id from tb_sessao_grupo where id=1",Long.class));assertTrue(jdbc.queryForObject("select responsabilidade_pendente from tb_sessao_grupo where id=1",Boolean.class));assertEquals(3,jdbc.queryForObject("select version from tb_sessao_grupo where id=1",Integer.class));
    }
    @Test void migratedOwnershipPermissionsAuditsAndNewCorrectionSurviveRestartAndRestore() throws Exception {
        var source=v4("restart-owner");AgendaMigrationTool.migrar(source);var jdbc=new JdbcTemplate(source);var oldAudits=jdbc.queryForList("select * from tb_auditoria_correcao_frequencia order by id");
        CorrectiveMigrationApplication.application(source).run(context->{
            assertThat(context).hasNotFailed();var mvc=MockMvcBuilders.webAppContextSetup((WebApplicationContext)context.getSourceApplicationContext()).addFilters(context.getBean("springSecurityFilterChain",jakarta.servlet.Filter.class)).build();var users=context.getBean(UsuarioRepository.class);var json=context.getBean(ObjectMapper.class);var tokenService=context.getBean(TokenService.class);var a=users.findById(2L).orElseThrow();var b=users.findById(3L).orElseThrow();var admin=users.findById(1L).orElseThrow();var p=context.getBean(PacienteRepository.class).findById(1L).orElseThrow();
            new org.springframework.transaction.support.TransactionTemplate(context.getBean(org.springframework.transaction.PlatformTransactionManager.class)).execute(tx->{
                var em=context.getBean(jakarta.persistence.EntityManager.class);var reader=org.hibernate.envers.AuditReaderFactory.get(em);
                var revision=jdbc.queryForObject("select min(rev) from tb_sessao_grupo_aud where id=1 and status='REALIZADA'",Integer.class);
                var historical=reader.find(com.pet.buscaativa.entities.SessaoGrupo.class,1L,revision);
                assertEquals(StatusSessaoGrupo.REALIZADA,historical.getStatus());assertNull(historical.getResponsavelAtendimento());
                assertEquals(2L,historical.getGrupo().getCoordenador().getId());return null;
            });
            var dto=new CorrigirFrequenciasGrupoDTO(List.of(new FrequenciaParticipanteGrupoDTO(p.getIdPublico(),StatusPresencaGrupo.PRESENTE)),3,"Correção sintética autorizada do histórico após migração.");
            var r=mvc.perform(patch("/api/grupos/sessoes/1/frequencias").header("Authorization","Bearer "+tokenService.gerarToken(b)).contentType("application/json").content(json.writeValueAsString(dto))).andReturn().getResponse();assertEquals(403,r.getStatus());
            r=mvc.perform(get("/api/grupos/sessoes/1/correcoes").header("Authorization","Bearer "+tokenService.gerarToken(a))).andReturn().getResponse();assertEquals(200,r.getStatus());var previous=json.readTree(r.getContentAsString()).get("content");assertEquals(2,previous.size());
            for(var row:previous)assertEquals(a.getIdPublico().toString(),row.get("responsavelHistoricoSessaoId").asText());assertTrue(java.util.stream.StreamSupport.stream(previous.spliterator(),false).anyMatch(row->b.getIdPublico().toString().equals(row.get("responsavelAtendimentoId").asText())));
            r=mvc.perform(patch("/api/grupos/sessoes/1/frequencias").header("Authorization","Bearer "+tokenService.gerarToken(a)).contentType("application/json").content(json.writeValueAsString(dto))).andReturn().getResponse();assertEquals(200,r.getStatus(),r.getContentAsString());
            var response=json.readTree(r.getContentAsString());assertEquals(a.getIdPublico().toString(),response.get("responsavelAtendimentoId").asText());assertFalse(response.get("responsabilidadePendente").asBoolean());
            var pendingDto=new CorrigirFrequenciasGrupoDTO(dto.frequencias(),0,dto.justificativa());
            for(var actor:List.of(a,b,admin)){r=mvc.perform(patch("/api/grupos/sessoes/2/frequencias").header("Authorization","Bearer "+tokenService.gerarToken(actor)).contentType("application/json").content(json.writeValueAsString(pendingDto))).andReturn().getResponse();assertEquals(409,r.getStatus());}
            r=mvc.perform(get("/api/grupos/sessoes/2/correcoes").header("Authorization","Bearer "+tokenService.gerarToken(admin))).andReturn().getResponse();assertEquals(200,r.getStatus());
            r=mvc.perform(get("/api/grupos/sessoes/2/correcoes").header("Authorization","Bearer "+tokenService.gerarToken(a))).andReturn().getResponse();assertEquals(403,r.getStatus());
        });
        var all=jdbc.queryForList("select * from tb_auditoria_correcao_frequencia order by id");assertEquals(3,all.size());assertTrue(all.containsAll(oldAudits));assertEquals(4,jdbc.queryForObject("select version from tb_sessao_grupo where id=1",Integer.class));
        var columns=LegacyMigrationFixture.columns(source);var latest=LegacyMigrationFixture.snapshot(source,columns);var backup=temp.resolve("latest.sql");LegacyMigrationFixture.backup(source,backup);jdbc.execute("SHUTDOWN");
        for(var copy:List.of(source,LegacyMigrationFixture.restore(temp.resolve("restored-owner"),backup))){assertEquals(0,AgendaMigrationTool.migrar(copy).migrationsExecuted);assertEquals(latest,LegacyMigrationFixture.snapshot(copy,columns));
            CorrectiveMigrationApplication.application(copy).run(context->{assertThat(context).hasNotFailed();var s=context.getBean(SessaoGrupoRepository.class).findById(1L).orElseThrow();assertEquals(2L,s.getResponsavelAtendimento().getId());assertEquals(3,context.getBean(AuditoriaCorrecaoRepository.class).count());});assertEquals(latest,LegacyMigrationFixture.snapshot(copy,columns));
        }
        System.out.println("RESPONSIBILITY_RESTORE applicationBoots=3 oldAudits=2 newAudit=1 knownOwner=A ambiguousPending=true restartMigrations=0 fullSnapshotPreserved=true");
    }
}
