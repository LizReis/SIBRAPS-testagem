package com.pet.buscaativa;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import com.pet.buscaativa.entities.*;
import com.pet.buscaativa.entities.enums.*;

class AttendanceTemporalHttpTests extends AgendaIntegritySupport {
    @MockitoSpyBean Clock clock;
    static final LocalDate DAY=LocalDate.of(2030,4,10);
    private void now(String time){doReturn(LocalDateTime.parse(time).atZone(ZoneId.of("America/Bahia")).toInstant()).when(clock).instant();}
    private Object database(){var source=Objects.requireNonNull(jdbc.getDataSource());return LegacyMigrationFixture.snapshot(source,LegacyMigrationFixture.columns(source));}
    private Agendamento fact(LocalDate d){return tx(()->seed(patient,professional,SituacaoAtendimento.FALTOU,d,"15:00"));}
    private int change(Usuario actor,Agendamento a,String status) throws Exception{return businessStatus(mvc.perform(patch("/api/agendamentos/"+a.getId()+"/status").param("novoStatus",status).param("version",a.getVersion().toString()).param("justificativa","Presença conferida no registro assistencial sintético.").header("Authorization","Bearer "+tokens.gerarToken(actor))).andReturn().getResponse());}
    @ParameterizedTest @CsvSource({"2030-04-10T14:59,422","2030-04-10T15:00,422","2030-04-10T15:01,200","2030-04-11T00:10,200","2030-04-09T23:59,422"})
    void legacyCorrectionUsesScheduledTimeAndPersistsOnlyAfterBoundary(String time,int expected) throws Exception {now(time);var a=fact(DAY);var before=database();assertEquals(expected,change(professional,a,"PRESENTE"));if(expected!=200)assertEquals(before,database());else{assertEquals(FrequenciaAssistencial.PRESENTE,appointment(a.getId()).getFrequenciaAssistencial());assertEquals(1,appointment(a.getId()).getVersion());assertEquals(1,jdbc.queryForObject("select count(*) from tb_auditoria_correcao_frequencia where agendamento_id=?",Integer.class,a.getId()));}}
    @ParameterizedTest @CsvSource({"2030-04-10T14:59,422","2030-04-10T15:00,200","2030-04-10T15:01,200"})
    void receptionInitialRecordingUsesInclusiveBoundaryWithoutArtificialReason(String time,int expected) throws Exception {now(time);var a=tx(()->seed(patient,professional,SituacaoAtendimento.AGENDADO,DAY,"15:00"));var reception=tx(()->user(TipoUsuario.RECEPCAO,UnidadeAtuacao.CAPS_AD));var before=database();var r=mvc.perform(patch("/api/agendamentos/"+a.getId()+"/status").param("novoStatus","FALTOU").header("Authorization","Bearer "+tokens.gerarToken(reception))).andReturn().getResponse();assertEquals(expected,r.getStatus());if(expected!=200)assertEquals(before,database());else assertEquals(0,jdbc.queryForObject("select count(*) from tb_auditoria_correcao_frequencia where agendamento_id=?",Integer.class,a.getId()));}
    @Test void missingTimeReturnsStructuredPendingWithoutAnyWrite() throws Exception {now("2030-04-10T16:00");var a=tx(()->{var x=fact(DAY);x.setHoraAtendimento(null);return appointments.saveAndFlush(x);});var before=database();var r=mvc.perform(patch("/api/agendamentos/"+a.getId()+"/status").param("novoStatus","PRESENTE").param("justificativa","Registro clínico sintético conferido pela equipe.").header("Authorization","Bearer "+tokens.gerarToken(professional))).andReturn().getResponse();assertEquals(409,r.getStatus());assertEquals("HORARIO_ASSISTENCIAL_PENDENTE_RECONCILIACAO",json.readTree(r.getContentAsString()).get("codigo").asText());assertEquals(before,database());}
    @Test void directLegacyOverloadCannotBypassTimePolicy(){now("2030-04-10T14:59");var a=fact(DAY);var before=database();authenticate();assertThrows(com.pet.buscaativa.services.exceptions.ValidationException.class,()->service.atualizarStatus(a.getId(),SituacaoAtendimento.PRESENTE,a.getVersion(),"Registro clínico sintético conferido pela equipe."));assertEquals(before,database());}
}
