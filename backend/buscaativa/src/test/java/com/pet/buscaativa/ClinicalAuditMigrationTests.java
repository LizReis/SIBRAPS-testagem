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
    @ParameterizedTest @ValueSource(ints={0,2,3})
    void auditMigrationWorksFromOldAndStageTwoSchemasWithoutInventingHistory(int version) throws Exception {
        var source=LegacyMigrationFixture.legacy(temp.resolve("schema-v"+version));
        if(version>0)Flyway.configure().dataSource(source).table("sibraps_agenda_schema_history").locations("classpath:agenda-migration/h2")
            .javaMigrations(new V2__identificar_frequencias_legadas()).baselineOnMigrate(true).baselineVersion("0").target(Integer.toString(version)).load().migrate();
        var columns=LegacyMigrationFixture.columns(source);columns.remove("sibraps_agenda_schema_history");var before=LegacyMigrationFixture.snapshot(source,columns);
        assertEquals(7-version,AgendaMigrationTool.migrar(source).migrationsExecuted);assertEquals(before,LegacyMigrationFixture.snapshot(source,columns));
        var jdbc=new JdbcTemplate(source);assertEquals(0,jdbc.queryForObject("select count(*) from tb_auditoria_correcao_frequencia",Integer.class));
        assertEquals(0,jdbc.queryForObject("select count(*) from tb_auditoria_busca_ativa",Integer.class));
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
        jdbc.update("insert into tb_grupo_terapeutico(id,coordenador_id,tema,ativo,version) values(500,2,'Grupo sintético de constraint',true,0)");
        jdbc.update("insert into tb_sessao_grupo(id,grupo_id,data_sessao,horario,status,version,responsavel_atendimento_id,responsabilidade_pendente) values(500,500,DATE '2026-01-01',TIME '09:00','REALIZADA',0,2,false)");
        jdbc.update("insert into tb_sessao_grupo_participante(id,sessao_grupo_id,paciente_id,status_presenca) values(500,500,1,'FALTOU')");
        assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("""
            insert into tb_auditoria_correcao_frequencia(id,sessao_id,participante_id,paciente_id,ator_id,responsavel_id,perfil,unidade,tipo_correcao,
            situacao_sessao,frequencia_anterior,frequencia_nova,justificativa,ocorrido_em,versao_anterior,versao_resultante)
            values(?,500,500,1,1,2,'ADMINISTRADOR','CAPS:CAPS_AD','GRUPO',null,'FALTOU','PRESENTE','Motivo sintético conferido',CURRENT_TIMESTAMP,0,1)
            """,UUID.randomUUID()));
        assertEquals(0,jdbc.queryForObject("select count(*) from tb_auditoria_correcao_frequencia",Integer.class));
        assertEquals(0,jdbc.queryForObject("select count(*) from tb_auditoria_busca_ativa",Integer.class));
    }
    @Test void alertChecksRejectNullOperationalDataAndPhantomIndividualViewing() throws Exception {
        var source=LegacyMigrationFixture.legacy(temp.resolve("event-constraints"));AgendaMigrationTool.migrar(source);var jdbc=new JdbcTemplate(source);
        var sql="""
            insert into tb_auditoria_busca_ativa(id,paciente_id,ator_id,perfil,unidade,tipo_evento,chave_requisicao,ocorrido_em,situacao,
            gatilho_confirmado,indicadores_pendentes,situacao_rua,quantidade,destino,descricao)
            values(?,?,1,'ADMINISTRADOR','CAPS:CAPS_AD',?,?,CURRENT_TIMESTAMP,'RISCO_VERMELHO',false,false,false,1,?,?)
            """;
        assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update(sql,UUID.randomUUID(),1,"ENCAMINHAMENTO_ALERTA",UUID.randomUUID(),null,"Registro formal conferido pela equipe."));
        assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update(sql,UUID.randomUUID(),1,"ENCAMINHAMENTO_ALERTA",UUID.randomUUID(),"Equipe sintética",null));
        assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update(sql,UUID.randomUUID(),null,"VISUALIZACAO_ALERTA",UUID.randomUUID(),null,null));
        assertEquals(0,jdbc.queryForObject("select count(*) from tb_auditoria_busca_ativa",Integer.class));
    }
    private WebApplicationContextRunner application(DataSource source,String profile,String ddl) {
        return new WebApplicationContextRunner().withInitializer(c->{TestEnvironmentIsolation.apply(c.getEnvironment());new ConfigDataApplicationContextInitializer().initialize(c);})
            .withUserConfiguration(BuscaativaApplication.class,H2ProfileSecurityTests.ExternalServices.class).withBean("dataSource",DataSource.class,()->source)
            .withPropertyValues("spring.config.location=file:src/main/resources/","spring.profiles.active="+profile,"spring.jpa.hibernate.ddl-auto="+ddl,
                "JWT_SECRET_BASE64="+Base64.getEncoder().encodeToString(SecurityTestSupport.randomBytes()),"DEV_DB_USERNAME=sa","DEV_DB_PASSWORD="+UUID.randomUUID(),"app.alertas.email.habilitado=false","logging.level.root=ERROR");
    }
    @Test void realCorrectionSurvivesApplicationRestartAndLatestBackupRestore() throws Exception {
        var source=LegacyMigrationFixture.legacy(temp.resolve("application-cycle"));AgendaMigrationTool.migrar(source);
        var id=new AtomicReference<String>();var alertPatient=new AtomicReference<UUID>();var alertEvent=new AtomicReference<String>();var reason="Correção sintética após conferência no registro de atendimento.";
        application(source,"test","validate").run(context->{
            assertThat(context).hasNotFailed();var mvc=MockMvcBuilders.webAppContextSetup((WebApplicationContext)context.getSourceApplicationContext()).addFilters(context.getBean("springSecurityFilterChain",jakarta.servlet.Filter.class)).build();
            var actor=context.getBean(UsuarioRepository.class).findAll().stream().filter(u->u.getTipoUsuario()==TipoUsuario.ADMINISTRADOR).findFirst().orElseThrow();var token=context.getBean(TokenService.class).gerarToken(actor);
            var r=mvc.perform(patch("/api/agendamentos/4/status").param("novoStatus","FALTOU").param("version","7").param("justificativa",reason).header("Authorization","Bearer "+token)).andReturn().getResponse();assertEquals(200,r.getStatus());
            var json=context.getBean(ObjectMapper.class);r=mvc.perform(get("/api/agendamentos/4/correcoes").header("Authorization","Bearer "+token)).andReturn().getResponse();assertEquals(200,r.getStatus());var row=json.readTree(r.getContentAsString()).get("content").get(0);id.set(row.get("id").asText());assertEquals(actor.getIdPublico().toString(),row.get("usuarioId").asText());assertEquals(8,row.get("versaoResultante").asInt());
            var manager=context.getBean(org.springframework.transaction.PlatformTransactionManager.class);
            var ids=new org.springframework.transaction.support.TransactionTemplate(manager).execute(status->{
                var p=new com.pet.buscaativa.entities.Paciente();p.setNome("Paciente sintético RNF17 persistido");p.setNomeMae("Mãe sintética");p.setDataNascimento(java.time.LocalDate.of(1990,1,1));p.setCapsReferencia(CapsEnum.CAPS_AD);p.setStatusPaciente(StatusPaciente.ATIVO);p.setTipoAcompanhamento(TipoAcompanhamento.INDIVIDUAL);context.getBean(PacienteRepository.class).saveAndFlush(p);alertPatient.set(p.getIdPublico());
                var responsible=context.getBean(UsuarioRepository.class).findAll().stream().filter(u->u.getTipoUsuario()==TipoUsuario.PROFISSIONAL).findFirst().orElseThrow();var appointments=context.getBean(AgendamentoRepository.class);var result=new ArrayList<Long>();
                for(int i=1;i<=3;i++){var a=new com.pet.buscaativa.entities.Agendamento();a.setPaciente(p);a.setUsuario(responsible);a.setDataAgendamento(java.time.LocalDate.now().minusDays(i));a.setHoraAtendimento(java.time.LocalTime.of(9,0));a.setTurnoAgendamento(TurnoEnum.MANHA);a.setSituacaoAtendimento(SituacaoAtendimento.AGENDADO);result.add(appointments.saveAndFlush(a).getId());}return result;
            });
            for(var appointmentId:ids)assertEquals(200,mvc.perform(patch("/api/agendamentos/"+appointmentId+"/status").param("novoStatus","FALTOU").header("Authorization","Bearer "+token)).andReturn().getResponse().getStatus());
            assertEquals(200,mvc.perform(get("/api/pacientes/busca-ativa").header("Authorization","Bearer "+token)).andReturn().getResponse().getStatus());
            var visual=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/pacientes/"+alertPatient.get()+"/alertas/visualizacoes").header("Authorization","Bearer "+token).contentType("application/json").content(json.writeValueAsString(new com.pet.buscaativa.entities.dto.VisualizacaoAlertaDTO(UUID.randomUUID())))).andReturn().getResponse();assertEquals(201,visual.getStatus());
            var forward=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/pacientes/"+alertPatient.get()+"/alertas/encaminhamentos").header("Authorization","Bearer "+token).contentType("application/json").content(json.writeValueAsString(new com.pet.buscaativa.entities.dto.EncaminhamentoAlertaDTO(UUID.randomUUID(),"Equipe sintética","Registrada solicitação formal de avaliação pela equipe.")))).andReturn().getResponse();assertEquals(201,forward.getStatus());alertEvent.set(json.readTree(forward.getContentAsString()).get("id").asText());
            assertEquals(3,context.getBean(AuditoriaBuscaAtivaRepository.class).count());
        });
        assertNotNull(id.get());var jdbc=new JdbcTemplate(source);jdbc.execute("SHUTDOWN");assertEquals(0,AgendaMigrationTool.migrar(source).migrationsExecuted);
        var columns=LegacyMigrationFixture.columns(source);var latest=LegacyMigrationFixture.snapshot(source,columns);var backup=temp.resolve("latest-audited.sql");LegacyMigrationFixture.backup(source,backup);
        for(var reopened:List.of(source,LegacyMigrationFixture.restore(temp.resolve("restored-audited"),backup))) {
            assertEquals(latest,LegacyMigrationFixture.snapshot(reopened,columns));assertEquals(0,AgendaMigrationTool.migrar(reopened).migrationsExecuted);
            application(reopened,"test","validate").run(context->{
                assertThat(context).hasNotFailed();var actor=context.getBean(UsuarioRepository.class).findAll().stream().filter(u->u.getTipoUsuario()==TipoUsuario.ADMINISTRADOR).findFirst().orElseThrow();var token=context.getBean(TokenService.class).gerarToken(actor);
                var mvc=MockMvcBuilders.webAppContextSetup((WebApplicationContext)context.getSourceApplicationContext()).addFilters(context.getBean("springSecurityFilterChain",jakarta.servlet.Filter.class)).build();var response=mvc.perform(get("/api/agendamentos/4/correcoes").header("Authorization","Bearer "+token)).andReturn().getResponse();assertEquals(200,response.getStatus());var row=context.getBean(ObjectMapper.class).readTree(response.getContentAsString()).get("content").get(0);assertEquals(id.get(),row.get("id").asText());assertEquals(reason,row.get("justificativa").asText());
                assertEquals(8,context.getBean(AgendamentoRepository.class).findById(4L).orElseThrow().getVersion());
                var alertResponse=mvc.perform(get("/api/pacientes/"+alertPatient.get()+"/alertas/auditoria").header("Authorization","Bearer "+token)).andReturn().getResponse();assertEquals(200,alertResponse.getStatus());var alertRows=context.getBean(ObjectMapper.class).readTree(alertResponse.getContentAsString()).get("content");assertEquals(2,alertRows.size());assertTrue(java.util.stream.StreamSupport.stream(alertRows.spliterator(),false).anyMatch(e->alertEvent.get().equals(e.get("id").asText())));assertEquals(3,context.getBean(AuditoriaBuscaAtivaRepository.class).count());
            });
            assertEquals(latest,LegacyMigrationFixture.snapshot(reopened,columns));
            var database=new JdbcTemplate(reopened);assertThrows(org.springframework.dao.DataAccessException.class,()->database.update("delete from tb_auditoria_correcao_frequencia"));
            assertThrows(org.springframework.dao.DataAccessException.class,()->database.update("delete from tb_auditoria_busca_ativa"));
        }
        System.out.println("AUDIT_RESTORE applicationBoots=3 auditRows=1 alertEvents=3 actualClinicalCorrection=true restartMigrations=0 latestBackupPreserved=true");
    }
    @Test void disposableDevMemoryCreatesProtectedAuditStructureWithoutHistoricalCorrections() {
        var config=new com.zaxxer.hikari.HikariConfig();config.setJdbcUrl("jdbc:h2:mem:dev-audit-"+UUID.randomUUID());config.setUsername("sa");config.setPassword(UUID.randomUUID().toString());config.setMaximumPoolSize(1);config.setConnectionTimeout(1000);
        try(var source=new com.zaxxer.hikari.HikariDataSource(config)) {
            application(source,"dev","create-drop").run(context->{assertThat(context).hasNotFailed();AuditoriaSchemaGuard.verificar(source);assertEquals(0,context.getBean(AuditoriaCorrecaoRepository.class).count());});
        }
    }
}
