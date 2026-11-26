package com.pet.buscaativa;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

import java.time.*;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import com.pet.buscaativa.entities.*;
import com.pet.buscaativa.entities.dto.*;
import com.pet.buscaativa.entities.enums.*;
import com.pet.buscaativa.repositories.*;

/** Fixtures históricas sintéticas já persistidas; a operação investigada sempre passa pela API. */
abstract class Historical61Support extends CancellationRegularizationSupport {
    static final LocalDate PAST = DAY.minusDays(2);
    static final LocalTime SLOT = LocalTime.of(8,0);
    static final String GROUP = "/api/grupos";
    @Autowired GrupoTerapeuticoRepository groups61;
    @Autowired SessaoGrupoRepository sessions61;
    @Autowired SessaoGrupoParticipanteRepository participants61;

    @BeforeEach void historicalModalities61() {
        tx(() -> {
            for (var p : List.of(patient, other)) {
                var x = patients.findById(p.getId()).orElseThrow();
                x.setTipoAcompanhamento(TipoAcompanhamento.AMBOS);
                patients.saveAndFlush(x);
            }
            return null;
        });
        patient = patients.findById(patient.getId()).orElseThrow();
        other = patients.findById(other.getId()).orElseThrow();
    }
    record GroupRef61(Long group, Long session, Integer version) {}
    record Indicators61(int absences, LocalDate last, ClassificacaoRisco risk, Boolean pending, Boolean visit) {}
    MockHttpServletResponse http61(MockHttpServletRequestBuilder req, Object body) throws Exception {
        req.header("Authorization", "Bearer " + tokens.gerarToken(admin));
        if (body != null) req.contentType("application/json").content(json.writeValueAsString(body));
        return mvc.perform(req).andReturn().getResponse();
    }
    @FunctionalInterface interface Operation61 { MockHttpServletResponse run() throws Exception; }
    void refuse61(String id, Operation61 operation) throws Exception {
        var before = database();
        var response = operation.run();
        int status = businessStatus(response);
        boolean unchanged = before.equals(database());
        System.out.printf("E61 %s expected=409 actual=%d unchanged=%s%n", id, status, unchanged);
        assertAll(id, () -> assertEquals(409, status, response.getContentAsString()),
                () -> assertTrue(unchanged, "A recusa deve preservar fatos, histórico, versões, indicadores e auditorias."));
    }
    void allow61(String id, Operation61 operation) throws Exception {
        var response = operation.run();
        int status = businessStatus(response);
        System.out.printf("E61 %s expected=200 actual=%d%n",id,status);
        assertEquals(200,status,response.getContentAsString());
    }
    Indicators61 indicators61() {
        var p=patients.findById(patient.getId()).orElseThrow();
        return new Indicators61(p.getCountFaltas(),p.getDataUltimaPresenca(),p.getClassificacaoRisco(),p.getIndicadoresPendentes(),p.getGatilhoVisitaAcionado());
    }
    void recalculate61() {
        authenticate();
        tx(() -> {
            clinical.recalcularAssiduidadePaciente(patients.findById(patient.getId()).orElseThrow());
            return null;
        });
    }
    Agendamento individual61(Paciente p, Usuario u, SituacaoAtendimento state, LocalDate day, String time) {
        return tx(() -> {
            var a=seed(p,u,state,day,time);
            histories.registrarAgendamento(a);
            if(state==SituacaoAtendimento.PRESENTE || state==SituacaoAtendimento.FALTOU)
                event61(a,state);
            return a;
        });
    }
    void event61(Agendamento a, SituacaoAtendimento state) {
        var h=new HistoricoPaciente();
        h.setPaciente(a.getPaciente());h.setProfissional(a.getUsuario());h.setAgendamento(a);
        h.setTipo(state==SituacaoAtendimento.PRESENTE?TipoEventoHistoricoPaciente.PRESENCA:TipoEventoHistoricoPaciente.FALTA);
        h.setSituacaoAtendimento(state);h.setOcorridoEm(LocalDateTime.of(a.getDataAgendamento(),a.getHoraAtendimento()));
        h.setDescricao("Evidência sintética para caracterização 6.1");history.saveAndFlush(h);
    }
    GroupRef61 session61(Usuario coordinator, LocalDate day, LocalTime time, boolean realized,
                         StatusPresencaGrupo presence, Paciente... people) {
        return tx(() -> {
            var g=new GrupoTerapeutico();g.setTema("Grupo histórico sintético 6.1");g.setCoordenador(coordinator);
            g.setRecorrencia(RecorrenciaGrupo.UNICA);g.setHorarioPadrao(time);g.setAtivo(true);groups61.saveAndFlush(g);
            var s=new SessaoGrupo();s.setGrupo(g);s.setDataSessao(day);s.setHorario(time);s.setStatus(StatusSessaoGrupo.AGENDADA);
            if(realized)s.confirmarRealizacao(coordinator);
            sessions61.saveAndFlush(s);
            for(var person:people){
                var p=new SessaoGrupoParticipante();p.setSessaoGrupo(s);p.setPaciente(person);p.setStatusPresenca(presence);
                participants61.saveAndFlush(p);
                if(realized && presence!=StatusPresencaGrupo.NAO_REGISTRADA) histories.registrarFrequenciaGrupo(person,s,presence);
            }
            return new GroupRef61(g.getId(),s.getId(),s.getVersion());
        });
    }
    FrequenciaParticipanteGrupoDTO frequency61(Paciente p, StatusPresencaGrupo presence) {
        return new FrequenciaParticipanteGrupoDTO(p.getIdPublico(),presence);
    }
    MockHttpServletResponse confirm61(GroupRef61 g, Paciente p) throws Exception {
        return http61(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(GROUP+"/sessoes/"+g.session()+"/confirmacao-ocorrencia"),
                new ConfirmarOcorrenciaGrupoDTO(true,List.of(frequency61(p,StatusPresencaGrupo.PRESENTE)),null,g.version()));
    }
    MockHttpServletResponse correctGroup61(GroupRef61 g, Paciente p) throws Exception {
        return http61(patch(GROUP+"/sessoes/"+g.session()+"/frequencias"),
                new CorrigirFrequenciasGrupoDTO(List.of(frequency61(p,StatusPresencaGrupo.PRESENTE)),g.version(),REASON));
    }
    MockHttpServletResponse retro61(GroupRef61 g, Paciente p) throws Exception {
        return http61(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(GROUP+"/"+g.group()+"/inscricoes-retroativas"),
                new InscricaoRetroativaGrupoDTO(p.getIdPublico(),List.of(new FrequenciaSessaoRetroativaDTO(g.session(),StatusPresencaGrupo.PRESENTE))));
    }
    MockHttpServletResponse initial61(Agendamento a) throws Exception {
        return http61(patch("/api/agendamentos/"+a.getId()+"/status").param("novoStatus","PRESENTE"),null);
    }
    MockHttpServletResponse correction61(Agendamento a) throws Exception {
        return http61(patch("/api/agendamentos/"+a.getId()+"/frequencia/correcao"),
                new CorrigirFrequenciaDTO(FrequenciaAssistencial.PRESENTE,a.getVersion(),REASON));
    }
}

class HistoricalCollision61Characterization extends Historical61Support {
    @ParameterizedTest @ValueSource(booleans={true,false})
    void confirmingSessionCannotCreateIncompatiblePresence(boolean samePatient) throws Exception {
        individual61(patient,professional,SituacaoAtendimento.PRESENTE,PAST,"08:00");
        var participant=samePatient?patient:other;
        var g=session61(samePatient?colleague:professional,PAST,SLOT,false,StatusPresencaGrupo.NAO_REGISTRADA,participant);
        refuse61(samePatient?"GI-07-PACIENTE":"GI-07-PROFISSIONAL",()->confirm61(g,participant));
    }
    @Test void groupCorrectionCannotCreateSecondPatientPresence() throws Exception {
        var g=session61(colleague,PAST,SLOT,true,StatusPresencaGrupo.FALTOU,patient);
        // Fato individual existente confronta a correção pretendida, sem decidir a validade da falta antiga.
        individual61(patient,professional,SituacaoAtendimento.PRESENTE,PAST,"08:00");
        recalculate61();refuse61("GI-08",()->correctGroup61(g,patient));
    }
    @Test void groupCorrectionCannotCreateTwoEffectiveGroupPresences() throws Exception {
        var g=session61(colleague,PAST,SLOT,true,StatusPresencaGrupo.FALTOU,patient);
        session61(professional,PAST,SLOT,true,StatusPresencaGrupo.PRESENTE,patient);
        recalculate61();refuse61("GR-10-CORRECAO",()->correctGroup61(g,patient));
    }
    @ParameterizedTest @ValueSource(booleans={true,false})
    void retroactiveEnrollmentCannotCreateSecondPresence(boolean individual) throws Exception {
        var g=session61(colleague,PAST,SLOT,true,StatusPresencaGrupo.PRESENTE,other);
        if(individual)individual61(patient,professional,SituacaoAtendimento.PRESENTE,PAST,"08:00");
        else session61(professional,PAST,SLOT,true,StatusPresencaGrupo.PRESENTE,patient);
        refuse61(individual?"GI-09":"GR-10-RETRO",()->retro61(g,patient));
    }
    @Test void distinctTimeHistoricalConfirmationRemainsLegitimate() throws Exception {
        individual61(patient,professional,SituacaoAtendimento.PRESENTE,PAST,"08:30");
        var g=session61(colleague,PAST,SLOT,false,StatusPresencaGrupo.NAO_REGISTRADA,patient);
        allow61("GI-07-CONTROLE",()->confirm61(g,patient));
        assertEquals(StatusSessaoGrupo.REALIZADA,sessions61.findById(g.session()).orElseThrow().getStatus());
    }
    @Test void compatibleCorrectionRemainsLegitimate() throws Exception {
        var g=session61(colleague,PAST,SLOT,true,StatusPresencaGrupo.FALTOU,patient);
        individual61(patient,professional,SituacaoAtendimento.PRESENTE,PAST,"08:30");
        allow61("GI-08-CONTROLE",()->correctGroup61(g,patient));
    }
}
