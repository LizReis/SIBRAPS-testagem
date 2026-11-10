package com.pet.buscaativa;

import static org.junit.jupiter.api.Assertions.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.flywaydb.core.Flyway;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import com.pet.buscaativa.entities.enums.*;
import com.pet.buscaativa.migration.*;
import com.pet.buscaativa.repositories.*;
import com.pet.buscaativa.services.TokenService;
import com.pet.buscaativa.services.impl.SusApiAdapter;
import tools.jackson.databind.ObjectMapper;

class ClinicalAuditMigrationTests {
    @TempDir Path temp;
    @ParameterizedTest @ValueSource(booleans={false,true})
    void auditMigrationWorksFromOldAndStageTwoSchemasWithoutInventingHistory(boolean stageTwo) throws Exception {
        var source=LegacyMigrationFixture.legacy(temp.resolve(stageTwo?"stage-two":"old"));
        if(stageTwo)Flyway.configure().dataSource(source).table("sibraps_agenda_schema_history").locations("classpath:agenda-migration/h2")
            .javaMigrations(new V2__identificar_frequencias_legadas()).baselineOnMigrate(true).baselineVersion("0").target("2").load().migrate();
        var columns=LegacyMigrationFixture.columns(source);columns.remove("sibraps_agenda_schema_history");var before=LegacyMigrationFixture.snapshot(source,columns);
        assertEquals(stageTwo?1:3,AgendaMigrationTool.migrar(source).migrationsExecuted);assertEquals(before,LegacyMigrationFixture.snapshot(source,columns));
        var jdbc=new JdbcTemplate(source);assertEquals(0,jdbc.queryForObject("select count(*) from tb_auditoria_correcao_frequencia",Integer.class));
        assertEquals(12,jdbc.queryForObject("select count(*) from tb_agendamento",Integer.class));assertEquals(6,jdbc.queryForObject("select count(*) from tb_historico_paciente",Integer.class));assertEquals(2,jdbc.queryForObject("select count(*) from revinfo",Integer.class));
        assertEquals(7,jdbc.queryForObject("select version from tb_agendamento where id=4",Integer.class));
        AuditoriaSchemaGuard.verificar(source);jdbc.execute("SHUTDOWN");assertEquals(0,AgendaMigrationTool.migrar(source).migrationsExecuted);
    }
    @Test void missingAppendOnlyProtectionBlocksStartupAndIsNotAutomaticallyRepaired() throws Exception {
        var source=LegacyMigrationFixture.legacy(temp.resolve("missing-trigger"));AgendaMigrationTool.migrar(source);var jdbc=new JdbcTemplate(source);jdbc.execute("drop trigger tr_correcao_append_only");
        var columns=LegacyMigrationFixture.columns(source);var before=LegacyMigrationFixture.snapshot(source,columns);
        assertThrows(IllegalStateException.class,()->AuditoriaSchemaGuard.verificar(source));assertThrows(IllegalStateException.class,()->AgendaMigrationTool.migrar(source));assertEquals(before,LegacyMigrationFixture.snapshot(source,columns));
    }
    @Test void foreignKeysAndCorrectionChecksAreEnforcedInPersistentDatabase() throws Exception {
        var source=LegacyMigrationFixture.legacy(temp.resolve("constraints"));AgendaMigrationTool.migrar(source);var jdbc=new JdbcTemplate(source);
        var sql="""
            insert into tb_auditoria_correcao_frequencia(id,agendamento_id,paciente_id,ator_id,responsavel_id,perfil,unidade,tipo_correcao,situacao_reserva,
            frequencia_anterior,frequencia_nova,justificativa,ocorrido_em,versao_anterior,versao_resultante)
            values(?,4,1,?,2,'ADMINISTRADOR','CAPS:CAPS_AD','INDIVIDUAL','AGENDADA',?,?,?,CURRENT_TIMESTAMP,?,?)
            """;
        assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update(sql,UUID.randomUUID(),999999,"FALTOU","PRESENTE","Motivo sintético de retificação",0,1));
        assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update(sql,UUID.randomUUID(),1,"PRESENTE","PRESENTE","Motivo sintético de retificação",0,1));
        assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update(sql,UUID.randomUUID(),1,"FALTOU","PRESENTE",null,0,1));
        assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update(sql,UUID.randomUUID(),1,"FALTOU","PRESENTE","Motivo sintético de retificação",1,1));
        assertEquals(0,jdbc.queryForObject("select count(*) from tb_auditoria_correcao_frequencia",Integer.class));
    }
    private WebApplicationContextRunner application(DataSource source,String profile,String ddl) {
        return new WebApplicationContextRunner().withInitializer(c->{TestEnvironmentIsolation.apply(c.getEnvironment());new ConfigDataApplicationContextInitializer().initialize(c);})
            .withUserConfiguration(BuscaativaApplication.class,H2ProfileSecurityTests.ExternalServices.class).withBean("dataSource",DataSource.class,()->source)
            .withPropertyValues("spring.config.location=file:src/main/resources/","spring.profiles.active="+profile,"spring.jpa.hibernate.ddl-auto="+ddl,
                "JWT_SECRET_BASE64="+Base64.getEncoder().encodeToString(SecurityTestSupport.randomBytes()),"DEV_DB_USERNAME=sa","DEV_DB_PASSWORD="+UUID.randomUUID(),"app.alertas.email.habilitado=false","logging.level.root=ERROR");
    }
    @Test void realCorrectionSurvivesApplicationRestartAndLatestBackupRestore() throws Exception {
        var source=LegacyMigrationFixture.legacy(temp.resolve("application-cycle"));AgendaMigrationTool.migrar(source);
        var id=new AtomicReference<String>();var reason="Correção sintética após conferência no registro de atendimento.";
        application(source,"test","validate").run(context->{
            assertThat(context).hasNotFailed();var mvc=MockMvcBuilders.webAppContextSetup((WebApplicationContext)context.getSourceApplicationContext()).addFilters(context.getBean("springSecurityFilterChain",jakarta.servlet.Filter.class)).build();
            var actor=context.getBean(UsuarioRepository.class).findAll().stream().filter(u->u.getTipoUsuario()==TipoUsuario.ADMINISTRADOR).findFirst().orElseThrow();var token=context.getBean(TokenService.class).gerarToken(actor);
            var r=mvc.perform(patch("/api/agendamentos/4/status").param("novoStatus","FALTOU").param("version","7").param("justificativa",reason).header("Authorization","Bearer "+token)).andReturn().getResponse();assertEquals(200,r.getStatus());
            var json=context.getBean(ObjectMapper.class);r=mvc.perform(get("/api/agendamentos/4/correcoes").header("Authorization","Bearer "+token)).andReturn().getResponse();assertEquals(200,r.getStatus());var row=json.readTree(r.getContentAsString()).get("content").get(0);id.set(row.get("id").asText());assertEquals(actor.getIdPublico().toString(),row.get("usuarioId").asText());assertEquals(8,row.get("versaoResultante").asInt());
        });
        assertNotNull(id.get());var jdbc=new JdbcTemplate(source);jdbc.execute("SHUTDOWN");assertEquals(0,AgendaMigrationTool.migrar(source).migrationsExecuted);
        var columns=LegacyMigrationFixture.columns(source);var latest=LegacyMigrationFixture.snapshot(source,columns);var backup=temp.resolve("latest-audited.sql");LegacyMigrationFixture.backup(source,backup);
        for(var reopened:List.of(source,LegacyMigrationFixture.restore(temp.resolve("restored-audited"),backup))) {
            assertEquals(latest,LegacyMigrationFixture.snapshot(reopened,columns));assertEquals(0,AgendaMigrationTool.migrar(reopened).migrationsExecuted);
            application(reopened,"test","validate").run(context->{
                assertThat(context).hasNotFailed();var actor=context.getBean(UsuarioRepository.class).findAll().stream().filter(u->u.getTipoUsuario()==TipoUsuario.ADMINISTRADOR).findFirst().orElseThrow();var token=context.getBean(TokenService.class).gerarToken(actor);
                var mvc=MockMvcBuilders.webAppContextSetup((WebApplicationContext)context.getSourceApplicationContext()).addFilters(context.getBean("springSecurityFilterChain",jakarta.servlet.Filter.class)).build();var response=mvc.perform(get("/api/agendamentos/4/correcoes").header("Authorization","Bearer "+token)).andReturn().getResponse();assertEquals(200,response.getStatus());var row=context.getBean(ObjectMapper.class).readTree(response.getContentAsString()).get("content").get(0);assertEquals(id.get(),row.get("id").asText());assertEquals(reason,row.get("justificativa").asText());
                assertEquals(8,context.getBean(AgendamentoRepository.class).findById(4L).orElseThrow().getVersion());
            });
            assertEquals(latest,LegacyMigrationFixture.snapshot(reopened,columns));
            var database=new JdbcTemplate(reopened);assertThrows(org.springframework.dao.DataAccessException.class,()->database.update("delete from tb_auditoria_correcao_frequencia"));
        }
        System.out.println("AUDIT_RESTORE applicationBoots=3 auditRows=1 actualClinicalCorrection=true restartMigrations=0 latestBackupPreserved=true");
    }
    @Test void disposableDevMemoryCreatesProtectedAuditStructureWithoutHistoricalCorrections() {
        var config=new com.zaxxer.hikari.HikariConfig();config.setJdbcUrl("jdbc:h2:mem:dev-audit-"+UUID.randomUUID());config.setUsername("sa");config.setPassword(UUID.randomUUID().toString());config.setMaximumPoolSize(1);config.setConnectionTimeout(1000);
        try(var source=new com.zaxxer.hikari.HikariDataSource(config)) {
            application(source,"dev","create-drop").run(context->{assertThat(context).hasNotFailed();AuditoriaSchemaGuard.verificar(source);assertEquals(0,context.getBean(AuditoriaCorrecaoRepository.class).count());});
        }
    }
}
