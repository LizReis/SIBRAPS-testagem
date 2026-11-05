package com.pet.buscaativa;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import com.pet.buscaativa.entities.enums.*;
import com.pet.buscaativa.repositories.*;
import com.pet.buscaativa.migration.*;
import com.pet.buscaativa.services.impl.SusApiAdapter;

@SpringBootTest(properties = {"spring.jpa.hibernate.ddl-auto=validate", "sibraps.test.fixture=migrated-indicators"})
@Import(MigratedIndicatorsInitializationTests.KnownPatientDatabase.class)
class MigratedIndicatorsInitializationTests extends SecurityTestSupport {
    @TestConfiguration
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "sibraps.test.fixture", havingValue = "migrated-indicators")
    static class KnownPatientDatabase {
        @Bean DataSource dataSource() throws Exception {
            var source = LegacyMigrationFixture.legacy(Files.createTempDirectory("sibraps-indicators-").resolve("legacy"));
            var jdbc = new JdbcTemplate(source);
            jdbc.update("""
                insert into tb_paciente(id,id_publico,nome,nome_mae,data_nascimento,status_paciente,caps_referencia,
                tipo_acompanhamento,count_faltas,classificacao_risco,gatilho_visita,situacao_rua,version)
                select 3,random_uuid(),'Paciente sintético completo',nome_mae,data_nascimento,status_paciente,caps_referencia,
                tipo_acompanhamento,0,1,false,false,0 from tb_paciente where id=1
                """);
            jdbc.update("""
                insert into tb_agendamento(id,paciente_id,usuario_id,data_agendamento,hora_atendimento,turno_agendamento,
                situacao_atendimento,remarcacao_apos_falta,version,agendamento_original_id)
                values(13,3,2,DATE '2026-01-01',TIME '10:00',1,6,false,0,null),
                (14,3,2,DATE '2026-01-02',TIME '10:00',1,3,true,0,13)
                """);
            jdbc.update("""
                insert into tb_historico_paciente(id,paciente_id,profissional_id,agendamento_id,tipo,situacao_atendimento,ocorrido_em,descricao)
                values(7,3,2,13,'FALTA',3,TIMESTAMP '2026-01-01 10:00:00','Falta sintética comprovada'),
                (8,3,2,14,'FALTA',3,TIMESTAMP '2026-01-02 10:00:00','Remarcação sintética comprovada')
                """);
            AgendaMigrationTool.migrar(source);
            return source;
        }
    }
    @MockitoBean SusApiAdapter sus;
    @Autowired DataSource source;
    @Autowired PacienteRepository patients;
    @Autowired AgendamentoRepository appointments;
    @Autowired AgendaMigratedIndicatorsInitializer initializer;

    @Test void completePatientsUseDomainRecalculationBeforeBeingServed() {
        var patient = patients.findById(3L).orElseThrow();
        assertEquals(2, patient.getCountFaltas()); assertNull(patient.getDataUltimaPresenca());
        assertEquals(ClassificacaoRisco.AMARELO, patient.getClassificacaoRisco());
        assertTrue(patient.getGatilhoVisitaAcionado()); assertFalse(patient.getIndicadoresPendentes());
        assertEquals("SISTEMA", patient.getUpdatedBy());
        assertEquals(0, appointments.findById(13L).orElseThrow().getVersion());
        assertEquals(FrequenciaAssistencial.FALTOU, appointments.findById(13L).orElseThrow().getFrequenciaAssistencial());
        assertEquals(14, appointments.count());
        assertEquals(8, new JdbcTemplate(source).queryForObject("select count(*) from tb_historico_paciente", Integer.class));
    }

    @Test void technicalRefreshIsIdempotentAndNeverInventsClinicalHistory() throws Exception {
        var columns = LegacyMigrationFixture.columns(source); var before = LegacyMigrationFixture.snapshot(source, columns);
        initializer.run(new org.springframework.boot.DefaultApplicationArguments());
        assertEquals(before, LegacyMigrationFixture.snapshot(source, columns));
    }
}
