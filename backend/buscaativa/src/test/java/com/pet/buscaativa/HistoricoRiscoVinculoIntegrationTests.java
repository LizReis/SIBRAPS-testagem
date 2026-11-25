package com.pet.buscaativa;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

import com.pet.buscaativa.entities.*;
import com.pet.buscaativa.entities.dto.EncerramentoPacienteDTO;
import com.pet.buscaativa.entities.enums.*;
import com.pet.buscaativa.repositories.VinculoAssistencialRepository;
import com.pet.buscaativa.services.impl.BuscaAtivaPainelLocalEnsaioService;
import com.pet.buscaativa.services.impl.CoberturaHistoricaPorUnidade;
import com.pet.buscaativa.services.impl.EstadoClinicoLocalEnsaioService;
import com.pet.buscaativa.services.impl.FrequenciaColetivaLocalEnsaioService;
import com.pet.buscaativa.services.impl.FrequenciaIndividualLocalEnsaioService;
import com.pet.buscaativa.services.impl.InicioAcompanhamentoLocalEnsaioService;
import com.pet.buscaativa.services.impl.SusApiAdapter;
import jakarta.persistence.EntityManager;

@ActiveProfiles({"ensaio-estado-local", "relogio-coordenacao"})
@Import(PersistenciaIndicadoresLocaisIntegrationTests.RelogioEnsaio.class)
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:historico-risco-local;DB_CLOSE_DELAY=-1")
class HistoricoRiscoVinculoIntegrationTests extends SecurityTestSupport {
    @Autowired EntityManager em;
    @Autowired TransactionTemplate tx;
    @Autowired DataSource fonte;
    @Autowired Clock clock;
    @Autowired VinculoAssistencialRepository vinculos;
    @Autowired InicioAcompanhamentoLocalEnsaioService inicio;
    @Autowired EstadoClinicoLocalEnsaioService estado;
    @Autowired FrequenciaIndividualLocalEnsaioService individual;
    @Autowired FrequenciaColetivaLocalEnsaioService coletivo;
    @Autowired BuscaAtivaPainelLocalEnsaioService painelLocal;
    @MockitoBean SusApiAdapter sus;
    @MockitoBean CoberturaHistoricaPorUnidade coberturaHistorica;

    private static final String JUSTIFICATIVA = "Correção de frequência comprovada no ensaio sintético.";
    private record Local(Paciente paciente, Usuario ator, UUID vinculo) {}
    private record Agenda(Long id, Integer versao) {}
    private record Sessao(Long id, Integer versao) {}

    @Test
    void historicoMensalReconstruiSomenteCoberturaConhecidaNoCorte() {
        record LocaisHistoricos(Local a, Local b, Local caps, Local outroCaps, Agenda faltaCorrigida) {}
        var locais = noTempo("2026-09-15T19:00:00Z", () -> {
            var a = novoLocal(UnidadeAtuacao.USF);
            var b = iniciar(a.paciente(),tx.execute(t->usuario(UnidadeAtuacao.USF)));
            var caps = iniciar(a.paciente(),tx.execute(t->usuario(UnidadeAtuacao.CAPS_I)));
            var outroCaps = iniciar(a.paciente(),tx.execute(t->usuario(UnidadeAtuacao.CAPS_II)));
            tx.executeWithoutResult(t->em.find(Paciente.class,a.paciente().getId()).setSituacaoRua(true));
            var falta = reserva(a,diasAtras(4),9,SituacaoAtendimento.AGENDADO,true);
            var segunda = reserva(a,diasAtras(3),9,SituacaoAtendimento.AGENDADO,true);
            var presente = reserva(b,diasAtras(2),9,SituacaoAtendimento.AGENDADO,true);
            for(var ag:List.of(falta,segunda))
                como(a.ator(),()->individual.registrar(ag.id(),ag.versao(),FrequenciaAssistencial.FALTOU));
            como(b.ator(),()->individual.registrar(presente.id(),presente.versao(),FrequenciaAssistencial.PRESENTE));
            var sessao = sessao(a.ator(),List.of(a),diasAtras(1),10);
            como(a.ator(),()->coletivo.confirmar(sessao.id(),sessao.versao(),Map.of()));
            return new LocaisHistoricos(a,b,caps,outroCaps,falta);
        });
        noTempo("2026-10-15T19:00:00Z", () -> {
            var a=locais.a();
            var b=locais.b();
            como(a.ator(),()->estado.encerrar(a.paciente().getIdPublico(),a.vinculo(),carregar(a).getVersion(),
                    new EncerramentoPacienteDTO(MotivoEncerramento.ALTA_TERAPEUTICA,StatusPaciente.ALTA_TERAPEUTICA,null)));
            como(b.ator(),()->estado.configurar(b.paciente().getIdPublico(),b.vinculo(),carregar(b).getVersion(),
                    TipoAcompanhamento.INDIVIDUAL,null));
            tx.executeWithoutResult(t->em.find(Paciente.class,b.paciente().getId()).setSituacaoRua(false));
            return null;
        });
        var a=locais.a();
        como(a.ator(),()->estado.reativar(a.paciente().getIdPublico(),a.vinculo(),carregar(a).getVersion(),JUSTIFICATIVA));
        var f=locais.faltaCorrigida();
        como(a.ator(),()->individual.corrigir(f.id(),f.versao()+1,FrequenciaAssistencial.PRESENTE,JUSTIFICATIVA));
        certificar(a,"2026-08",java.util.Set.of());
        for (var l : List.of(a,locais.b(),locais.caps(),locais.outroCaps())) {
            certificar(l,"2026-09",java.util.Set.of(carregar(l).getId()));
            certificar(l,"2026-10",java.util.Set.of(carregar(l).getId()));
        }

        var colunas=LegacyMigrationFixture.columns(fonte);
        var snapshot=LegacyMigrationFixture.snapshot(fonte,colunas);
        var painel=como(a.ator(),()->painelLocal.painel(6,null,null,null));
        var porMes=painel.evolucao().stream().collect(java.util.stream.Collectors.toMap(e->e.mes(),e->e));
        assertTrue(porMes.get("2026-08").disponivel());
        assertEquals(0L,porMes.get("2026-08").verdes());
        assertEquals(1L,porMes.get("2026-09").vermelhos());
        assertEquals(0L,porMes.get("2026-10").vermelhos());
        assertEquals(0L,porMes.get("2026-10").verdes());
        assertNull(porMes.get("2026-11").verdes());
        assertEquals(1L,painel.totalPacientesAtivos());
        assertEquals(LocalDate.now(clock),painel.pontoAtual().data());
        var b=locais.b();
        var filtro=como(b.ator(),()->painelLocal.painel(3,null,TipoAcompanhamento.INDIVIDUAL,false));
        assertEquals(0L,filtro.evolucao().getFirst().verdes());
        assertEquals(1L,filtro.evolucao().get(1).verdes());
        assertEquals(1L,como(b.ator(),()->painelLocal.painel(3,null,null,true)).evolucao().getFirst().amarelos());
        for (var caps : List.of(locais.caps(),locais.outroCaps()))
            assertEquals(1L,como(caps.ator(),()->painelLocal.painel(3,null,null,null)).evolucao().getFirst().verdes());
        certificar(a,"2026-09",java.util.Set.of(carregar(a).getId(),Long.MAX_VALUE)); // vínculo ausente no Envers
        var inconclusivo=como(a.ator(),()->painelLocal.painel(3,null,null,null)).evolucao().getFirst();
        assertFalse(inconclusivo.disponivel());assertNull(inconclusivo.verdes());
        assertEquals("INDISPONIVEL", inconclusivo.situacao().name());
        assertEquals(snapshot,LegacyMigrationFixture.snapshot(fonte,colunas));
        certificar(a,"2026-09",java.util.Set.of(carregar(a).getId()));
        new JdbcTemplate(fonte).update("update revinfo set revtstmp=? where rev=(select min(rev) from revinfo)",
                Instant.parse("2026-11-25T00:00:00Z").toEpochMilli());
        var corteIncoerente=como(a.ator(),()->painelLocal.painel(3,null,null,null)).evolucao().getFirst();
        assertFalse(corteIncoerente.disponivel());
        assertNull(corteIncoerente.vermelhos());
    }

    private void certificar(Local local,String mes,java.util.Set<Long> ids) {
        var corte=java.time.YearMonth.parse(mes).plusMonths(1).atDay(1).atStartOfDay(clock.getZone()).toInstant().minusMillis(1);
        when(coberturaHistorica.vinculosCertificados(carregar(local).getUnidadeChave(),corte))
                .thenReturn(java.util.Optional.of(ids));
    }

    /** Envers usa o relógio do host. Apenas a fixture alinha os timestamps de suas próprias revisões. */
    private <T> T noTempo(String instante,Supplier<T> comando) {
        long ultima=total("select coalesce(max(rev),0) from revinfo");
        var data=Instant.parse(instante);
        PersistenciaIndicadoresLocaisIntegrationTests.RelogioHistorico.HISTORICO.set(data);
        try {
            var resultado=comando.get();
            new JdbcTemplate(fonte).update("update revinfo set revtstmp=? where rev>?",data.toEpochMilli(),ultima);
            return resultado;
        } finally {
            PersistenciaIndicadoresLocaisIntegrationTests.RelogioHistorico.HISTORICO.remove();
        }
    }

    private long total(String sql, Object... params) {
        return new JdbcTemplate(fonte).queryForObject(sql, Long.class, params);
    }

    private LocalDate diasAtras(int dias) {
        return LocalDate.now(clock).minusDays(dias);
    }

    private VinculoAssistencial carregar(Local l) {
        return vinculos.findByIdPublico(l.vinculo()).orElseThrow();
    }

    private Local novoLocal(UnidadeAtuacao unidade) {
        return iniciar(novoPaciente(), tx.execute(t -> usuario(unidade)));
    }

    private Local iniciar(Paciente pessoa, Usuario ator) {
        var resultado = como(ator, () -> inicio.iniciar(pessoa.getIdPublico(), TipoAcompanhamento.AMBOS, null));
        return new Local(pessoa, ator, resultado.vinculo());
    }

    private Paciente novoPaciente() {
        return tx.execute(t -> {
            var p = new Paciente();
            p.setNome("Paciente sintético do ensaio");
            p.setCpf(UUID.randomUUID().toString());
            p.setCns(UUID.randomUUID().toString());
            p.setCountFaltas(8);
            p.setClassificacaoRisco(ClassificacaoRisco.VERMELHO);
            p.setIndicadoresPendentes(true);
            em.persist(p);
            return p;
        });
    }

    private Usuario usuario(UnidadeAtuacao unidade) {
        var ator = new Usuario();
        ator.setNome("Ator sintético do ensaio");
        ator.setEmail(UUID.randomUUID() + "@test.local");
        ator.setSenha("fixture");
        ator.setTipoUsuario(TipoUsuario.ADMINISTRADOR);
        ator.setUnidadeAtuacao(unidade);
        if (unidade == UnidadeAtuacao.USF) {
            var usf = new UsfReferencia();
            usf.setNomeUsf("USF sintética");
            usf.setCnes(UUID.randomUUID().toString());
            em.persist(usf);
            ator.setUsfAtuacao(usf);
        }
        em.persist(ator);
        return ator;
    }

    private Agenda reserva(Local local, LocalDate data, int hora, SituacaoAtendimento situacao, boolean origem) {
        return tx.execute(t -> {
            var a = new Agendamento();
            a.setPaciente(em.find(Paciente.class, local.paciente().getId()));
            a.setUsuario(em.find(Usuario.class, local.ator().getId()));
            a.setDataAgendamento(data);
            a.setHoraAtendimento(LocalTime.of(hora, 0));
            a.setTurnoAgendamento(TurnoEnum.MANHA);
            a.setSituacaoAtendimento(situacao);
            if (origem) a.registrarUnidadeDeOrigem(a.getUsuario());
            em.persist(a);
            return new Agenda(a.getId(), a.getVersion());
        });
    }

    private Sessao sessao(Usuario ator, List<Local> locais, LocalDate data, int hora) {
        return tx.execute(t -> {
            var g = new GrupoTerapeutico();
            g.setTema("Grupo sintético do ensaio");
            g.setCoordenador(em.find(Usuario.class, ator.getId()));
            g.registrarUnidadeDeOrigem(g.getCoordenador());
            em.persist(g);
            var s = new SessaoGrupo();
            s.setGrupo(g);
            s.setDataSessao(data);
            s.setHorario(LocalTime.of(hora, 0));
            s.setStatus(StatusSessaoGrupo.AGENDADA);
            s.registrarUnidadeDeOrigemDoGrupo();
            em.persist(s);
            locais.forEach(local -> adicionar(s, local.paciente()));
            return new Sessao(s.getId(), s.getVersion());
        });
    }

    private void adicionar(SessaoGrupo s, Paciente pessoa) {
        var p = new SessaoGrupoParticipante();
        p.setSessaoGrupo(s);
        p.setPaciente(em.find(Paciente.class, pessoa.getId()));
        em.persist(p);
    }

    private static <T> T como(Usuario ator, Supplier<T> acao) {
        var anterior = SecurityContextHolder.getContext();
        var contexto = SecurityContextHolder.createEmptyContext();
        contexto.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(ator, null, ator.getAuthorities()));
        SecurityContextHolder.setContext(contexto);
        try {
            return acao.get();
        } finally {
            SecurityContextHolder.setContext(anterior);
        }
    }
}
