package com.pet.buscaativa;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import com.pet.buscaativa.entities.*;
import com.pet.buscaativa.entities.dto.*;
import com.pet.buscaativa.entities.enums.*;

/** Fatos coletivos, evidência e atomicidade; dados sintéticos. */
class GroupFactual62Tests extends Historical61Support {
    @ParameterizedTest @ValueSource(booleans={true,false})
    void performedSessionAlwaysRequiresCompatibleResponsible(boolean empty) throws Exception {
        var people=empty?new Paciente[0]:new Paciente[]{other};
        var g=session61(professional,PAST,SLOT,false,StatusPresencaGrupo.NAO_REGISTRADA,people);
        individual61(patient,professional,SituacaoAtendimento.PRESENTE,PAST,"08:00");
        refuse61("E62-D2-OWNER-"+empty,()->http61(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(GROUP+"/sessoes/"+g.session()+"/confirmacao-ocorrencia"),
                new ConfirmarOcorrenciaGrupoDTO(true,List.of(),null,g.version())));
    }

    @Test void groupConfirmationChecksAllParticipantsBeforeRealization() throws Exception {
        var g=session61(colleague,PAST,SLOT,false,StatusPresencaGrupo.NAO_REGISTRADA,other,patient);
        individual61(patient,professional,SituacaoAtendimento.PRESENTE,PAST,"08:00");
        refuse61("E62-D2-BATCH-CONFIRM",()->http61(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(GROUP+"/sessoes/"+g.session()+"/confirmacao-ocorrencia"),
                new ConfirmarOcorrenciaGrupoDTO(true,List.of(frequency61(other,StatusPresencaGrupo.PRESENTE),frequency61(patient,StatusPresencaGrupo.PRESENTE)),null,g.version())));
    }

    @Test void groupCorrectionChecksWholeBatchBeforeChangingAnyParticipant() throws Exception {
        var g=session61(colleague,PAST,SLOT,true,StatusPresencaGrupo.FALTOU,other,patient);
        individual61(patient,professional,SituacaoAtendimento.PRESENTE,PAST,"08:00");
        refuse61("E62-D2-BATCH-CORRECTION",()->http61(patch(GROUP+"/sessoes/"+g.session()+"/frequencias"),
                new CorrigirFrequenciasGrupoDTO(List.of(frequency61(other,StatusPresencaGrupo.PRESENTE),frequency61(patient,StatusPresencaGrupo.PRESENTE)),g.version(),REASON)));
    }

    @Test void anAbsenceElsewhereIsNotPhysicalPresence() throws Exception {
        individual61(patient,professional,SituacaoAtendimento.FALTOU,PAST,"08:00");
        var g=session61(colleague,PAST,SLOT,true,StatusPresencaGrupo.FALTOU,patient);
        allow61("E62-D2-ABSENCE-NOT-PRESENCE",()->correctGroup61(g,patient));
        assertEquals(StatusPresencaGrupo.PRESENTE,tx(()->participants61.findBySessaoGrupoAndPaciente(
                sessions61.findById(g.session()).orElseThrow(),patient).orElseThrow().getStatusPresenca()));
        // Nenhuma asserção sobre desempate dos indicadores: política reservada à etapa posterior.
    }

    @Test void retroactiveConflictInLaterFactPreservesEntireSeries() throws Exception {
        var g=session61(colleague,PAST.minusDays(1),SLOT,true,StatusPresencaGrupo.PRESENTE,other);
        var second=tx(()->{
            var s=new SessaoGrupo();s.setGrupo(groups61.findById(g.group()).orElseThrow());
            s.setDataSessao(PAST);s.setHorario(SLOT);s.setStatus(StatusSessaoGrupo.AGENDADA);
            s.confirmarRealizacao(colleague);return sessions61.saveAndFlush(s).getId();
        });
        individual61(patient,professional,SituacaoAtendimento.PRESENTE,PAST,"08:00");
        refuse61("E62-D2-RETRO-BATCH",()->http61(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(GROUP+"/"+g.group()+"/inscricoes-retroativas"),
                new InscricaoRetroativaGrupoDTO(patient.getIdPublico(),List.of(
                        new FrequenciaSessaoRetroativaDTO(g.session(),StatusPresencaGrupo.PRESENTE),
                        new FrequenciaSessaoRetroativaDTO(second,StatusPresencaGrupo.PRESENTE)))));
    }

    @Test void provisionalIsNotFactAndConfirmationRechecksAfterAnotherFactAppears() throws Exception {
        var g=session61(colleague,PAST,SLOT,false,StatusPresencaGrupo.NAO_REGISTRADA,patient);
        var before=indicators61();long events=history.count();
        allow61("E62-D2-PROVISIONAL",()->http61(patch(GROUP+"/sessoes/"+g.session()+"/participantes/"+patient.getIdPublico()+"/presenca"),
                new RegistrarPresencaGrupoDTO(StatusPresencaGrupo.PRESENTE)));
        assertEquals(before,indicators61());assertEquals(events,history.count());
        assertEquals(StatusSessaoGrupo.AGENDADA,sessions61.findById(g.session()).orElseThrow().getStatus());
        individual61(patient,professional,SituacaoAtendimento.PRESENTE,PAST,"08:00");
        refuse61("E62-D2-RECHECK",()->confirm61(g,patient));
    }

    @ParameterizedTest @ValueSource(strings={"duplicate","contradictory","professional","canonical"})
    void ordinaryGroupCorrectionCannotRewriteInconsistentHistoricalEvidence(String defect) throws Exception {
        var g=session61(professional,PAST,SLOT,true,StatusPresencaGrupo.FALTOU,patient);
        tx(()->{
            var sessao=sessions61.findById(g.session()).orElseThrow();
            var evento=history.findByPacienteAndSessaoGrupo(patient,sessao).orElseThrow();
            switch(defect) {
                case "duplicate","contradictory" -> {
                    var copy=new HistoricoPaciente();copy.setPaciente(patient);copy.setSessaoGrupo(sessao);
                    copy.setProfissional(professional);copy.setOcorridoEm(PAST.atTime(SLOT));
                    copy.setTipo(defect.equals("duplicate")?TipoEventoHistoricoPaciente.FALTA:TipoEventoHistoricoPaciente.PARTICIPACAO_GRUPO_TERAPEUTICO);
                    copy.setSituacaoAtendimento(defect.equals("duplicate")?SituacaoAtendimento.FALTOU:SituacaoAtendimento.PRESENTE);
                    history.saveAndFlush(copy);
                }
                case "professional" -> {evento.setProfissional(colleague);history.saveAndFlush(evento);}
                case "canonical" -> {evento.setSituacaoAtendimento(SituacaoAtendimento.PRESENTE);history.saveAndFlush(evento);}
                default -> throw new IllegalArgumentException(defect);
            }
            return null;
        });
        refuse61("E62-D2-HISTORY-"+defect,()->correctGroup61(g,patient));
    }

    @Test void absentGroupHistoryPreservesLegacyRefusal() throws Exception {
        var g=session61(professional,PAST,SLOT,true,StatusPresencaGrupo.NAO_REGISTRADA,patient);
        tx(()->{
            var x=participants61.findBySessaoGrupoAndPaciente(sessions61.findById(g.session()).orElseThrow(),patient).orElseThrow();
            x.setStatusPresenca(StatusPresencaGrupo.FALTOU);participants61.saveAndFlush(x);return null;
        });
        var before=database();var r=correctGroup61(g,patient);
        assertEquals(404,businessStatus(r),r.getContentAsString());assertEquals(before,database());
    }
}
