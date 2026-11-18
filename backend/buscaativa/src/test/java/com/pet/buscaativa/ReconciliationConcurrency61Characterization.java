package com.pet.buscaativa;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletResponse;
import com.pet.buscaativa.entities.*;
import com.pet.buscaativa.entities.dto.*;
import com.pet.buscaativa.entities.enums.*;

/** Opt-in: disputa real em conexões independentes; não aceita dupla confirmação incompatível. */
class ReconciliationConcurrency61Characterization extends Historical61Support {
    static final LocalTime FUTURE_SLOT=LocalTime.of(15,0);
    MockHttpServletResponse create61(Paciente p, Usuario u, Long original) throws Exception {
        return http61(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/agendamentos"),
            new AgendamentoDTO(null,u.getIdPublico(),null,p.getIdPublico(),null,null,DAY,TurnoEnum.TARDE,FUTURE_SLOT,
                original==null?SituacaoAtendimento.AGENDADO:SituacaoAtendimento.REMARCADO,original,null));
    }
    MockHttpServletResponse add61(GroupRef61 g, Paciente p) throws Exception {
        return http61(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(GROUP+"/sessoes/"+g.session()+"/participantes"),new AdicionarParticipanteDTO(p.getIdPublico()));
    }
    MockHttpServletResponse createGroup61(Usuario owner, Paciente p) throws Exception {
        return http61(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(GROUP),
            new CriarGrupoDTO("Grupo sintético concorrência 6.1",owner.getIdPublico(),RecorrenciaGrupo.UNICA,DAY,null,FUTURE_SLOT,List.of(p.getIdPublico())));
    }
    boolean enrolled61(GroupRef61 g, Paciente p) {return participants61.findPacienteIds(g.session()).contains(p.getIdPublico());}
    long active61(Paciente p, Usuario u) {
        return tx(()->appointments.findByPaciente(p).stream().filter(a->a.getUsuario().getId().equals(u.getId())
            && DAY.equals(a.getDataAgendamento()) && FUTURE_SLOT.equals(a.getHoraAtendimento()) && Boolean.TRUE.equals(a.getOcupaVaga())).count());
    }
    long groupSlots61(Usuario u) {
        return tx(()->sessions61.findByDataSessaoBetween(DAY,DAY).stream().filter(s->s.getStatus()==StatusSessaoGrupo.AGENDADA
            && FUTURE_SLOT.equals(s.getHorario()) && s.getGrupo().getCoordenador().getId().equals(u.getId())).count());
    }
    long groupPresences61(Paciente p) {
        return tx(()->participants61.findByPaciente(p).stream().filter(x->x.getStatusPresenca()==StatusPresencaGrupo.PRESENTE
            && x.getSessaoGrupo().getStatus()==StatusSessaoGrupo.REALIZADA && PAST.equals(x.getSessaoGrupo().getDataSessao())
            && SLOT.equals(x.getSessaoGrupo().getHorario())).count());
    }
    long individualPresences61(Paciente p) {
        return tx(()->appointments.findByPaciente(p).stream().filter(a->PAST.equals(a.getDataAgendamento())
            && SLOT.equals(a.getHoraAtendimento()) && a.getFrequenciaAssistencial()==FrequenciaAssistencial.PRESENTE).count());
    }
    RaceResult race61(String id, Operation61 first, Operation61 second) throws Exception {
        return race(id,List.<Callable<Integer>>of(()->businessStatus(first.run()),()->businessStatus(second.run())));
    }
    void outcome61(String id,RaceResult result,long persisted,int expected) {
        System.out.printf("E61 %s expectedConfirmed=%d actualConfirmed=%d rejected=%d persisted=%d%n",id,expected,result.confirmed(),result.rejected(),persisted);
        assertAll(id,()->assertEquals(expected,result.confirmed()),()->assertEquals(2-expected,result.rejected()),()->assertEquals((long)expected,persisted));
    }
    @Test void co01CreateIndividualVersusEnrollment() throws Exception {
        var g=session61(colleague,DAY,FUTURE_SLOT,false,StatusPresencaGrupo.NAO_REGISTRADA);
        var result=race61("CO-01",()->create61(patient,professional,null),()->add61(g,patient));
        outcome61("CO-01",result,active61(patient,professional)+(enrolled61(g,patient)?1:0),1);
    }
    @Test void co02CreateIndividualVersusCoordinatorSession() throws Exception {
        var result=race61("CO-02",()->create61(patient,professional,null),()->createGroup61(professional,other));
        outcome61("CO-02",result,active61(patient,professional)+groupSlots61(professional),1);
    }
    @Test void co03RescheduleVersusEnrollment() throws Exception {
        var original=individual61(patient,professional,SituacaoAtendimento.FALTOU,PAST,"08:00");
        var g=session61(colleague,DAY,FUTURE_SLOT,false,StatusPresencaGrupo.NAO_REGISTRADA);
        var result=race61("CO-03",()->create61(patient,professional,original.getId()),()->add61(g,patient));
        var n=active61(patient,professional);
        System.out.printf("E61 CO-03 originalReserve=%s successor=%s%n",appointment(original.getId()).getSituacaoReserva(),appointments.existsByAgendamentoOriginal_Id(original.getId()));
        assertEquals(n==1,appointments.existsByAgendamentoOriginal_Id(original.getId()));
        assertEquals(n==1,appointment(original.getId()).getSituacaoReserva()==SituacaoReserva.SUBSTITUIDA);
        outcome61("CO-03",result,n+(enrolled61(g,patient)?1:0),1);
    }
    @ParameterizedTest @ValueSource(booleans={true,false})
    void co04And05RecoveryVersusGroup(boolean samePatient) throws Exception {
        var a=canceled(true);
        GroupRef61 g=samePatient?session61(colleague,DAY,FUTURE_SLOT,false,StatusPresencaGrupo.NAO_REGISTRADA):null;
        var id=samePatient?"CO-04":"CO-05";
        var result=race61(id,()->recover(admin,a,recovery(a)),()->samePatient?add61(g,patient):createGroup61(professional,other));
        var count=active61(patient,professional);
        assertEquals(count==1,appointment(a.getId()).getSituacaoReserva()==SituacaoReserva.AGENDADA);
        outcome61(id,result,count+(samePatient?(enrolled61(g,patient)?1:0):groupSlots61(professional)),1);
    }
    @Test void co06ConcurrentEnrollmentInDistinctGroups() throws Exception {
        var a=session61(professional,DAY,FUTURE_SLOT,false,StatusPresencaGrupo.NAO_REGISTRADA);
        var b=session61(colleague,DAY,FUTURE_SLOT,false,StatusPresencaGrupo.NAO_REGISTRADA);
        var result=race61("CO-06",()->add61(a,patient),()->add61(b,patient));
        outcome61("CO-06",result,(enrolled61(a,patient)?1:0)+(enrolled61(b,patient)?1:0),1);
    }
    @Test void co07ConcurrentSessionsForSameCoordinator() throws Exception {
        var result=race61("CO-07",()->createGroup61(professional,patient),()->createGroup61(professional,other));
        outcome61("CO-07",result,groupSlots61(professional),1);
    }
    @Test void co08TransferVersusIndividual() throws Exception {
        var g=session61(colleague,DAY,FUTURE_SLOT,false,StatusPresencaGrupo.NAO_REGISTRADA,other);
        var version=groups61.findById(g.group()).orElseThrow().getVersion();
        var dto=new AtualizarGrupoTerapeuticoDTO("Grupo histórico sintético 6.1",professional.getIdPublico(),DAY,FUTURE_SLOT,RecorrenciaGrupo.UNICA,null,version);
        var result=race61("CO-08",()->create61(patient,professional,null),()->http61(put(GROUP+"/"+g.group()),dto));
        outcome61("CO-08",result,active61(patient,professional)+groupSlots61(professional),1);
    }
    @Test void co09CorrectionVersusConfirmation() throws Exception {
        var a=individual61(patient,professional,SituacaoAtendimento.FALTOU,PAST,"08:00");
        var g=session61(colleague,PAST,SLOT,false,StatusPresencaGrupo.NAO_REGISTRADA,patient);
        var result=race61("CO-09",()->correction61(a),()->confirm61(g,patient));
        outcome61("CO-09",result,individualPresences61(patient)+groupPresences61(patient),1);
    }
    @Test void co10TwoHistoricalCorrections() throws Exception {
        var a=individual61(patient,professional,SituacaoAtendimento.FALTOU,PAST,"08:00");
        var g=session61(colleague,PAST,SLOT,true,StatusPresencaGrupo.FALTOU,patient);
        var result=race61("CO-10",()->correction61(a),()->correctGroup61(g,patient));
        outcome61("CO-10",result,individualPresences61(patient)+groupPresences61(patient),1);
    }
    @Test void co11RegularizationVersusRetroactiveEnrollment() throws Exception {
        var a=individual61(patient,professional,SituacaoAtendimento.CANCELADO,PAST,"08:00");
        var g=session61(colleague,PAST,SLOT,true,StatusPresencaGrupo.PRESENTE,other);
        var result=race61("CO-11",()->factual(admin,a,fact(a,FrequenciaAssistencial.PRESENTE)),()->retro61(g,patient));
        outcome61("CO-11",result,individualPresences61(patient)+groupPresences61(patient),1);
    }
    @Test void co13CompatibleOperationsRemainPossible() throws Exception {
        var result=race61("CO-13",()->create61(patient,professional,null),()->createGroup61(colleague,other));
        outcome61("CO-13",result,active61(patient,professional)+groupSlots61(colleague),2);
    }
}
