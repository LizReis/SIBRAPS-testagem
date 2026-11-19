package com.pet.buscaativa;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.RepeatedTest;
import com.pet.buscaativa.entities.*;
import com.pet.buscaativa.entities.dto.*;
import com.pet.buscaativa.entities.enums.*;

/**
 * F2 — Disputas adicionais, HTTP real e duas conexões independentes.
 * Não aceita HTTP 409 genérico técnico: o suporte valida o corpo de erro.
 */
class AdditionalConcurrency62Tests extends Historical61Support {
    static final LocalTime TIME = LocalTime.of(15,0);
    static final LocalTime LATER = LocalTime.of(15,30);

    private int operation(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request, Object payload) throws Exception {
        return businessStatus(http61(request,payload));
    }

    private int createIndividual(Paciente person, Usuario owner) throws Exception {
        return operation(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/agendamentos"),
                new AgendamentoDTO(null,owner.getIdPublico(),null,person.getIdPublico(),null,null,
                    DAY,TurnoEnum.TARDE,TIME,SituacaoAtendimento.AGENDADO,null,null));
    }

    private int createGroup(Usuario coordinator, Paciente person) throws Exception {
        return operation(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(GROUP), new CriarGrupoDTO("Grupo adicional F2",coordinator.getIdPublico(),
                RecorrenciaGrupo.UNICA,DAY,null,TIME,List.of(person.getIdPublico())));
    }

    private long reservationsAt(Paciente person, LocalDate when, LocalTime hour) {
        return tx(()->{
            long individual = appointments.findByPaciente(person).stream()
                    .filter(a->when.equals(a.getDataAgendamento()) && hour.equals(a.getHoraAtendimento())
                            && Boolean.TRUE.equals(a.getOcupaVaga())).count();
            long collective = participants61.findByPaciente(person).stream()
                    .filter(p->when.equals(p.getSessaoGrupo().getDataSessao())
                            && hour.equals(p.getSessaoGrupo().getHorario())
                            && p.getSessaoGrupo().getStatus()==StatusSessaoGrupo.AGENDADA).count();
            return individual+collective;
        });
    }

    @RepeatedTest(2)
    void createGroupAndIndividualForSamePatientAreMutuallyExclusive() throws Exception {
        var result=race("F2-CREATE-GROUP-X-INDIVIDUAL",List.<Callable<Integer>>of(
                ()->createIndividual(patient,professional),
                ()->createGroup(colleague,patient)));
        assertEquals(new RaceResult(1,1),result);
        assertEquals(1,reservationsAt(patient,DAY,TIME));
    }

    @RepeatedTest(2)
    void regenerationAndIndividualReservationCannotOccupySamePatientSlot() throws Exception {
        var g=session61(colleague,DAY,LATER,false,StatusPresencaGrupo.NAO_REGISTRADA,patient);
        var dto=new AtualizarGrupoTerapeuticoDTO("Grupo histórico sintético 6.1",colleague.getIdPublico(),
                DAY,TIME,RecorrenciaGrupo.UNICA,null,groups61.findById(g.group()).orElseThrow().getVersion());
        var result=race("F2-REGENERATION-X-INDIVIDUAL",List.<Callable<Integer>>of(
                ()->operation(put(GROUP+"/"+g.group()),dto),
                ()->createIndividual(patient,professional)));
        assertEquals(new RaceResult(1,1),result);
        assertEquals(1,reservationsAt(patient,DAY,TIME));
        assertEquals(1,tx(()->sessions61.findByGrupoOrderByDataSessaoDesc(
                groups61.findById(g.group()).orElseThrow()).size()));
    }

    @Test
    void twoFutureEnrollmentsForSamePatientAndTimeAreExclusive() throws Exception {
        var a=session61(professional,DAY,TIME,false,StatusPresencaGrupo.NAO_REGISTRADA);
        var b=session61(colleague,DAY,TIME,false,StatusPresencaGrupo.NAO_REGISTRADA);
        var dto=new InscricaoFuturaGrupoDTO(patient.getIdPublico());
        var result=race("F2-TWO-FUTURE-ENROLLMENTS",List.<Callable<Integer>>of(
                ()->operation(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(GROUP+"/"+a.group()+"/inscricoes-futuras"),dto),
                ()->operation(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(GROUP+"/"+b.group()+"/inscricoes-futuras"),dto)));
        assertEquals(new RaceResult(1,1),result);
        assertEquals(1,reservationsAt(patient,DAY,TIME));
    }

    @Test
    void twoNewSessionsForOneCoordinatorAtSameTimeAreExclusive() throws Exception {
        var first=session61(professional,DAY,TIME,false,StatusPresencaGrupo.NAO_REGISTRADA,patient);
        var second=session61(professional,DAY,LATER,false,StatusPresencaGrupo.NAO_REGISTRADA,other);
        LocalDate next=DAY.plusWeeks(1);
        var result=race("F2-TWO-NEXT-SESSIONS",List.<Callable<Integer>>of(
                ()->operation(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(GROUP+"/sessoes"),new NovaSessaoDTO(first.group(),next,TIME,List.of(patient.getIdPublico()))),
                ()->operation(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(GROUP+"/sessoes"),new NovaSessaoDTO(second.group(),next,TIME,List.of(other.getIdPublico())))));
        assertEquals(new RaceResult(1,1),result);
        long futureSlots=tx(()->sessions61.findByDataSessaoBetween(next,next).stream()
                .filter(x->TIME.equals(x.getHorario())
                        && x.getStatus()==StatusSessaoGrupo.AGENDADA
                        && x.getGrupo().getCoordenador().getId().equals(professional.getId())).count());
        assertEquals(1,futureSlots);
    }

    @Test
    void transferAndEnrollmentCannotLoseCommittedParticipantOrCoordinator() throws Exception {
        var g=session61(colleague,DAY,TIME,false,StatusPresencaGrupo.NAO_REGISTRADA,other);
        var version=groups61.findById(g.group()).orElseThrow().getVersion();
        var transfer=new AtualizarGrupoTerapeuticoDTO("Grupo histórico sintético 6.1",professional.getIdPublico(),
                DAY,TIME,RecorrenciaGrupo.UNICA,null,version);
        var enrollment=new AdicionarParticipanteDTO(patient.getIdPublico());
        AtomicInteger transferCode=new AtomicInteger(-1), enrollmentCode=new AtomicInteger(-1);
        var result=race("F2-TRANSFER-X-ENROLLMENT",List.<Callable<Integer>>of(
                ()->{int code=operation(put(GROUP+"/"+g.group()),transfer); transferCode.set(code);return code;},
                ()->{int code=operation(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(GROUP+"/sessoes/"+g.session()+"/participantes"),enrollment); enrollmentCode.set(code);return code;}));
        assertTrue(result.confirmed()>=1 && result.confirmed()<=2,"Concurrent stale identity may safely be retried by client.");
        assertEquals(2,result.confirmed()+result.rejected());
        assertEquals(transferCode.get()==200?professional.getId():colleague.getId(),
                groups61.findById(g.group()).orElseThrow().getCoordenador().getId());
        assertEquals(enrollmentCode.get()==200,
                participants61.findPacienteIds(g.session()).contains(patient.getIdPublico()));
        assertTrue(participants61.findPacienteIds(g.session()).contains(other.getIdPublico()));
    }

    @Test
    void independentGroupCreationsMustBothBePossible() throws Exception {
        var result=race("F2-TWO-INDEPENDENT-GROUPS",List.<Callable<Integer>>of(
                ()->createGroup(professional,patient),()->createGroup(colleague,other)));
        assertEquals(new RaceResult(2,0),result);
        assertEquals(1,reservationsAt(patient,DAY,TIME));
        assertEquals(1,reservationsAt(other,DAY,TIME));
    }

    @Test
    void regularizationAndConfirmationCannotCreateTwoRealPresences() throws Exception {
        var canceled=individual61(patient,professional,SituacaoAtendimento.CANCELADO,PAST,"08:00");
        var group=session61(colleague,PAST,SLOT,false,StatusPresencaGrupo.NAO_REGISTRADA,patient);
        var result=race("F2-FACTUAL-REGULARIZATION-X-CONFIRMATION",List.<Callable<Integer>>of(
                ()->operation(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/agendamentos/"+canceled.getId()+"/regularizacao-cancelamento/frequencia"),
                        fact(canceled,FrequenciaAssistencial.PRESENTE)),
                ()->businessStatus(confirm61(group,patient))));
        assertEquals(new RaceResult(1,1),result);
        long presences=tx(()->{
            long individual=appointments.findByPaciente(patient).stream()
                    .filter(a->PAST.equals(a.getDataAgendamento()) && SLOT.equals(a.getHoraAtendimento())
                            && a.getFrequenciaAssistencial()==FrequenciaAssistencial.PRESENTE).count();
            long collective=participants61.findByPaciente(patient).stream()
                    .filter(p->PAST.equals(p.getSessaoGrupo().getDataSessao())
                            && SLOT.equals(p.getSessaoGrupo().getHorario())
                            && p.getSessaoGrupo().getStatus()==StatusSessaoGrupo.REALIZADA
                            && p.getStatusPresenca()==StatusPresencaGrupo.PRESENTE).count();
            return individual+collective;
        });
        assertEquals(1,presences);
    }
}
