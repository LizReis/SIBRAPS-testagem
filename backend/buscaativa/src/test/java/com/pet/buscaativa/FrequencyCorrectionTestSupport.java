package com.pet.buscaativa;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;
import com.pet.buscaativa.entities.*;
import com.pet.buscaativa.entities.dto.*;
import com.pet.buscaativa.entities.enums.*;
import com.pet.buscaativa.services.*;
import com.pet.buscaativa.repositories.*;

abstract class FrequencyCorrectionTestSupport extends AgendaIntegritySupport {
    static final String REASON="Frequência conferida no prontuário sintético pela equipe responsável.";
    @Autowired HistoricoPacienteService histories;
    @Autowired PacienteService clinical;
    @Autowired AuditoriaCorrecaoRepository audits;
    Agendamento fact(SituacaoAtendimento state,LocalDate day,String time){return tx(()->{var a=seed(patient,professional,state,day,time);histories.registrarAlteracaoDeAtendimento(a,SituacaoAtendimento.AGENDADO);clinical.recalcularAssiduidadePaciente(patients.findById(patient.getId()).orElseThrow());return a;});}
    Object database(){var source=Objects.requireNonNull(jdbc.getDataSource());return LegacyMigrationFixture.snapshot(source,LegacyMigrationFixture.columns(source));}
    MockHttpServletResponse correct(Usuario actor,Agendamento a,FrequenciaAssistencial next,Integer version) throws Exception {
        return mvc.perform(patch("/api/agendamentos/"+a.getId()+"/frequencia/correcao").header("Authorization","Bearer "+tokens.gerarToken(actor)).contentType("application/json").content(json.writeValueAsString(new CorrigirFrequenciaDTO(next,version,REASON)))).andReturn().getResponse();
    }
    int correct(Agendamento a) throws Exception {return businessStatus(correct(professional,a,FrequenciaAssistencial.PRESENTE,a.getVersion()));}
    void conflict(Agendamento a,String type) throws Exception {var before=database();var r=correct(professional,a,FrequenciaAssistencial.PRESENTE,a.getVersion());assertEquals(409,businessStatus(r),r.getContentAsString());var n=json.readTree(r.getContentAsString());assertEquals("CONFLITO_ASSISTENCIAL_PENDENTE_RECONCILIACAO",n.get("codigo").asText());assertEquals(type,n.get("tipoConflito").asText());assertEquals(a.getId(),n.get("agendamentoId").asLong());assertEquals(before,database());}
}
