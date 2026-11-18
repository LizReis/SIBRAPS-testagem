package com.pet.buscaativa;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import com.pet.buscaativa.entities.*;
import com.pet.buscaativa.entities.enums.*;

/** Observações explícitas não são aprovação da política futura de reconciliação. */
class ClinicalIndicators61Characterization extends Historical61Support {
    long totalHistory61(String field) throws Exception {
        var r=http61(get("/api/pacientes/"+patient.getIdPublico()+"/historico"),null);
        assertEquals(200,businessStatus(r),r.getContentAsString());
        var node=json.readTree(r.getContentAsString()).get(field);
        assertNotNull(node,"Campo ausente: "+field);
        return node.asLong();
    }
    @Test void duplicateAppointmentImpactIsMeasuredWithoutAutomaticDeduplication() throws Exception {
        individual61(patient,professional,SituacaoAtendimento.FALTOU,PAST.minusDays(1),"08:00");
        var original=individual61(patient,professional,SituacaoAtendimento.FALTOU,PAST,"08:00");
        recalculate61();var before=indicators61();assertEquals(2,before.absences());assertEquals(ClassificacaoRisco.AMARELO,before.risk());
        // A fixture representa deliberadamente a mesma falta duas vezes; não inferir duplicidade apenas por hora no produto.
        var duplicate=individual61(patient,professional,SituacaoAtendimento.FALTOU,PAST,"08:00");
        recalculate61();var after=indicators61();
        System.out.printf("E61 DP-01-RF-03 OBSERVACAO original=%d exemploDuplicado=%d before=%s after=%s%n",original.getId(),duplicate.getId(),before,after);
        assertNotEquals(original.getId(),duplicate.getId());
        assertEquals(FrequenciaAssistencial.FALTOU,appointment(original.getId()).getFrequenciaAssistencial());
        assertEquals(FrequenciaAssistencial.FALTOU,appointment(duplicate.getId()).getFrequenciaAssistencial());
    }
    @Test void duplicatedHistoryEventImpactIsMeasuredSeparatelyFromClinicalFact() throws Exception {
        var a=individual61(patient,professional,SituacaoAtendimento.FALTOU,PAST,"08:00");
        recalculate61();var before=indicators61();long historyBefore=totalHistory61("totalFaltas");
        assertEquals(1,before.absences());assertEquals(1,historyBefore);
        tx(()->{event61(appointment(a.getId()),SituacaoAtendimento.FALTOU);return null;});
        recalculate61();var after=indicators61();long historyAfter=totalHistory61("totalFaltas");
        System.out.printf("E61 DP-03 OBSERVACAO historyBefore=%d historyAfter=%d indicatorsBefore=%s indicatorsAfter=%s%n",historyBefore,historyAfter,before,after);
        assertEquals(before,after,"Duplicar evento de histórico não deve criar uma nova frequência canônica.");
        assertEquals(FrequenciaAssistencial.FALTOU,appointment(a.getId()).getFrequenciaAssistencial());
    }
    @Test void contradictoryHistoryCannotBeSilentlyResolvedByOrdinaryCorrection() throws Exception {
        var a=individual61(patient,professional,SituacaoAtendimento.FALTOU,PAST,"08:00");
        tx(()->{event61(appointment(a.getId()),SituacaoAtendimento.PRESENTE);return null;});
        recalculate61();refuse61("DP-04",()->correction61(a));
    }
    @Test void sameTimePresenceAndAbsenceAcrossModalitiesNeedsExplicitPolicy() throws Exception {
        individual61(patient,professional,SituacaoAtendimento.PRESENTE,PAST,"08:00");
        session61(colleague,PAST,SLOT,true,StatusPresencaGrupo.FALTOU,patient);
        recalculate61();var value=indicators61();
        System.out.printf("E61 SEMANTICA-01 OBSERVACAO individual=PRESENTE grupo=FALTOU %s%n",value);
        assertEquals(PAST,value.last());
        // O resultado da contagem no empate não é decisão clínica aprovada nesta etapa.
    }
    @Test void reversedSameTimePresenceAndAbsenceAcrossModalitiesNeedsExplicitPolicy() throws Exception {
        individual61(patient,professional,SituacaoAtendimento.FALTOU,PAST,"08:00");
        session61(colleague,PAST,SLOT,true,StatusPresencaGrupo.PRESENTE,patient);
        recalculate61();var value=indicators61();
        System.out.printf("E61 SEMANTICA-02 OBSERVACAO individual=FALTOU grupo=PRESENTE %s%n",value);
        assertEquals(PAST,value.last());
    }
    @Test void unknownRealizedGroupFrequencyDoesNotBecomeAnAbsence() {
        session61(colleague,PAST,SLOT,true,StatusPresencaGrupo.NAO_REGISTRADA,patient);
        recalculate61();var value=indicators61();
        System.out.printf("E61 LG-GRUPO OBSERVACAO frequencia=NAO_REGISTRADA sessao=REALIZADA %s%n",value);
        assertEquals(0,value.absences());assertNull(value.last());
        // A política de indicadores provisórios para esta categoria ainda deve ser definida.
    }
    @Test void unknownRealizedGroupFrequencyRejectsOrdinaryCorrection() throws Exception {
        var g=session61(colleague,PAST,SLOT,true,StatusPresencaGrupo.NAO_REGISTRADA,patient);
        refuse61("LG-GRUPO-CORRECAO",()->correctGroup61(g,patient));
    }
}
