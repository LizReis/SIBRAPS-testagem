package com.pet.buscaativa;

import static org.junit.jupiter.api.Assertions.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.transaction.support.TransactionTemplate;
import com.pet.buscaativa.entities.*;
import com.pet.buscaativa.entities.dto.*;
import com.pet.buscaativa.entities.enums.*;
import com.pet.buscaativa.repositories.*;
import com.pet.buscaativa.services.*;
import com.pet.buscaativa.migration.*;
import tools.jackson.databind.ObjectMapper;

class CancellationMigrationPersistenceTests {
    @TempDir Path temp;
    private void toVersion(DataSource source,int version){Flyway.configure().dataSource(source).table("sibraps_agenda_schema_history").locations("classpath:agenda-migration/h2").javaMigrations(new V2__identificar_frequencias_legadas(),new V6__identificar_responsabilidade_historica()).baselineOnMigrate(true).baselineVersion("0").target(""+version).load().migrate();}
    @ParameterizedTest @ValueSource(ints={0,4,6})
    void v7IsAdditiveFromOldAndV6AndNeverBackfillsRegularization(int version) throws Exception {var source=LegacyMigrationFixture.legacy(temp.resolve("from-"+version));if(version>0)toVersion(source,version);var columns=LegacyMigrationFixture.columns(source);columns.remove("sibraps_agenda_schema_history");var before=LegacyMigrationFixture.snapshot(source,columns);assertEquals(7-version,AgendaMigrationTool.migrar(source).migrationsExecuted);assertEquals(before,LegacyMigrationFixture.snapshot(source,columns));var jdbc=new JdbcTemplate(source);assertEquals(0,jdbc.queryForObject("select count(*) from tb_auditoria_regularizacao_cancelamento",Integer.class));AuditoriaSchemaGuard.verificar(source);jdbc.execute("SHUTDOWN");assertEquals(0,AgendaMigrationTool.migrar(source).migrationsExecuted);assertEquals(before,LegacyMigrationFixture.snapshot(source,columns));}
    @Test void v6ApplicationRequiresExplicitV7AndDoesNotMigrateAtStartup() throws Exception {var source=LegacyMigrationFixture.legacy(temp.resolve("v6-start"));toVersion(source,6);var cols=LegacyMigrationFixture.columns(source);var before=LegacyMigrationFixture.snapshot(source,cols);CorrectiveMigrationApplication.application(source).run(context->assertThat(context).hasFailed());assertEquals(before,LegacyMigrationFixture.snapshot(source,cols));assertEquals(1,AgendaMigrationTool.migrar(source).migrationsExecuted);CorrectiveMigrationApplication.application(source).run(context->assertThat(context).hasNotFailed());}
    @Test void v7ChecksForeignIdentityAndAppendOnlyAreEnforced() throws Exception {var source=LegacyMigrationFixture.legacy(temp.resolve("constraints"));AgendaMigrationTool.migrar(source);var jdbc=new JdbcTemplate(source);var sql="""
        insert into tb_auditoria_regularizacao_cancelamento(id,agendamento_id,paciente_id,ator_id,responsavel_id,perfil,unidade,tipo_regularizacao,
        reserva_anterior,reserva_resultante,frequencia_anterior,frequencia_resultante,justificativa,ocorrido_em,versao_anterior,versao_resultante)
        values(?,6,?, ?,?,'ADMINISTRADOR','CAPS:CAPS_AD',?,'CANCELADA',?,'NAO_REGISTRADA',?,'Regularização sintética conferida',CURRENT_TIMESTAMP,0,?)
        """;
        assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update(sql,UUID.randomUUID(),1,99999,2,"RECUPERACAO_RESERVA","AGENDADA","NAO_REGISTRADA",1));
        assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update(sql,UUID.randomUUID(),2,1,2,"RECUPERACAO_RESERVA","AGENDADA","NAO_REGISTRADA",1));
        assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update(sql,UUID.randomUUID(),1,1,3,"RECUPERACAO_RESERVA","AGENDADA","NAO_REGISTRADA",1));
        assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update(sql,UUID.randomUUID(),1,1,2,"RECUPERACAO_RESERVA","AGENDADA","PRESENTE",1));
        assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update(sql,UUID.randomUUID(),1,1,2,"REGISTRO_FATO_ASSISTENCIAL","AGENDADA","PRESENTE",1));
        assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update(sql,UUID.randomUUID(),1,1,2,"RECUPERACAO_RESERVA","AGENDADA","NAO_REGISTRADA",0));
        jdbc.update(sql,UUID.randomUUID(),1,1,2,"RECUPERACAO_RESERVA","AGENDADA","NAO_REGISTRADA",1);
        assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("update tb_auditoria_regularizacao_cancelamento set justificativa='Outra razão sintética conferida'"));assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("delete from tb_auditoria_regularizacao_cancelamento"));assertEquals(1,jdbc.queryForObject("select count(*) from tb_auditoria_regularizacao_cancelamento",Integer.class));
    }
    @Test void missingProtectionFailsClosedWithoutAutomaticRepair() throws Exception {var source=LegacyMigrationFixture.legacy(temp.resolve("missing-protection"));AgendaMigrationTool.migrar(source);var jdbc=new JdbcTemplate(source);jdbc.execute("drop trigger tr_regularizacao_append_only");assertThrows(IllegalStateException.class,()->AuditoriaSchemaGuard.verificar(source));assertThrows(IllegalStateException.class,()->AgendaMigrationTool.migrar(source));assertEquals(0,jdbc.queryForObject("select count(*) from tb_auditoria_regularizacao_cancelamento",Integer.class));}
    @Test void bothRealOperationsAndSubsequentCorrectionSurviveRestartAndLatestRestore() throws Exception {
        var source=LegacyMigrationFixture.legacy(temp.resolve("real-cycle"));AgendaMigrationTool.migrar(source);var initialBackup=temp.resolve("before.sql");LegacyMigrationFixture.backup(source,initialBackup);var ids=new java.util.concurrent.atomic.AtomicReference<Long[]>();var ledgerIds=new java.util.concurrent.atomic.AtomicReference<List<UUID>>();
        CorrectiveMigrationApplication.application(source).run(context->{assertThat(context).hasNotFailed();var mvc=MockMvcBuilders.webAppContextSetup((WebApplicationContext)context.getSourceApplicationContext()).addFilters(context.getBean("springSecurityFilterChain",jakarta.servlet.Filter.class)).build();var users=context.getBean(UsuarioRepository.class);var admin=users.findById(1L).orElseThrow();var owner=users.findById(2L).orElseThrow();var token=context.getBean(TokenService.class).gerarToken(admin);var json=context.getBean(ObjectMapper.class);var today=LocalDate.now(ZoneId.of("America/Bahia"));
            var data=new TransactionTemplate(context.getBean(org.springframework.transaction.PlatformTransactionManager.class)).execute(status->{var p=new Paciente();p.setNome("Paciente sintético de regularização persistida");p.setNomeMae("Mãe sintética");p.setDataNascimento(LocalDate.of(1990,1,1));p.setCapsReferencia(CapsEnum.CAPS_AD);p.setStatusPaciente(StatusPaciente.ATIVO);p.setTipoAcompanhamento(TipoAcompanhamento.INDIVIDUAL);context.getBean(PacienteRepository.class).saveAndFlush(p);var config=new DisponibilidadeExcecao();config.setUsuario(owner);config.setData(today.plusDays(2));config.setTurno(TurnoEnum.MANHA);config.setCapacidade(10);context.getBean(DisponibilidadeExcecaoRepository.class).saveAndFlush(config);var past=new Agendamento();past.setPaciente(p);past.setUsuario(owner);past.setDataAgendamento(today.minusDays(2));past.setHoraAtendimento(LocalTime.of(8,0));past.setTurnoAgendamento(TurnoEnum.MANHA);past.setSituacaoAtendimento(SituacaoAtendimento.CANCELADO);context.getBean(AgendamentoRepository.class).saveAndFlush(past);return new Object[]{p,past};});
            var p=(Paciente)data[0];var past=(Agendamento)data[1];var create=new AgendamentoDTO(null,owner.getIdPublico(),null,p.getIdPublico(),null,null,today.plusDays(2),TurnoEnum.MANHA,LocalTime.of(8,0),SituacaoAtendimento.AGENDADO,null,null);
            var r=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/agendamentos").header("Authorization","Bearer "+token).contentType("application/json").content(json.writeValueAsString(create))).andReturn().getResponse();assertEquals(201,r.getStatus(),r.getContentAsString());long future=json.readTree(r.getContentAsString()).get("id").asLong();assertEquals(200,mvc.perform(patch("/api/agendamentos/"+future+"/status").param("novoStatus","CANCELADO").header("Authorization","Bearer "+token)).andReturn().getResponse().getStatus());
            r=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/agendamentos/"+future+"/regularizacao-cancelamento/recuperar-reserva").header("Authorization","Bearer "+token).contentType("application/json").content(json.writeValueAsString(new RecuperarReservaCanceladaDTO(1,"Cancelamento sintético conferido para recuperação futura.")))).andReturn().getResponse();assertEquals(200,r.getStatus(),r.getContentAsString());
            r=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/agendamentos/"+past.getId()+"/regularizacao-cancelamento/frequencia").header("Authorization","Bearer "+token).contentType("application/json").content(json.writeValueAsString(new RegularizarFrequenciaCanceladaDTO(FrequenciaAssistencial.PRESENTE,0,"Comparecimento sintético comprovado pelo responsável.")))).andReturn().getResponse();assertEquals(200,r.getStatus(),r.getContentAsString());
            r=mvc.perform(patch("/api/agendamentos/"+past.getId()+"/frequencia/correcao").header("Authorization","Bearer "+token).contentType("application/json").content(json.writeValueAsString(new CorrigirFrequenciaDTO(FrequenciaAssistencial.FALTOU,1,"Correção sintética posterior conferida na documentação.")))).andReturn().getResponse();assertEquals(200,r.getStatus(),r.getContentAsString());ids.set(new Long[]{future,past.getId()});assertEquals(2,context.getBean(AuditoriaRegularizacaoRepository.class).count());assertEquals(1,context.getBean(AuditoriaCorrecaoRepository.class).count());
            var revisions=new JdbcTemplate(source);
            assertEquals(3,revisions.queryForObject("select count(*) from tb_agendamento_aud where id=?",Integer.class,future));
            assertEquals(3,revisions.queryForObject("select count(*) from tb_agendamento_aud where id=?",Integer.class,past.getId()));
            ledgerIds.set(new JdbcTemplate(source).queryForList("select id from tb_auditoria_regularizacao_cancelamento order by id",UUID.class));
        });
        var columns=LegacyMigrationFixture.columns(source);var latest=LegacyMigrationFixture.snapshot(source,columns);var backup=temp.resolve("latest.sql");LegacyMigrationFixture.backup(source,backup);new JdbcTemplate(source).execute("SHUTDOWN");
        for(var copy:List.of(source,LegacyMigrationFixture.restore(temp.resolve("restored-latest"),backup))){assertEquals(0,AgendaMigrationTool.migrar(copy).migrationsExecuted);assertEquals(latest,LegacyMigrationFixture.snapshot(copy,columns));CorrectiveMigrationApplication.application(copy).run(context->{assertThat(context).hasNotFailed();var repo=context.getBean(AgendamentoRepository.class);var future=repo.findById(ids.get()[0]).orElseThrow();var past=repo.findById(ids.get()[1]).orElseThrow();assertEquals(SituacaoReserva.AGENDADA,future.getSituacaoReserva());assertEquals(2,future.getVersion());assertEquals(SituacaoReserva.CANCELADA,past.getSituacaoReserva());assertEquals(FrequenciaAssistencial.FALTOU,past.getFrequenciaAssistencial());assertFalse(past.getOcupaVaga());assertEquals(2,past.getVersion());assertEquals(ledgerIds.get(),new JdbcTemplate(copy).queryForList("select id from tb_auditoria_regularizacao_cancelamento order by id",UUID.class));AuditoriaSchemaGuard.verificar(copy);});assertEquals(latest,LegacyMigrationFixture.snapshot(copy,columns));}
        var restoredOld=LegacyMigrationFixture.restore(temp.resolve("restored-initial-only"),initialBackup);assertEquals(0,new JdbcTemplate(restoredOld).queryForObject("select count(*) from tb_auditoria_regularizacao_cancelamento",Integer.class));System.out.println("E5_RESTORE applicationBoots=3 recovery=true factual=true subsequentCorrection=true ledgerRows=2 latestSnapshotPreserved=true initialBackupDoesNotContainLaterWrites=true");
    }
}
