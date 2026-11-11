package com.pet.buscaativa;

import static org.junit.jupiter.api.Assertions.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import com.pet.buscaativa.entities.dto.*;
import com.pet.buscaativa.entities.enums.*;
import com.pet.buscaativa.migration.*;
import com.pet.buscaativa.repositories.*;
import com.pet.buscaativa.services.*;
import tools.jackson.databind.ObjectMapper;

class SpecificCorrectionPersistenceTests {
    @TempDir Path temp;
    @Test void dedicatedCorrectionAndUtcAuditSurviveRestartAndLatestBackupRestore() throws Exception {
        var source=LegacyMigrationFixture.legacy(temp.resolve("specific-api"));AgendaMigrationTool.migrar(source);var id=new java.util.concurrent.atomic.AtomicReference<String>();
        CorrectiveMigrationApplication.application(source).run(context->{assertThat(context).hasNotFailed();var mvc=MockMvcBuilders.webAppContextSetup((WebApplicationContext)context.getSourceApplicationContext()).addFilters(context.getBean("springSecurityFilterChain",jakarta.servlet.Filter.class)).build();var actor=context.getBean(UsuarioRepository.class).findById(2L).orElseThrow();var token=context.getBean(TokenService.class).gerarToken(actor);var json=context.getBean(ObjectMapper.class);var dto=new CorrigirFrequenciaDTO(FrequenciaAssistencial.FALTOU,7,"Registro sintético conferido pelo responsável após migração.");var r=mvc.perform(patch("/api/agendamentos/4/frequencia/correcao").header("Authorization","Bearer "+token).contentType("application/json").content(json.writeValueAsString(dto))).andReturn().getResponse();assertEquals(200,r.getStatus(),r.getContentAsString());var row=context.getBean(AuditoriaCorrecaoRepository.class).findByAgendamento_IdAndUnidade(4L,"CAPS:CAPS_AD",org.springframework.data.domain.Pageable.unpaged()).getContent().get(0);id.set(row.getId().toString());assertEquals(actor.getId(),row.getResponsavel().getId());assertEquals(actor.getId(),row.getAtor().getId());assertEquals(8,row.getVersaoResultante());});
        var columns=LegacyMigrationFixture.columns(source);var snapshot=LegacyMigrationFixture.snapshot(source,columns);var backup=temp.resolve("latest.sql");LegacyMigrationFixture.backup(source,backup);new JdbcTemplate(source).execute("SHUTDOWN");
        for(var copy:List.of(source,LegacyMigrationFixture.restore(temp.resolve("restored-specific"),backup))){assertEquals(0,AgendaMigrationTool.migrar(copy).migrationsExecuted);assertEquals(snapshot,LegacyMigrationFixture.snapshot(copy,columns));CorrectiveMigrationApplication.application(copy).run(context->{assertThat(context).hasNotFailed();assertEquals(8,context.getBean(AgendamentoRepository.class).findById(4L).orElseThrow().getVersion());var row=context.getBean(AuditoriaCorrecaoRepository.class).findByAgendamento_IdAndUnidade(4L,"CAPS:CAPS_AD",org.springframework.data.domain.Pageable.unpaged()).getContent().get(0);assertEquals(id.get(),row.getId().toString());assertEquals(7,row.getVersaoAnterior());assertEquals(8,row.getVersaoResultante());});assertEquals(snapshot,LegacyMigrationFixture.snapshot(copy,columns));}
        System.out.println("E4_RESTORE applicationBoots=3 dedicatedCorrection=true auditRows=1 restartMigrations=0 fullSnapshotPreserved=true");
    }
    @Test void e06MigratedUnknownFactStaysProvisionalAndCannotBeInventedByNewRoute() throws Exception {
        var source=LegacyMigrationFixture.legacy(temp.resolve("unknown-specific"));AgendaMigrationTool.migrar(source);
        CorrectiveMigrationApplication.application(source).run(context->{assertThat(context).hasNotFailed();var mvc=MockMvcBuilders.webAppContextSetup((WebApplicationContext)context.getSourceApplicationContext()).addFilters(context.getBean("springSecurityFilterChain",jakarta.servlet.Filter.class)).build();var actor=context.getBean(UsuarioRepository.class).findById(1L).orElseThrow();var token=context.getBean(TokenService.class).gerarToken(actor);var json=context.getBean(ObjectMapper.class);var columns=LegacyMigrationFixture.columns(source);var before=LegacyMigrationFixture.snapshot(source,columns);var r=mvc.perform(patch("/api/agendamentos/12/frequencia/correcao").header("Authorization","Bearer "+token).contentType("application/json").content(json.writeValueAsString(new CorrigirFrequenciaDTO(FrequenciaAssistencial.PRESENTE,0,"Documento sintético conferido sem presumir frequência desconhecida.")))).andReturn().getResponse();assertEquals(409,r.getStatus());assertEquals(before,LegacyMigrationFixture.snapshot(source,columns));assertTrue(context.getBean(PacienteRepository.class).findById(1L).orElseThrow().getIndicadoresPendentes());assertNull(context.getBean(AgendamentoRepository.class).findById(12L).orElseThrow().getFrequenciaAssistencial());});
    }
}
