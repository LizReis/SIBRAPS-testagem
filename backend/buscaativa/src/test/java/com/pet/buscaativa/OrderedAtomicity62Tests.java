package com.pet.buscaativa;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionTemplate;
import com.pet.buscaativa.entities.*;
import com.pet.buscaativa.entities.dto.*;
import com.pet.buscaativa.entities.enums.*;
import com.pet.buscaativa.services.GrupoTerapeuticoService;
import com.pet.buscaativa.services.UsuarioService;

/** F3B: ordenação por barreiras, transações reais, regressão de rollback de séries. */
class OrderedAtomicity62Tests extends Historical61Support {
    static final LocalTime TIME=LocalTime.of(15,0);
    static final LocalTime LATER=LocalTime.of(15,30);
    @Autowired GrupoTerapeuticoService groupService;
    @Autowired UsuarioService userService;

    private int call(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder req, Object dto) throws Exception {
        return businessStatus(http61(req,dto));
    }
    private int booking() throws Exception {
        return call(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/agendamentos"),
                new AgendamentoDTO(null,professional.getIdPublico(),null,patient.getIdPublico(),
                        null,null,DAY,TurnoEnum.TARDE,TIME,SituacaoAtendimento.AGENDADO,null,null));
    }
    private int enrollment(GroupRef61 group) throws Exception {
        return call(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                GROUP+"/sessoes/"+group.session()+"/participantes"),
                new AdicionarParticipanteDTO(patient.getIdPublico()));
    }
    private int transfer(GroupRef61 group) throws Exception {
        return call(put(GROUP+"/"+group.group()),
                new AtualizarGrupoTerapeuticoDTO("Grupo histórico sintético 6.1",
                        professional.getIdPublico(),DAY,TIME,RecorrenciaGrupo.UNICA,null,
                        groups61.findById(group.group()).orElseThrow().getVersion()));
    }
    private int regenerate(GroupRef61 group) throws Exception {
        return call(put(GROUP+"/"+group.group()),
                new AtualizarGrupoTerapeuticoDTO("Grupo histórico sintético 6.1",
                        colleague.getIdPublico(),DAY,TIME,RecorrenciaGrupo.UNICA,null,
                        groups61.findById(group.group()).orElseThrow().getVersion()));
    }

    private record Ordered(int first, int second) {}
    /**
     * As duas transações seguram conexões distintas antes da liberação.
     * A primeira conclui e confirma antes da segunda executar o comando.
     * Não usa sleeps, rejeição técnica genérica ou resultado lido antes do commit.
     */
    private Ordered ordered(String label, Callable<Integer> first, Callable<Integer> second,
                            AtomicReference<Object> snapshotBeforeSecond) throws Exception {
        var pool=Executors.newFixedThreadPool(2);
        var ready=new CountDownLatch(2);
        var firstCommitted=new CountDownLatch(1);
        var ids=ConcurrentHashMap.<Integer>newKeySet();
        try {
            var a=pool.submit(()->{
                try {
                    return new TransactionTemplate(manager).execute(tx->{
                        ids.add(jdbc.queryForObject("SELECT SESSION_ID()",Integer.class));
                        ready.countDown();
                        try {
                            assertTrue(ready.await(10,TimeUnit.SECONDS));
                            authenticate();
                            int result=first.call();
                            if(result>=400)tx.setRollbackOnly();
                            return result;
                        } catch(Exception e) {
                            throw new IllegalStateException("First operation failed technically",e);
                        } finally {SecurityContextHolder.clearContext();}
                    });
                } finally {firstCommitted.countDown();}
            });
            var z=pool.submit(()->new TransactionTemplate(manager).execute(tx->{
                ids.add(jdbc.queryForObject("SELECT SESSION_ID()",Integer.class));
                ready.countDown();
                try {
                    assertTrue(ready.await(10,TimeUnit.SECONDS));
                    assertTrue(firstCommitted.await(20,TimeUnit.SECONDS));
                    if(snapshotBeforeSecond!=null) snapshotBeforeSecond.set(database());
                    authenticate();
                    int result=second.call();
                    if(result>=400)tx.setRollbackOnly();
                    return result;
                } catch(Exception e) {
                    throw new IllegalStateException("Second operation failed technically",e);
                } finally {SecurityContextHolder.clearContext();}
            }));
            int x=a.get(30,TimeUnit.SECONDS),y=z.get(30,TimeUnit.SECONDS);
            assertEquals(2,ids.size(),"As transações devem manter conexões H2 distintas");
            assertTrue(x==200||x==201,"Primeira operação deveria confirmar: "+x);
            assertTrue(y==200||y==201||y==409,"Segunda operação inesperada: "+y);
            System.out.printf("E62-F3B %s first=%d second=%d connections=%d%n",label,x,y,ids.size());
            return new Ordered(x,y);
        } finally {
            firstCommitted.countDown();
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(10,TimeUnit.SECONDS));
        }
    }

    @ParameterizedTest @ValueSource(booleans={true,false})
    void transferAndEnrollmentInBothOrdersMustPreserveAllCommittedEffects(boolean transferFirst) throws Exception {
        var g=session61(colleague,DAY,TIME,false,StatusPresencaGrupo.NAO_REGISTRADA,other);
        var operations=transferFirst
                ?ordered("TRANSFER-BEFORE-ENROLLMENT",()->transfer(g),()->enrollment(g),null)
                :ordered("ENROLLMENT-BEFORE-TRANSFER",()->enrollment(g),()->transfer(g),null);
        assertEquals(200,operations.first());
        assertEquals(200,operations.second());
        assertEquals(professional.getId(),groups61.findById(g.group()).orElseThrow().getCoordenador().getId());
        assertEquals(Set.of(patient.getIdPublico(),other.getIdPublico()),
                Set.copyOf(participants61.findPacienteIds(g.session())));
    }

    @ParameterizedTest @ValueSource(booleans={true,false})
    void regenerationAndBookingInBothOrdersMustCommitOnlyOneCompatibleSlot(boolean regenerationFirst) throws Exception {
        var g=session61(colleague,DAY,LATER,false,StatusPresencaGrupo.NAO_REGISTRADA,patient);
        var state=new AtomicReference<Object>();
        var result=regenerationFirst
                ?ordered("REGENERATE-BEFORE-BOOKING",()->regenerate(g),()->booking(),state)
                :ordered("BOOKING-BEFORE-REGENERATE",()->booking(),()->regenerate(g),state);
        assertEquals(regenerationFirst?200:201,result.first());
        assertEquals(409,result.second());
        assertEquals(state.get(),database(),"A segunda operação recusada não pode modificar o banco");
        assertEquals(regenerationFirst?TIME:LATER,
                sessions61.findByGrupoOrderByDataSessaoDesc(groups61.findById(g.group()).orElseThrow())
                        .getFirst().getHorario());
        long confirmed=tx(()->appointments.findByPaciente(patient).stream()
                .filter(a->DAY.equals(a.getDataAgendamento())&&TIME.equals(a.getHoraAtendimento())
                        && Boolean.TRUE.equals(a.getOcupaVaga())).count());
        assertEquals(regenerationFirst?0:1,confirmed);
    }

    @Test
    void groupCreationWritesThreeSessionsButOuterFailureRollsBackEveryTable() {
        var before=database();
        long groups=groups61.count(),sessions=sessions61.count(),members=participants61.count();
        authenticate();
        var dto=new CriarGrupoDTO("Série sintética F3B",colleague.getIdPublico(),
                RecorrenciaGrupo.SEMANAL,DAY,DAY.plusWeeks(2),TIME,List.of(patient.getIdPublico()));
        assertThrows(IllegalStateException.class,()->new TransactionTemplate(manager).execute(tx->{
            groupService.criarGrupo(dto);
            assertEquals(groups+1,groups61.count());
            assertEquals(sessions+3,sessions61.count());
            assertEquals(members+3,participants61.count());
            throw new IllegalStateException("synthetic late failure after series creation");
        }));
        assertEquals(before,database());
    }

    @Test
    void regenerationDeletesAndRecreatesSessionButOuterFailureRollsBackEveryTable() {
        var g=session61(colleague,DAY,LATER,false,StatusPresencaGrupo.NAO_REGISTRADA,patient);
        var before=database();
        authenticate();
        var dto=new AtualizarGrupoTerapeuticoDTO("Grupo histórico sintético 6.1",
                colleague.getIdPublico(),DAY,TIME,RecorrenciaGrupo.UNICA,null,
                groups61.findById(g.group()).orElseThrow().getVersion());
        assertThrows(IllegalStateException.class,()->new TransactionTemplate(manager).execute(tx->{
            groupService.atualizarGrupo(g.group(),dto);
            var changed=sessions61.findByGrupoOrderByDataSessaoDesc(
                    groups61.findById(g.group()).orElseThrow());
            assertEquals(1,changed.size());
            assertEquals(TIME,changed.getFirst().getHorario());
            assertNotEquals(g.session(),changed.getFirst().getId());
            throw new IllegalStateException("synthetic late failure after regeneration");
        }));
        assertEquals(before,database());
    }

    @Test
    void profileDemotionCommittedBeforeNewGroupMustPreventIneligibleCoordinator() throws Exception {
        authenticate();
        var role=new UsuarioDTO(colleague.getIdPublico(),colleague.getNome(),colleague.getEmail(),
                TipoUsuario.RECEPCAO,colleague.getUnidadeAtuacao(),
                colleague.getUsfAtuacao()==null?null:colleague.getUsfAtuacao().getId(),null,null);
        userService.save(role);
        assertEquals(TipoUsuario.RECEPCAO,users.findById(colleague.getId()).orElseThrow().getTipoUsuario());
        var before=database();
        int result=call(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(GROUP),
                new CriarGrupoDTO("Perfil inelegível F3B",colleague.getIdPublico(),
                        RecorrenciaGrupo.UNICA,DAY,null,TIME,List.of(patient.getIdPublico())));
        assertEquals(422,result,"Coordenador com perfil de recepção não é elegível para novo grupo");
        assertEquals(before,database());
    }
}
