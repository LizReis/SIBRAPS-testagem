package com.pet.buscaativa;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import com.pet.buscaativa.entities.*;
import com.pet.buscaativa.entities.dto.*;
import com.pet.buscaativa.entities.enums.*;
import com.pet.buscaativa.repositories.*;
import com.pet.buscaativa.services.*;
import tools.jackson.databind.JsonNode;

/** Persisted reservations + actual HTTP initial frequency, P05, corrections and group confirmation. */
class RiskVisitRealFlowTests extends AgendaIntegritySupport {
    static final String REASON="Retificação após conferência do prontuário sintético original.";
    @Autowired PacienteService clinical;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean Clock clock;
    @Autowired AuditoriaCorrecaoRepository audits;
    @Autowired GrupoTerapeuticoService groupService;
    @Autowired SessaoGrupoRepository sessions;
    @BeforeEach void offerToday(){date=LocalDate.now();org.mockito.Mockito.doReturn(date.atTime(20,0).toInstant(ZoneOffset.UTC)).when(clock).instant();tx(()->{capacity(professional,date,TurnoEnum.MANHA,10);return null;});}
    private Agendamento reservation(int days,String time){return tx(()->seed(patient,professional,SituacaoAtendimento.AGENDADO,LocalDate.now().minusDays(days),time));}
    private void frequency(Agendamento a,SituacaoAtendimento f) throws Exception {
        var r=mvc.perform(patch("/api/agendamentos/"+a.getId()+"/status").param("novoStatus",f.name()).param("justificativa",REASON)
            .header("Authorization","Bearer "+tokens.gerarToken(professional))).andReturn().getResponse();assertEquals(200,businessStatus(r),r.getContentAsString());
    }
    private Agendamento reschedule(Agendamento a,String time) throws Exception {
        var dto=new AgendamentoDTO(null,professional.getIdPublico(),null,patient.getIdPublico(),null,null,date,TurnoEnum.MANHA,LocalTime.parse(time),SituacaoAtendimento.REMARCADO,a.getId(),null);
        var r=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/agendamentos").header("Authorization","Bearer "+tokens.gerarToken(admin)).contentType("application/json").content(json.writeValueAsString(dto))).andReturn().getResponse();
        assertEquals(201,businessStatus(r),r.getContentAsString());return appointment(json.readTree(r.getContentAsString()).get("id").asLong());
    }
    private Agendamento[] pair() throws Exception {var a=reservation(5,"07:00");frequency(a,SituacaoAtendimento.FALTOU);var b=reschedule(a,"08:00");frequency(b,SituacaoAtendimento.FALTOU);return new Agendamento[]{a,b};}
    private Paciente current(){return patients.findById(patient.getId()).orElseThrow();}
    private void indicators(int count,ClassificacaoRisco risk,boolean visit){var p=current();assertEquals(count,p.getCountFaltas());assertEquals(risk,p.getClassificacaoRisco());assertEquals(visit,p.getGatilhoVisitaAcionado());assertFalse(p.getIndicadoresPendentes());}
    private JsonNode selected() throws Exception {
        var r=mvc.perform(get("/api/pacientes/busca-ativa").header("Authorization","Bearer "+tokens.gerarToken(admin))).andReturn().getResponse();assertEquals(200,r.getStatus());
        for(var n:json.readTree(r.getContentAsString()))if(patient.getIdPublico().toString().equals(n.get("idPublico").asText()))return n;return null;
    }
    private void recalc(){authenticate();tx(()->{clinical.recalcularAssiduidadePaciente(current());return null;});}
    @Test void a01FirstActualAbsence(){assertDoesNotThrow(()->frequency(reservation(5,"07:00"),SituacaoAtendimento.FALTOU));indicators(1,ClassificacaoRisco.VERDE,false);}
    @Test void a02ActualP05PairIsYellowAndVisibleWithoutForcingRed() throws Exception {pair();indicators(2,ClassificacaoRisco.AMARELO,true);assertNotNull(selected());}
    @Test void a03CorrectOriginalPreservesSubstitutedReservationAndHistory() throws Exception {
        var chain=pair();long rows=appointments.count(),events=history.count(),before=audits.count();frequency(chain[0],SituacaoAtendimento.PRESENTE);
        indicators(1,ClassificacaoRisco.VERDE,false);assertNull(selected());assertEquals(rows,appointments.count());assertEquals(events,history.count());assertEquals(before+1,audits.count());
        assertEquals(SituacaoReserva.SUBSTITUIDA,appointment(chain[0].getId()).getSituacaoReserva());assertEquals(chain[0].getId(),tx(()->appointment(chain[1].getId()).getAgendamentoOriginal().getId()));
        assertEquals(LocalDate.now().minusDays(5),current().getDataUltimaPresenca());assertAudit(chain[0]);
    }
    private void assertAudit(Agendamento a) throws Exception {
        var r=mvc.perform(get("/api/agendamentos/"+a.getId()+"/correcoes").header("Authorization","Bearer "+tokens.gerarToken(professional))).andReturn().getResponse();assertEquals(200,r.getStatus());
        var row=json.readTree(r.getContentAsString()).get("content").get(0);assertEquals("FALTOU",row.get("frequenciaAnterior").asText());assertEquals("PRESENTE",row.get("frequenciaNova").asText());assertEquals(REASON,row.get("justificativa").asText());assertEquals(professional.getIdPublico().toString(),row.get("usuarioId").asText());assertNotNull(Instant.parse(row.get("ocorridoEm").asText()));
    }
    @Test void a04CorrectReplacementClearsAlertAndAudits() throws Exception {var c=pair();frequency(c[1],SituacaoAtendimento.PRESENTE);indicators(0,ClassificacaoRisco.VERDE,false);assertEquals(date,current().getDataUltimaPresenca());assertAudit(c[1]);}
    @Test void a05LaterPairSurvivesEarlierCorrectionInActualSuccessiveReschedules() throws Exception {
        var c=pair();var last=reschedule(c[1],"09:00");frequency(last,SituacaoAtendimento.FALTOU);indicators(3,ClassificacaoRisco.VERMELHO,true);
        frequency(c[0],SituacaoAtendimento.PRESENTE);indicators(2,ClassificacaoRisco.AMARELO,true);assertNotNull(selected());
    }
    @Test void a06ThreeUnrelatedAbsencesAreRedWithoutSpecificVisit() throws Exception {for(int i=3;i>=1;i--)frequency(reservation(i,"07:00"),SituacaoAtendimento.FALTOU);indicators(3,ClassificacaoRisco.VERMELHO,false);assertNotNull(selected());}
    @Test void a07EffectiveGroupPresenceResetsSharedChronology() throws Exception {
        pair();authenticate();var g=groupService.criarGrupo(new CriarGrupoDTO("Grupo sintético cronologia",professional.getIdPublico(),RecorrenciaGrupo.UNICA,date,null,LocalTime.of(13,0),List.of(patient.getIdPublico())));
        var s=tx(()->sessions.findAll().stream().filter(x->x.getGrupo().getId().equals(g.id())).findFirst().orElseThrow());
        // Retrieve by scalar group identity; avoid relying on a detached collection.
        var dto=new ConfirmarOcorrenciaGrupoDTO(true,List.of(new FrequenciaParticipanteGrupoDTO(patient.getIdPublico(),StatusPresencaGrupo.PRESENTE)),null,s.getVersion());
        var r=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/grupos/sessoes/"+s.getId()+"/confirmacao-ocorrencia").header("Authorization","Bearer "+tokens.gerarToken(professional)).contentType("application/json").content(json.writeValueAsString(dto))).andReturn().getResponse();assertEquals(200,businessStatus(r),r.getContentAsString());
        indicators(0,ClassificacaoRisco.VERDE,false);assertEquals(date,current().getDataUltimaPresenca());assertNull(selected());
    }
    @Test void a09ArtificialGreenVisitIsVisibleThenRepairedByCompleteFacts() throws Exception {
        frequency(reservation(5,"07:00"),SituacaoAtendimento.PRESENTE);
        tx(()->{var p=current();p.setClassificacaoRisco(ClassificacaoRisco.VERDE);p.setGatilhoVisitaAcionado(true);patients.saveAndFlush(p);return null;});assertNotNull(selected());recalc();indicators(0,ClassificacaoRisco.VERDE,false);assertNull(selected());
    }
    @Test void existingInactiveExceptionDoesNotRedefineActivePatientRisk() throws Exception {
        pair();var r=mvc.perform(patch("/api/pacientes/"+patient.getIdPublico()+"/encerrar").header("Authorization","Bearer "+tokens.gerarToken(admin)).contentType("application/json").content("{\"motivo\":\"ALTA_TERAPEUTICA\",\"statusPaciente\":\"INATIVO\",\"descricao\":\"Encerramento sintético autorizado.\"}")).andReturn().getResponse();assertEquals(204,r.getStatus(),r.getContentAsString());recalc();
        indicators(2,ClassificacaoRisco.VERDE,true);assertEquals(StatusPaciente.INATIVO,current().getStatusPaciente());assertNull(selected());
    }
    @Test void existingTimeThresholdCanTurnOneAbsenceRedWithoutVisit() throws Exception {
        frequency(reservation(121,"07:00"),SituacaoAtendimento.PRESENTE);frequency(reservation(1,"07:00"),SituacaoAtendimento.FALTOU);indicators(1,ClassificacaoRisco.VERMELHO,false);assertNotNull(selected());
    }
}
