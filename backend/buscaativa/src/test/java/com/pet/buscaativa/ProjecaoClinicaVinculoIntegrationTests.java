package com.pet.buscaativa;

import static com.pet.buscaativa.services.impl.ProjecaoClinicaVinculoService.Comparacao.COINCIDE;
import static com.pet.buscaativa.services.impl.ProjecaoClinicaVinculoService.Comparacao.DIVERGE;
import static com.pet.buscaativa.services.impl.ProjecaoClinicaVinculoService.Comparacao.INCONCLUSIVA;
import static com.pet.buscaativa.services.impl.ProjecaoClinicaVinculoService.Motivo.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.verifyNoInteractions;

import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import javax.sql.DataSource;

import org.aopalliance.intercept.MethodInterceptor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

import com.pet.buscaativa.alertas.EmailService;
import com.pet.buscaativa.alertas.RelatorioEmailBuilder;
import com.pet.buscaativa.entities.Agendamento;
import com.pet.buscaativa.entities.DecisaoRevisaoProveniencia;
import com.pet.buscaativa.entities.GrupoTerapeutico;
import com.pet.buscaativa.entities.HistoricoPaciente;
import com.pet.buscaativa.entities.Paciente;
import com.pet.buscaativa.entities.PendenciaRevisaoProveniencia;
import com.pet.buscaativa.entities.SessaoGrupo;
import com.pet.buscaativa.entities.SessaoGrupoParticipante;
import com.pet.buscaativa.entities.UsfReferencia;
import com.pet.buscaativa.entities.Usuario;
import com.pet.buscaativa.entities.VinculoAssistencial;
import com.pet.buscaativa.entities.enums.*;
import com.pet.buscaativa.migration.PlanoIndicadoresVinculo;
import com.pet.buscaativa.migration.PlanoIndicadoresVinculo.Campo;
import com.pet.buscaativa.repositories.HistoricoPacienteRepository;
import com.pet.buscaativa.services.ExcelRelatorioService;
import com.pet.buscaativa.services.PdfRendererService;
import com.pet.buscaativa.services.exceptions.ResourceNotFoundException;
import com.pet.buscaativa.services.impl.ComparadorSelecaoBuscaAtivaVinculo.Decisao;
import com.pet.buscaativa.services.impl.ComparadorSelecaoBuscaAtivaVinculo.Motivo;
import com.pet.buscaativa.services.impl.ProjecaoClinicaVinculoService;
import com.pet.buscaativa.services.impl.ProjecaoClinicaVinculoService.Projecao;
import com.pet.buscaativa.services.impl.RegistroDecisaoRevisaoProvenienciaService;
import com.pet.buscaativa.services.impl.SolicitacaoRevisaoProvenienciaService;
import com.pet.buscaativa.services.impl.SusApiAdapter;

import jakarta.persistence.EntityManager;

@ActiveProfiles("diagnostico-migracao")
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:projecao-clinica-63;DB_CLOSE_DELAY=-1")
class ProjecaoClinicaVinculoIntegrationTests extends SecurityTestSupport {
    static final LocalDate REFERENCIA = LocalDate.of(2026, 11, 23);

    @Autowired EntityManager em;
    @Autowired TransactionTemplate transacoes;
    @Autowired DataSource fonte;
    @Autowired ProjecaoClinicaVinculoService projecoes;
    @Autowired SolicitacaoRevisaoProvenienciaService solicitacoes;
    @Autowired RegistroDecisaoRevisaoProvenienciaService decisoes;
    @Autowired PdfRendererService pdf;
    @Autowired ExcelRelatorioService excel;
    @Autowired RelatorioEmailBuilder email;
    @MockitoBean SusApiAdapter sus;
    @MockitoBean EmailService envio;
    @Autowired BarreiraHistorico barreira;
    @TempDir Path temp;

    private record Pausa(UUID paciente, String unidade, CountDownLatch leitura, CountDownLatch escrita) {}

    static class BarreiraHistorico {
        volatile Pausa pausa;
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Concorrencia {
        @Bean
        BarreiraHistorico barreiraHistorico() {
            return new BarreiraHistorico();
        }

        @Bean
        @Primary
        HistoricoPacienteRepository historicoSincronizado(
                @Qualifier("historicoPacienteRepository") HistoricoPacienteRepository repositorio,
                BarreiraHistorico barreira) {
            var proxy = new ProxyFactory(repositorio);
            proxy.addAdvice((MethodInterceptor) invocacao -> {
                var pausa = barreira.pausa;
                if (pausa != null && invocacao.getMethod().getName().equals("findLocaisComOrigemComprovada")
                        && pausa.paciente().equals(invocacao.getArguments()[0])
                        && pausa.unidade().equals(invocacao.getArguments()[1])) {
                    pausa.leitura().countDown();
                    assertTrue(pausa.escrita().await(15, TimeUnit.SECONDS));
                }
                return invocacao.proceed();
            });
            return (HistoricoPacienteRepository) proxy.getProxy();
        }
    }

    private record Local(Usuario ator, Long id, UUID publico, String unidade) {}
    private record Cenario(UUID paciente, Local usfA, Local usfB, Local capsAd, Local capsII, Usuario capsI,
            Long faltaCorrigida, Long agendaAd, UUID outroPaciente, Local vinculoSemStatus,
            Local vinculoPendente, Long agendaUsfA, Long sessaoNaoProvada) {}

    @Test
    void projetaQuatroVinculosEComparaConsumidoresSemEfeitosOperacionais() throws Exception {
        var c = transacoes.execute(tx -> preparar());
        confirmarEstados(c);
        como(c.capsAd().ator(), () -> {
            var solicitacao = solicitacoes.solicitar(c.paciente(), PendenciaRevisaoProveniencia.TipoRegistro.AGENDAMENTO,
                    c.agendaAd(), "Conferir evidências da remarcação local na fixture.");
            return decisoes.registrar(c.paciente(), solicitacao, DecisaoRevisaoProveniencia.Decisao.APROVAR,
                    "Deliberação administrativa da prova sintética de origem.");
        });
        var colunas = LegacyMigrationFixture.columns(fonte);
        var antes = LegacyMigrationFixture.snapshot(fonte, colunas);
        verificarTrilhas(antes);

        assertThrows(AccessDeniedException.class, () -> projecoes.projetar(c.paciente(), c.usfA().publico(), REFERENCIA));
        for (var local : List.of(c.usfA(), c.usfB(), c.capsAd(), c.capsII())) {
            for (var outro : List.of(c.usfA(), c.usfB(), c.capsAd(), c.capsII())) {
                if (local.equals(outro)) continue;
                assertThrows(ResourceNotFoundException.class, () -> projetar(projecoes, c.paciente(), local, outro.publico()));
            }
        }
        assertThrows(ResourceNotFoundException.class,
                () -> como(c.capsI(), () -> projecoes.projetar(c.paciente(), c.capsAd().publico(), REFERENCIA)));
        assertThrows(ResourceNotFoundException.class,
                () -> projetar(projecoes, c.outroPaciente(), c.capsAd(), c.capsAd().publico()));

        var a = projetar(projecoes, c.paciente(), c.usfA());
        var b = projetar(projecoes, c.paciente(), c.usfB());
        var ad = projetar(projecoes, c.paciente(), c.capsAd());
        var ii = projetar(projecoes, c.paciente(), c.capsII());
        verificarQuatroVinculos(c, a, b, ad, ii);

        var semStatus = projetar(projecoes, c.outroPaciente(), c.vinculoSemStatus());
        assertTrue(semStatus.status().motivos().contains(STATUS_AUSENTE));
        assertTrue(semStatus.pendencias().motivos().contains(PENDENCIA_AUSENTE));
        assertTrue(semStatus.faltas().motivos().contains(ORIGEM_HISTORICA_INDETERMINADA));
        assertTrue(semStatus.historico().isEmpty());
        assertTrue(semStatus.agenda().isEmpty());

        var pendente = projetar(projecoes, c.outroPaciente(), c.vinculoPendente());
        assertEquals(Motivo.REVISAO_HISTORICA, pendente.buscaAtiva().valor().orElseThrow().motivo());
        assertTrue(pendente.visita().motivos().contains(INDICADORES_PENDENTES));
        assertEquals(Decisao.INCLUIR, pendente.buscaAtiva().valor().orElseThrow().decisao());

        ProjecaoContratosConsumidores.verificar(a, b, ad, ii, pendente, pdf, excel, email);
        assertEquals(a, projetar(projecoes, c.paciente(), c.usfA()));
        assertEquals(antes, LegacyMigrationFixture.snapshot(fonte, colunas));

        verificarRestauracao(c, List.of(a, b, ad, ii), colunas, antes);
        verificarConcorrenciaECorrecao(c, a, colunas, antes);
        verifyNoInteractions(envio);
    }

    @Test
    void projetaBuscaAtivaComUmCriterioConfirmadoSemCompletarOOutroIndicador() {
        var c = transacoes.execute(tx -> preparar());
        confirmarEstados(c);
        transacoes.executeWithoutResult(tx -> {
            campo(vinculo(c.capsAd()), "tipoAcompanhamento", null);
            var paciente = vinculo(c.usfA()).getPaciente();
            // A reserva sem frequência tem origem local, mas falta o agendamento original.
            agenda(paciente, c.usfA().ator(), 1, SituacaoAtendimento.REMARCADO, null, true);
        });

        var vermelho = projetar(projecoes, c.paciente(), c.usfA());
        assertEquals(ClassificacaoRisco.VERMELHO, vermelho.risco().valor().orElseThrow());
        assertTrue(vermelho.visita().valor().isEmpty());
        assertFalse(vermelho.visita().conclusiva());
        assertEquals(Decisao.INCLUIR, vermelho.buscaAtiva().valor().orElseThrow().decisao());
        assertEquals(Motivo.RISCO_VERMELHO, vermelho.buscaAtiva().valor().orElseThrow().motivo());

        var visita = projetar(projecoes, c.paciente(), c.capsAd());
        assertTrue(visita.risco().valor().isEmpty());
        assertTrue(visita.risco().motivos().contains(TIPO_AUSENTE));
        assertTrue(visita.visita().valor().orElseThrow());
        assertEquals(Decisao.INCLUIR, visita.buscaAtiva().valor().orElseThrow().decisao());
        assertEquals(Motivo.VISITA_CONFIRMADA, visita.buscaAtiva().valor().orElseThrow().motivo());
    }

    @Test
    void preparaPlanoDeIndicadoresSemCopiarEstadoGlobalNemEscreverNoBanco() {
        var c = transacoes.execute(tx -> preparar());
        confirmarEstados(c);
        var colunas = LegacyMigrationFixture.columns(fonte);
        var antes = LegacyMigrationFixture.snapshot(fonte, colunas);

        var a = PlanoIndicadoresVinculo.preparar(projetar(projecoes, c.paciente(), c.usfA()));
        var ad = PlanoIndicadoresVinculo.preparar(projetar(projecoes, c.paciente(), c.capsAd()));
        assertEquals(c.usfA().publico(), a.vinculo());
        assertEquals(c.capsAd().publico(), ad.vinculo());
        assertEquals(c.usfA().unidade(), a.unidade());
        assertEquals(c.capsAd().unidade(), ad.unidade());
        assertEquals(REFERENCIA, a.dataReferencia());
        assertTrue(a.bloqueios().isEmpty());
        assertTrue(ad.bloqueios().isEmpty());
        assertEquals(5, a.candidato().orElseThrow().faltas());
        assertEquals(2, ad.candidato().orElseThrow().faltas());
        assertEquals(REFERENCIA.minusDays(10), a.candidato().orElseThrow().ultimaPresenca().orElseThrow());
        assertEquals(REFERENCIA.minusDays(7), ad.candidato().orElseThrow().ultimaPresenca().orElseThrow());
        assertEquals(ClassificacaoRisco.VERMELHO, a.candidato().orElseThrow().risco());
        assertEquals(ClassificacaoRisco.AMARELO, ad.candidato().orElseThrow().risco());
        assertTrue(a.candidato().orElseThrow().gatilhoVisita());
        assertTrue(ad.candidato().orElseThrow().gatilhoVisita());

        var b = PlanoIndicadoresVinculo.preparar(projetar(projecoes, c.paciente(), c.usfB()));
        assertTrue(b.candidato().isEmpty());
        assertTrue(b.bloqueios().get(Campo.STATUS).motivos().contains(STATUS_SEM_TRANSICAO_AUDITADA));
        assertTrue(b.bloqueios().containsKey(Campo.ACOMPANHAMENTO));
        assertTrue(b.bloqueios().containsKey(Campo.PENDENCIAS));
        assertFalse(b.bloqueios().containsKey(Campo.ULTIMA_PRESENCA));

        var ii = PlanoIndicadoresVinculo.preparar(projetar(projecoes, c.paciente(), c.capsII()));
        assertTrue(ii.candidato().isEmpty());
        assertTrue(ii.bloqueios().get(Campo.FALTAS).motivos().contains(ORDEM_DE_FREQUENCIAS_INDETERMINADA));
        var legado = PlanoIndicadoresVinculo.preparar(projetar(projecoes, c.outroPaciente(), c.vinculoSemStatus()));
        assertTrue(legado.candidato().isEmpty());
        assertTrue(legado.bloqueios().get(Campo.FALTAS).motivos().contains(ORIGEM_HISTORICA_INDETERMINADA));
        var pendente = PlanoIndicadoresVinculo.preparar(projetar(projecoes, c.outroPaciente(), c.vinculoPendente()));
        assertTrue(pendente.candidato().isEmpty());
        assertTrue(pendente.bloqueios().get(Campo.PENDENCIAS).motivos().contains(INDICADORES_PENDENTES));

        for (var plano : List.of(a, ad, b, ii, legado, pendente)) {
            assertFalse(plano.aplicacaoAutorizada());
        }
        assertEquals(a, PlanoIndicadoresVinculo.preparar(projetar(projecoes, c.paciente(), c.usfA())));
        assertEquals(antes, LegacyMigrationFixture.snapshot(fonte, colunas));
        verifyNoInteractions(envio);
    }

    private void verificarQuatroVinculos(Cenario c, Projecao a, Projecao b, Projecao ad, Projecao ii) {
        assertEquals(c.usfA().publico(), a.vinculo());
        assertEquals(c.usfA().unidade(), a.unidade());
        assertEquals(REFERENCIA, a.dataReferencia());
        assertEquals(List.of(c.agendaUsfA()), a.historico().stream().map(h -> h.fonteId()).distinct().toList());
        assertEquals(2, a.historico().size()); // Dois registros de histórico para o mesmo fato de frequência.
        assertTrue(a.historico().get(0).id() > a.historico().get(1).id());
        assertEquals(4, a.agenda().size());
        assertTrue(a.agenda().stream().anyMatch(r -> r.id().equals(c.agendaUsfA())));
        assertFalse(a.agenda().stream().anyMatch(r -> r.id().equals(c.agendaAd())));
        assertEquals(2, a.grupos().size());
        assertEquals(1, a.inventario().totalPresencasComprovadas());
        assertEquals(5, a.inventario().totalFaltasComprovadas());
        assertEquals(5, a.faltas().valor().orElseThrow());
        assertEquals(COINCIDE, a.faltas().estadoLocalArmazenado());
        assertEquals(DIVERGE, a.faltas().referenciaGlobal());
        assertEquals(ClassificacaoRisco.VERMELHO, a.risco().valor().orElseThrow());
        assertTrue(a.visita().valor().orElseThrow());
        assertEquals(Motivo.RISCO_VERMELHO, a.buscaAtiva().valor().orElseThrow().motivo());

        assertTrue(b.agenda().isEmpty());
        assertTrue(b.grupos().isEmpty());
        assertTrue(b.historico().isEmpty());
        assertEquals(0, b.faltas().valor().orElseThrow());
        assertTrue(b.ultimaPresenca().conclusiva());
        assertTrue(b.ultimaPresenca().valor().isEmpty());
        assertEquals(INCONCLUSIVA, b.risco().referenciaGlobal());
        assertTrue(b.risco().motivos().containsAll(List.of(STATUS_SEM_TRANSICAO_AUDITADA, TIPO_AUSENTE, PENDENCIA_AUSENTE)));
        assertEquals(Decisao.INDETERMINADA, b.buscaAtiva().valor().orElseThrow().decisao());

        assertEquals(4, ad.agenda().size());
        assertTrue(ad.agenda().stream().anyMatch(r -> r.id().equals(c.agendaAd())));
        assertFalse(ad.agenda().stream().anyMatch(r -> r.id().equals(c.agendaUsfA())));
        assertTrue(ad.agenda().stream().anyMatch(e -> e.situacao() == SituacaoReserva.CANCELADA
                && e.frequencia() == FrequenciaAssistencial.PRESENTE));
        assertEquals(REFERENCIA.minusDays(7), ad.ultimaPresenca().valor().orElseThrow());
        assertTrue(ad.agenda().stream().anyMatch(e -> e.situacao() == SituacaoReserva.CANCELADA
                && e.frequencia() == FrequenciaAssistencial.NAO_REGISTRADA));
        assertTrue(ad.grupos().stream().allMatch(p -> p.frequencia().isEmpty()));
        assertEquals(2, ad.grupos().size());
        assertEquals(1, ad.historico().size());
        assertEquals(ProjecaoClinicaVinculoService.FonteHistorico.ACAO_LOCAL, ad.historico().getFirst().fonte());
        assertEquals(2, ad.faltas().valor().orElseThrow());
        assertEquals(ClassificacaoRisco.AMARELO, ad.risco().valor().orElseThrow());
        assertEquals(DIVERGE, ad.risco().estadoLocalArmazenado());
        assertEquals(DIVERGE, ad.risco().referenciaGlobal());
        assertTrue(ad.visita().valor().orElseThrow());
        assertEquals(Motivo.VISITA_CONFIRMADA, ad.buscaAtiva().valor().orElseThrow().motivo());

        assertEquals(2, ii.frequencias().eventosComOrigemLocal().size());
        assertEquals(ii.frequencias().eventosComOrigemLocal().getFirst().ocorridoEm(),
                ii.frequencias().eventosComOrigemLocal().getLast().ocorridoEm());
        assertTrue(ii.faltas().valor().isEmpty());
        assertTrue(ii.faltas().motivos().containsAll(
                List.of(ORDEM_DE_FREQUENCIAS_INDETERMINADA, FREQUENCIA_INCONSISTENTE)));
        assertFalse(ii.grupos().stream().anyMatch(g -> g.sessaoId().equals(c.sessaoNaoProvada())));
        assertEquals(1, ii.historico().size());
        assertEquals(ClassificacaoRisco.VERDE, ii.risco().valor().orElseThrow());
        assertEquals(Motivo.ACOMPANHAMENTO_NAO_ATIVO, ii.buscaAtiva().valor().orElseThrow().motivo());
    }

    private void verificarRestauracao(Cenario c, List<Projecao> esperadas,
            Map<String, List<String>> colunas,
            Map<String, List<Map<String, Object>>> antes) throws Exception {
        var script = temp.resolve("projecao.sql");
        LegacyMigrationFixture.backup(fonte, script);
        var copia = LegacyMigrationFixture.restore(temp.resolve("projecao-restaurada"), script);
        var somenteLeitura = new DriverManagerDataSource(copia.getUrl() + ";ACCESS_MODE_DATA=r",
                copia.getUsername(), copia.getPassword());
        CorrectiveMigrationApplication.application(somenteLeitura)
                .withPropertyValues("spring.profiles.active=test,diagnostico-migracao")
                .run(contexto -> {
                    assertNull(contexto.getStartupFailure());
                    var restaurada = contexto.getBean(ProjecaoClinicaVinculoService.class);
                    var locais = List.of(c.usfA(), c.usfB(), c.capsAd(), c.capsII());
                    assertEquals(esperadas, locais.stream().map(local -> projetar(restaurada, c.paciente(), local)).toList());
                });
        assertEquals(colunas, LegacyMigrationFixture.columns(copia));
        assertEquals(antes, LegacyMigrationFixture.snapshot(copia, colunas));
    }

    private void verificarConcorrenciaECorrecao(Cenario c, Projecao anterior,
            Map<String, List<String>> colunas,
            Map<String, List<Map<String, Object>>> antes) throws Exception {
        var leitorParado = new CountDownLatch(1);
        var escritorConfirmado = new CountDownLatch(1);
        barreira.pausa = new Pausa(c.paciente(), c.usfA().unidade(), leitorParado, escritorConfirmado);

        try (var executor = Executors.newSingleThreadExecutor()) {
            var leitura = executor.submit(() -> projetar(projecoes, c.paciente(), c.usfA()));
            try {
                assertTrue(leitorParado.await(15, TimeUnit.SECONDS));
                transacoes.executeWithoutResult(tx -> {
                    var corrigida = em.find(SessaoGrupoParticipante.class, c.faltaCorrigida());
                    corrigida.setStatusPresenca(StatusPresencaGrupo.PRESENTE);
                    var autor = em.getReference(Usuario.class, c.usfA().ator().getId());
                    historico(corrigida.getPaciente(), autor,
                            TipoEventoHistoricoPaciente.PARTICIPACAO_GRUPO_TERAPEUTICO, null, corrigida.getSessaoGrupo());
                });
            } finally {
                escritorConfirmado.countDown();
            }
            assertEquals(anterior, leitura.get(20, TimeUnit.SECONDS));
        } finally {
            barreira.pausa = null;
        }

        var depoisDaCorrecao = LegacyMigrationFixture.snapshot(fonte, colunas);
        assertNotEquals(antes.get("TB_SESSAO_GRUPO_PARTICIPANTE_AUD"), depoisDaCorrecao.get("TB_SESSAO_GRUPO_PARTICIPANTE_AUD"));
        assertNotEquals(antes.get("TB_HISTORICO_PACIENTE"), depoisDaCorrecao.get("TB_HISTORICO_PACIENTE"));
        var nova = projetar(projecoes, c.paciente(), c.usfA());
        assertEquals(0, nova.faltas().valor().orElseThrow());
        assertEquals(REFERENCIA.minusDays(2), nova.ultimaPresenca().valor().orElseThrow());
        assertFalse(nova.visita().valor().orElseThrow());
        assertEquals(ClassificacaoRisco.VERDE, nova.risco().valor().orElseThrow());
        assertEquals(Motivo.SEM_CRITERIO, nova.buscaAtiva().valor().orElseThrow().motivo());
        assertEquals(3, nova.historico().size());
        assertEquals(nova, projetar(projecoes, c.paciente(), c.usfA()));
        var novaReferencia = como(c.usfA().ator(), () ->
                projecoes.projetar(c.paciente(), c.usfA().publico(), REFERENCIA.plusDays(14)));
        assertEquals(nova.frequencias(), novaReferencia.frequencias());
        assertEquals(ClassificacaoRisco.AMARELO, novaReferencia.risco().valor().orElseThrow());
        assertEquals(depoisDaCorrecao, LegacyMigrationFixture.snapshot(fonte, colunas));
    }

    private Cenario preparar() {
        var p = pessoa("Identidade sintética compartilhada");
        p.setCapsReferencia(CapsEnum.CAPS_I);
        p.setStatusPaciente(StatusPaciente.ATIVO);
        p.setTipoAcompanhamento(TipoAcompanhamento.INDIVIDUAL);
        p.setCountFaltas(8);
        p.setIndicadoresPendentes(false);
        p.setClassificacaoRisco(ClassificacaoRisco.VERMELHO);

        var a = local(p, UnidadeAtuacao.USF, "A");
        var b = local(p, UnidadeAtuacao.USF, "B");
        var ad = local(p, UnidadeAtuacao.CAPS_AD, null);
        var ii = local(p, UnidadeAtuacao.CAPS_II, null);
        var i = ator(UnidadeAtuacao.CAPS_I, null);

        var presenca = agenda(p, a.ator(), 10, SituacaoAtendimento.PRESENTE, null, true);
        remarcacoes(p, a.ator(), 3);
        var grupoA = grupo(a.ator());
        participacao(p, grupoA, a.ator(), 9, StatusSessaoGrupo.REALIZADA, StatusPresencaGrupo.FALTOU, true);
        var correcao = participacao(p, grupoA, a.ator(), 2, StatusSessaoGrupo.REALIZADA, StatusPresencaGrupo.FALTOU, true);
        historico(p, a.ator(), TipoEventoHistoricoPaciente.PRESENCA, presenca, null);
        historico(p, a.ator(), TipoEventoHistoricoPaciente.PRESENCA, presenca, null);

        var ultimaAd = remarcacoes(p, ad.ator(), 2);
        agenda(p, ad.ator(), 1, SituacaoAtendimento.CANCELADO, null, true);
        var canceladaComPresenca = agenda(p, ad.ator(), 7, SituacaoAtendimento.CANCELADO, null, true);
        canceladaComPresenca.registrarFatoEmReservaCancelada(FrequenciaAssistencial.PRESENTE);
        var grupoAd = grupo(ad.ator());
        participacao(p, grupoAd, ad.ator(), 9, StatusSessaoGrupo.AGENDADA, StatusPresencaGrupo.NAO_REGISTRADA, false);
        participacao(p, grupoAd, ad.ator(), 8, StatusSessaoGrupo.CANCELADA, StatusPresencaGrupo.NAO_REGISTRADA, false);
        historico(p, ad.ator(), TipoEventoHistoricoPaciente.BUSCA_ATIVA, null, null);

        agenda(p, ii.ator(), 5, SituacaoAtendimento.PRESENTE, null, true);
        var grupoII = grupo(ii.ator());
        var comFalta = participacao(p, grupoII, ii.ator(), 5, StatusSessaoGrupo.REALIZADA, StatusPresencaGrupo.FALTOU, true);
        var semProva = participacao(p, grupoII, ii.ator(), 7, StatusSessaoGrupo.REALIZADA, StatusPresencaGrupo.PRESENTE, false);
        historico(p, ii.ator(), TipoEventoHistoricoPaciente.FALTA, null, comFalta.getSessaoGrupo());
        historico(p, ii.ator(), TipoEventoHistoricoPaciente.PARTICIPACAO_GRUPO_TERAPEUTICO, null, semProva.getSessaoGrupo());

        var p2 = pessoa("Identidade sintética com legado");
        var semStatus = local(p2, ii.ator());
        var pendente = local(p2, ad.ator());
        agenda(p2, ii.ator(), 4, SituacaoAtendimento.FALTOU, null, false);
        return new Cenario(p.getIdPublico(), a, b, ad, ii, i, correcao.getId(),
                ultimaAd.getId(), p2.getIdPublico(), semStatus, pendente, presenca.getId(), semProva.getSessaoGrupo().getId());
    }

    private void confirmarEstados(Cenario c) {
        transacoes.executeWithoutResult(tx -> {
            for (var local : List.of(c.usfA(), c.capsAd(), c.capsII(), c.vinculoPendente()))
                campo(vinculo(local), "statusPaciente", StatusPaciente.INATIVO);
            campo(vinculo(c.vinculoSemStatus()), "statusPaciente", null);
        });
        transacoes.executeWithoutResult(tx -> {
            estadoAtivo(c.usfA(), TipoAcompanhamento.AMBOS, false, 5, ClassificacaoRisco.VERMELHO);
            estadoAtivo(c.capsAd(), TipoAcompanhamento.AMBOS, false, 2, ClassificacaoRisco.VERMELHO);
            estadoAtivo(c.vinculoPendente(), TipoAcompanhamento.INDIVIDUAL, true, 5, ClassificacaoRisco.VERMELHO);
        });
    }

    private void estadoAtivo(Local local, TipoAcompanhamento tipo, boolean pendente, int faltas, ClassificacaoRisco risco) {
        var v = vinculo(local);
        campo(v, "statusPaciente", StatusPaciente.ATIVO);
        campo(v, "tipoAcompanhamento", tipo);
        campo(v, "indicadoresPendentes", pendente);
        campo(v, "countFaltas", faltas);
        campo(v, "classificacaoRisco", risco);
        campo(v, "gatilhoVisitaAcionado", false);
    }

    private VinculoAssistencial vinculo(Local local) {
        return em.find(VinculoAssistencial.class, local.id());
    }

    private void campo(VinculoAssistencial vinculo, String nome, Object valor) {
        ReflectionTestUtils.setField(vinculo, nome, valor);
    }

    private Paciente pessoa(String nome) {
        var p = new Paciente();
        p.setNome(nome);
        p.setCpf(UUID.randomUUID().toString());
        p.setCns(UUID.randomUUID().toString());
        em.persist(p);
        return p;
    }

    private Local local(Paciente p, UnidadeAtuacao unidade, String nomeUsf) {
        UsfReferencia usf = null;
        if (nomeUsf != null) {
            usf = new UsfReferencia();
            usf.setNomeUsf("USF sintética " + nomeUsf);
            usf.setCnes(nomeUsf);
            em.persist(usf);
        }
        return local(p, ator(unidade, usf));
    }

    private Local local(Paciente p, Usuario ator) {
        var v = ator.getUnidadeAtuacao() == UnidadeAtuacao.USF
                ? VinculoAssistencial.paraUsf(p, ator.getUsfAtuacao())
                : VinculoAssistencial.paraCaps(p, ator.getUnidadeAtuacao());
        em.persist(v);
        return new Local(ator, v.getId(), v.getIdPublico(), v.getUnidadeChave());
    }

    private Usuario ator(UnidadeAtuacao unidade, UsfReferencia usf) {
        var u = new Usuario();
        u.setNome("Profissional sintético");
        u.setEmail(UUID.randomUUID() + "@test.local");
        u.setSenha("fixture");
        u.setTipoUsuario(TipoUsuario.ADMINISTRADOR);
        u.setUnidadeAtuacao(unidade);
        u.setUsfAtuacao(usf);
        em.persist(u);
        return u;
    }

    private Agendamento remarcacoes(Paciente p, Usuario ator, int tamanho) {
        Agendamento anterior = null;
        for (int elo = 0; elo < tamanho; elo++) {
            if (anterior != null) anterior.setSituacaoAtendimento(SituacaoAtendimento.REMARCADO_ORIGEM);
            anterior = agenda(p, ator, 6 - elo, SituacaoAtendimento.FALTOU, anterior, true);
        }
        return anterior;
    }

    private Agendamento agenda(Paciente p, Usuario ator, int dias, SituacaoAtendimento situacao,
            Agendamento anterior, boolean comOrigem) {
        var a = new Agendamento();
        a.setPaciente(p);
        a.setUsuario(ator);
        a.setDataAgendamento(REFERENCIA.minusDays(dias));
        a.setHoraAtendimento(LocalTime.of(9, 0));
        a.setAgendamentoOriginal(anterior);
        a.setRemarcacaoAposFalta(anterior != null);
        a.setSituacaoAtendimento(situacao);
        if (comOrigem) a.registrarUnidadeDeOrigem(ator);
        em.persist(a);
        return a;
    }

    private GrupoTerapeutico grupo(Usuario ator) {
        var grupo = new GrupoTerapeutico();
        grupo.setTema("Grupo sintético");
        grupo.setCoordenador(ator);
        grupo.registrarUnidadeDeOrigem(ator);
        em.persist(grupo);
        return grupo;
    }

    private SessaoGrupoParticipante participacao(Paciente p, GrupoTerapeutico grupo, Usuario ator, int dias,
            StatusSessaoGrupo status, StatusPresencaGrupo frequencia, boolean confirmar) {
        var s = new SessaoGrupo();
        s.setGrupo(grupo);
        s.setDataSessao(REFERENCIA.minusDays(dias));
        s.setHorario(LocalTime.of(9, 0));
        s.registrarUnidadeDeOrigemDoGrupo();
        if (confirmar) {
            s.setStatus(StatusSessaoGrupo.AGENDADA);
            s.confirmarRealizacao(ator);
        } else {
            s.setStatus(status);
        }
        em.persist(s);
        var associacao = new SessaoGrupoParticipante();
        associacao.setSessaoGrupo(s);
        associacao.setPaciente(p);
        associacao.setStatusPresenca(frequencia);
        em.persist(associacao);
        return associacao;
    }

    private void historico(Paciente p, Usuario autor, TipoEventoHistoricoPaciente tipo, Agendamento a, SessaoGrupo s) {
        var h = new HistoricoPaciente();
        h.setPaciente(p);
        h.setProfissional(autor);
        h.setTipo(tipo);
        h.setOcorridoEm(LocalDateTime.of(REFERENCIA.minusDays(1), LocalTime.NOON));
        if (a != null) {
            h.setAgendamento(a);
            h.registrarOrigemDoAgendamento(a);
        } else if (s != null) {
            h.setSessaoGrupo(s);
            h.registrarOrigemDaSessao(s);
        } else {
            h.registrarOrigemDeAcaoLocal(autor);
        }
        em.persist(h);
    }

    private static Projecao projetar(ProjecaoClinicaVinculoService service, UUID p, Local local) {
        return projetar(service, p, local, local.publico());
    }

    private static Projecao projetar(ProjecaoClinicaVinculoService service, UUID p, Local local, UUID vinculo) {
        return como(local.ator(), () -> service.projetar(p, vinculo, REFERENCIA));
    }

    private static <T> T como(Usuario ator, Supplier<T> trabalho) {
        var anterior = SecurityContextHolder.getContext();
        var atual = SecurityContextHolder.createEmptyContext();
        atual.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(ator, null, ator.getAuthorities()));
        SecurityContextHolder.setContext(atual);
        try {
            return trabalho.get();
        } finally {
            SecurityContextHolder.setContext(anterior);
        }
    }

    private static void verificarTrilhas(Map<String, List<Map<String, Object>>> snapshot) {
        for (String auditada : List.of("PACIENTE", "VINCULO_ASSISTENCIAL", "AGENDAMENTO", "GRUPO_TERAPEUTICO",
                "SESSAO_GRUPO", "SESSAO_GRUPO_PARTICIPANTE", "HISTORICO_PACIENTE",
                "PENDENCIA_REVISAO_PROVENIENCIA", "DECISAO_REVISAO_PROVENIENCIA")) {
            assertFalse(snapshot.get("TB_" + auditada + "_AUD").isEmpty(), auditada);
        }
        assertTrue(snapshot.get("TB_AUDITORIA_BUSCA_ATIVA").isEmpty());
        assertEquals(1, snapshot.get("TB_PENDENCIA_REVISAO_PROVENIENCIA").size());
        assertEquals(1, snapshot.get("TB_DECISAO_REVISAO_PROVENIENCIA").size());
    }
}
