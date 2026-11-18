package com.pet.buscaativa;

import static org.junit.jupiter.api.Assertions.*;
import java.time.*;
import java.util.*;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.IllegalTransactionStateException;
import com.pet.buscaativa.entities.*;
import com.pet.buscaativa.entities.enums.*;
import com.pet.buscaativa.services.exceptions.ResourceNotFoundException;
import com.pet.buscaativa.services.impl.AgendaAssistencialLocks;
import com.pet.buscaativa.services.impl.IntegridadeAgendaAssistencialService;
import com.pet.buscaativa.services.impl.IntegridadeAgendaAssistencialService.*;

/** Contratos da infraestrutura 6.2A; exercita JPQL real, não os endpoints ainda não integrados. */
class AgendaAssistencialArchitecture62Tests extends Historical61Support {
    @Autowired AgendaAssistencialLocks locks62;
    @Autowired IntegridadeAgendaAssistencialService policy62;
    @Autowired EntityManager em62;

    private Avaliacao paciente62(Paciente p, LocalDate date, LocalTime time, Exclusao exclusion) {
        authenticate();
        return tx(() -> {
            var r = locks62.bloquear(List.of(p.getIdPublico()), List.of());
            return policy62.avaliarPaciente(r.paciente(p.getIdPublico()), new Instante(date,time), exclusion);
        });
    }
    private Avaliacao profissional62(Usuario u, LocalDate date, LocalTime time, Exclusao exclusion) {
        authenticate();
        return tx(() -> {
            var r = locks62.bloquear(List.of(), List.of(u.getIdPublico()));
            return policy62.avaliarProfissional(r.profissional(u.getIdPublico()), new Instante(date,time), exclusion);
        });
    }

    @Test void ar01IndividualReservationHasOccupancyButNotProvenPresence() {
        var a = individual61(patient,professional,SituacaoAtendimento.AGENDADO,PAST,"08:00");
        var p = paciente62(patient,PAST,SLOT,Exclusao.nenhuma());
        var u = profissional62(professional,PAST,SLOT,Exclusao.nenhuma());
        assertAll(() -> assertEquals(Set.of(Sinal.OCUPACAO_INDIVIDUAL),p.sinais()),
                  () -> assertEquals(p.sinais(),u.sinais()), () -> assertTrue(p.temImpedimentoOperacional()),
                  () -> assertFalse(p.temSinalFactualIncompativel()),
                  () -> assertTrue(paciente62(patient,PAST,SLOT,new Exclusao(a.getId(),null)).sinais().isEmpty()),
                  () -> assertTrue(profissional62(professional,PAST,SLOT,new Exclusao(a.getId(),null)).sinais().isEmpty()));
    }

    @Test void ar02CanonicalCanceledPresenceRemainsFactualButDoesNotOccupy() throws Exception {
        var a = canceled(false);
        assertEquals(200,factual(a,FrequenciaAssistencial.PRESENTE));
        var p = paciente62(patient,a.getDataAgendamento(),a.getHoraAtendimento(),Exclusao.nenhuma());
        var u = profissional62(professional,a.getDataAgendamento(),a.getHoraAtendimento(),Exclusao.nenhuma());
        assertEquals(Set.of(Sinal.PRESENCA_INDIVIDUAL),p.sinais());
        assertEquals(p.sinais(),u.sinais());
        assertTrue(p.temSinalFactualIncompativel());
        assertFalse(appointment(a.getId()).getOcupaVaga());
    }

    @Test void ar03IndividualAbsenceIsNotAnOccupancyOrPhysicalPresence() {
        individual61(patient,professional,SituacaoAtendimento.FALTOU,PAST,"08:00");
        var p = paciente62(patient,PAST,SLOT,Exclusao.nenhuma());
        var u = profissional62(professional,PAST,SLOT,Exclusao.nenhuma());
        assertEquals(Set.of(Sinal.FALTA_INDIVIDUAL),p.sinais());
        assertTrue(u.sinais().isEmpty());
        assertFalse(p.temImpedimentoOperacional());
        assertFalse(p.temSinalFactualIncompativel());
    }

    @Test void ar04CanceledReservationWithoutResultDoesNotCreateConflict() {
        var a = canceled(false);
        assertTrue(paciente62(patient,a.getDataAgendamento(),a.getHoraAtendimento(),Exclusao.nenhuma()).sinais().isEmpty());
        assertTrue(profissional62(professional,a.getDataAgendamento(),a.getHoraAtendimento(),Exclusao.nenhuma()).sinais().isEmpty());
    }

    @Test void ar05ProvisionalGroupPresenceIsOnlyAnOperationalCommitment() {
        session61(professional,PAST,SLOT,false,StatusPresencaGrupo.PRESENTE,patient);
        var p = paciente62(patient,PAST,SLOT,Exclusao.nenhuma());
        var u = profissional62(professional,PAST,SLOT,Exclusao.nenhuma());
        assertEquals(Set.of(Sinal.INSCRICAO_AGENDADA),p.sinais());
        assertEquals(Set.of(Sinal.SESSAO_AGENDADA),u.sinais());
        assertTrue(p.temImpedimentoOperacional());
        assertFalse(p.temSinalFactualIncompativel());
        assertFalse(u.temSinalFactualIncompativel());
    }

    @ParameterizedTest @EnumSource(StatusPresencaGrupo.class)
    void ar06RealizedGroupDistinguishesPatientFrequencyFromOwnerFact(StatusPresencaGrupo frequency) {
        session61(professional,PAST,SLOT,true,frequency,patient);
        var p = paciente62(patient,PAST,SLOT,Exclusao.nenhuma());
        var u = profissional62(professional,PAST,SLOT,Exclusao.nenhuma());
        var expected = switch (frequency) {
            case PRESENTE -> Sinal.PRESENCA_GRUPO;
            case FALTOU -> Sinal.FALTA_GRUPO;
            case NAO_REGISTRADA -> Sinal.FREQUENCIA_GRUPO_DESCONHECIDA;
        };
        assertEquals(Set.of(expected),p.sinais());
        assertEquals(frequency==StatusPresencaGrupo.PRESENTE,p.temSinalFactualIncompativel());
        assertEquals(frequency==StatusPresencaGrupo.NAO_REGISTRADA,p.temInformacaoInconclusiva());
        assertEquals(Set.of(Sinal.SESSAO_REALIZADA),u.sinais());
        assertTrue(u.temSinalFactualIncompativel());
    }

    @Test void ar07SessionWithoutParticipantsStillOccupiesCoordinator() {
        var g = session61(professional,DAY,SLOT,false,StatusPresencaGrupo.NAO_REGISTRADA);
        assertEquals(Set.of(Sinal.SESSAO_AGENDADA),profissional62(professional,DAY,SLOT,Exclusao.nenhuma()).sinais());
        assertTrue(paciente62(patient,DAY,SLOT,Exclusao.nenhuma()).sinais().isEmpty());
        assertTrue(profissional62(professional,DAY,SLOT,new Exclusao(null,g.session())).sinais().isEmpty());
    }

    @Test void ar08ExcludingOneSessionDoesNotHideAnotherInSameGroup() {
        var g = session61(professional,DAY,SLOT,false,StatusPresencaGrupo.NAO_REGISTRADA,patient);
        tx(() -> {
            var s = new SessaoGrupo();
            s.setGrupo(groups61.findById(g.group()).orElseThrow());
            s.setDataSessao(DAY); s.setHorario(SLOT); s.setStatus(StatusSessaoGrupo.AGENDADA);
            sessions61.saveAndFlush(s);
            var p = new SessaoGrupoParticipante(); p.setSessaoGrupo(s); p.setPaciente(patient);
            p.setStatusPresenca(StatusPresencaGrupo.NAO_REGISTRADA); participants61.saveAndFlush(p);
            return null;
        });
        assertTrue(paciente62(patient,DAY,SLOT,new Exclusao(null,g.session())).tem(Sinal.INSCRICAO_AGENDADA));
        assertTrue(profissional62(professional,DAY,SLOT,new Exclusao(null,g.session())).tem(Sinal.SESSAO_AGENDADA));
    }

    @Test void ar09HistoricalOwnerNotCurrentCoordinator() {
        var g = session61(professional,PAST,SLOT,true,StatusPresencaGrupo.PRESENTE,patient);
        tx(() -> {
            var group=groups61.findById(g.group()).orElseThrow(); group.setCoordenador(colleague);
            groups61.saveAndFlush(group);return null;
        });
        assertEquals(Set.of(Sinal.SESSAO_REALIZADA),profissional62(professional,PAST,SLOT,Exclusao.nenhuma()).sinais());
        assertTrue(profissional62(colleague,PAST,SLOT,Exclusao.nenhuma()).sinais().isEmpty());
    }

    @Test void ar10CanceledGroupAndDifferentInstantDoNotCauseFalseConflicts() {
        var g = session61(professional,DAY,SLOT,false,StatusPresencaGrupo.NAO_REGISTRADA,patient);
        assertTrue(paciente62(patient,DAY,SLOT.plusMinutes(1),Exclusao.nenhuma()).sinais().isEmpty());
        assertTrue(profissional62(professional,DAY,SLOT.plusMinutes(1),Exclusao.nenhuma()).sinais().isEmpty());
        tx(() -> {var s=sessions61.findById(g.session()).orElseThrow();s.setStatus(StatusSessaoGrupo.CANCELADA);sessions61.saveAndFlush(s);return null;});
        assertTrue(paciente62(patient,DAY,SLOT,Exclusao.nenhuma()).sinais().isEmpty());
        assertTrue(profissional62(professional,DAY,SLOT,Exclusao.nenhuma()).sinais().isEmpty());
    }

    @Test void ar11ResourcesAreLockedInStableDeduplicatedOrderWithinTransaction() {
        tx(() -> {
            var p = List.of(patient.getIdPublico(),other.getIdPublico(),patient.getIdPublico());
            var u = List.of(professional.getIdPublico(),colleague.getIdPublico(),professional.getIdPublico());
            var r = locks62.bloquear(p,u);
            assertEquals(new ArrayList<>(new TreeSet<>(p)),new ArrayList<>(r.pacientes().keySet()));
            assertEquals(new ArrayList<>(new TreeSet<>(u)),new ArrayList<>(r.profissionais().keySet()));
            for (var x:r.pacientes().values()) assertEquals(LockModeType.PESSIMISTIC_WRITE,em62.getLockMode(x));
            for (var x:r.profissionais().values()) assertEquals(LockModeType.PESSIMISTIC_WRITE,em62.getLockMode(x));
            return null;
        });
    }

    @Test void ar12LockHelperRequiresExistingTransaction() {
        assertThrows(IllegalTransactionStateException.class,()->locks62.bloquear(List.of(patient.getIdPublico()),List.of()));
    }

    @Test void ar13EvaluatorRejectsManagedResourceWithoutWriteLock() {
        assertThrows(IllegalStateException.class,()->tx(()->policy62.avaliarPaciente(
                patients.findById(patient.getId()).orElseThrow(),new Instante(DAY,SLOT),Exclusao.nenhuma())));
    }

    @Test void ar14ForeignPatientCannotBeIncludedInLockPlan() {
        var foreign=tx(()->patient(CapsEnum.CAPS_I));
        var before=database();
        assertThrows(ResourceNotFoundException.class,()->tx(()->locks62.bloquear(List.of(foreign.getIdPublico()),List.of())));
        assertEquals(before,database());
    }

    @Test void ar17MigratedUnknownFrequencyIsAnUncertaintyNotPhysicalPresenceOrAbsence() {
        var a=individual61(patient,professional,SituacaoAtendimento.FALTOU,PAST,"08:00");
        // Existing synthetic legacy technique (CanonicalAppointmentTests); no schema change.
        assertEquals(1,jdbc.update("update tb_agendamento set frequencia_assistencial=null,migracao_pendente=true,motivo_migracao_pendente='SYNTHETIC_UNKNOWN' where id=?",a.getId()));
        var before=database();
        var p=paciente62(patient,PAST,SLOT,Exclusao.nenhuma());
        var u=profissional62(professional,PAST,SLOT,Exclusao.nenhuma());
        assertEquals(Set.of(Sinal.INDIVIDUAL_PENDENTE),p.sinais());
        assertEquals(p.sinais(),u.sinais());
        assertTrue(p.temInformacaoInconclusiva());
        assertFalse(p.temSinalFactualIncompativel());
        assertEquals(before,database());
    }

    @Test void ar18UnknownHistoricalOwnerIsNotAssignedToCurrentCoordinator() {
        var g=session61(professional,PAST,SLOT,false,StatusPresencaGrupo.NAO_REGISTRADA,patient);
        tx(()->{
            var s=sessions61.findById(g.session()).orElseThrow();
            s.setStatus(StatusSessaoGrupo.REALIZADA);
            sessions61.saveAndFlush(s);
            return null;
        });
        var before=database();
        var p=paciente62(patient,PAST,SLOT,Exclusao.nenhuma());
        var u=profissional62(professional,PAST,SLOT,Exclusao.nenhuma());
        assertTrue(p.tem(Sinal.FREQUENCIA_GRUPO_DESCONHECIDA));
        assertTrue(u.sinais().isEmpty());
        tx(()->{
            var s=sessions61.findById(g.session()).orElseThrow();
            assertTrue(s.isResponsabilidadePendente());
            assertNull(s.getResponsavelAtendimento());
            return null;
        });
        assertEquals(before,database());
    }
}
