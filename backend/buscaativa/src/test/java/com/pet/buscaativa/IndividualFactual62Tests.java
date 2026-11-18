package com.pet.buscaativa;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import com.pet.buscaativa.entities.*;
import com.pet.buscaativa.entities.enums.*;

/** Contraprovas D1, sem dados reais, com snapshots nas recusas. */
class IndividualFactual62Tests extends Historical61Support {
    @ParameterizedTest @ValueSource(strings={"PRESENTE","FALTOU","CANCELADO"})
    void initialOperationRemainsAllowedForReception(String target) throws Exception {
        var a=individual61(patient,professional,SituacaoAtendimento.AGENDADO,PAST,"08:00");
        var receiver=actor("reception");
        var r=mvc.perform(patch("/api/agendamentos/"+a.getId()+"/status")
                .param("novoStatus",target).header("Authorization","Bearer "+tokens.gerarToken(receiver)))
                .andReturn().getResponse();
        assertEquals(200,businessStatus(r),r.getContentAsString());
        var saved=appointment(a.getId());
        assertEquals(target,saved.getSituacaoAtendimento().name());
        assertEquals(target.equals("CANCELADO")?"NAO_REGISTRADA":target,saved.getFrequenciaAssistencial().name());
        System.out.println("E62-D1-INITIAL "+target+" reception=200");
    }

    @Test void initialPresenceAtAnotherTimeIsCompatible() throws Exception {
        session61(colleague,PAST,LocalTime.of(8,30),true,StatusPresencaGrupo.PRESENTE,patient);
        var a=individual61(patient,professional,SituacaoAtendimento.AGENDADO,PAST,"08:00");
        allow61("E62-D1-INITIAL-OK",()->initial61(a));
        assertEquals(FrequenciaAssistencial.PRESENTE,appointment(a.getId()).getFrequenciaAssistencial());
    }

    @Test void correctionAtAnotherTimePreservesHistoryIdentity() throws Exception {
        session61(colleague,PAST,LocalTime.of(8,30),true,StatusPresencaGrupo.FALTOU,patient);
        var a=individual61(patient,professional,SituacaoAtendimento.FALTOU,PAST,"08:00");
        var id=history.findByAgendamento(a).stream().filter(x->x.getTipo()==TipoEventoHistoricoPaciente.FALTA).findFirst().orElseThrow().getId();
        allow61("E62-D1-CORRECTION-OK",()->correction61(a));
        assertEquals(TipoEventoHistoricoPaciente.PRESENCA,history.findById(id).orElseThrow().getTipo());
        assertEquals(FrequenciaAssistencial.PRESENTE,appointment(a.getId()).getFrequenciaAssistencial());
    }

    @ParameterizedTest @ValueSource(strings={"duplicate","contradictory","canonical","patient","professional","type"})
    void inconsistentHistoryCannotBeSilentlyRewritten(String defect) throws Exception {
        var a=individual61(patient,professional,SituacaoAtendimento.FALTOU,PAST,"08:00");
        tx(()->{
            var h=history.findByAgendamento(a).stream().filter(x->x.getTipo()==TipoEventoHistoricoPaciente.FALTA).findFirst().orElseThrow();
            switch(defect) {
                case "duplicate" -> event61(a,SituacaoAtendimento.FALTOU);
                case "contradictory" -> event61(a,SituacaoAtendimento.PRESENTE);
                case "canonical" -> h.setSituacaoAtendimento(SituacaoAtendimento.PRESENTE);
                case "patient" -> h.setPaciente(other);
                case "professional" -> h.setProfissional(colleague);
                case "type" -> h.setTipo(TipoEventoHistoricoPaciente.PARTICIPACAO_GRUPO_TERAPEUTICO);
                default -> throw new IllegalArgumentException(defect);
            }
            history.saveAndFlush(h);return null;
        });
        refuse61("E62-D1-HISTORY-"+defect,()->correction61(a));
    }

    @Test void initialFrequencyCannotDuplicateExistingHistoricalFact() throws Exception {
        var a=individual61(patient,professional,SituacaoAtendimento.AGENDADO,PAST,"08:00");
        tx(()->{event61(a,SituacaoAtendimento.PRESENTE);return null;});
        refuse61("E62-D1-INITIAL-HISTORY",()->initial61(a));
    }

    @Test void legacyPresenceTypeAndRegistrationTimestampRemainCompatible() throws Exception {
        var a=individual61(patient,professional,SituacaoAtendimento.PRESENTE,PAST,"08:00");
        var id=tx(()->{
            var h=history.findByAgendamento(a).stream().filter(x->x.getTipo()==TipoEventoHistoricoPaciente.PRESENCA).findFirst().orElseThrow();
            h.setTipo(TipoEventoHistoricoPaciente.PARTICIPACAO_GRUPO_TERAPEUTICO);h.setOcorridoEm(PAST.atTime(11,0));
            history.saveAndFlush(h);return h.getId();
        });
        assertEquals(200,correct(admin,a,FrequenciaAssistencial.FALTOU));
        var saved=history.findById(id).orElseThrow();
        assertEquals(TipoEventoHistoricoPaciente.FALTA,saved.getTipo());
        assertEquals(PAST.atTime(11,0),saved.getOcorridoEm());
    }

    @Test void absentHistoryKeepsLegacyFallbackWithoutSelectingAnotherEvent() throws Exception {
        var a=tx(()->seed(patient,professional,SituacaoAtendimento.FALTOU,PAST,"08:00"));
        assertTrue(history.findByAgendamento(a).isEmpty());
        allow61("E62-D1-HISTORY-ABSENT-LEGACY",()->correction61(a));
        assertEquals(1,history.findByAgendamento(a).size());
        assertEquals(FrequenciaAssistencial.PRESENTE,appointment(a.getId()).getFrequenciaAssistencial());
    }
}
