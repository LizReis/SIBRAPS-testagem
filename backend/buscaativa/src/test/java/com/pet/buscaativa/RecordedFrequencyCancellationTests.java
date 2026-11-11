package com.pet.buscaativa;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import com.pet.buscaativa.entities.*;
import com.pet.buscaativa.entities.dto.*;
import com.pet.buscaativa.entities.enums.*;
import com.pet.buscaativa.services.*;
import com.pet.buscaativa.services.exceptions.ConflictException;

class RecordedFrequencyCancellationTests extends AgendaIntegritySupport {
    static final String REASON="Motivo sintético não autoriza cancelar um fato clínico comprovado.";
    @Autowired PacienteService clinical;
    @MockitoSpyBean HistoricoPacienteService histories;
    private Agendamento fact(SituacaoAtendimento state){return tx(()->{var a=seed(patient,professional,state,LocalDate.now().minusDays(5),"08:00");histories.registrarAlteracaoDeAtendimento(a,SituacaoAtendimento.AGENDADO);clinical.recalcularAssiduidadePaciente(patients.findById(patient.getId()).orElseThrow());return a;});}
    private Object database(){var ds=Objects.requireNonNull(jdbc.getDataSource());return LegacyMigrationFixture.snapshot(ds,LegacyMigrationFixture.columns(ds));}
    private Usuario actor(String role){return switch(role){case "admin"->admin;case "owner"->professional;case "colleague"->colleague;case "reception"->tx(()->user(TipoUsuario.RECEPCAO,UnidadeAtuacao.CAPS_AD));case "foreign"->tx(()->user(TipoUsuario.ADMINISTRADOR,UnidadeAtuacao.CAPS_I));default->throw new IllegalArgumentException();};}
    private int cancel(Usuario u,Agendamento a,Integer version,boolean reason) throws Exception {var req=patch("/api/agendamentos/"+a.getId()+"/status").param("novoStatus","CANCELADO").header("Authorization","Bearer "+tokens.gerarToken(u));if(version!=null)req.param("version",version.toString());if(reason)req.param("justificativa",REASON);return businessStatus(mvc.perform(req).andReturn().getResponse());}
    @ParameterizedTest @ValueSource(strings={"admin","owner","reception"})
    void ca01FutureUnrecordedReservationCanBeCanceled(String role) throws Exception {var a=tx(()->seed(patient,professional,SituacaoAtendimento.AGENDADO,date,"08:00"));var u=actor(role);assertEquals(200,cancel(u,a,a.getVersion(),false));var current=appointment(a.getId());assertEquals(SituacaoReserva.CANCELADA,current.getSituacaoReserva());assertEquals(FrequenciaAssistencial.NAO_REGISTRADA,current.getFrequenciaAssistencial());assertFalse(current.getOcupaVaga());}
    @ParameterizedTest @CsvSource({"PRESENTE,owner,409","FALTOU,owner,409","PRESENTE,admin,409","FALTOU,admin,409","PRESENTE,reception,409","FALTOU,reception,409","PRESENTE,colleague,403","FALTOU,colleague,403","PRESENTE,foreign,404","FALTOU,foreign,404"})
    void ca02To08GenericClinicalCancellationIsBlockedWithEntireDatabaseUnchanged(SituacaoAtendimento state,String role,int expected) throws Exception {var a=fact(state);var u=actor(role);var before=database();assertEquals(expected,cancel(u,a,a.getVersion(),false));assertEquals(before,database());assertEquals(expected,cancel(u,a,a.getVersion(),true));assertEquals(before,database());}
    @Test void ca09UnrecordedPastReservationCanStillBeCanceled() throws Exception {var a=fact(SituacaoAtendimento.AGENDADO);assertEquals(200,cancel(admin,a,a.getVersion(),false));assertEquals(FrequenciaAssistencial.NAO_REGISTRADA,appointment(a.getId()).getFrequenciaAssistencial());}
    @Test void ca10And11P05RetainsOriginalMissAndLinkWhileRejectingCancellation() throws Exception {var a=fact(SituacaoAtendimento.FALTOU);var dto=dto(patient,colleague,"08:30",TurnoEnum.MANHA,a.getId());assertEquals(201,post(dto));var source=appointment(a.getId());assertEquals(SituacaoReserva.SUBSTITUIDA,source.getSituacaoReserva());assertEquals(FrequenciaAssistencial.FALTOU,source.getFrequenciaAssistencial());assertFalse(source.getOcupaVaga());var successor=tx(()->appointments.findAll().stream().filter(x->x.getAgendamentoOriginal()!=null&&x.getAgendamentoOriginal().getId().equals(a.getId())).findFirst().orElseThrow());assertTrue(successor.isRemarcacaoAposFalta());assertEquals(patient.getId(),tx(()->appointment(successor.getId()).getPaciente().getId()));var before=database();assertEquals(409,cancel(admin,source,null,true));assertEquals(before,database());}
    @Test void substitutedUnrecordedOriginCannotBeCanceledEither() throws Exception {var a=fact(SituacaoAtendimento.AGENDADO);assertEquals(201,post(dto(patient,colleague,"08:30",TurnoEnum.MANHA,a.getId())));var before=database();assertEquals(409,cancel(admin,appointment(a.getId()),null,false));assertEquals(before,database());}
    @Test void ca12StaleVersionDoesNotCancelValidReservation() throws Exception {var a=fact(SituacaoAtendimento.AGENDADO);var before=database();assertEquals(409,cancel(admin,a,a.getVersion()+1,false));assertEquals(before,database());}
    @Test void ca13RepeatedValidCancellationIsIdempotentAndRecordedCancellationAlwaysConflicts() throws Exception {var a=fact(SituacaoAtendimento.AGENDADO);assertEquals(200,cancel(admin,a,null,false));var before=database();assertEquals(200,cancel(admin,a,null,false));assertEquals(before,database());var recorded=fact(SituacaoAtendimento.PRESENTE);before=database();for(int i=0;i<2;i++)assertEquals(409,cancel(admin,recorded,null,true));assertEquals(before,database());}
    @Test void ca14LegacyStatusEndpointCannotBypassStateProtection() throws Exception {var a=fact(SituacaoAtendimento.FALTOU);var before=database();assertEquals(409,cancel(professional,a,null,false));assertEquals(before,database());}
    @ParameterizedTest @EnumSource(value=SituacaoAtendimento.class,names={"PRESENTE","FALTOU"})
    void ca15BothDirectServiceOverloadsEnforceProtection(SituacaoAtendimento state){var a=fact(state);var before=database();authenticate();assertThrows(ConflictException.class,()->service.atualizarStatus(a.getId(),SituacaoAtendimento.CANCELADO,null));assertEquals(before,database());assertThrows(ConflictException.class,()->service.atualizarStatus(a.getId(),SituacaoAtendimento.CANCELADO,null,REASON));assertEquals(before,database());}
    @Test void ca16FailureDuringLegitimateCancellationRollsBackEverything(){var a=fact(SituacaoAtendimento.AGENDADO);var before=database();doAnswer(i->{i.callRealMethod();appointments.flush();assertEquals("CANCELADA",jdbc.queryForObject("select situacao_reserva from tb_agendamento where id=?",String.class,a.getId()));throw new IllegalStateException("synthetic failure after cancellation write");}).when(histories).registrarAlteracaoDeAtendimento(any(Agendamento.class),any(SituacaoAtendimento.class));authenticate();assertThrows(IllegalStateException.class,()->service.atualizarStatus(a.getId(),SituacaoAtendimento.CANCELADO,a.getVersion()));assertEquals(before,database());}
    @Test void futureClinicalRecordingRemainsForbidden() throws Exception {var a=tx(()->seed(patient,professional,SituacaoAtendimento.AGENDADO,date,"08:00"));var before=database();var r=mvc.perform(patch("/api/agendamentos/"+a.getId()+"/status").param("novoStatus","PRESENTE").header("Authorization","Bearer "+tokens.gerarToken(admin))).andReturn().getResponse();assertEquals(422,r.getStatus());assertEquals(before,database());}
    @Test void legitimateClinicalCorrectionStillRequiresReasonAndCreatesAudit() throws Exception {var a=fact(SituacaoAtendimento.FALTOU);var before=database();assertEquals(422,mvc.perform(patch("/api/agendamentos/"+a.getId()+"/status").param("novoStatus","PRESENTE").header("Authorization","Bearer "+tokens.gerarToken(professional))).andReturn().getResponse().getStatus());assertEquals(before,database());assertEquals(200,mvc.perform(patch("/api/agendamentos/"+a.getId()+"/status").param("novoStatus","PRESENTE").param("justificativa",REASON).header("Authorization","Bearer "+tokens.gerarToken(professional))).andReturn().getResponse().getStatus());assertEquals(FrequenciaAssistencial.PRESENTE,appointment(a.getId()).getFrequenciaAssistencial());}
}
