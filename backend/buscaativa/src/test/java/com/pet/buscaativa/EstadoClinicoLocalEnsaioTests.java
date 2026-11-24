package com.pet.buscaativa;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.UUID;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import javax.sql.DataSource;

import org.hibernate.envers.AuditReaderFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

import com.pet.buscaativa.alertas.EmailService;
import com.pet.buscaativa.entities.*;
import com.pet.buscaativa.entities.dto.EncerramentoPacienteDTO;
import com.pet.buscaativa.entities.enums.*;
import com.pet.buscaativa.repositories.HistoricoPacienteRepository;
import com.pet.buscaativa.services.exceptions.ResourceNotFoundException;
import com.pet.buscaativa.services.exceptions.ValidationException;
import com.pet.buscaativa.services.impl.EstadoClinicoLocalEnsaioService;
import com.pet.buscaativa.services.impl.EvidenciaStatusVinculo.Situacao;
import com.pet.buscaativa.services.impl.ProjecaoClinicaVinculoService;
import com.pet.buscaativa.migration.PlanoIndicadoresVinculo;
import com.pet.buscaativa.services.impl.InicioAcompanhamentoLocalEnsaioService;
import com.pet.buscaativa.repositories.VinculoAssistencialRepository;
import com.pet.buscaativa.services.impl.SusApiAdapter;

import jakarta.persistence.EntityManager;

@ActiveProfiles({"ensaio-estado-local", "diagnostico-migracao"})
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:estado-local-63;DB_CLOSE_DELAY=-1")
class EstadoClinicoLocalEnsaioTests extends SecurityTestSupport {
    @Autowired EntityManager em;
    @Autowired TransactionTemplate tx;
    @Autowired DataSource fonte;
    @Autowired ProjecaoClinicaVinculoService projecoes;
    @Autowired EstadoClinicoLocalEnsaioService servico;
    @Autowired InicioAcompanhamentoLocalEnsaioService inicioLocal;
    @Autowired VinculoAssistencialRepository vinculos;
    @Autowired com.pet.buscaativa.repositories.PacienteRepository pacientes;
    @Autowired HistoricoPacienteRepository historicos;
    @MockitoBean SusApiAdapter sus;
    @MockitoBean EmailService email;

    @AfterEach
    void nenhumaOperacaoDisparaEmail() {
        verifyNoInteractions(email);
    }

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

    @Test
    void iniciaNovoVinculoSemHerdarIndicadoresERepeticaoNaoReinicializa() {
        var c = tx.execute(t -> preparar());
        var atorAd = tx.execute(t -> {
            var ator = usuario(UnidadeAtuacao.CAPS_AD, null, TipoUsuario.PROFISSIONAL);
            // Um fato conhecido de outra unidade não impede acompanhamento novo.
            var h = new HistoricoPaciente();
            h.setPaciente(em.find(Paciente.class, c.idPessoa()));
            h.setProfissional(em.find(Usuario.class, c.a().admin().getId()));
            h.setTipo(TipoEventoHistoricoPaciente.SITUACAO_ATUALIZADA);
            h.setOcorridoEm(java.time.LocalDateTime.now());
            h.registrarOrigemDeAcaoLocal(h.getProfissional());
            em.persist(h);
            return ator;
        });
        var colunas = LegacyMigrationFixture.columns(fonte);
        var antes = LegacyMigrationFixture.snapshot(fonte, colunas);
        var criado = como(atorAd, () -> inicioLocal.iniciar(c.pessoa(), TipoAcompanhamento.GRUPO_TERAPEUTICO,
                atorAd.getIdPublico()));
        assertEquals(InicioAcompanhamentoLocalEnsaioService.Situacao.CRIADO_NO_ENSAIO, criado.situacao());
        tx.executeWithoutResult(t -> {
            var v = vinculos.findByIdPublico(criado.vinculo()).orElseThrow();
            assertEquals("CAPS_AD", v.getUnidadeChave());
            assertEquals(StatusPaciente.ATIVO, v.getStatusPaciente());
            assertEquals(TipoAcompanhamento.GRUPO_TERAPEUTICO, v.getTipoAcompanhamento());
            assertEquals(0, v.getCountFaltas());
            assertNull(v.getDataUltimaPresenca());
            assertEquals(ClassificacaoRisco.VERDE, v.getClassificacaoRisco());
            assertEquals(Boolean.FALSE, v.getGatilhoVisitaAcionado());
            assertEquals(Boolean.FALSE, v.getIndicadoresPendentes());
            assertEquals(atorAd.getId(), v.getProfissionalReferencia().getId());
            var leitor = AuditReaderFactory.get(em);
            var revisoes = leitor.getRevisions(VinculoAssistencial.class, v.getId());
            assertEquals(1, revisoes.size());
            assertEquals(0, leitor.find(VinculoAssistencial.class, v.getId(), revisoes.getFirst()).getCountFaltas());
            var trilha = historicos.findLocaisComOrigemComprovada(c.pessoa(), "CAPS_AD");
            assertEquals(1, trilha.size());
            assertEquals(atorAd.getId(), trilha.getFirst().getProfissional().getId());
            assertEquals(revisoes, leitor.getRevisions(HistoricoPaciente.class, trilha.getFirst().getId()));
            var evento = eventos(v.getId()).getFirst();
            assertEquals(EventoEstadoClinicoVinculo.Natureza.INICIALIZACAO, evento.getNatureza());
            assertEquals(v.getUnidadeChave(), evento.getUnidadeChave());
            assertEquals(atorAd.getId(), evento.getAtor().getId());
            assertEquals(trilha.getFirst().getOcorridoEm(), evento.getOcorridoEm());
            assertEquals(revisoes, leitor.getRevisions(EventoEstadoClinicoVinculo.class, evento.getId()));
            for (var externo : List.of(c.a(), c.b(), c.caps())) {
                assertEquals(externo.versao(), em.find(VinculoAssistencial.class, externo.id()).getVersion());
            }
            assertEquals(8, em.find(Paciente.class, c.idPessoa()).getCountFaltas());
        });
        var depois = LegacyMigrationFixture.snapshot(fonte, colunas);
        assertEquals(antes.get("TB_PACIENTE"), depois.get("TB_PACIENTE"));
        assertEquals(antes.get("TB_PACIENTE_AUD"), depois.get("TB_PACIENTE_AUD"));
        var repetido = como(atorAd, () -> inicioLocal.iniciar(c.pessoa(), TipoAcompanhamento.AMBOS, null));
        assertEquals(criado.vinculo(), repetido.vinculo());
        assertEquals(InicioAcompanhamentoLocalEnsaioService.Situacao.JA_EXISTENTE_NAO_MODIFICADO, repetido.situacao());
        var existente = como(c.a().admin(), () -> inicioLocal.iniciar(c.pessoa(), TipoAcompanhamento.AMBOS, null));
        assertEquals(c.a().publico(), existente.vinculo());
        assertEquals(depois, LegacyMigrationFixture.snapshot(fonte, colunas));
    }

    @Test
    void inicioRecusaLegadoLocalOuSemOrigemEReferenciaExternaSemEscrever() {
        var c = tx.execute(t -> preparar());
        var atorAd = tx.execute(t -> usuario(UnidadeAtuacao.CAPS_AD, null, TipoUsuario.ADMINISTRADOR));
        var colunas = LegacyMigrationFixture.columns(fonte);
        var antes = LegacyMigrationFixture.snapshot(fonte, colunas);
        assertThrows(ResourceNotFoundException.class, () -> como(atorAd,
                () -> inicioLocal.iniciar(c.pessoa(), TipoAcompanhamento.AMBOS, c.a().admin().getIdPublico())));
        assertThrows(AccessDeniedException.class, () -> como(c.recepcao(),
                () -> inicioLocal.iniciar(c.pessoa(), TipoAcompanhamento.AMBOS, null)));
        assertEquals(antes, LegacyMigrationFixture.snapshot(fonte, colunas));

        tx.executeWithoutResult(t -> {
            var h = new HistoricoPaciente();
            h.setPaciente(em.find(Paciente.class, c.idPessoa()));
            h.setProfissional(em.find(Usuario.class, atorAd.getId()));
            h.setTipo(TipoEventoHistoricoPaciente.SITUACAO_ATUALIZADA);
            h.setOcorridoEm(java.time.LocalDateTime.now());
            // Sem origem: lotação atual não comprova fato antigo.
            em.persist(h);
        });
        var comLegado = LegacyMigrationFixture.snapshot(fonte, colunas);
        assertThrows(ValidationException.class, () -> como(atorAd,
                () -> inicioLocal.iniciar(c.pessoa(), TipoAcompanhamento.AMBOS, null)));
        assertEquals(comLegado, LegacyMigrationFixture.snapshot(fonte, colunas));

        var outro = tx.execute(t -> {
            var p = new Paciente();
            p.setNome("Identidade sintética com fato local");
            p.setCpf(UUID.randomUUID().toString());
            p.setCns(UUID.randomUUID().toString());
            em.persist(p);
            var a = new Agendamento();
            a.setPaciente(p);
            a.setUsuario(em.find(Usuario.class, atorAd.getId()));
            a.setDataAgendamento(java.time.LocalDate.of(2026, 11, 23));
            a.setHoraAtendimento(java.time.LocalTime.of(9, 0));
            a.setSituacaoAtendimento(SituacaoAtendimento.FALTOU);
            a.registrarUnidadeDeOrigem(a.getUsuario());
            em.persist(a);
            return p.getIdPublico();
        });
        var comFatoLocal = LegacyMigrationFixture.snapshot(fonte, colunas);
        assertThrows(ValidationException.class, () -> como(atorAd,
                () -> inicioLocal.iniciar(outro, TipoAcompanhamento.INDIVIDUAL, null)));
        assertEquals(comFatoLocal, LegacyMigrationFixture.snapshot(fonte, colunas));
    }

    @Test
    void criaIdentidadeEInicializaUsfOuCapsNaMesmaTransacaoEProjetaSemEscrita() {
        var atores = tx.execute(t -> List.of(usuario(UnidadeAtuacao.USF, usf(), TipoUsuario.ADMINISTRADOR),
                usuario(UnidadeAtuacao.CAPS_I, null, TipoUsuario.PROFISSIONAL)));
        for (var ator : atores) {
            var ids = tx.execute(t -> {
                var pessoa = pessoa();
                var inicio = como(ator, () -> inicioLocal.iniciar(pessoa.getIdPublico(),
                        TipoAcompanhamento.INDIVIDUAL, ator.getIdPublico()));
                return List.of(pessoa.getIdPublico(), inicio.vinculo());
            });
            var colunas = LegacyMigrationFixture.columns(fonte);
            var antes = LegacyMigrationFixture.snapshot(fonte, colunas);
            var p = como(ator, () -> projecoes.projetar(ids.getFirst(), ids.getLast(), LocalDate.now()));
            assertEquals(Situacao.INICIALIZACAO_AUDITADA, p.evidenciaStatus());
            assertEquals(StatusPaciente.ATIVO, p.status().valor().orElseThrow());
            assertFalse(PlanoIndicadoresVinculo.preparar(p).aplicacaoAutorizada());
            assertEquals(antes, LegacyMigrationFixture.snapshot(fonte, colunas));
        }
    }

    @Test
    void preservaCadaCicloComMotivoAutorHorarioERevisaoLocal() {
        var c = tx.execute(t -> preparar());
        var a = c.a();
        var anterior = como(a.admin(), () -> projecoes.projetar(c.pessoa(), a.publico(), LocalDate.now()));
        assertEquals(Situacao.SEM_COMPROVACAO, anterior.evidenciaStatus());
        Integer versao = a.versao();
        for (int i = 0; i < 2; i++) {
            var v = versao;
            var fechado = como(c.profissional(), () -> servico.encerrar(c.pessoa(), a.publico(), v,
                    new EncerramentoPacienteDTO(MotivoEncerramento.OUTRO, StatusPaciente.OUTRO, "Complemento local")));
            var projecao = como(a.admin(), () -> projecoes.projetar(c.pessoa(), a.publico(), LocalDate.now()));
            assertEquals(Situacao.TRANSICAO_AUDITADA, projecao.evidenciaStatus());
            assertEquals(StatusPaciente.OUTRO, projecao.status().valor().orElseThrow());
            versao = como(a.admin(), () -> servico.reativar(c.pessoa(), a.publico(), fechado.versao(),
                    "Motivo local")).versao();
        }
        var reaberto = como(a.admin(), () -> projecoes.projetar(c.pessoa(), a.publico(), LocalDate.now()));
        assertEquals(Situacao.TRANSICAO_AUDITADA, reaberto.evidenciaStatus());
        assertEquals(StatusPaciente.ATIVO, reaberto.status().valor().orElseThrow());
        tx.executeWithoutResult(t -> {
            var lista = eventos(a.id());
            var leitor = AuditReaderFactory.get(em);
            var revisoes = leitor.getRevisions(VinculoAssistencial.class, a.id());
            var trilha = historicos.findLocaisComOrigemComprovada(c.pessoa(), a.unidade());
            assertEquals(4, lista.size());
            for (int i = 0; i < lista.size(); i++) {
                var e = lista.get(i);
                var encerramento = i % 2 == 0;
                assertEquals(encerramento ? "OUTRO" : "Motivo local", e.getMotivo());
                assertEquals(encerramento ? "Complemento local" : null, e.getDescricao());
                assertEquals(encerramento ? c.profissional().getId() : a.admin().getId(), e.getAtor().getId());
                assertEquals(List.of(revisoes.get(i + 1)), leitor.getRevisions(EventoEstadoClinicoVinculo.class, e.getId()));
                assertTrue(trilha.stream().anyMatch(h -> h.getOcorridoEm().equals(e.getOcorridoEm())
                        && h.getProfissional().getId().equals(e.getAtor().getId())));
            }
            assertTrue(eventos(c.b().id()).isEmpty());
        });
    }

    @Test
    void inicializacoesConcorrentesCriamUmEventoPorUnidade() throws Exception {
        var a = tx.execute(t -> usuario(UnidadeAtuacao.USF, usf(), TipoUsuario.ADMINISTRADOR));
        var b = tx.execute(t -> usuario(UnidadeAtuacao.USF, usf(), TipoUsuario.ADMINISTRADOR));
        var pessoa = tx.execute(t -> pessoa().getIdPublico());
        var inicio = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(3)) {
            var um = executor.submit(() -> inicializarApos(inicio, a, pessoa));
            var dois = executor.submit(() -> inicializarApos(inicio, a, pessoa));
            var tres = executor.submit(() -> inicializarApos(inicio, b, pessoa));
            inicio.countDown();
            var primeira = um.get(20, TimeUnit.SECONDS);
            var segunda = dois.get(20, TimeUnit.SECONDS);
            var externa = tres.get(20, TimeUnit.SECONDS);
            assertEquals(primeira.vinculo(), segunda.vinculo());
            assertNotEquals(primeira.situacao(), segunda.situacao());
            assertNotEquals(primeira.vinculo(), externa.vinculo());
            assertEquals(InicioAcompanhamentoLocalEnsaioService.Situacao.CRIADO_NO_ENSAIO, externa.situacao());
            tx.executeWithoutResult(t -> {
                for (var criado : List.of(primeira, externa)) {
                    var v = vinculos.findByIdPublico(criado.vinculo()).orElseThrow();
                    assertEquals(1, eventos(v.getId()).size());
                    assertEquals(1, historicos.findLocaisComOrigemComprovada(pessoa, v.getUnidadeChave()).size());
                }
            });
        }
    }

    @Test
    void encerramentoReativacaoEConfiguracaoDisputamAMesmaVersao() throws Exception {
        var c = tx.execute(t -> preparar());
        var a = c.a();
        var fechado = como(a.admin(), () -> servico.encerrar(c.pessoa(), a.publico(), a.versao(), alta()));
        List<Supplier<EstadoClinicoLocalEnsaioService.Resultado>> comandos = List.of(
                () -> como(c.profissional(), () -> servico.encerrar(c.pessoa(), a.publico(), fechado.versao(),
                        new EncerramentoPacienteDTO(MotivoEncerramento.OBITO, StatusPaciente.OBITO, null))),
                () -> como(a.admin(), () -> servico.reativar(c.pessoa(), a.publico(), fechado.versao(), "Retorno")),
                () -> como(a.admin(), () -> servico.configurar(c.pessoa(), a.publico(), fechado.versao(),
                        TipoAcompanhamento.AMBOS, null)));
        var inicio = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(3)) {
            var tentativas = comandos.stream().map(comando -> executor.submit(() -> {
                assertTrue(inicio.await(10, TimeUnit.SECONDS));
                try {
                    return comando.get();
                } catch (ValidationException conflito) {
                    assertTrue(conflito.getMessage().contains("Versão local divergente"));
                    return null;
                }
            })).toList();
            inicio.countDown();
            int confirmadas = 0;
            for (var tentativa : tentativas) {
                if (tentativa.get(20, TimeUnit.SECONDS) != null) confirmadas++;
            }
            assertEquals(1, confirmadas);
        }
        tx.executeWithoutResult(t -> {
            assertEquals(2, historicos.findLocaisComOrigemComprovada(c.pessoa(), a.unidade()).size());
            assertEquals(StatusPaciente.ATIVO, em.find(VinculoAssistencial.class, c.b().id()).getStatusPaciente());
            assertEquals(StatusPaciente.ATIVO, em.find(VinculoAssistencial.class, c.caps().id()).getStatusPaciente());
        });
    }

    @Test
    void escritorSemOProtocoloPodeGravarFatoEnquantoInicializacaoEstaAberta() throws Exception {
        var ator = tx.execute(t -> usuario(UnidadeAtuacao.CAPS_AD, null, TipoUsuario.ADMINISTRADOR));
        var escritor = tx.execute(t -> usuario(UnidadeAtuacao.CAPS_AD, null, TipoUsuario.PROFISSIONAL));
        var pessoa = tx.execute(t -> pessoa());
        try (var executor = Executors.newSingleThreadExecutor()) {
            var inicio = tx.execute(t -> {
                var criado = como(ator, () -> inicioLocal.iniciar(pessoa.getIdPublico(), TipoAcompanhamento.AMBOS, null));
                var fato = executor.submit(() -> tx.execute(status -> {
                    var p = em.find(Paciente.class, pessoa.getId());
                    return agenda(p, escritor, true).getId();
                }));
                assertDoesNotThrow(() -> fato.get(10, TimeUnit.SECONDS));
                return criado;
            });
            var projetado = como(ator, () -> projecoes.projetar(pessoa.getIdPublico(), inicio.vinculo(), LocalDate.now()));
            assertEquals(1, projetado.faltas().valor().orElseThrow());
            assertEquals(ProjecaoClinicaVinculoService.Comparacao.DIVERGE, projetado.faltas().estadoLocalArmazenado());
        }
    }

    @Test
    void escritorCoordenadoAnteriorAoInicioImpedeInicializacaoComZeroFaltas() throws Exception {
        var ator = tx.execute(t -> usuario(UnidadeAtuacao.CAPS_AD, null, TipoUsuario.ADMINISTRADOR));
        var escritor = tx.execute(t -> usuario(UnidadeAtuacao.CAPS_AD, null, TipoUsuario.PROFISSIONAL));
        var pessoa = tx.execute(t -> pessoa());
        var travaObtida = new CountDownLatch(1);
        var inicioSolicitado = new CountDownLatch(1);
        var liberarEscritor = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var fato = executor.submit(() -> tx.execute(t -> {
                var p = pacientes.findByIdPublicoForUpdate(pessoa.getIdPublico()).orElseThrow();
                travaObtida.countDown();
                try {
                    assertTrue(liberarEscritor.await(10, TimeUnit.SECONDS));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
                return agenda(p, escritor, true).getId();
            }));
            try {
                assertTrue(travaObtida.await(10, TimeUnit.SECONDS));
                var inicializacao = executor.submit(() -> {
                    inicioSolicitado.countDown();
                    return assertThrows(ValidationException.class, () -> como(ator,
                            () -> inicioLocal.iniciar(pessoa.getIdPublico(), TipoAcompanhamento.AMBOS, null)));
                });
                assertTrue(inicioSolicitado.await(10, TimeUnit.SECONDS));
                // O início não deve concluir enquanto a identidade está bloqueada.
                assertThrows(java.util.concurrent.TimeoutException.class,
                        () -> inicializacao.get(200, TimeUnit.MILLISECONDS));
                liberarEscritor.countDown();
                assertNotNull(fato.get(10, TimeUnit.SECONDS));
                assertEquals("Não foi possível comprovar acompanhamento novo.",
                        inicializacao.get(10, TimeUnit.SECONDS).getMessage());
            } finally {
                liberarEscritor.countDown();
            }
        }
        tx.executeWithoutResult(t -> {
            assertTrue(vinculos.findByPacienteId(pessoa.getId()).isEmpty());
            assertEquals(1L, em.createQuery("select count(a) from Agendamento a where a.paciente.id = :id",
                    Long.class).setParameter("id", pessoa.getId()).getSingleResult());
            assertTrue(historicos.findLocaisComOrigemComprovada(pessoa.getIdPublico(), "CAPS_AD").isEmpty());
        });
    }

    @Test
    void rollbackReverteIdentidadeVinculoHistoricoEventoEAuditoria() {
        var c = tx.execute(t -> preparar());
        var ator = c.a().admin();
        var colunas = LegacyMigrationFixture.columns(fonte);
        var antes = LegacyMigrationFixture.snapshot(fonte, colunas);
        assertThrows(IllegalStateException.class, () -> tx.executeWithoutResult(t -> {
            var pessoa = pessoa();
            como(ator, () -> inicioLocal.iniciar(pessoa.getIdPublico(), TipoAcompanhamento.INDIVIDUAL, null));
            como(ator, () -> servico.encerrar(c.pessoa(), c.a().publico(), c.a().versao(), alta()));
            em.flush();
            throw new IllegalStateException("Falha sintética após persistência");
        }));
        assertEquals(antes, LegacyMigrationFixture.snapshot(fonte, colunas));
    }

    @Test
    void recusaAtorTransferidoOuSemPerfilEReferenciaTransferidaOuInelegivel() {
        var c = tx.execute(t -> preparar());
        var a = c.a().admin();
        var referencia = c.profissional();
        var pessoa = tx.execute(t -> pessoa().getIdPublico());
        tx.executeWithoutResult(t -> em.find(Usuario.class, referencia.getId()).setTipoUsuario(TipoUsuario.RECEPCAO));
        assertThrows(ResourceNotFoundException.class, () -> como(a,
                () -> inicioLocal.iniciar(pessoa, TipoAcompanhamento.INDIVIDUAL, referencia.getIdPublico())));
        tx.executeWithoutResult(t -> {
            var u = em.find(Usuario.class, referencia.getId());
            u.setTipoUsuario(TipoUsuario.PROFISSIONAL);
            u.setUsfAtuacao(em.find(Usuario.class, c.b().admin().getId()).getUsfAtuacao());
        });
        assertThrows(ResourceNotFoundException.class, () -> como(a,
                () -> inicioLocal.iniciar(pessoa, TipoAcompanhamento.INDIVIDUAL, referencia.getIdPublico())));
        tx.executeWithoutResult(t -> em.find(Usuario.class, a.getId()).setTipoUsuario(TipoUsuario.RECEPCAO));
        assertThrows(AccessDeniedException.class, () -> como(a,
                () -> servico.encerrar(c.pessoa(), c.a().publico(), c.a().versao(), alta())));
        tx.executeWithoutResult(t -> {
            var u = em.find(Usuario.class, a.getId());
            u.setTipoUsuario(TipoUsuario.ADMINISTRADOR);
            u.setUsfAtuacao(em.find(Usuario.class, c.b().admin().getId()).getUsfAtuacao());
        });
        assertThrows(AccessDeniedException.class, () -> como(a,
                () -> inicioLocal.iniciar(pessoa, TipoAcompanhamento.INDIVIDUAL, null)));
    }

    @Test
    void fatosInconclusivosEParticipacaoLocalImpedemInicializacao() {
        var a = tx.execute(t -> usuario(UnidadeAtuacao.CAPS_AD, null, TipoUsuario.ADMINISTRADOR));
        var b = tx.execute(t -> usuario(UnidadeAtuacao.CAPS_II, null, TipoUsuario.PROFISSIONAL));
        var c = tx.execute(t -> usuario(UnidadeAtuacao.CAPS_I, null, TipoUsuario.PROFISSIONAL));
        var pessoas = tx.execute(t -> {
            var semOrigem = pessoa();
            agenda(semOrigem, b, false);
            var grupoLocal = pessoa();
            participacao(grupoLocal, a, true);
            var grupoInconclusivo = pessoa();
            participacao(grupoInconclusivo, b, false);
            var historicoLocal = pessoa();
            historico(historicoLocal, a, null);
            var conflito = pessoa();
            historico(conflito, b, agenda(conflito, c, true));
            return List.of(semOrigem, grupoLocal, grupoInconclusivo, historicoLocal, conflito).stream()
                    .map(Paciente::getIdPublico).toList();
        });
        var colunas = LegacyMigrationFixture.columns(fonte);
        var antes = LegacyMigrationFixture.snapshot(fonte, colunas);
        for (var id : pessoas) {
            assertThrows(ValidationException.class, () -> como(a,
                    () -> inicioLocal.iniciar(id, TipoAcompanhamento.INDIVIDUAL, null)));
        }
        assertEquals(antes, LegacyMigrationFixture.snapshot(fonte, colunas));
    }

    private List<EventoEstadoClinicoVinculo> eventos(Long id) {
        return em.createQuery("select e from EventoEstadoClinicoVinculo e where e.vinculo.id = :id order by e.id",
                EventoEstadoClinicoVinculo.class).setParameter("id", id).getResultList();
    }

    private InicioAcompanhamentoLocalEnsaioService.Resultado inicializarApos(CountDownLatch inicio,
            Usuario ator, UUID pessoa) throws InterruptedException {
        assertTrue(inicio.await(10, TimeUnit.SECONDS));
        return como(ator, () -> inicioLocal.iniciar(pessoa, TipoAcompanhamento.INDIVIDUAL, null));
    }

    private Paciente pessoa() {
        var p = new Paciente();
        p.setNome("Identidade sintética");
        p.setCpf(UUID.randomUUID().toString());
        p.setCns(UUID.randomUUID().toString());
        em.persist(p);
        return p;
    }

    private Agendamento agenda(Paciente pessoa, Usuario ator, boolean comOrigem) {
        var a = new Agendamento();
        a.setPaciente(pessoa);
        a.setUsuario(em.find(Usuario.class, ator.getId()));
        a.setDataAgendamento(LocalDate.of(2026, 11, 23));
        a.setHoraAtendimento(java.time.LocalTime.of(9, 0));
        a.setSituacaoAtendimento(SituacaoAtendimento.FALTOU);
        if (comOrigem) a.registrarUnidadeDeOrigem(a.getUsuario());
        em.persist(a);
        return a;
    }

    private void participacao(Paciente pessoa, Usuario ator, boolean comOrigem) {
        var g = new GrupoTerapeutico();
        g.setCoordenador(em.find(Usuario.class, ator.getId()));
        if (comOrigem) g.registrarUnidadeDeOrigem(g.getCoordenador());
        em.persist(g);
        var sessao = new SessaoGrupo();
        sessao.setGrupo(g);
        sessao.setDataSessao(LocalDate.of(2026, 11, 23));
        sessao.setHorario(java.time.LocalTime.of(8, 0));
        sessao.setStatus(StatusSessaoGrupo.AGENDADA);
        if (comOrigem) sessao.registrarUnidadeDeOrigemDoGrupo();
        em.persist(sessao);
        var participante = new SessaoGrupoParticipante();
        participante.setPaciente(pessoa);
        participante.setSessaoGrupo(sessao);
        em.persist(participante);
    }

    private void historico(Paciente pessoa, Usuario ator, Agendamento agenda) {
        var h = new HistoricoPaciente();
        h.setPaciente(pessoa);
        h.setProfissional(em.find(Usuario.class, ator.getId()));
        h.setOcorridoEm(LocalDateTime.now());
        h.setTipo(TipoEventoHistoricoPaciente.SITUACAO_ATUALIZADA);
        h.registrarOrigemDeAcaoLocal(h.getProfissional());
        h.setAgendamento(agenda);
        em.persist(h);
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
