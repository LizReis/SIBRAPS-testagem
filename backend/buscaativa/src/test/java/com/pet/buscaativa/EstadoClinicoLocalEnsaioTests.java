package com.pet.buscaativa;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import javax.sql.DataSource;

import org.hibernate.envers.AuditReaderFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

import com.pet.buscaativa.entities.*;
import com.pet.buscaativa.entities.dto.EncerramentoPacienteDTO;
import com.pet.buscaativa.entities.enums.*;
import com.pet.buscaativa.repositories.HistoricoPacienteRepository;
import com.pet.buscaativa.services.exceptions.ResourceNotFoundException;
import com.pet.buscaativa.services.exceptions.ValidationException;
import com.pet.buscaativa.services.impl.EstadoClinicoLocalEnsaioService;
import com.pet.buscaativa.services.impl.SusApiAdapter;

import jakarta.persistence.EntityManager;

@ActiveProfiles("ensaio-estado-local")
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:estado-local-63;DB_CLOSE_DELAY=-1")
class EstadoClinicoLocalEnsaioTests extends SecurityTestSupport {
    @Autowired EntityManager em;
    @Autowired TransactionTemplate tx;
    @Autowired DataSource fonte;
    @Autowired EstadoClinicoLocalEnsaioService servico;
    @Autowired HistoricoPacienteRepository historicos;
    @MockitoBean SusApiAdapter sus;

    private record Local(Long id, UUID publico, Integer versao, String unidade, Usuario admin) {}
    private record Cenario(UUID pessoa, Long idPessoa, Local a, Local b, Local caps,
            Usuario profissional, Usuario recepcao) {}

    @Test
    void configuraEncerraEReativaSomenteVinculoAutorizadoComAuditoria() {
        var c = tx.execute(t -> preparar());
        var colunas = LegacyMigrationFixture.columns(fonte);
        var antes = LegacyMigrationFixture.snapshot(fonte, colunas);

        var configurado = como(c.a().admin(), () -> servico.configurar(c.pessoa(), c.a().publico(),
                c.a().versao(), TipoAcompanhamento.INDIVIDUAL, c.profissional().getIdPublico()));
        assertEquals(StatusPaciente.ATIVO, configurado.status());
        assertEquals(TipoAcompanhamento.INDIVIDUAL, configurado.tipo());
        assertNotEquals(c.a().versao(), configurado.versao());
        tx.executeWithoutResult(t -> {
            var v = em.find(VinculoAssistencial.class, c.a().id());
            assertEquals(c.profissional().getId(), v.getProfissionalReferencia().getId());
            assertNull(v.getCountFaltas());
            assertNull(v.getIndicadoresPendentes());
            assertNull(v.getClassificacaoRisco());
            // Configurar tipo não fabrica transição histórica de status.
            var leitor = AuditReaderFactory.get(em);
            var revisoes = leitor.getRevisions(VinculoAssistencial.class, v.getId());
            assertEquals(2, revisoes.size());
            assertTrue(revisoes.stream().allMatch(rev -> leitor.find(VinculoAssistencial.class, v.getId(), rev)
                    .getStatusPaciente() == StatusPaciente.ATIVO));
        });
        var encerrado = como(c.profissional(), () -> servico.encerrar(c.pessoa(), c.a().publico(),
                configurado.versao(), alta()));
        assertEquals(StatusPaciente.ALTA_TERAPEUTICA, encerrado.status());
        tx.executeWithoutResult(t -> {
            assertEquals(StatusPaciente.ATIVO, em.find(VinculoAssistencial.class, c.b().id()).getStatusPaciente());
            assertEquals(StatusPaciente.ATIVO, em.find(VinculoAssistencial.class, c.caps().id()).getStatusPaciente());
            assertEquals(StatusPaciente.ATIVO, em.find(Paciente.class, c.idPessoa()).getStatusPaciente());
        });
        var reativado = como(c.a().admin(), () -> servico.reativar(c.pessoa(), c.a().publico(),
                encerrado.versao(), "Retomada do acompanhamento na unidade."));
        assertEquals(StatusPaciente.ATIVO, reativado.status());
        assertNotEquals(encerrado.versao(), reativado.versao());

        tx.executeWithoutResult(t -> {
            var v = em.find(VinculoAssistencial.class, c.a().id());
            var leitor = AuditReaderFactory.get(em);
            var revisoes = leitor.getRevisions(VinculoAssistencial.class, v.getId());
            assertEquals(4, revisoes.size());
            assertEquals(StatusPaciente.ALTA_TERAPEUTICA,
                    leitor.find(VinculoAssistencial.class, v.getId(), revisoes.get(2)).getStatusPaciente());
            assertEquals(StatusPaciente.ATIVO,
                    leitor.find(VinculoAssistencial.class, v.getId(), revisoes.get(3)).getStatusPaciente());
            for (var outro : List.of(c.b(), c.caps())) {
                var externo = em.find(VinculoAssistencial.class, outro.id());
                assertEquals(outro.versao(), externo.getVersion());
                assertNull(externo.getTipoAcompanhamento());
                assertEquals(1, leitor.getRevisions(VinculoAssistencial.class, externo.getId()).size());
                assertTrue(historicos.findLocaisComOrigemComprovada(c.pessoa(), outro.unidade()).isEmpty());
            }
            var trilha = historicos.findLocaisComOrigemComprovada(c.pessoa(), c.a().unidade());
            assertEquals(3, trilha.size());
            assertTrue(trilha.stream().allMatch(h -> h.getProfissional() != null && h.getAgendamento() == null
                    && h.getSessaoGrupo() == null && h.getTipo() == TipoEventoHistoricoPaciente.SITUACAO_ATUALIZADA));
            assertTrue(trilha.stream().anyMatch(h -> h.getDescricao().contains("ALTA_TERAPEUTICA")
                    && h.getProfissional().getId().equals(c.profissional().getId())));
            assertTrue(trilha.stream().anyMatch(h -> h.getDescricao().contains("Retomada")
                    && h.getProfissional().getId().equals(c.a().admin().getId())));
        });
        var depois = LegacyMigrationFixture.snapshot(fonte, colunas);
        for (String tabela : List.of("TB_PACIENTE", "TB_PACIENTE_AUD", "TB_AGENDAMENTO",
                "TB_SESSAO_GRUPO", "TB_SESSAO_GRUPO_PARTICIPANTE", "TB_PENDENCIA_REVISAO_PROVENIENCIA",
                "TB_DECISAO_REVISAO_PROVENIENCIA", "TB_AUDITORIA_BUSCA_ATIVA"))
            assertEquals(antes.get(tabela), depois.get(tabela), tabela);
    }

    @Test
    void recusaEscopoPerfilReferenciaVersaoEValidacaoSemEscrita() {
        var c = tx.execute(t -> preparar());
        var colunas = LegacyMigrationFixture.columns(fonte);
        var antes = LegacyMigrationFixture.snapshot(fonte, colunas);
        assertThrows(AccessDeniedException.class, () -> servico.encerrar(c.pessoa(), c.a().publico(), c.a().versao(), alta()));
        for (var par : List.of(c.b(), c.caps())) {
            assertThrows(ResourceNotFoundException.class, () -> como(par.admin(),
                    () -> servico.encerrar(c.pessoa(), c.a().publico(), c.a().versao(), alta())));
            assertThrows(ResourceNotFoundException.class, () -> como(c.a().admin(),
                    () -> servico.encerrar(c.pessoa(), par.publico(), par.versao(), alta())));
        }
        assertThrows(ResourceNotFoundException.class, () -> como(c.a().admin(),
                () -> servico.encerrar(UUID.randomUUID(), c.a().publico(), c.a().versao(), alta())));
        assertThrows(AccessDeniedException.class, () -> como(c.recepcao(),
                () -> servico.encerrar(c.pessoa(), c.a().publico(), c.a().versao(), alta())));
        assertThrows(AccessDeniedException.class, () -> como(c.profissional(),
                () -> servico.reativar(c.pessoa(), c.a().publico(), c.a().versao(), "Motivo")));
        assertThrows(ResourceNotFoundException.class, () -> como(c.a().admin(),
                () -> servico.configurar(c.pessoa(), c.a().publico(), c.a().versao(),
                        TipoAcompanhamento.AMBOS, c.b().admin().getIdPublico())));
        assertThrows(ResourceNotFoundException.class, () -> como(c.a().admin(),
                () -> servico.configurar(c.pessoa(), c.a().publico(), c.a().versao(),
                        TipoAcompanhamento.AMBOS, c.recepcao().getIdPublico())));
        assertThrows(ValidationException.class, () -> como(c.a().admin(),
                () -> servico.encerrar(c.pessoa(), c.a().publico(), c.a().versao() + 1, alta())));
        assertThrows(ValidationException.class, () -> como(c.a().admin(),
                () -> servico.encerrar(c.pessoa(), c.a().publico(), c.a().versao(),
                        new EncerramentoPacienteDTO(MotivoEncerramento.OUTRO, StatusPaciente.OUTRO, " "))));
        assertThrows(ValidationException.class, () -> como(c.a().admin(),
                () -> servico.encerrar(c.pessoa(), c.a().publico(), c.a().versao(),
                        new EncerramentoPacienteDTO(MotivoEncerramento.OUTRO, StatusPaciente.OUTRO, "x".repeat(2001)))));
        assertThrows(ValidationException.class, () -> como(c.a().admin(),
                () -> servico.reativar(c.pessoa(), c.a().publico(), c.a().versao(), "Motivo")));
        assertEquals(antes, LegacyMigrationFixture.snapshot(fonte, colunas));
    }

    @Test
    void duasEscritasComMesmaVersaoNaoSobrescrevemEstadoLocal() throws Exception {
        var c = tx.execute(t -> preparar());
        var inicio = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var um = executor.submit(() -> tentativa(c, inicio, TipoAcompanhamento.INDIVIDUAL));
            var dois = executor.submit(() -> tentativa(c, inicio, TipoAcompanhamento.GRUPO_TERAPEUTICO));
            inicio.countDown();
            assertNotEquals(um.get(20, TimeUnit.SECONDS), dois.get(20, TimeUnit.SECONDS));
        }
        tx.executeWithoutResult(t -> {
            assertEquals(1, historicos.findLocaisComOrigemComprovada(c.pessoa(), c.a().unidade()).size());
            assertEquals(c.b().versao(), em.find(VinculoAssistencial.class, c.b().id()).getVersion());
            assertEquals(c.caps().versao(), em.find(VinculoAssistencial.class, c.caps().id()).getVersion());
        });
    }

    private boolean tentativa(Cenario c, CountDownLatch inicio, TipoAcompanhamento tipo) throws InterruptedException {
        assertTrue(inicio.await(10, TimeUnit.SECONDS));
        try {
            como(c.a().admin(), () -> servico.configurar(c.pessoa(), c.a().publico(), c.a().versao(), tipo, null));
            return true;
        } catch (ValidationException conflito) {
            assertTrue(conflito.getMessage().contains("Versão local divergente"));
            return false;
        }
    }

    private Cenario preparar() {
        var p = new Paciente();
        p.setNome("Identidade sintética");
        p.setCpf(UUID.randomUUID().toString());
        p.setCns(UUID.randomUUID().toString());
        p.setStatusPaciente(StatusPaciente.ATIVO);
        p.setTipoAcompanhamento(TipoAcompanhamento.AMBOS);
        p.setCountFaltas(8);
        em.persist(p);
        var usfA = usf();
        var usfB = usf();
        var a = usuario(UnidadeAtuacao.USF, usfA, TipoUsuario.ADMINISTRADOR);
        var b = usuario(UnidadeAtuacao.USF, usfB, TipoUsuario.ADMINISTRADOR);
        var caps = usuario(UnidadeAtuacao.CAPS_II, null, TipoUsuario.ADMINISTRADOR);
        var profissional = usuario(UnidadeAtuacao.USF, usfA, TipoUsuario.PROFISSIONAL);
        var recepcao = usuario(UnidadeAtuacao.USF, usfA, TipoUsuario.RECEPCAO);
        var va = VinculoAssistencial.paraUsf(p, usfA);
        var vb = VinculoAssistencial.paraUsf(p, usfB);
        var vc = VinculoAssistencial.paraCaps(p, UnidadeAtuacao.CAPS_II);
        em.persist(va); em.persist(vb); em.persist(vc); em.flush();
        return new Cenario(p.getIdPublico(), p.getId(), local(va, a), local(vb, b), local(vc, caps), profissional, recepcao);
    }

    private Local local(VinculoAssistencial v, Usuario ator) {
        return new Local(v.getId(), v.getIdPublico(), v.getVersion(), v.getUnidadeChave(), ator);
    }

    private UsfReferencia usf() {
        var u = new UsfReferencia();
        u.setNomeUsf("USF sintética");
        u.setCnes(UUID.randomUUID().toString());
        em.persist(u);
        return u;
    }

    private Usuario usuario(UnidadeAtuacao unidade, UsfReferencia usf, TipoUsuario papel) {
        var u = new Usuario();
        u.setNome("Ator sintético");
        u.setEmail(UUID.randomUUID() + "@test.local");
        u.setSenha("fixture");
        u.setTipoUsuario(papel);
        u.setUnidadeAtuacao(unidade);
        u.setUsfAtuacao(usf);
        em.persist(u);
        return u;
    }

    private static EncerramentoPacienteDTO alta() {
        return new EncerramentoPacienteDTO(MotivoEncerramento.ALTA_TERAPEUTICA, StatusPaciente.ALTA_TERAPEUTICA, null);
    }

    private static <T> T como(Usuario ator, Supplier<T> acao) {
        var anterior = SecurityContextHolder.getContext();
        var contexto = SecurityContextHolder.createEmptyContext();
        contexto.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(ator, null, ator.getAuthorities()));
        SecurityContextHolder.setContext(contexto);
        try { return acao.get(); }
        finally { SecurityContextHolder.setContext(anterior); }
    }
}
