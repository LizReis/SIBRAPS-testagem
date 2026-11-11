package com.pet.buscaativa;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.dao.DataAccessException;
import com.pet.buscaativa.entities.*;
import com.pet.buscaativa.entities.dto.*;
import com.pet.buscaativa.entities.enums.*;
import com.pet.buscaativa.repositories.*;
import com.pet.buscaativa.services.*;
import tools.jackson.databind.JsonNode;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:clinical-audit;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000","spring.datasource.hikari.maximum-pool-size=16"})
class ClinicalAuditIntegrationTests extends AgendaIntegritySupport {
    private static final String REASON="Registro anterior retificado após conferência da documentação assistencial.";
    @MockitoSpyBean AuditoriaCorrecaoService auditService;
    @MockitoSpyBean HistoricoPacienteService histories;
    @MockitoSpyBean Clock clock;
    @Autowired AuditoriaCorrecaoRepository audits;
    @Autowired PacienteService clinical;
    @Autowired GrupoTerapeuticoRepository groups;
    @Autowired SessaoGrupoRepository sessions;
    @Autowired SessaoGrupoParticipanteRepository participants;
    @Autowired GrupoTerapeuticoService groupService;
    private Agendamento fact(SituacaoAtendimento state){return tx(()->{var a=seed(patient,professional,state,LocalDate.now().minusDays(3),"08:00");histories.registrarAlteracaoDeAtendimento(a,SituacaoAtendimento.AGENDADO);clinical.recalcularAssiduidadePaciente(patients.findById(patient.getId()).orElseThrow());return a;});}
    private int correct(Usuario actor,Agendamento a,SituacaoAtendimento state,Integer version) throws Exception {
        var request=patch("/api/agendamentos/"+a.getId()+"/status").param("novoStatus",state.name()).param("justificativa",REASON).header("Authorization","Bearer "+tokens.gerarToken(actor));if(version!=null)request.param("version",version.toString());
        return businessStatus(mvc.perform(request).andReturn().getResponse());
    }
    private JsonNode records(Usuario actor,String path) throws Exception {
        var r=mvc.perform(get(path).header("Authorization","Bearer "+tokens.gerarToken(actor))).andReturn().getResponse();assertEquals(200,r.getStatus());return json.readTree(r.getContentAsString()).get("content");
    }
    private Object database(){var source=Objects.requireNonNull(jdbc.getDataSource());return LegacyMigrationFixture.snapshot(source,LegacyMigrationFixture.columns(source));}
    @ParameterizedTest @EnumSource(value=SituacaoAtendimento.class,names={"PRESENTE","FALTOU"})
    void ac01To04EachDirectionStoresOneRealCorrectionWithoutNewAttendance(SituacaoAtendimento before) throws Exception {
        var a=fact(before);var next=before==SituacaoAtendimento.FALTOU?SituacaoAtendimento.PRESENTE:SituacaoAtendimento.FALTOU;
        long rows=appointments.count(),events=history.count(),count=audits.count();int version=a.getVersion();assertEquals(200,correct(professional,a,next,version));
        var records=records(professional,"/api/agendamentos/"+a.getId()+"/correcoes");assertEquals(1,records.size());var record=records.get(0);
        assertEquals(before.name(),record.get("frequenciaAnterior").asText());assertEquals(next.name(),record.get("frequenciaNova").asText());assertEquals(REASON,record.get("justificativa").asText());
        assertEquals(professional.getIdPublico().toString(),record.get("usuarioId").asText());assertEquals("PROFISSIONAL",record.get("perfil").asText());assertEquals("CAPS:CAPS_AD",record.get("unidade").asText());
        assertEquals(version,record.get("versaoAnterior").asInt());assertEquals(appointment(a.getId()).getVersion(),record.get("versaoResultante").asInt());assertEquals(version+1,record.get("versaoResultante").asInt());
        assertEquals(count+1,audits.count());assertEquals(rows,appointments.count());assertEquals(events,history.count());assertEquals(next==SituacaoAtendimento.FALTOU?1:0,patients.findById(patient.getId()).orElseThrow().getCountFaltas());
    }
    @Test void ac03And11ActorRoleUnitAndServerClockCannotBeForged() throws Exception {
        var a=fact(SituacaoAtendimento.FALTOU);var instant=Instant.now().truncatedTo(ChronoUnit.MICROS);doReturn(instant).when(clock).instant();
        var response=mvc.perform(patch("/api/agendamentos/"+a.getId()+"/status").param("novoStatus","PRESENTE").param("justificativa","  Documento\n  conferido\t pela equipe.  ")
                .param("usuarioId",colleague.getIdPublico().toString()).param("perfil","PROFISSIONAL").param("unidade","CAPS:CAPS_I").param("ocorridoEm","1900-01-01T00:00:00Z")
                .header("Authorization","Bearer "+tokens.gerarToken(admin))).andReturn().getResponse();assertEquals(200,response.getStatus());
        var record=records(admin,"/api/agendamentos/"+a.getId()+"/correcoes").get(0);assertEquals(admin.getIdPublico().toString(),record.get("usuarioId").asText());assertEquals(professional.getIdPublico().toString(),record.get("responsavelAtendimentoId").asText());
        assertEquals("ADMINISTRADOR",record.get("perfil").asText());assertEquals("CAPS:CAPS_AD",record.get("unidade").asText());assertEquals(instant,Instant.parse(record.get("ocorridoEm").asText()));assertEquals("Documento conferido pela equipe.",record.get("justificativa").asText());
    }
    @Test void repeatedCorrectionsAppendInsteadOfOverwritingPreviousEvidence() throws Exception {
        var a=fact(SituacaoAtendimento.FALTOU);assertEquals(200,correct(admin,a,SituacaoAtendimento.PRESENTE,null));var first=records(admin,"/api/agendamentos/"+a.getId()+"/correcoes").get(0);
        assertEquals(200,correct(admin,a,SituacaoAtendimento.FALTOU,null));var result=records(admin,"/api/agendamentos/"+a.getId()+"/correcoes");assertEquals(2,result.size());assertEquals(2,result.get(0).get("versaoResultante").asInt());assertEquals(first,result.get(1));
    }
    @Test void ac05IdempotenceAndStaleVersionProduceNoAdditionalAudit() throws Exception {
        var a=fact(SituacaoAtendimento.FALTOU);assertEquals(200,correct(admin,a,SituacaoAtendimento.PRESENTE,0));var before=database();assertEquals(200,correct(admin,a,SituacaoAtendimento.PRESENTE,null));assertEquals(before,database());assertEquals(409,correct(admin,a,SituacaoAtendimento.FALTOU,0));assertEquals(before,database());
    }
    @Test void initialRegistrationIsNotFalselyAuditedAsACorrection() throws Exception {
        var a=tx(()->seed(patient,professional,SituacaoAtendimento.AGENDADO,LocalDate.now().minusDays(3),"08:00"));long count=audits.count();
        assertEquals(200,mvc.perform(patch("/api/agendamentos/"+a.getId()+"/status").param("novoStatus","FALTOU").header("Authorization","Bearer "+tokens.gerarToken(professional))).andReturn().getResponse().getStatus());assertEquals(count,audits.count());
    }
    @Test void ac09KnownSubstitutedFrequencyIsAuditedWithoutReopeningTheReservation() throws Exception {
        var a=fact(SituacaoAtendimento.FALTOU);tx(()->{var row=appointment(a.getId());row.setSituacaoAtendimento(SituacaoAtendimento.REMARCADO_ORIGEM);appointments.saveAndFlush(row);return null;});
        assertEquals(200,correct(professional,a,SituacaoAtendimento.PRESENTE,null));var row=appointment(a.getId());assertEquals(SituacaoReserva.SUBSTITUIDA,row.getSituacaoReserva());assertFalse(row.getOcupaVaga());var record=records(admin,"/api/agendamentos/"+a.getId()+"/correcoes").get(0);assertEquals("SUBSTITUIDA",record.get("situacaoReserva").asText());
    }
    @Test void unauthorizedAuditQueriesDoNotExposeReasons() throws Exception {
        var a=fact(SituacaoAtendimento.FALTOU);assertEquals(200,correct(admin,a,SituacaoAtendimento.PRESENTE,null));var reception=tx(()->user(TipoUsuario.RECEPCAO,UnidadeAtuacao.CAPS_AD));var foreign=tx(()->user(TipoUsuario.ADMINISTRADOR,UnidadeAtuacao.CAPS_I));var before=database();
        for(var actor:List.of(colleague,reception,foreign)){var response=mvc.perform(get("/api/agendamentos/"+a.getId()+"/correcoes").header("Authorization","Bearer "+tokens.gerarToken(actor))).andReturn().getResponse();assertEquals(actor==foreign?404:403,response.getStatus());assertFalse(response.getContentAsString().contains(REASON));}assertEquals(before,database());
    }
    @Test void auditQueriesArePaginatedAndLimited() throws Exception {
        var a=fact(SituacaoAtendimento.FALTOU);assertEquals(200,correct(admin,a,SituacaoAtendimento.PRESENTE,null));assertEquals(200,correct(admin,a,SituacaoAtendimento.FALTOU,null));
        assertEquals(1,records(admin,"/api/agendamentos/"+a.getId()+"/correcoes?size=1").size());assertEquals(1,records(admin,"/api/agendamentos/"+a.getId()+"/correcoes?size=1&page=1").size());
        assertEquals(422,mvc.perform(get("/api/agendamentos/"+a.getId()+"/correcoes?size=101").header("Authorization","Bearer "+tokens.gerarToken(admin))).andReturn().getResponse().getStatus());
    }
    @Test void databaseRejectsUpdateAndDeleteOfExistingAuditRows() throws Exception {
        var a=fact(SituacaoAtendimento.FALTOU);assertEquals(200,correct(admin,a,SituacaoAtendimento.PRESENTE,null));var before=database();
        assertThrows(DataAccessException.class,()->jdbc.update("update tb_auditoria_correcao_frequencia set justificativa='Texto adulterado por DML' where agendamento_id=?",a.getId()));
        assertThrows(DataAccessException.class,()->jdbc.update("delete from tb_auditoria_correcao_frequencia where agendamento_id=?",a.getId()));assertEquals(before,database());
    }
    @ParameterizedTest @ValueSource(strings={"PUT","DELETE"})
    void normalApiExposesNoAuditMutation(String method) throws Exception {
        var a=fact(SituacaoAtendimento.FALTOU);assertEquals(200,correct(admin,a,SituacaoAtendimento.PRESENTE,null));var before=database();
        var request=method.equals("PUT")?put("/api/agendamentos/"+a.getId()+"/correcoes"):delete("/api/agendamentos/"+a.getId()+"/correcoes");assertEquals(405,mvc.perform(request.header("Authorization","Bearer "+tokens.gerarToken(admin))).andReturn().getResponse().getStatus());assertEquals(before,database());
    }
    @Test void ac06FailureAfterAuditInsertRollsBackEveryTable() {
        var a=fact(SituacaoAtendimento.FALTOU);var before=database();doAnswer(i->{i.callRealMethod();throw new IllegalStateException("synthetic audit storage failure");}).when(org.springframework.test.util.AopTestUtils.<AuditoriaCorrecaoService>getUltimateTargetObject(auditService)).registrarIndividual(any(),any(),anyString(),anyInt());
        assertThrows(IllegalStateException.class,()->service.atualizarStatus(a.getId(),SituacaoAtendimento.PRESENTE,null,REASON));assertEquals(before,database());
    }
    @Test void ac07HistoryFailureRollsBackClinicalDataAndAudit() {
        var a=fact(SituacaoAtendimento.FALTOU);var before=database();doThrow(new IllegalStateException("synthetic history failure")).when(histories).registrarAlteracaoDeAtendimento(any(),any());
        assertThrows(IllegalStateException.class,()->service.atualizarStatus(a.getId(),SituacaoAtendimento.PRESENTE,null,REASON));assertEquals(before,database());
    }
    private SessaoGrupo groupFact(){return tx(()->{
        var g=new GrupoTerapeutico();g.setCoordenador(professional);g.setTema("Auditoria de grupo sintético");g.setRecorrencia(RecorrenciaGrupo.UNICA);groups.saveAndFlush(g);
        var s=new SessaoGrupo();s.setGrupo(g);s.setDataSessao(LocalDate.now().minusDays(2));s.setHorario(LocalTime.of(9,0));s.setStatus(StatusSessaoGrupo.AGENDADA);s.confirmarRealizacao(professional);sessions.saveAndFlush(s);
        for(var p:List.of(patient,other)){var item=new SessaoGrupoParticipante();item.setSessaoGrupo(s);item.setPaciente(p);item.setStatusPresenca(StatusPresencaGrupo.FALTOU);participants.saveAndFlush(item);histories.registrarFrequenciaGrupo(p,s,StatusPresencaGrupo.FALTOU);clinical.recalcularAssiduidadePaciente(patients.findById(p.getId()).orElseThrow());}return s;});}
    private CorrigirFrequenciasGrupoDTO batch(SessaoGrupo s,boolean reverse){var list=new ArrayList<>(List.of(new FrequenciaParticipanteGrupoDTO(patient.getIdPublico(),StatusPresencaGrupo.PRESENTE),new FrequenciaParticipanteGrupoDTO(other.getIdPublico(),StatusPresencaGrupo.PRESENTE)));if(reverse)Collections.reverse(list);return new CorrigirFrequenciasGrupoDTO(list,s.getVersion(),REASON);}
    private int correctGroup(Usuario actor,SessaoGrupo s,CorrigirFrequenciasGrupoDTO dto) throws Exception{return businessStatus(mvc.perform(patch("/api/grupos/sessoes/"+s.getId()+"/frequencias").header("Authorization","Bearer "+tokens.gerarToken(actor)).contentType("application/json").content(json.writeValueAsString(dto))).andReturn().getResponse());}
    @Test void eachChangedParticipantGetsAnAuditAndSessionVersionAdvancesOnce() throws Exception {
        var s=groupFact();long events=history.count(),rows=appointments.count(),count=audits.count();assertEquals(200,correctGroup(professional,s,batch(s,false)));var records=records(admin,"/api/grupos/sessoes/"+s.getId()+"/correcoes");assertEquals(2,records.size());
        for(var record:records){assertTrue(record.get("agendamentoId").isNull());assertEquals(s.getId(),record.get("sessaoId").asLong());assertFalse(record.get("participanteId").isNull());assertEquals("REALIZADA",record.get("situacaoSessao").asText());assertEquals(0,record.get("versaoAnterior").asInt());assertEquals(1,record.get("versaoResultante").asInt());}
        assertEquals(1,sessions.findById(s.getId()).orElseThrow().getVersion());assertEquals(count+2,audits.count());assertEquals(events,history.count());assertEquals(rows,appointments.count());assertEquals(0,patients.findById(patient.getId()).orElseThrow().getCountFaltas());
        var before=database();assertEquals(409,correctGroup(admin,s,batch(s,false)));assertEquals(before,database());
        var current=sessions.findById(s.getId()).orElseThrow();var identical=new CorrigirFrequenciasGrupoDTO(batch(current,false).frequencias(),current.getVersion());assertEquals(200,correctGroup(professional,current,identical));assertEquals(before,database());
    }
    @Test void unchangedBatchItemsDoNotCreateFictitiousCorrections() throws Exception {
        var s=groupFact();long count=audits.count();var dto=new CorrigirFrequenciasGrupoDTO(List.of(new FrequenciaParticipanteGrupoDTO(patient.getIdPublico(),StatusPresencaGrupo.PRESENTE),new FrequenciaParticipanteGrupoDTO(other.getIdPublico(),StatusPresencaGrupo.FALTOU)),s.getVersion(),REASON);
        assertEquals(200,correctGroup(admin,s,dto));assertEquals(count+1,audits.count());assertEquals(1,records(admin,"/api/grupos/sessoes/"+s.getId()+"/correcoes").size());assertEquals(1,patients.findById(other.getId()).orElseThrow().getCountFaltas());
    }
    @Test void groupAuditFailureAfterFirstParticipantRevertsEntireBatch() {
        var s=groupFact();var before=database();var calls=new AtomicInteger();doAnswer(i->{i.callRealMethod();if(calls.incrementAndGet()==2)throw new IllegalStateException("synthetic batch audit failure");return null;}).when(org.springframework.test.util.AopTestUtils.<AuditoriaCorrecaoService>getUltimateTargetObject(auditService)).registrarGrupo(any(),any(),any(),anyString(),anyInt());
        assertThrows(IllegalStateException.class,()->groupService.corrigirFrequencias(s.getId(),batch(s,false)));assertEquals(before,database());
    }
    @Test void groupHistoryFailureRevertsEntireBatchAndAudits() {
        var s=groupFact();var before=database();doThrow(new IllegalStateException("synthetic group history failure")).when(histories).corrigirFrequenciaGrupo(any(),any(),any());
        assertThrows(IllegalStateException.class,()->groupService.corrigirFrequencias(s.getId(),batch(s,false)));assertEquals(before,database());
    }
    @Test void concurrentProfessionalAndAdminCorrectionsProduceExactlyOneAudit() throws Exception {
        var a=fact(SituacaoAtendimento.FALTOU);long count=audits.count();assertEquals(new RaceResult(1,1),race("E3-owner-admin",List.of(()->correct(professional,a,SituacaoAtendimento.PRESENTE,0),()->correct(admin,a,SituacaoAtendimento.PRESENTE,0))));assertEquals(count+1,audits.count());assertEquals(1,appointment(a.getId()).getVersion());
        String actor=records(admin,"/api/agendamentos/"+a.getId()+"/correcoes").get(0).get("usuarioId").asText();assertTrue(Set.of(admin.getIdPublico().toString(),professional.getIdPublico().toString()).contains(actor));
    }
    @Test void concurrentGroupCorrectionsWithReversedInputOrderAreAtomic() throws Exception {
        var s=groupFact();long count=audits.count();assertEquals(new RaceResult(1,1),race("E3-group-batch",List.of(()->correctGroup(professional,s,batch(s,false)),()->correctGroup(admin,s,batch(s,true)))));assertEquals(count+2,audits.count());assertEquals(1,sessions.findById(s.getId()).orElseThrow().getVersion());
    }
    @Test void concurrentCorrectionAndOperationalStatusHaveNoPhantomAudit() throws Exception {
        var a=fact(SituacaoAtendimento.FALTOU);long count=audits.count();assertEquals(new RaceResult(1,1),race("E3-correction-cancel",List.of(()->correct(admin,a,SituacaoAtendimento.PRESENTE,0),()->correct(admin,a,SituacaoAtendimento.CANCELADO,0))));
        var row=appointment(a.getId());assertEquals(count+(row.getFrequenciaAssistencial()==FrequenciaAssistencial.PRESENTE?1:0),audits.count());assertEquals(1,row.getVersion());
    }
    @Test void concurrentCorrectionAndReschedulingPreserveReservationAndAudit() throws Exception {
        var a=fact(SituacaoAtendimento.FALTOU);long count=audits.count();var dto=dto(patient,colleague,"08:30",TurnoEnum.MANHA,a.getId());assertEquals(new RaceResult(1,1),race("E3-correction-reschedule",List.of(()->correct(admin,a,SituacaoAtendimento.PRESENTE,0),()->post(dto))));
        var row=appointment(a.getId());assertEquals(count+(row.getFrequenciaAssistencial()==FrequenciaAssistencial.PRESENTE?1:0),audits.count());assertEquals(1,row.getVersion());
    }
    @ParameterizedTest @CsvSource({"owner,200","colleague,403","admin,200","reception,403","foreign,404"})
    void groupAuditReadRequiresCoordinatorOrLocalAdministrator(String role,int expected) throws Exception {
        var s=groupFact();assertEquals(200,correctGroup(admin,s,batch(s,false)));var actor=switch(role){case "owner"->professional;case "colleague"->colleague;case "admin"->admin;case "reception"->tx(()->user(TipoUsuario.RECEPCAO,UnidadeAtuacao.CAPS_AD));case "foreign"->tx(()->user(TipoUsuario.ADMINISTRADOR,UnidadeAtuacao.CAPS_I));default->throw new IllegalArgumentException();};var before=database();
        var response=mvc.perform(get("/api/grupos/sessoes/"+s.getId()+"/correcoes").header("Authorization","Bearer "+tokens.gerarToken(actor))).andReturn().getResponse();assertEquals(expected,response.getStatus());
        if(expected==200)assertEquals(2,json.readTree(response.getContentAsString()).get("content").size());else assertFalse(response.getContentAsString().contains(REASON));assertEquals(before,database());
    }

}
