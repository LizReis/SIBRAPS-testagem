package com.pet.buscaativa;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import com.pet.buscaativa.entities.*;
import com.pet.buscaativa.entities.dto.*;
import com.pet.buscaativa.entities.enums.*;
import com.pet.buscaativa.repositories.*;
import com.pet.buscaativa.services.GrupoTerapeuticoService;

class ClinicalAuthorizationTests extends AgendaIntegritySupport {
    @Autowired GrupoTerapeuticoRepository groups;
    @Autowired SessaoGrupoRepository sessions;
    @Autowired SessaoGrupoParticipanteRepository participants;
    @Autowired UsfReferenciaRepository usfs;
    @Autowired GrupoTerapeuticoService groupService;
    @Autowired com.pet.buscaativa.services.HistoricoPacienteService histories;
    private Usuario actor(String role) {return switch(role){case "owner"->professional;case "colleague"->colleague;case "admin"->admin;
        case "reception"->tx(()->user(TipoUsuario.RECEPCAO,UnidadeAtuacao.CAPS_AD));
        case "foreign"->tx(()->user(TipoUsuario.ADMINISTRADOR,UnidadeAtuacao.CAPS_I));default->throw new IllegalArgumentException();};}
    private int change(Usuario actor,Agendamento a,SituacaoAtendimento status) throws Exception {
        return mvc.perform(patch("/api/agendamentos/"+a.getId()+"/status").param("novoStatus",status.name())
                .param("justificativa","Registro anterior conferido na documentação assistencial.")
                .header("Authorization","Bearer "+tokens.gerarToken(actor))).andReturn().getResponse().getStatus();
    }
    @ParameterizedTest @CsvSource({"owner,200","colleague,403","admin,200","reception,403","foreign,404"})
    void au01To07ResponsibilityIsPerAppointment(String role,int expected) throws Exception {
        var a=tx(()->seed(patient,professional,SituacaoAtendimento.FALTOU,LocalDate.now().minusDays(3),"08:00"));
        var before=snapshot(patient,a.getId());assertEquals(expected,change(actor(role),a,SituacaoAtendimento.PRESENTE));
        if(expected!=200)assertEquals(before,snapshot(patient,a.getId()));
        else assertEquals(FrequenciaAssistencial.PRESENTE,appointment(a.getId()).getFrequenciaAssistencial());
    }
    @Test void au08UnauthenticatedCannotChangeFrequency() throws Exception {
        var a=tx(()->seed(patient,professional,SituacaoAtendimento.FALTOU,LocalDate.now().minusDays(3),"08:00"));var before=snapshot(patient,a.getId());
        assertEquals(401,mvc.perform(patch("/api/agendamentos/"+a.getId()+"/status").param("novoStatus","PRESENTE")).andReturn().getResponse().getStatus());assertEquals(before,snapshot(patient,a.getId()));
    }
    @Test void au09PatientReferenceDoesNotAuthorizeColleaguesAppointment() throws Exception {
        tx(()->{var p=patients.findById(patient.getId()).orElseThrow();p.setProfissionalReferencia(colleague);patients.saveAndFlush(p);return null;});
        var a=tx(()->seed(patient,professional,SituacaoAtendimento.FALTOU,LocalDate.now().minusDays(3),"08:00"));var before=snapshot(patient,a.getId());
        assertEquals(403,change(colleague,a,SituacaoAtendimento.PRESENTE));assertEquals(before,snapshot(patient,a.getId()));
    }
    @Test void idempotentRequestStillRequiresResponsibility() throws Exception {
        var a=tx(()->seed(patient,professional,SituacaoAtendimento.FALTOU,LocalDate.now().minusDays(3),"08:00"));var before=snapshot(patient,a.getId());
        assertEquals(403,change(colleague,a,SituacaoAtendimento.FALTOU));assertEquals(before,snapshot(patient,a.getId()));
    }
    @Test void serviceCannotBeBypassedBySkippingTheController() {
        var a=tx(()->seed(patient,professional,SituacaoAtendimento.FALTOU,LocalDate.now().minusDays(3),"08:00"));var before=snapshot(patient,a.getId());
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(colleague,null,colleague.getAuthorities()));
        assertThrows(AccessDeniedException.class,()->service.atualizarStatus(a.getId(),SituacaoAtendimento.PRESENTE,null));assertEquals(before,snapshot(patient,a.getId()));
    }
    @Test void au10ResponsibleCanCorrectKnownFactWithoutReopeningSubstitutedBooking() throws Exception {
        var a=tx(()->{var x=seed(patient,professional,SituacaoAtendimento.FALTOU,LocalDate.now().minusDays(3),"08:00");x.setSituacaoAtendimento(SituacaoAtendimento.REMARCADO_ORIGEM);return appointments.saveAndFlush(x);});
        assertEquals(200,change(professional,a,SituacaoAtendimento.PRESENTE));var fresh=appointment(a.getId());assertEquals(SituacaoReserva.SUBSTITUIDA,fresh.getSituacaoReserva());assertEquals(FrequenciaAssistencial.PRESENTE,fresh.getFrequenciaAssistencial());assertFalse(fresh.getOcupaVaga());
    }
    @ParameterizedTest @CsvSource({"owner,200","colleague,403","reception,200"})
    void initialRecordingPreservesReceptionButRequiresResponsibleProfessional(String role,int expected) throws Exception {
        var a=tx(()->seed(patient,professional,SituacaoAtendimento.AGENDADO,LocalDate.now().minusDays(3),"08:00"));var before=snapshot(patient,a.getId());
        assertEquals(expected,change(actor(role),a,SituacaoAtendimento.FALTOU));if(expected!=200)assertEquals(before,snapshot(patient,a.getId()));
    }
    @Test void au06DifferentUsfIsNotTheSameUnit() throws Exception {
        var data=tx(()->{
            var x=new UsfReferencia();x.setCnes(UUID.randomUUID().toString());x=usfs.saveAndFlush(x);var y=new UsfReferencia();y.setCnes(UUID.randomUUID().toString());y=usfs.saveAndFlush(y);
            var owner=user(TipoUsuario.PROFISSIONAL,UnidadeAtuacao.USF);owner.setUsfAtuacao(x);users.saveAndFlush(owner);
            var external=user(TipoUsuario.ADMINISTRADOR,UnidadeAtuacao.USF);external.setUsfAtuacao(y);users.saveAndFlush(external);
            var p=patients.findById(patient.getId()).orElseThrow();p.setCapsReferencia(null);p.setUsfReferencia(x);patients.saveAndFlush(p);
            return new Object[]{seed(p,owner,SituacaoAtendimento.FALTOU,LocalDate.now().minusDays(3),"08:00"),external};});
        var a=(Agendamento)data[0];var before=snapshot(patient,a.getId());assertEquals(404,change((Usuario)data[1],a,SituacaoAtendimento.PRESENTE));assertEquals(before,snapshot(patient,a.getId()));
    }
    private SessaoGrupo session(StatusSessaoGrupo status) {
        return tx(()->{var g=new GrupoTerapeutico();g.setCoordenador(professional);g.setTema("Grupo sintético protegido");g.setRecorrencia(RecorrenciaGrupo.UNICA);g.setHorarioPadrao(LocalTime.of(8,0));g=groups.saveAndFlush(g);
            var s=new SessaoGrupo();s.setGrupo(g);s.setStatus(status);s.setDataSessao(LocalDate.now().minusDays(2));s.setHorario(LocalTime.of(8,0));s=sessions.saveAndFlush(s);
            var p=new SessaoGrupoParticipante();p.setPaciente(patient);p.setSessaoGrupo(s);p.setStatusPresenca(status==StatusSessaoGrupo.REALIZADA?StatusPresencaGrupo.FALTOU:StatusPresencaGrupo.NAO_REGISTRADA);participants.saveAndFlush(p);if(status==StatusSessaoGrupo.REALIZADA)histories.registrarFrequenciaGrupo(patient,s,StatusPresencaGrupo.FALTOU);return s;});
    }
    @ParameterizedTest @CsvSource({"owner,200","colleague,403","admin,200","reception,403","foreign,404"})
    void groupCorrectionsRequireCoordinatorOrUnitAdmin(String role,int expected) throws Exception {
        var s=session(StatusSessaoGrupo.REALIZADA);var dto=new CorrigirFrequenciasGrupoDTO(List.of(new FrequenciaParticipanteGrupoDTO(patient.getIdPublico(),StatusPresencaGrupo.PRESENTE)),s.getVersion());
        var before=snapshot(patient,null);var r=mvc.perform(patch("/api/grupos/sessoes/"+s.getId()+"/frequencias")
            .header("Authorization","Bearer "+tokens.gerarToken(actor(role))).contentType("application/json").content(json.writeValueAsString(dto))).andReturn().getResponse();assertEquals(expected,r.getStatus());
        var state=tx(()->participants.findBySessaoGrupoAndPaciente(s,patient).orElseThrow().getStatusPresenca());
        assertEquals(expected==200?StatusPresencaGrupo.PRESENTE:StatusPresencaGrupo.FALTOU,state);if(expected!=200)assertEquals(before,snapshot(patient,null));
    }
    @Test void groupBatchWithForeignParticipantIsRejectedBeforeAnyChanges() throws Exception {
        var s=session(StatusSessaoGrupo.REALIZADA);tx(()->{var p=patient(CapsEnum.CAPS_I);var x=new SessaoGrupoParticipante();x.setPaciente(p);x.setSessaoGrupo(s);x.setStatusPresenca(StatusPresencaGrupo.FALTOU);participants.saveAndFlush(x);return null;});
        var before=snapshot(patient,null);var dto=new CorrigirFrequenciasGrupoDTO(List.of(new FrequenciaParticipanteGrupoDTO(patient.getIdPublico(),StatusPresencaGrupo.PRESENTE)),s.getVersion());
        assertEquals(404,mvc.perform(patch("/api/grupos/sessoes/"+s.getId()+"/frequencias").header("Authorization","Bearer "+tokens.gerarToken(admin)).contentType("application/json").content(json.writeValueAsString(dto))).andReturn().getResponse().getStatus());
        assertEquals(before,snapshot(patient,null));assertEquals(StatusPresencaGrupo.FALTOU,tx(()->participants.findBySessaoGrupoAndPaciente(s,patient).orElseThrow().getStatusPresenca()));
    }
    @ParameterizedTest @CsvSource({"owner,200","colleague,403"})
    void provisionalGroupAttendanceCannotBypassResponsibility(String role,int expected) throws Exception {
        var s=session(StatusSessaoGrupo.AGENDADA);assertEquals(expected,mvc.perform(patch("/api/grupos/sessoes/"+s.getId()+"/participantes/"+patient.getIdPublico()+"/presenca")
            .header("Authorization","Bearer "+tokens.gerarToken(actor(role))).contentType("application/json").content("{\"statusPresenca\":\"PRESENTE\"}")).andReturn().getResponse().getStatus());
    }
    @Test void colleagueCannotAssignTheGroupToSelfToBypassResponsibility() throws Exception {
        var s=session(StatusSessaoGrupo.REALIZADA);var g=groups.findById(s.getGrupo().getId()).orElseThrow();
        var dto=new AtualizarGrupoTerapeuticoDTO(g.getTema(),colleague.getIdPublico(),s.getDataSessao(),s.getHorario(),RecorrenciaGrupo.UNICA,null,g.getVersion());
        var before=snapshot(patient,null);assertEquals(403,mvc.perform(put("/api/grupos/"+g.getId()).header("Authorization","Bearer "+tokens.gerarToken(colleague))
                .contentType("application/json").content(json.writeValueAsString(dto))).andReturn().getResponse().getStatus());
        assertEquals(before,snapshot(patient,null));assertEquals(professional.getId(),tx(()->groups.findById(g.getId()).orElseThrow().getCoordenador().getId()));
    }

}
