package com.pet.buscaativa;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.beans.factory.annotation.Autowired;
import com.pet.buscaativa.entities.*;
import com.pet.buscaativa.entities.enums.*;
import com.pet.buscaativa.repositories.*;

class FrequencyCollisionIdentificationTests extends FrequencyCorrectionTestSupport {
    @Autowired GrupoTerapeuticoRepository groups;
    @Autowired SessaoGrupoRepository sessions;
    @Autowired SessaoGrupoParticipanteRepository participants;
    @ParameterizedTest @CsvSource({"true,AGENDADO","true,REMARCADO","true,PRESENTE","false,AGENDADO","false,REMARCADO","false,PRESENTE"})
    void ag09And10ActualCollisionIsStructuredAndRepeatedWithoutAnyWrites(boolean samePatient,SituacaoAtendimento state) throws Exception {var day=LocalDate.now().minusDays(5);var a=fact(SituacaoAtendimento.FALTOU,day,"08:00");tx(()->{seed(samePatient?patient:other,samePatient?colleague:professional,state,day,"08:00");return null;});for(int i=0;i<2;i++)conflict(a,samePatient?"PACIENTE":"PROFISSIONAL");var before=database();var r=mvc.perform(patch("/api/agendamentos/"+a.getId()+"/status").param("novoStatus","PRESENTE").param("justificativa",REASON).header("Authorization","Bearer "+tokens.gerarToken(admin))).andReturn().getResponse();assertEquals(409,r.getStatus());assertEquals("CONFLITO_ASSISTENCIAL_PENDENTE_RECONCILIACAO",json.readTree(r.getContentAsString()).get("codigo").asText());assertEquals(before,database());}
    @ParameterizedTest @CsvSource({"true,FALTOU","true,CANCELADO","true,REMARCADO_ORIGEM","false,FALTOU","false,CANCELADO","false,REMARCADO_ORIGEM"})
    void ag11And12NonoccupyingNeighborsDoNotCreateFalseConflicts(boolean samePatient,SituacaoAtendimento state) throws Exception {var day=LocalDate.now().minusDays(5);var a=fact(SituacaoAtendimento.FALTOU,day,"08:00");tx(()->{seed(samePatient?patient:other,samePatient?colleague:professional,state,day,"08:00");return null;});assertEquals(200,correct(a));}
    @Test void differentPatientsAndProfessionalsAtSameTimeDoNotConflict() throws Exception {var day=LocalDate.now().minusDays(5);var a=fact(SituacaoAtendimento.FALTOU,day,"08:00");tx(()->seed(other,colleague,SituacaoAtendimento.PRESENTE,day,"08:00"));assertEquals(200,correct(a));}
    @Test void samePatientAtDifferentTimeIsAllowed() throws Exception {var day=LocalDate.now().minusDays(5);var a=fact(SituacaoAtendimento.FALTOU,day,"08:00");tx(()->seed(patient,colleague,SituacaoAtendimento.PRESENTE,day,"08:30"));assertEquals(200,correct(a));}
    @ParameterizedTest @EnumSource(value=StatusSessaoGrupo.class,names={"REALIZADA","AGENDADA","CANCELADA"})
    void existingGroupParticipationUsesItsExistingConflictRule(StatusSessaoGrupo status) throws Exception {var day=LocalDate.now().minusDays(5);var a=fact(SituacaoAtendimento.FALTOU,day,"08:00");tx(()->{var g=new GrupoTerapeutico();g.setCoordenador(colleague);g.setTema("Grupo sintético colisão");groups.saveAndFlush(g);var s=new SessaoGrupo();s.setGrupo(g);s.setDataSessao(day);s.setHorario(LocalTime.of(8,0));s.setStatus(StatusSessaoGrupo.AGENDADA);if(status==StatusSessaoGrupo.REALIZADA)s.confirmarRealizacao(colleague);else s.setStatus(status);sessions.saveAndFlush(s);var p=new SessaoGrupoParticipante();p.setSessaoGrupo(s);p.setPaciente(patient);p.setStatusPresenca(status==StatusSessaoGrupo.REALIZADA?StatusPresencaGrupo.PRESENTE:StatusPresencaGrupo.NAO_REGISTRADA);participants.saveAndFlush(p);return null;});if(status==StatusSessaoGrupo.CANCELADA)assertEquals(200,correct(a));else conflict(a,"PARTICIPACAO_GRUPO");}
    @Test void subsequentlyDiscoveredCollisionDoesNotRewriteEarlierSuccessfulAudits() throws Exception {var day=LocalDate.now().minusDays(5);var a=fact(SituacaoAtendimento.FALTOU,day,"08:00");assertEquals(200,correct(a));a=appointment(a.getId());assertEquals(200,correct(professional,a,FrequenciaAssistencial.FALTOU,a.getVersion()).getStatus());var current=appointment(a.getId());tx(()->seed(other,professional,SituacaoAtendimento.AGENDADO,day,"08:00"));long count=audits.count();conflict(current,"PROFISSIONAL");assertEquals(count,audits.count());assertEquals(2,current.getVersion());}
    @Test void conflictNeverDisclosesForeignPatientAndExternalActorCannotProbe() throws Exception {var day=LocalDate.now().minusDays(5);var a=fact(SituacaoAtendimento.FALTOU,day,"08:00");var foreignPatient=tx(()->{var p=patient(CapsEnum.CAPS_I);seed(p,professional,SituacaoAtendimento.AGENDADO,day,"08:00");return p;});var r=correct(professional,a,FrequenciaAssistencial.PRESENTE,a.getVersion());assertEquals(409,r.getStatus());assertFalse(r.getContentAsString().contains(foreignPatient.getNome()));assertFalse(r.getContentAsString().contains(foreignPatient.getIdPublico().toString()));var foreignActor=tx(()->user(TipoUsuario.ADMINISTRADOR,UnidadeAtuacao.CAPS_I));var before=database();r=correct(foreignActor,a,FrequenciaAssistencial.PRESENTE,a.getVersion());assertEquals(404,r.getStatus());assertFalse(r.getContentAsString().contains("tipoConflito"));assertEquals(before,database());}
    @ParameterizedTest @ValueSource(booleans={true,false})
    void groupProfessionalConflictUsesFrozenResponsibilityRatherThanCurrentCoordinator(boolean originalResponsibleMatches) throws Exception {
        var day=LocalDate.now().minusDays(5);var a=fact(SituacaoAtendimento.FALTOU,day,"08:00");tx(()->{var old=originalResponsibleMatches?professional:colleague;var g=new GrupoTerapeutico();g.setCoordenador(old);g.setTema("Grupo sintético responsabilidade de conflito");groups.saveAndFlush(g);var s=new SessaoGrupo();s.setGrupo(g);s.setStatus(StatusSessaoGrupo.AGENDADA);s.setDataSessao(day);s.setHorario(LocalTime.of(8,0));s.confirmarRealizacao(old);sessions.saveAndFlush(s);var p=new SessaoGrupoParticipante();p.setSessaoGrupo(s);p.setPaciente(other);p.setStatusPresenca(StatusPresencaGrupo.PRESENTE);participants.saveAndFlush(p);g.setCoordenador(originalResponsibleMatches?colleague:professional);groups.saveAndFlush(g);return null;});
        if(originalResponsibleMatches)conflict(a,"SESSAO_PROFISSIONAL");else assertEquals(200,correct(a));
    }
}
