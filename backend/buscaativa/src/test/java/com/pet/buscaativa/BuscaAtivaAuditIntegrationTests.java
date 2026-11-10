package com.pet.buscaativa;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
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
import com.pet.buscaativa.services.exceptions.ValidationException;
import tools.jackson.databind.JsonNode;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:alert-audit;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000","spring.datasource.hikari.maximum-pool-size=16"})
class BuscaAtivaAuditIntegrationTests extends AgendaIntegritySupport {
    @MockitoSpyBean AuditoriaBuscaAtivaService audit;
    @MockitoSpyBean Clock clock;
    @Autowired AuditoriaBuscaAtivaRepository events;
    @Autowired AuditoriaCorrecaoRepository corrections;
    @Autowired PacienteService clinical;
    @Autowired HistoricoPacienteService histories;
    @Autowired UsfReferenciaRepository usfs;
    private static final String DESTINATION="Equipe de referência sintética";
    private static final String DESCRIPTION="Registrado encaminhamento formal para avaliação da equipe de referência.";
    private Agendamento eligible(){return tx(()->{
        var original=seed(patient,professional,SituacaoAtendimento.FALTOU,LocalDate.now().minusDays(6),"08:00");histories.registrarAlteracaoDeAtendimento(original,SituacaoAtendimento.AGENDADO);
        original.setSituacaoAtendimento(SituacaoAtendimento.REMARCADO_ORIGEM);appointments.saveAndFlush(original);
        var successor=seed(patient,professional,SituacaoAtendimento.FALTOU,LocalDate.now().minusDays(4),"08:00");successor.setAgendamentoOriginal(original);successor.setRemarcacaoAposFalta(true);appointments.saveAndFlush(successor);histories.registrarAlteracaoDeAtendimento(successor,SituacaoAtendimento.REMARCADO);
        clinical.recalcularAssiduidadePaciente(patients.findById(patient.getId()).orElseThrow());return successor;});}
    private org.springframework.mock.web.MockHttpServletResponse postEvent(Usuario actor,String suffix,Object body) throws Exception {
        return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/pacientes/"+patient.getIdPublico()+"/alertas/"+suffix).header("Authorization","Bearer "+tokens.gerarToken(actor)).contentType("application/json").content(json.writeValueAsString(body))).andReturn().getResponse();
    }
    private JsonNode json(org.springframework.mock.web.MockHttpServletResponse response,int expected) throws Exception {assertEquals(expected,response.getStatus());return json.readTree(response.getContentAsString());}
    private JsonNode trail() throws Exception {
        var r=mvc.perform(get("/api/pacientes/"+patient.getIdPublico()+"/alertas/auditoria").header("Authorization","Bearer "+tokens.gerarToken(admin))).andReturn().getResponse();return json(r,200).get("content");
    }
    private Object domain(){var source=Objects.requireNonNull(jdbc.getDataSource());var columns=LegacyMigrationFixture.columns(source);columns.remove("TB_AUDITORIA_BUSCA_ATIVA");return LegacyMigrationFixture.snapshot(source,columns);}
    private Object database(){var source=Objects.requireNonNull(jdbc.getDataSource());return LegacyMigrationFixture.snapshot(source,LegacyMigrationFixture.columns(source));}
    private EncaminhamentoAlertaDTO forward(UUID key){return new EncaminhamentoAlertaDTO(key,DESTINATION,DESCRIPTION);}
    @Test void listConsultationIsNotIndividualViewingOrForwarding() throws Exception {
        eligible();tx(()->{patients.findAll().stream().filter(p->!p.getId().equals(patient.getId())).forEach(p->{p.setStatusPaciente(StatusPaciente.INATIVO);patients.save(p);});return null;});long count=events.count();var before=domain();var response=mvc.perform(get("/api/pacientes/busca-ativa").header("Authorization","Bearer "+tokens.gerarToken(admin))).andReturn().getResponse();assertEquals(200,response.getStatus());assertEquals(count+1,events.count());assertEquals(before,domain());assertEquals(0,trail().size());
        var r=mvc.perform(get("/api/alertas/auditoria").header("Authorization","Bearer "+tokens.gerarToken(admin))).andReturn().getResponse();var rows=json(r,200).get("content");
        assertTrue(java.util.stream.StreamSupport.stream(rows.spliterator(),false).anyMatch(e->"CONSULTA_LISTA_BUSCA_ATIVA".equals(e.get("tipoEvento").asText()) && e.get("pacienteId").isNull()));
    }
    @ParameterizedTest @ValueSource(strings={"ADMINISTRADOR","PROFISSIONAL","RECEPCAO"})
    void authorizedVisualizationStoresRealActorAndServerInstant(String role) throws Exception {
        eligible();var actor=role.equals("ADMINISTRADOR")?admin:role.equals("PROFISSIONAL")?professional:tx(()->user(TipoUsuario.RECEPCAO,UnidadeAtuacao.CAPS_AD));var now=Instant.now().truncatedTo(ChronoUnit.MICROS);doReturn(now).when(clock).instant();var before=domain();
        var request=Map.of("idRequisicao",UUID.randomUUID(),"usuarioId",colleague.getIdPublico(),"perfil","ADMINISTRADOR","unidade","CAPS:CAPS_I","ocorridoEm","1900-01-01T00:00:00Z","tipoEvento","ENCAMINHAMENTO_ALERTA");
        var e=json(postEvent(actor,"visualizacoes",request),201);assertEquals("VISUALIZACAO_ALERTA",e.get("tipoEvento").asText());assertEquals(actor.getIdPublico().toString(),e.get("usuarioId").asText());assertEquals(role,e.get("perfil").asText());assertEquals("CAPS:CAPS_AD",e.get("unidade").asText());assertEquals(now,Instant.parse(e.get("ocorridoEm").asText()));assertEquals("AMARELO",e.get("risco").asText());assertTrue(e.get("gatilhoConfirmado").asBoolean());assertEquals(before,domain());
    }
    @Test void streetAlertStoresOnlyItsSituationWithoutCopyingPrivateReference() throws Exception {
        eligible();tx(()->{var p=patients.findById(patient.getId()).orElseThrow();var address=new Endereco();address.setComplemento("Ponto sintético protegido");p.setEndereco(address);p.setSituacaoRua(true);patients.saveAndFlush(p);return null;});
        var response=postEvent(professional,"visualizacoes",new VisualizacaoAlertaDTO(UUID.randomUUID()));var e=json(response,201);assertEquals("BUSCA_PONTO_REFERENCIA",e.get("situacao").asText());assertTrue(e.get("situacaoRua").asBoolean());assertFalse(response.getContentAsString().contains("Ponto sintético protegido"));
    }
    @Test void inactiveAndResolvedSituationsDoNotGenerateNewEvents() throws Exception {
        var a=eligible();assertEquals(200,mvc.perform(patch("/api/agendamentos/"+a.getId()+"/status").param("novoStatus","PRESENTE").param("justificativa","Registro revisto após conferência da documentação original.").header("Authorization","Bearer "+tokens.gerarToken(admin))).andReturn().getResponse().getStatus());
        long count=events.count();assertEquals(409,postEvent(admin,"visualizacoes",new VisualizacaoAlertaDTO(UUID.randomUUID())).getStatus());assertEquals(count,events.count());
        tx(()->{var p=patients.findById(patient.getId()).orElseThrow();p.setStatusPaciente(StatusPaciente.INATIVO);patients.saveAndFlush(p);return null;});assertEquals(409,postEvent(admin,"visualizacoes",new VisualizacaoAlertaDTO(UUID.randomUUID())).getStatus());assertEquals(count,events.count());
    }
    @Test void pendingIndicatorsCanBeViewedAsReviewButNotForwardedAsConfirmed() throws Exception {
        eligible();tx(()->{var p=patients.findById(patient.getId()).orElseThrow();p.setIndicadoresPendentes(true);patients.saveAndFlush(p);return null;});
        var e=json(postEvent(admin,"visualizacoes",new VisualizacaoAlertaDTO(UUID.randomUUID())),201);assertEquals("REVISAO_HISTORICO",e.get("situacao").asText());assertFalse(e.get("gatilhoConfirmado").asBoolean());assertTrue(e.get("indicadoresPendentes").asBoolean());long count=events.count();var before=domain();assertEquals(409,postEvent(admin,"encaminhamentos",forward(UUID.randomUUID())).getStatus());assertEquals(count,events.count());assertEquals(before,domain());
    }
    @Test void visualRetryIsIdempotentAndDoesNotBecomeForwarding() throws Exception {
        eligible();var key=UUID.randomUUID();long count=events.count();var before=domain();var first=json(postEvent(admin,"visualizacoes",new VisualizacaoAlertaDTO(key)),201);var retry=json(postEvent(admin,"visualizacoes",new VisualizacaoAlertaDTO(key)),200);assertEquals(first,retry);assertEquals(count+1,events.count());assertEquals(before,domain());assertEquals(1,trail().size());assertEquals("VISUALIZACAO_ALERTA",trail().get(0).get("tipoEvento").asText());
        assertEquals(409,postEvent(admin,"encaminhamentos",forward(key)).getStatus());assertEquals(count+1,events.count());
    }
    @ParameterizedTest @ValueSource(strings={"ADMINISTRADOR","PROFISSIONAL"})
    void explicitFormalForwardingRecordsActionWithoutCreatingClinicalFacts(String role) throws Exception {
        eligible();var actor=role.equals("ADMINISTRADOR")?admin:professional;long rows=appointments.count(),historyRows=history.count(),correctionRows=corrections.count();var before=domain();var key=UUID.randomUUID();var e=json(postEvent(actor,"encaminhamentos",forward(key)),201);
        assertEquals("ENCAMINHAMENTO_ALERTA",e.get("tipoEvento").asText());assertEquals(DESTINATION,e.get("destino").asText());assertEquals(DESCRIPTION,e.get("descricao").asText());assertEquals(actor.getIdPublico().toString(),e.get("usuarioId").asText());assertEquals("VISITA_DOMICILIAR",e.get("situacao").asText());assertEquals(before,domain());assertEquals(rows,appointments.count());assertEquals(historyRows,history.count());assertEquals(correctionRows,corrections.count());
        assertEquals(e,json(postEvent(actor,"encaminhamentos",forward(key)),200));
    }
    @Test void redWithoutVisitIsAValidSearchSituation() throws Exception {
        tx(()->{var p=patients.findById(patient.getId()).orElseThrow();p.setCountFaltas(3);p.setClassificacaoRisco(ClassificacaoRisco.VERMELHO);patients.saveAndFlush(p);return null;});
        var e=json(postEvent(admin,"visualizacoes",new VisualizacaoAlertaDTO(UUID.randomUUID())),201);assertEquals("RISCO_VERMELHO",e.get("situacao").asText());assertFalse(e.get("gatilhoConfirmado").asBoolean());
    }
    @Test void receptionCannotForwardOrReadSensitiveOperationalTrail() throws Exception {
        eligible();var actor=tx(()->user(TipoUsuario.RECEPCAO,UnidadeAtuacao.CAPS_AD));var before=database();assertEquals(403,postEvent(actor,"encaminhamentos",forward(UUID.randomUUID())).getStatus());
        assertEquals(403,mvc.perform(get("/api/pacientes/"+patient.getIdPublico()+"/alertas/auditoria").header("Authorization","Bearer "+tokens.gerarToken(actor))).andReturn().getResponse().getStatus());assertEquals(before,database());
    }
    @Test void externalUnitCannotCreateOrReadAnAlertEvent() throws Exception {
        eligible();var foreign=tx(()->user(TipoUsuario.ADMINISTRADOR,UnidadeAtuacao.CAPS_I));var before=database();assertEquals(404,postEvent(foreign,"visualizacoes",new VisualizacaoAlertaDTO(UUID.randomUUID())).getStatus());assertEquals(404,postEvent(foreign,"encaminhamentos",forward(UUID.randomUUID())).getStatus());
        var response=mvc.perform(get("/api/pacientes/"+patient.getIdPublico()+"/alertas/auditoria").header("Authorization","Bearer "+tokens.gerarToken(foreign))).andReturn().getResponse();assertEquals(404,response.getStatus());assertFalse(response.getContentAsString().contains(patient.getNome()));assertEquals(before,database());
    }
    @Test void unauthenticatedEventRequestIsRejectedWithoutWrites() throws Exception {
        eligible();var before=database();assertEquals(401,mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/pacientes/"+patient.getIdPublico()+"/alertas/visualizacoes").contentType("application/json").content(json.writeValueAsString(new VisualizacaoAlertaDTO(UUID.randomUUID())))).andReturn().getResponse().getStatus());assertEquals(before,database());
    }
    @Test void usfIdentityIsSpecificForAlertAuditToo() throws Exception {
        eligible();var values=tx(()->{var x=new UsfReferencia();x.setCnes(UUID.randomUUID().toString());x=usfs.saveAndFlush(x);var y=new UsfReferencia();y.setCnes(UUID.randomUUID().toString());y=usfs.saveAndFlush(y);
            var p=patients.findById(patient.getId()).orElseThrow();p.setCapsReferencia(null);p.setUsfReferencia(x);patients.saveAndFlush(p);
            var owner=users.findById(professional.getId()).orElseThrow();owner.setUnidadeAtuacao(UnidadeAtuacao.USF);owner.setUsfAtuacao(x);users.saveAndFlush(owner);
            var foreign=user(TipoUsuario.ADMINISTRADOR,UnidadeAtuacao.USF);foreign.setUsfAtuacao(y);users.saveAndFlush(foreign);return new Object[]{foreign,x.getCnes()};});
        var before=database();assertEquals(404,postEvent((Usuario)values[0],"visualizacoes",new VisualizacaoAlertaDTO(UUID.randomUUID())).getStatus());assertEquals(before,database());var e=json(postEvent(professional,"visualizacoes",new VisualizacaoAlertaDTO(UUID.randomUUID())),201);assertEquals("USF:"+values[1],e.get("unidade").asText());
    }
    @Test void requestKeyCannotBeReusedForAnotherPatientOrOperation() throws Exception {
        eligible();var key=UUID.randomUUID();json(postEvent(admin,"visualizacoes",new VisualizacaoAlertaDTO(key)),201);long count=events.count();
        assertEquals(409,mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/pacientes/"+other.getIdPublico()+"/alertas/visualizacoes").header("Authorization","Bearer "+tokens.gerarToken(admin)).contentType("application/json").content(json.writeValueAsString(new VisualizacaoAlertaDTO(key)))).andReturn().getResponse().getStatus());assertEquals(409,postEvent(admin,"encaminhamentos",forward(key)).getStatus());assertEquals(count,events.count());
    }
    @ParameterizedTest @ValueSource(strings={"missing-key","missing-destination","short-description","large-destination","large-description"})
    void invalidOperationalPayloadIsRejectedWithoutEvents(String scenario) throws Exception {
        eligible();var key=UUID.randomUUID();var body=new HashMap<String,Object>();body.put("idRequisicao",key);body.put("destino",DESTINATION);body.put("descricao",DESCRIPTION);
        switch(scenario){case "missing-key"->body.remove("idRequisicao");case "missing-destination"->body.put("destino"," ");case "short-description"->body.put("descricao"," ");case "large-destination"->body.put("destino","S".repeat(201));case "large-description"->body.put("descricao","S".repeat(1001));default->throw new IllegalArgumentException();}
        var before=database();assertEquals(422,postEvent(admin,"encaminhamentos",body).getStatus());assertEquals(before,database());
    }
    @Test void directServiceCannotBypassOperationalValidation() {
        eligible();var before=database();assertThrows(ValidationException.class,()->audit.registrarEncaminhamento(patient.getIdPublico(),new EncaminhamentoAlertaDTO(UUID.randomUUID()," ",DESCRIPTION)));assertThrows(ValidationException.class,()->audit.registrarVisualizacao(patient.getIdPublico(),new VisualizacaoAlertaDTO(null)));assertEquals(before,database());
    }
    @Test void auditQueriesRespectPageLimitsAndProfiles() throws Exception {
        eligible();json(postEvent(admin,"visualizacoes",new VisualizacaoAlertaDTO(UUID.randomUUID())),201);json(postEvent(admin,"visualizacoes",new VisualizacaoAlertaDTO(UUID.randomUUID())),201);
        var base="/api/pacientes/"+patient.getIdPublico()+"/alertas/auditoria";assertEquals(1,json(mvc.perform(get(base+"?size=1").header("Authorization","Bearer "+tokens.gerarToken(professional))).andReturn().getResponse(),200).get("content").size());
        assertEquals(422,mvc.perform(get(base+"?size=101").header("Authorization","Bearer "+tokens.gerarToken(admin))).andReturn().getResponse().getStatus());assertEquals(403,mvc.perform(get("/api/alertas/auditoria").header("Authorization","Bearer "+tokens.gerarToken(professional))).andReturn().getResponse().getStatus());
    }
    @Test void patientMovedToAnotherUnitDoesNotLeakThroughUnitAuditQuery() throws Exception {
        eligible();json(postEvent(admin,"visualizacoes",new VisualizacaoAlertaDTO(UUID.randomUUID())),201);long count=events.count();tx(()->{var p=patients.findById(patient.getId()).orElseThrow();p.setCapsReferencia(CapsEnum.CAPS_I);patients.saveAndFlush(p);return null;});
        var response=mvc.perform(get("/api/alertas/auditoria").header("Authorization","Bearer "+tokens.gerarToken(admin))).andReturn().getResponse();assertEquals(200,response.getStatus());assertFalse(response.getContentAsString().contains(patient.getIdPublico().toString()));
        assertEquals(404,mvc.perform(get("/api/pacientes/"+patient.getIdPublico()+"/alertas/auditoria").header("Authorization","Bearer "+tokens.gerarToken(admin))).andReturn().getResponse().getStatus());assertEquals(count,events.count());
    }
    @Test void alertAuditIsAppendOnlyInDatabase() throws Exception {
        eligible();json(postEvent(admin,"visualizacoes",new VisualizacaoAlertaDTO(UUID.randomUUID())),201);var before=database();assertThrows(DataAccessException.class,()->jdbc.update("update tb_auditoria_busca_ativa set tipo_evento='ENCAMINHAMENTO_ALERTA' where paciente_id=?",patient.getId()));assertThrows(DataAccessException.class,()->jdbc.update("delete from tb_auditoria_busca_ativa where paciente_id=?",patient.getId()));assertEquals(before,database());
    }
    @Test void eventStorageFailureDoesNotLeavePartialAuditOrClinicalWrites() {
        eligible();var before=database();doAnswer(i->{i.callRealMethod();throw new IllegalStateException("synthetic alert audit failure");}).when(org.springframework.test.util.AopTestUtils.<AuditoriaBuscaAtivaService>getUltimateTargetObject(audit)).registrarVisualizacao(any(),any());
        assertThrows(IllegalStateException.class,()->audit.registrarVisualizacao(patient.getIdPublico(),new VisualizacaoAlertaDTO(UUID.randomUUID())));assertEquals(before,database());
    }
    @Test void failedListAuditRollsBackItsPreexistingRiskRefreshToo() {
        eligible();tx(()->{var a=seed(patient,professional,SituacaoAtendimento.FALTOU,LocalDate.now().minusDays(2),"08:00");histories.registrarAlteracaoDeAtendimento(a,SituacaoAtendimento.AGENDADO);var p=patients.findById(patient.getId()).orElseThrow();clinical.recalcularAssiduidadePaciente(p);p.setClassificacaoRisco(ClassificacaoRisco.AMARELO);patients.saveAndFlush(p);return null;});
        var before=database();doAnswer(i->{i.callRealMethod();throw new IllegalStateException("synthetic list audit failure");}).when(org.springframework.test.util.AopTestUtils.<AuditoriaBuscaAtivaService>getUltimateTargetObject(audit)).registrarConsultaLista(anyInt());
        assertThrows(IllegalStateException.class,()->clinical.listarPacientesEmBuscaAtiva());assertEquals(before,database());
    }
    @Test void concurrentRetriesCreateOnlyOneVisualization() throws Exception {
        eligible();var key=UUID.randomUUID();long count=events.count();var before=domain();assertEquals(new RaceResult(2,0),race("E3-rnf17-idempotent",List.of(()->postEvent(admin,"visualizacoes",new VisualizacaoAlertaDTO(key)).getStatus(),()->postEvent(admin,"visualizacoes",new VisualizacaoAlertaDTO(key)).getStatus())));assertEquals(count+1,events.count());assertEquals(before,domain());
    }
    @ParameterizedTest @ValueSource(strings={"{\"idRequisicao\":\"valor-sintetico-invalido\"}","valor-sintetico-invalido"})
    void malformedPayloadIsSanitizedLocallyWithoutGlobalHandlerChanges(String body) throws Exception {
        eligible();var before=database();var response=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/pacientes/"+patient.getIdPublico()+"/alertas/visualizacoes").header("Authorization","Bearer "+tokens.gerarToken(admin)).contentType("application/json").content(body)).andReturn().getResponse();assertEquals(422,response.getStatus());assertFalse(response.getContentAsString().contains("valor-sintetico-invalido"));assertEquals(before,database());
    }

}
