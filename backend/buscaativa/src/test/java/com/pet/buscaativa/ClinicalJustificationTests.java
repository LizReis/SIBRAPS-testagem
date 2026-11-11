package com.pet.buscaativa;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import java.time.*;
import java.util.*;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import com.pet.buscaativa.entities.*;
import com.pet.buscaativa.entities.dto.*;
import com.pet.buscaativa.entities.enums.*;
import com.pet.buscaativa.repositories.*;
import com.pet.buscaativa.services.*;
import com.pet.buscaativa.services.exceptions.ValidationException;

class ClinicalJustificationTests extends AgendaIntegritySupport {
    @Autowired GrupoTerapeuticoRepository groups;
    @Autowired SessaoGrupoRepository sessions;
    @Autowired SessaoGrupoParticipanteRepository participants;
    @Autowired HistoricoPacienteService histories;
    @Autowired PoliticaJustificativaCorrecao reasons;
    private Agendamento fact(){return tx(()->seed(patient,professional,SituacaoAtendimento.FALTOU,LocalDate.now().minusDays(2),"08:00"));}
    private int change(Agendamento a,String reason,SituacaoAtendimento next) throws Exception {
        var request=patch("/api/agendamentos/"+a.getId()+"/status").param("novoStatus",next.name()).header("Authorization","Bearer "+tokens.gerarToken(admin));
        if(reason!=null)request.param("justificativa",reason);return mvc.perform(request).andReturn().getResponse().getStatus();
    }
    static Stream<String> invalidReasons(){return Stream.of(null,"","   ","curto","Correção","Alteração","Sem motivo","Não informado","x".repeat(2001),"Conferido no registro\u0000assistencial");}
    @ParameterizedTest @MethodSource("invalidReasons")
    void ju02To06And08LegacyCorrectionRejectsInvalidReasonWithoutWrites(String reason) throws Exception {
        var a=fact();var before=snapshot(patient,a.getId());assertEquals(422,change(a,reason,SituacaoAtendimento.PRESENTE));assertEquals(before,snapshot(patient,a.getId()));
    }
    @ParameterizedTest @ValueSource(ints={10,2000})
    void ju01StorageBoundariesAreAccepted(int size) throws Exception {
        var a=fact();assertEquals(200,change(a,"A".repeat(size),SituacaoAtendimento.PRESENTE));assertEquals(FrequenciaAssistencial.PRESENTE,appointment(a.getId()).getFrequenciaAssistencial());
    }
    @Test void normalizationIsControlledAndDoesNotInventAMotive(){assertEquals("Documento conferido pela equipe.",reasons.validar("  Documento\n  conferido\t pela equipe.  "));}
    @Test void serviceLegacyOverloadCannotBypassMandatoryReason(){var a=fact();var before=snapshot(patient,a.getId());assertThrows(ValidationException.class,()->service.atualizarStatus(a.getId(),SituacaoAtendimento.PRESENTE,null));assertEquals(before,snapshot(patient,a.getId()));}
    @Test void ju03DirectServiceRejectsExplicitNull(){var a=fact();var before=snapshot(patient,a.getId());assertThrows(ValidationException.class,()->service.atualizarStatus(a.getId(),SituacaoAtendimento.PRESENTE,null,null));assertEquals(before,snapshot(patient,a.getId()));}
    @Test void ju07InitialFrequencyDoesNotRequireArtificialReason() throws Exception {
        var a=tx(()->seed(patient,professional,SituacaoAtendimento.AGENDADO,LocalDate.now().minusDays(2),"08:00"));assertEquals(200,change(a,null,SituacaoAtendimento.FALTOU));assertEquals(FrequenciaAssistencial.FALTOU,appointment(a.getId()).getFrequenciaAssistencial());
    }
    @Test void unchangedAuthorizedStateIgnoresReasonAndCreatesNoClinicalEvent() throws Exception {
        var a=fact();var before=snapshot(patient,a.getId());assertEquals(200,change(a,"Sem motivo",SituacaoAtendimento.FALTOU));assertEquals(before,snapshot(patient,a.getId()));
    }
    @Test void registrationDtoCannotWriteClinicalFrequencyOrCanonicalResponseFields() throws Exception {
        var body=Map.of("usuarioId",professional.getIdPublico(),"pacienteId",patient.getIdPublico(),"dataAgendamento",date,
            "turnoAgendamento","MANHA","horaAtendimento","08:00","situacaoAtendimento","PRESENTE","frequenciaAssistencial","PRESENTE","situacaoReserva","SUBSTITUIDA","migracaoPendente",false);
        var r=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/agendamentos").header("Authorization","Bearer "+tokens.gerarToken(admin)).contentType("application/json").content(json.writeValueAsString(body))).andReturn().getResponse();
        assertEquals(201,r.getStatus());var result=json.readTree(r.getContentAsString());assertEquals("AGENDADA",result.get("situacaoReserva").asText());assertEquals("NAO_REGISTRADA",result.get("frequenciaAssistencial").asText());
    }
    private SessaoGrupo groupFact(){return tx(()->{
        var g=new GrupoTerapeutico();g.setCoordenador(professional);g.setTema("Justificativa sintética");groups.saveAndFlush(g);
        var s=new SessaoGrupo();s.setGrupo(g);s.setStatus(StatusSessaoGrupo.AGENDADA);s.confirmarRealizacao(professional);s.setDataSessao(LocalDate.now().minusDays(2));s.setHorario(LocalTime.of(8,0));sessions.saveAndFlush(s);
        var p=new SessaoGrupoParticipante();p.setSessaoGrupo(s);p.setPaciente(patient);p.setStatusPresenca(StatusPresencaGrupo.FALTOU);participants.saveAndFlush(p);histories.registrarFrequenciaGrupo(patient,s,StatusPresencaGrupo.FALTOU);return s;});}
    @Test void groupCorrectionWithoutReasonRollsBackWholeBatch() throws Exception {
        var s=groupFact();var before=snapshot(patient,null);var dto=new CorrigirFrequenciasGrupoDTO(List.of(new FrequenciaParticipanteGrupoDTO(patient.getIdPublico(),StatusPresencaGrupo.PRESENTE)),s.getVersion());
        assertEquals(422,mvc.perform(patch("/api/grupos/sessoes/"+s.getId()+"/frequencias").header("Authorization","Bearer "+tokens.gerarToken(admin)).contentType("application/json").content(json.writeValueAsString(dto))).andReturn().getResponse().getStatus());
        assertEquals(before,snapshot(patient,null));assertEquals(StatusPresencaGrupo.FALTOU,tx(()->participants.findBySessaoGrupoAndPaciente(s,patient).orElseThrow().getStatusPresenca()));
    }
    @Test void unchangedGroupFrequencyDoesNotRequireFictitiousCorrectionReason() throws Exception {
        var s=groupFact();var before=snapshot(patient,null);var dto=new CorrigirFrequenciasGrupoDTO(List.of(new FrequenciaParticipanteGrupoDTO(patient.getIdPublico(),StatusPresencaGrupo.FALTOU)),s.getVersion());
        assertEquals(200,mvc.perform(patch("/api/grupos/sessoes/"+s.getId()+"/frequencias").header("Authorization","Bearer "+tokens.gerarToken(admin)).contentType("application/json").content(json.writeValueAsString(dto))).andReturn().getResponse().getStatus());assertEquals(before,snapshot(patient,null));
    }
}
