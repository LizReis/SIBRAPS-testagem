package com.pet.buscaativa;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

import com.pet.buscaativa.alertas.EmailService;
import com.pet.buscaativa.entities.*;
import com.pet.buscaativa.entities.dto.EncerramentoPacienteDTO;
import com.pet.buscaativa.entities.enums.*;
import com.pet.buscaativa.repositories.PacienteRepository;
import com.pet.buscaativa.repositories.VinculoAssistencialRepository;
import com.pet.buscaativa.services.exceptions.ConflictException;
import com.pet.buscaativa.services.exceptions.ResourceNotFoundException;
import com.pet.buscaativa.services.exceptions.ValidationException;
import com.pet.buscaativa.services.impl.EstadoClinicoLocalEnsaioService;
import com.pet.buscaativa.services.impl.FrequenciaColetivaLocalEnsaioService;
import com.pet.buscaativa.services.impl.FrequenciaIndividualLocalEnsaioService;
import com.pet.buscaativa.services.impl.InicioAcompanhamentoLocalEnsaioService;
import com.pet.buscaativa.services.impl.PersistenciaIndicadoresVinculoEnsaioService;
import com.pet.buscaativa.services.impl.PersistenciaIndicadoresVinculoEnsaioService.Situacao;
import com.pet.buscaativa.services.impl.SusApiAdapter;

import jakarta.persistence.EntityManager;

@ActiveProfiles({"ensaio-estado-local", "relogio-coordenacao"})
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:persistencia-local-63;DB_CLOSE_DELAY=-1")
class PersistenciaIndicadoresLocaisIntegrationTests extends SecurityTestSupport {
    @Autowired EntityManager em;
    @Autowired TransactionTemplate tx;
    @Autowired DataSource fonte;
    @Autowired Clock clock;
    @Autowired PacienteRepository pacientes;
    @Autowired VinculoAssistencialRepository vinculos;
    @Autowired InicioAcompanhamentoLocalEnsaioService inicio;
    @Autowired EstadoClinicoLocalEnsaioService estado;
    @Autowired FrequenciaIndividualLocalEnsaioService individual;
    @Autowired FrequenciaColetivaLocalEnsaioService coletivo;
    @Autowired PersistenciaIndicadoresVinculoEnsaioService indicadores;
    @Autowired com.pet.buscaativa.services.impl.ReservaIndividualLocalEnsaioService reservasLocais;
    @Autowired com.pet.buscaativa.services.impl.ComandosAssistenciaisLocaisEnsaioService comandosLocais;
    @Autowired com.pet.buscaativa.services.impl.SerieGruposLocaisEnsaioService seriesLocais;
    @Autowired com.pet.buscaativa.services.impl.DtosAssistenciaisLocaisEnsaioService dtosLocais;
    @Autowired com.pet.buscaativa.services.impl.ComandosEstadoLocalEnsaioService comandosEstado;
    @Autowired com.pet.buscaativa.services.impl.HistoricoManualLocalEnsaioService historicoManual;
    @Autowired com.pet.buscaativa.services.impl.MonitoramentoVinculosEnsaioService monitorLocal;
    @MockitoBean SusApiAdapter sus;
    @MockitoBean EmailService email;

    @TestConfiguration
    @Profile("relogio-coordenacao")
    static class RelogioEnsaio {
        @Bean
        @Primary
        Clock relogioFixo() {
            return Clock.fixed(Instant.parse("2026-11-24T22:00:00Z"), ZoneId.of("America/Sao_Paulo"));
        }
    }

    private static final String JUSTIFICATIVA = "Correção de frequência comprovada no ensaio sintético.";
    private record Local(Paciente paciente, Usuario ator, UUID vinculo) {}
    private record Agenda(Long id, Integer versao) {}
    private record Sessao(Long id, Integer versao) {}

    @AfterEach
    void naoNotifica() {
        verifyNoInteractions(email);
    }

    @Test
    void enxergaFatoNaoConfirmadoERecalculaCorrecaoMantendoCadastroEDoisVinculosExternos() {
        var local = novoLocal(UnidadeAtuacao.USF);
        var outraUsf = tx.execute(t -> usuario(UnidadeAtuacao.USF));
        var caps = tx.execute(t -> usuario(UnidadeAtuacao.CAPS_AD));
        var externoA = iniciar(local.paciente(), outraUsf);
        var externoB = iniciar(local.paciente(), caps);
        var reserva = reserva(local, diasAtras(3), 9, SituacaoAtendimento.AGENDADO, true);
        var colunas = LegacyMigrationFixture.columns(fonte);
        var antes = LegacyMigrationFixture.snapshot(fonte, colunas);

        var falta = como(local.ator(), () -> individual.registrar(reserva.id(), reserva.versao(), FrequenciaAssistencial.FALTOU));
        assertEquals(Situacao.ATUALIZADO, falta.indicadores().situacao());
        assertEquals(1, carregar(local).getCountFaltas());
        var presente = como(local.ator(), () -> individual.corrigir(reserva.id(), falta.versaoFato(),
                FrequenciaAssistencial.PRESENTE, JUSTIFICATIVA));
        assertEquals(0, carregar(local).getCountFaltas());
        assertEquals(diasAtras(3), carregar(local).getDataUltimaPresenca());

        var depois = LegacyMigrationFixture.snapshot(fonte, colunas);
        for (String tabela : List.of("TB_PACIENTE", "TB_PACIENTE_AUD", "TB_AUDITORIA_BUSCA_ATIVA",
                "TB_PENDENCIA_REVISAO_PROVENIENCIA", "TB_DECISAO_REVISAO_PROVENIENCIA")) {
            assertEquals(antes.get(tabela), depois.get(tabela), tabela);
        }
        for (var outro : List.of(externoA, externoB)) {
            assertEquals(0, carregar(outro).getCountFaltas());
            assertEquals(0, carregar(outro).getVersion());
        }
        assertEquals(1L, total("select count(*) from tb_auditoria_correcao_frequencia where agendamento_id=?", reserva.id()));
        assertEquals(1L, total("select count(*) from tb_historico_paciente where agendamento_id=?", reserva.id()));
        assertEquals(1L, total("""
                select count(*) from tb_agendamento_aud a
                join tb_vinculo_assistencial_aud v on v.rev=a.rev
                join tb_historico_paciente_aud h on h.rev=a.rev and h.agendamento_id=a.id
                where a.id=? and v.id_publico=? and a.frequencia_assistencial='PRESENTE' and v.count_faltas=0
                """, reserva.id(), local.vinculo()));

        assertThrows(ResourceNotFoundException.class, () -> como(outraUsf,
                () -> individual.corrigir(reserva.id(), presente.versaoFato(), FrequenciaAssistencial.FALTOU, JUSTIFICATIVA)));
        assertThrows(ResourceNotFoundException.class, () -> como(caps,
                () -> indicadores.recalcular(local.paciente().getIdPublico(), local.vinculo(), carregar(local).getVersion())));
        assertThrows(ValidationException.class, () -> como(local.ator(), () -> individual.corrigir(reserva.id(),
                falta.versaoFato(), FrequenciaAssistencial.FALTOU, JUSTIFICATIVA)));
        var repetido = como(local.ator(), () -> individual.corrigir(reserva.id(), presente.versaoFato(),
                FrequenciaAssistencial.PRESENTE, JUSTIFICATIVA));
        assertEquals(Situacao.SEM_ALTERACAO, repetido.indicadores().situacao());
        assertEquals(Situacao.SEM_ALTERACAO, como(local.ator(), () -> indicadores.recalcular(
                local.paciente().getIdPublico(), local.vinculo(), carregar(local).getVersion())).situacao());
        assertEquals(depois, LegacyMigrationFixture.snapshot(fonte, colunas));
    }

    @Test
    void regularizaReservaEReutilizaCadeiaDeAuditoriaNasCorrecoes() {
        var local = novoLocal(UnidadeAtuacao.CAPS_AD);
        var cancelada = reserva(local, diasAtras(4), 9, SituacaoAtendimento.CANCELADO, true);
        var inicial = como(local.ator(), () -> individual.regularizarCancelamento(cancelada.id(), cancelada.versao(),
                FrequenciaAssistencial.FALTOU, JUSTIFICATIVA));
        assertEquals(1, carregar(local).getCountFaltas());
        var corrigido = como(local.ator(), () -> individual.corrigir(cancelada.id(), inicial.versaoFato(),
                FrequenciaAssistencial.PRESENTE, JUSTIFICATIVA));
        como(local.ator(), () -> individual.corrigir(cancelada.id(), corrigido.versaoFato(),
                FrequenciaAssistencial.FALTOU, JUSTIFICATIVA));
        assertEquals(1, carregar(local).getCountFaltas());
        assertEquals(1L, total("select count(*) from tb_agendamento where id=? and situacao_reserva='CANCELADA'", cancelada.id()));
        assertEquals(1L, total("select count(*) from tb_auditoria_regularizacao_cancelamento where agendamento_id=?", cancelada.id()));
        assertEquals(2L, total("select count(*) from tb_auditoria_correcao_frequencia where agendamento_id=?", cancelada.id()));
    }

    @Test
    void remarcacaoPreservaFaltaOriginalERf22ConsideraAFaltaDoSucessor() {
        var local = novoLocal(UnidadeAtuacao.CAPS_I);
        var data = diasAtras(0);
        tx.executeWithoutResult(t -> {
            var oferta = new DisponibilidadeExcecao();
            oferta.setUsuario(em.find(Usuario.class, local.ator().getId()));
            oferta.setData(data);
            oferta.setTurno(TurnoEnum.MANHA);
            oferta.setCapacidade(10);
            em.persist(oferta);
        });
        var original = reserva(local, diasAtras(5), 9, SituacaoAtendimento.AGENDADO, true);
        var falta = como(local.ator(), () -> individual.registrar(original.id(), original.versao(), FrequenciaAssistencial.FALTOU));
        var remarcado = como(local.ator(), () -> individual.remarcar(original.id(), falta.versaoFato(),
                data, LocalTime.of(9, 0), TurnoEnum.MANHA));
        assertEquals(1, carregar(local).getCountFaltas());
        assertFalse(carregar(local).getGatilhoVisitaAcionado());
        como(local.ator(), () -> individual.registrar(remarcado.atendimento(), remarcado.versaoFato(),
                FrequenciaAssistencial.FALTOU));
        assertEquals(2, carregar(local).getCountFaltas());
        assertTrue(carregar(local).getGatilhoVisitaAcionado());
        assertEquals(1L, total("""
                select count(*) from tb_agendamento where id=? and situacao_reserva='SUBSTITUIDA'
                and frequencia_assistencial='FALTOU'
                """, original.id()));
    }

    @Test
    void confirmaECorrigeLoteDeDoisPacientesComAuditoriaUnicaPorOperacao() {
        var a = novoLocal(UnidadeAtuacao.USF);
        var b = iniciar(novoPaciente(), a.ator());
        var sessao = sessao(a.ator(), List.of(a, b), diasAtras(3), 10);
        var versao = como(a.ator(), () -> coletivo.confirmar(sessao.id(), sessao.versao(),
                Map.of(a.paciente().getIdPublico(), StatusPresencaGrupo.PRESENTE)));
        assertEquals(0, carregar(a).getCountFaltas());
        assertEquals(1, carregar(b).getCountFaltas());
        var corrigido = como(a.ator(), () -> coletivo.corrigir(sessao.id(), versao,
                Map.of(a.paciente().getIdPublico(), StatusPresencaGrupo.FALTOU,
                        b.paciente().getIdPublico(), StatusPresencaGrupo.PRESENTE), JUSTIFICATIVA));
        assertTrue(corrigido > versao);
        assertEquals(1, carregar(a).getCountFaltas());
        assertEquals(0, carregar(b).getCountFaltas());
        assertEquals(2L, total("select count(*) from tb_historico_paciente where sessao_grupo_id=?", sessao.id()));
        assertEquals(2L, total("select count(*) from tb_auditoria_correcao_frequencia where sessao_id=?", sessao.id()));
        assertEquals(1L, total("select count(distinct rev) from tb_sessao_grupo_participante_aud where sessao_grupo_id=? and revtype=1 and rev=(select max(rev) from tb_sessao_grupo_participante_aud where sessao_grupo_id=?)",
                sessao.id(), sessao.id()));
    }

    @Test
    void preservaValoresConhecidosQuandoOProprioVinculoTemLegadoERegistraLoteValido() {
        var a = novoLocal(UnidadeAtuacao.CAPS_II);
        var b = iniciar(novoPaciente(), a.ator());
        var conhecida = reserva(a, diasAtras(4), 9, SituacaoAtendimento.AGENDADO, true);
        como(a.ator(), () -> individual.registrar(conhecida.id(), conhecida.versao(), FrequenciaAssistencial.PRESENTE));
        reserva(a, diasAtras(3), 9, SituacaoAtendimento.FALTOU, false);
        var local = reserva(a, diasAtras(2), 9, SituacaoAtendimento.AGENDADO, true);
        var resultado = como(a.ator(), () -> individual.registrar(local.id(), local.versao(), FrequenciaAssistencial.FALTOU));
        assertEquals(Situacao.INCONCLUSIVO, resultado.indicadores().situacao());
        assertTrue(resultado.indicadores().indicacaoDePendenciaAlterada());
        assertTrue(carregar(a).getIndicadoresPendentes());
        assertEquals(diasAtras(4), carregar(a).getDataUltimaPresenca());
        assertEquals(0, carregar(a).getCountFaltas());

        var sessao = sessao(a.ator(), List.of(a, b), diasAtras(1), 10);
        var colunas = LegacyMigrationFixture.columns(fonte);
        var antes = LegacyMigrationFixture.snapshot(fonte, colunas);
        como(a.ator(), () -> coletivo.confirmar(sessao.id(), sessao.versao(), Map.of()));
        assertTrue(carregar(a).getIndicadoresPendentes());
        assertEquals(0, carregar(a).getCountFaltas());
        assertEquals(diasAtras(4), carregar(a).getDataUltimaPresenca());
        assertFalse(carregar(b).getIndicadoresPendentes());
        assertEquals(1, carregar(b).getCountFaltas());
        tx.executeWithoutResult(t -> {
            var s = em.find(SessaoGrupo.class, sessao.id());
            assertEquals(StatusSessaoGrupo.REALIZADA, s.getStatus());
            assertEquals(2, s.getParticipantes().size());
            assertTrue(s.getParticipantes().stream().allMatch(p -> p.getStatusPresenca() == StatusPresencaGrupo.FALTOU));
        });
        var depois = LegacyMigrationFixture.snapshot(fonte, colunas);
        assertEquals(antes.get("TB_PACIENTE"), depois.get("TB_PACIENTE"));
        assertEquals(antes.get("TB_PACIENTE_AUD"), depois.get("TB_PACIENTE_AUD"));
    }

    @Test
    void falhaRealNaAuditoriaReverteCorrecaoFatoIndicadoresHistoricoEEnvers() {
        var local = novoLocal(UnidadeAtuacao.CAPS_AD);
        var agenda = reserva(local, diasAtras(2), 9, SituacaoAtendimento.AGENDADO, true);
        var falta = como(local.ator(), () -> individual.registrar(agenda.id(), agenda.versao(), FrequenciaAssistencial.FALTOU));
        var jdbc = new JdbcTemplate(fonte);
        jdbc.execute("alter table tb_auditoria_correcao_frequencia add constraint falha_ensaio check (justificativa <> 'Falha sintética na trilha auditável.')");
        try {
            var colunas = LegacyMigrationFixture.columns(fonte);
            var antes = LegacyMigrationFixture.snapshot(fonte, colunas);
            var falha = assertThrows(RuntimeException.class, () -> como(local.ator(), () -> individual.corrigir(agenda.id(),
                    falta.versaoFato(), FrequenciaAssistencial.PRESENTE, "Falha sintética na trilha auditável.")));
            assertTrue(falha.getMessage().toUpperCase(java.util.Locale.ROOT).contains("FALHA_ENSAIO"));
            assertEquals(antes, LegacyMigrationFixture.snapshot(fonte, colunas));
        } finally {
            jdbc.execute("alter table tb_auditoria_correcao_frequencia drop constraint falha_ensaio");
        }
    }

    @Test
    void primeiraInicializacaoSeguraEscritorAteCommitERegistroRecalcula() throws Exception {
        var ator = tx.execute(t -> usuario(UnidadeAtuacao.CAPS_AD));
        var paciente = novoPaciente();
        try (var executor = Executors.newSingleThreadExecutor()) {
            var resultado = tx.execute(t -> {
                var criado = iniciar(paciente, ator);
                var escritor = executor.submit(() -> tx.execute(s -> {
                    // Fase de identidade comum antes de materializar a nova reserva da fixture.
                    pacientes.findByIdPublicoForUpdate(paciente.getIdPublico()).orElseThrow();
                    var a = reserva(criado, diasAtras(2), 9, SituacaoAtendimento.AGENDADO, true);
                    return como(ator, () -> individual.registrar(a.id(), a.versao(), FrequenciaAssistencial.FALTOU));
                }));
                aguardarBloqueio();
                assertFalse(escritor.isDone());
                return escritor;
            }).get(20, TimeUnit.SECONDS);
            assertEquals(Situacao.ATUALIZADO, resultado.indicadores().situacao());
            assertEquals(1, total("select count(*) from tb_vinculo_assistencial where paciente_id=? and count_faltas=1", paciente.getId()));
        }
    }

    @Test
    void presencaEFaltaDisputamVersaoEFrequenciasDeModalidadesDistintasSeCompoem() throws Exception {
        var local = novoLocal(UnidadeAtuacao.CAPS_AD);
        var a = reserva(local, diasAtras(4), 9, SituacaoAtendimento.AGENDADO, true);
        var s = sessao(local.ator(), List.of(local), diasAtras(2), 10);
        var partir = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(3)) {
            var falta = executor.submit(() -> apos(partir, () -> tentativa(local, a, FrequenciaAssistencial.FALTOU)));
            var presente = executor.submit(() -> apos(partir, () -> tentativa(local, a, FrequenciaAssistencial.PRESENTE)));
            var grupo = executor.submit(() -> apos(partir, () -> como(local.ator(),
                    () -> coletivo.confirmar(s.id(), s.versao(), Map.of()))));
            partir.countDown();
            boolean faltou = falta.get(20, TimeUnit.SECONDS);
            assertNotEquals(faltou, presente.get(20, TimeUnit.SECONDS));
            assertTrue(grupo.get(20, TimeUnit.SECONDS) > s.versao());
            assertEquals(faltou ? 2 : 1, carregar(local).getCountFaltas());
        }
    }

    @Test
    void abortaLoteQuandoParticipantesMudamDuranteEsperaDosLocks() throws Exception {
        var a = novoLocal(UnidadeAtuacao.CAPS_I);
        var b = iniciar(novoPaciente(), a.ator());
        var s = sessao(a.ator(), List.of(a), diasAtras(2), 10);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var pendente = tx.execute(t -> {
                pacientes.findByIdPublicoForUpdate(a.paciente().getIdPublico()).orElseThrow();
                var comando = executor.submit(() -> assertThrows(ConflictException.class, () -> como(a.ator(),
                        () -> coletivo.confirmar(s.id(), s.versao(), Map.of()))));
                aguardarBloqueio();
                adicionar(em.find(SessaoGrupo.class, s.id()), b.paciente());
                return comando;
            });
            pendente.get(20, TimeUnit.SECONDS);
        }
        assertEquals(0L, total("select count(*) from tb_historico_paciente where sessao_grupo_id=?", s.id()));
        assertEquals(0, carregar(a).getCountFaltas());
        assertEquals(0, carregar(b).getCountFaltas());
    }

    @Test
    void recarregaERecusaAlteracaoDeResponsavelOuPerfilDuranteEspera() throws Exception {
        var a = novoLocal(UnidadeAtuacao.CAPS_I);
        var substituto = tx.execute(t -> usuario(UnidadeAtuacao.CAPS_I));
        for (boolean trocaResponsavel : List.of(true, false)) {
            var s = sessao(a.ator(), List.of(a), diasAtras(3), trocaResponsavel ? 10 : 11);
            try (var executor = Executors.newSingleThreadExecutor()) {
                var pendente = tx.execute(t -> {
                    pacientes.findByIdPublicoForUpdate(a.paciente().getIdPublico()).orElseThrow();
                    var comando = executor.submit(() -> assertThrows(ConflictException.class, () -> como(a.ator(),
                            () -> coletivo.confirmar(s.id(), s.versao(), Map.of()))));
                    aguardarBloqueio();
                    if (trocaResponsavel) {
                        em.find(SessaoGrupo.class, s.id()).getGrupo().setCoordenador(em.find(Usuario.class, substituto.getId()));
                    } else {
                        em.find(Usuario.class, a.ator().getId()).setTipoUsuario(TipoUsuario.PROFISSIONAL);
                    }
                    return comando;
                });
                pendente.get(20, TimeUnit.SECONDS);
            }
            assertEquals(0L, total("select count(*) from tb_historico_paciente where sessao_grupo_id=?", s.id()));
        }
    }

    @Test
    void transicoesEIndicadoresParticipamDaMesmaRevisao() {
        var a = novoLocal(UnidadeAtuacao.CAPS_I);
        for (int dias : List.of(4, 3)) {
            var reserva = reserva(a, diasAtras(dias), 9, SituacaoAtendimento.AGENDADO, true);
            como(a.ator(), () -> individual.registrar(reserva.id(), reserva.versao(), FrequenciaAssistencial.FALTOU));
        }
        assertEquals(ClassificacaoRisco.AMARELO, carregar(a).getClassificacaoRisco());
        var fechado = como(a.ator(), () -> estado.encerrar(a.paciente().getIdPublico(), a.vinculo(), carregar(a).getVersion(),
                new EncerramentoPacienteDTO(MotivoEncerramento.OUTRO, StatusPaciente.INATIVO, "Motivo local.")));
        assertEquals(ClassificacaoRisco.VERDE, carregar(a).getClassificacaoRisco());
        como(a.ator(), () -> estado.reativar(a.paciente().getIdPublico(), a.vinculo(), fechado.versao(), JUSTIFICATIVA));
        assertEquals(ClassificacaoRisco.AMARELO, carregar(a).getClassificacaoRisco());
        assertEquals(2L, total("""
                select count(*) from tb_evento_estado_clinico_vinculo_aud e
                join tb_vinculo_assistencial_aud v on v.id=e.vinculo_id and v.rev=e.rev
                where v.id_publico=? and e.natureza in ('ENCERRAMENTO','REATIVACAO') and v.count_faltas=2
                """, a.vinculo()));
    }

    @Test
    void altaLocalDesativaRiscoEVisitaSemApagarFaltasOuPendencia() {
        var a = novoLocal(UnidadeAtuacao.CAPS_AD);
        var externo = iniciar(a.paciente(), tx.execute(t -> usuario(UnidadeAtuacao.CAPS_I)));
        for (int dias : List.of(5, 4, 3)) {
            var ag = reserva(a, diasAtras(dias), 9, SituacaoAtendimento.AGENDADO, true);
            como(a.ator(), () -> individual.registrar(ag.id(), ag.versao(), FrequenciaAssistencial.FALTOU));
        }
        assertEquals(ClassificacaoRisco.VERMELHO, carregar(a).getClassificacaoRisco());
        tx.executeWithoutResult(t -> {
            var local = vinculos.findByIdPublico(a.vinculo()).orElseThrow();
            local.aplicarIndicadoresLocais(3, null, ClassificacaoRisco.VERMELHO, true);
            local.sinalizarIndicadoresLocaisPendentes();
        });
        var versaoExterna = carregar(externo).getVersion();
        var colunas = LegacyMigrationFixture.columns(fonte);
        var antes = LegacyMigrationFixture.snapshot(fonte, colunas);
        como(a.ator(), () -> estado.encerrar(a.paciente().getIdPublico(), a.vinculo(), carregar(a).getVersion(),
                new EncerramentoPacienteDTO(MotivoEncerramento.ALTA_TERAPEUTICA, StatusPaciente.ALTA_TERAPEUTICA, null)));
        var fechado = carregar(a);
        assertEquals(StatusPaciente.ALTA_TERAPEUTICA, fechado.getStatusPaciente());
        assertEquals(ClassificacaoRisco.VERDE, fechado.getClassificacaoRisco());
        assertFalse(fechado.getGatilhoVisitaAcionado());
        assertEquals(3, fechado.getCountFaltas());
        assertTrue(fechado.getIndicadoresPendentes());
        assertEquals(StatusPaciente.ATIVO, carregar(externo).getStatusPaciente());
        assertEquals(versaoExterna, carregar(externo).getVersion());
        var depois = LegacyMigrationFixture.snapshot(fonte, colunas);
        for (var tabela : List.of("TB_PACIENTE", "TB_PACIENTE_AUD", "TB_AGENDAMENTO", "TB_AGENDAMENTO_AUD"))
            assertEquals(antes.get(tabela), depois.get(tabela), tabela);
    }

    @Test
    void renumeraFaltasIndividuaisEColetivasAposCorrecaoRetroativa() {
        var a = novoLocal(UnidadeAtuacao.CAPS_AD);
        var externa = iniciar(a.paciente(), tx.execute(t -> usuario(UnidadeAtuacao.CAPS_I)));
        var primeira = reserva(a, diasAtras(5), 9, SituacaoAtendimento.AGENDADO, true);
        var segunda = reserva(a, diasAtras(4), 9, SituacaoAtendimento.AGENDADO, true);
        var s = sessao(a.ator(), List.of(a), diasAtras(3), 10);
        como(a.ator(), () -> individual.registrar(primeira.id(), primeira.versao(), FrequenciaAssistencial.FALTOU));
        var falta = como(a.ator(), () -> individual.registrar(segunda.id(), segunda.versao(), FrequenciaAssistencial.FALTOU));
        como(a.ator(), () -> coletivo.confirmar(s.id(), s.versao(), Map.of()));
        assertEquals(3L, total("select numero_falta_consecutiva from tb_historico_paciente where sessao_grupo_id=?", s.id()));
        como(a.ator(), () -> individual.corrigir(segunda.id(), falta.versaoFato(), FrequenciaAssistencial.PRESENTE, JUSTIFICATIVA));
        assertEquals(1L, total("select numero_falta_consecutiva from tb_historico_paciente where agendamento_id=?", primeira.id()));
        assertEquals(1L, total("select count(*) from tb_historico_paciente where agendamento_id=? and numero_falta_consecutiva is null", segunda.id()));
        assertEquals(1L, total("select numero_falta_consecutiva from tb_historico_paciente where sessao_grupo_id=?", s.id()));
        assertEquals(1L, total("select count(*) from tb_historico_paciente_aud where sessao_grupo_id=? and numero_falta_consecutiva=3", s.id()));
        assertEquals(1, carregar(a).getCountFaltas());
        assertEquals(0, carregar(externa).getCountFaltas());
    }

    @Test
    void resolveSomenteEmpateLocalComprovadoPelaCorrecao() {
        var a = novoLocal(UnidadeAtuacao.CAPS_AD);
        var primeira = reserva(a, diasAtras(3), 9, SituacaoAtendimento.AGENDADO, true);
        var presente = como(a.ator(), () -> individual.registrar(primeira.id(), primeira.versao(), FrequenciaAssistencial.PRESENTE));
        var segunda = reserva(a, diasAtras(3), 9, SituacaoAtendimento.AGENDADO, true);
        como(a.ator(), () -> individual.registrar(segunda.id(), segunda.versao(), FrequenciaAssistencial.FALTOU));
        assertTrue(carregar(a).getIndicadoresPendentes());
        assertEquals(Boolean.TRUE, carregar(a).getPendenciaEmpateLocal());
        assertEquals(Situacao.INCONCLUSIVO, como(a.ator(), () -> indicadores.recalcular(
                a.paciente().getIdPublico(), a.vinculo(), carregar(a).getVersion())).situacao());
        var resultado = como(a.ator(), () -> individual.corrigir(primeira.id(), presente.versaoFato(),
                FrequenciaAssistencial.FALTOU, JUSTIFICATIVA));
        assertEquals(Situacao.ATUALIZADO, resultado.indicadores().situacao());
        assertTrue(resultado.indicadores().indicacaoDePendenciaAlterada());
        assertFalse(carregar(a).getIndicadoresPendentes());
        assertNull(carregar(a).getPendenciaEmpateLocal());
        assertEquals(2, carregar(a).getCountFaltas());
    }

    @Test
    void recalculoNaoLimpaPendenciaDesconhecidaNemReativaAlertasDeAlta() {
        var a = novoLocal(UnidadeAtuacao.CAPS_II);
        tx.executeWithoutResult(t -> vinculos.findByIdPublico(a.vinculo()).orElseThrow().sinalizarIndicadoresLocaisPendentes());
        var fechado = como(a.ator(), () -> estado.encerrar(a.paciente().getIdPublico(), a.vinculo(), carregar(a).getVersion(),
                new EncerramentoPacienteDTO(MotivoEncerramento.ALTA_TERAPEUTICA, StatusPaciente.ALTA_TERAPEUTICA, null)));
        var res = como(a.ator(), () -> indicadores.recalcular(a.paciente().getIdPublico(), a.vinculo(), fechado.versao()));
        assertEquals(Situacao.INCONCLUSIVO, res.situacao());
        assertTrue(carregar(a).getIndicadoresPendentes());
        assertNull(carregar(a).getPendenciaEmpateLocal());
        assertEquals(ClassificacaoRisco.VERDE, carregar(a).getClassificacaoRisco());
        assertFalse(carregar(a).getGatilhoVisitaAcionado());
    }

    @Test
    void recalculoDeAltaComFaltasConclusivasMantemRiscoVerde() {
        var a = novoLocal(UnidadeAtuacao.CAPS_AD);
        for (int dias : List.of(5, 4, 3)) {
            var ag = reserva(a, diasAtras(dias), 9, SituacaoAtendimento.AGENDADO, true);
            como(a.ator(), () -> individual.registrar(ag.id(), ag.versao(), FrequenciaAssistencial.FALTOU));
        }
        var fechado = como(a.ator(), () -> estado.encerrar(a.paciente().getIdPublico(), a.vinculo(), carregar(a).getVersion(),
                new EncerramentoPacienteDTO(MotivoEncerramento.ALTA_TERAPEUTICA, StatusPaciente.ALTA_TERAPEUTICA, null)));
        como(a.ator(), () -> indicadores.recalcular(a.paciente().getIdPublico(), a.vinculo(), fechado.versao()));
        assertEquals(3, carregar(a).getCountFaltas());
        assertEquals(ClassificacaoRisco.VERDE, carregar(a).getClassificacaoRisco());
        assertFalse(carregar(a).getGatilhoVisitaAcionado());
        como(a.ator(), () -> estado.reativar(a.paciente().getIdPublico(), a.vinculo(), carregar(a).getVersion(), JUSTIFICATIVA));
        assertEquals(ClassificacaoRisco.VERMELHO, carregar(a).getClassificacaoRisco());
    }

    @Test
    void reservaLocalPelaRecepcaoNaoProduzFrequenciaNemAlteraOutroVinculo() {
        var a = novoLocal(UnidadeAtuacao.CAPS_AD);
        var externo = iniciar(a.paciente(), tx.execute(t -> usuario(UnidadeAtuacao.CAPS_I)));
        var recepcao = tx.execute(t -> {
            var u = usuario(UnidadeAtuacao.CAPS_AD);
            u.setTipoUsuario(TipoUsuario.RECEPCAO);
            return u;
        });
        var data = LocalDate.now(clock).plusDays(1);
        tx.executeWithoutResult(t -> {
            var oferta = new DisponibilidadeExcecao();
            oferta.setUsuario(em.find(Usuario.class, a.ator().getId()));
            oferta.setData(data);
            oferta.setTurno(TurnoEnum.MANHA);
            oferta.setCapacidade(10);
            em.persist(oferta);
        });
        var dto = new com.pet.buscaativa.entities.dto.AgendamentoDTO(null, a.ator().getIdPublico(), null,
                a.paciente().getIdPublico(), null, TipoAcompanhamento.AMBOS, data, TurnoEnum.MANHA,
                LocalTime.of(9, 0), null, null, null);
        var colunas = LegacyMigrationFixture.columns(fonte);
        var antes = LegacyMigrationFixture.snapshot(fonte, colunas);
        var criada = como(recepcao, () -> reservasLocais.criar(dto));
        assertEquals(1L, total("select count(*) from tb_agendamento where id=? and unidade_origem_chave='CAPS_AD' and frequencia_assistencial='NAO_REGISTRADA'", criada.atendimento()));
        assertEquals(1L, total("select count(*) from tb_historico_paciente where agendamento_id=?", criada.atendimento()));
        assertEquals(0, carregar(a).getCountFaltas());
        assertEquals(0, carregar(externo).getCountFaltas());
        var depois = LegacyMigrationFixture.snapshot(fonte, colunas);
        assertEquals(antes.get("TB_PACIENTE"), depois.get("TB_PACIENTE"));
        assertEquals(antes.get("TB_VINCULO_ASSISTENCIAL"), depois.get("TB_VINCULO_ASSISTENCIAL"));
        assertThrows(ConflictException.class, () -> como(recepcao, () -> reservasLocais.criar(dto)));
        assertThrows(org.springframework.security.access.AccessDeniedException.class, () -> como(recepcao,
                () -> individual.registrar(criada.atendimento(), criada.versao(), FrequenciaAssistencial.FALTOU)));
        assertEquals(depois, LegacyMigrationFixture.snapshot(fonte, colunas));
    }

    @Test
    void inscricaoRemocaoEProvisoriaLocaisNaoProduzemFrequenciaAteConfirmacao() {
        var a = novoLocal(UnidadeAtuacao.CAPS_I);
        var b = iniciar(novoPaciente(), a.ator());
        var externo = iniciar(a.paciente(), tx.execute(t -> usuario(UnidadeAtuacao.CAPS_II)));
        var recepcao = tx.execute(t -> {
            var u = usuario(UnidadeAtuacao.CAPS_I);
            u.setTipoUsuario(TipoUsuario.RECEPCAO);
            return u;
        });
        var futura = sessao(a.ator(), List.of(a), LocalDate.now(clock).plusDays(2), 10);
        int v = como(recepcao, () -> coletivo.adicionarParticipante(futura.id(), futura.versao(), b.paciente().getIdPublico()));
        assertThrows(ResourceNotFoundException.class, () -> como(externo.ator(),
                () -> coletivo.adicionarParticipante(futura.id(), v, externo.paciente().getIdPublico())));
        assertEquals(2L, total("select count(*) from tb_sessao_grupo_participante where sessao_grupo_id=?", futura.id()));
        como(recepcao, () -> coletivo.removerParticipante(futura.id(), v, b.paciente().getIdPublico()));
        assertEquals(1L, total("select count(*) from tb_sessao_grupo_participante where sessao_grupo_id=?", futura.id()));
        var passada = sessao(a.ator(), List.of(a,b), diasAtras(2), 10);
        int pv = como(a.ator(), () -> coletivo.registrarProvisoria(passada.id(), passada.versao(),
                a.paciente().getIdPublico(), StatusPresencaGrupo.PRESENTE));
        assertEquals(0, carregar(a).getCountFaltas());
        assertNull(carregar(a).getDataUltimaPresenca());
        assertEquals(0L, total("select count(*) from tb_historico_paciente where sessao_grupo_id=?", passada.id()));
        assertThrows(org.springframework.security.access.AccessDeniedException.class, () -> como(recepcao,
                () -> coletivo.registrarProvisoria(passada.id(), pv, b.paciente().getIdPublico(), StatusPresencaGrupo.FALTOU)));
        var f = new com.pet.buscaativa.entities.dto.FrequenciaParticipanteGrupoDTO(a.paciente().getIdPublico(), StatusPresencaGrupo.PRESENTE);
        var duplicado = new com.pet.buscaativa.entities.dto.ConfirmarOcorrenciaGrupoDTO(true, List.of(f,f), null, pv);
        assertThrows(ConflictException.class, () -> como(a.ator(), () -> comandosLocais.confirmarOcorrencia(passada.id(), duplicado)));
        var dto = new com.pet.buscaativa.entities.dto.ConfirmarOcorrenciaGrupoDTO(true, List.of(f), null, pv);
        como(a.ator(), () -> comandosLocais.confirmarOcorrencia(passada.id(), dto));
        assertEquals(diasAtras(2), carregar(a).getDataUltimaPresenca());
        assertEquals(1, carregar(b).getCountFaltas());
        assertEquals(0, carregar(externo).getCountFaltas());
        assertEquals(2L, total("select count(*) from tb_historico_paciente where sessao_grupo_id=?", passada.id()));
    }

    @Test
    void cancelamentoColetivoDescartaSomenteProvisoriasSemCriarFaltas() {
        var a = novoLocal(UnidadeAtuacao.CAPS_II);
        var s = sessao(a.ator(), List.of(a), diasAtras(2), 10);
        int v = como(a.ator(), () -> coletivo.registrarProvisoria(s.id(), s.versao(),
                a.paciente().getIdPublico(), StatusPresencaGrupo.FALTOU));
        var dto = new com.pet.buscaativa.entities.dto.ConfirmarOcorrenciaGrupoDTO(false, null, "Sessão não ocorreu.", v);
        como(a.ator(), () -> comandosLocais.confirmarOcorrencia(s.id(), dto));
        assertEquals(1L, total("select count(*) from tb_sessao_grupo where id=? and status='CANCELADA'", s.id()));
        assertEquals(1L, total("select count(*) from tb_sessao_grupo_participante where sessao_grupo_id=? and status_presenca='NAO_REGISTRADA'", s.id()));
        assertEquals(0, carregar(a).getCountFaltas());
        assertEquals(0L, total("select count(*) from tb_historico_paciente where sessao_grupo_id=?", s.id()));
    }

    @Test
    void correcaoDeEmpateNaoRemovePendenciaQuandoOutroFatoSegueSemOrigem() {
        var a = novoLocal(UnidadeAtuacao.CAPS_AD);
        var primeira = reserva(a, diasAtras(3), 9, SituacaoAtendimento.AGENDADO, true);
        var presente = como(a.ator(), () -> individual.registrar(primeira.id(), primeira.versao(), FrequenciaAssistencial.PRESENTE));
        var segunda = reserva(a, diasAtras(3), 9, SituacaoAtendimento.AGENDADO, true);
        como(a.ator(), () -> individual.registrar(segunda.id(), segunda.versao(), FrequenciaAssistencial.FALTOU));
        assertEquals(Boolean.TRUE, carregar(a).getPendenciaEmpateLocal());
        reserva(a, diasAtras(4), 10, SituacaoAtendimento.FALTOU, false);
        var dto = new com.pet.buscaativa.entities.dto.CorrigirFrequenciaDTO(FrequenciaAssistencial.FALTOU, presente.versaoFato(), JUSTIFICATIVA);
        var resultado = como(a.ator(), () -> comandosLocais.corrigirIndividual(primeira.id(), dto));
        assertEquals(Situacao.INCONCLUSIVO, resultado.indicadores().situacao());
        assertTrue(carregar(a).getIndicadoresPendentes());
        assertNull(carregar(a).getPendenciaEmpateLocal());
        assertEquals(0, carregar(a).getCountFaltas());
    }

    @Test
    void cancelamentoRecuperacaoERemarcacaoAdministrativaPreservamVinculoEFatos() {
        var a = novoLocal(UnidadeAtuacao.CAPS_AD);
        var externo = iniciar(a.paciente(), tx.execute(t -> usuario(UnidadeAtuacao.CAPS_I)));
        var novoProf = tx.execute(t -> usuario(UnidadeAtuacao.CAPS_AD));
        var recepcao = tx.execute(t -> {
            var u = usuario(UnidadeAtuacao.CAPS_AD);
            u.setTipoUsuario(TipoUsuario.RECEPCAO);
            return u;
        });
        var data = LocalDate.now(clock).plusDays(1);
        var dataNova = data.plusDays(1);
        tx.executeWithoutResult(t -> {
            for (var u : List.of(a.ator(), novoProf)) {
                var oferta = new DisponibilidadeExcecao();
                oferta.setUsuario(em.find(Usuario.class, u.getId()));
                oferta.setData(u.getId().equals(a.ator().getId()) ? data : dataNova);
                oferta.setTurno(TurnoEnum.MANHA);
                oferta.setCapacidade(10);
                em.persist(oferta);
            }
        });
        var criada = como(recepcao, () -> reservasLocais.criar(new com.pet.buscaativa.entities.dto.AgendamentoDTO(
                null, a.ator().getIdPublico(), null, a.paciente().getIdPublico(), null, null,
                data, TurnoEnum.MANHA, LocalTime.of(9, 0), null, null, null)));
        var cancelada = como(recepcao, () -> comandosLocais.atualizarStatusIndividual(criada.atendimento(),
                SituacaoAtendimento.CANCELADO, criada.versao(), null));
        assertEquals(1L, total("select count(*) from tb_agendamento where id=? and situacao_reserva='CANCELADA' and frequencia_assistencial='NAO_REGISTRADA'", criada.atendimento()));
        var dtoRecuperar = new com.pet.buscaativa.entities.dto.RecuperarReservaCanceladaDTO(cancelada.versaoFato(), JUSTIFICATIVA);
        assertThrows(org.springframework.security.access.AccessDeniedException.class,
                () -> como(recepcao, () -> comandosLocais.recuperarReserva(criada.atendimento(), dtoRecuperar)));
        var recuperada = como(a.ator(), () -> comandosLocais.recuperarReserva(criada.atendimento(), dtoRecuperar));
        assertEquals(1L, total("select count(*) from tb_auditoria_regularizacao_cancelamento where agendamento_id=? and tipo_regularizacao='RECUPERACAO_RESERVA'", criada.atendimento()));
        var remarcacao = new com.pet.buscaativa.entities.dto.AgendamentoDTO(null, novoProf.getIdPublico(), null,
                a.paciente().getIdPublico(), null, null, dataNova, TurnoEnum.MANHA, LocalTime.of(9, 0),
                null, criada.atendimento(), recuperada.versaoFato());
        var sucessor = como(recepcao, () -> individual.remarcarPorCadastro(remarcacao));
        assertEquals(1L, total("select count(*) from tb_agendamento where id=? and situacao_reserva='SUBSTITUIDA'", criada.atendimento()));
        assertEquals(1L, total("select count(*) from tb_agendamento where id=? and usuario_id=? and agendamento_original_id=? and unidade_origem_chave='CAPS_AD'", sucessor.atendimento(), novoProf.getId(), criada.atendimento()));
        assertEquals(0, carregar(a).getCountFaltas());
        assertEquals(0, carregar(externo).getCountFaltas());
    }

    @Test
    void patchClinicoCorrigeSobLockSemApagarFatoPorCancelamento() {
        var a = novoLocal(UnidadeAtuacao.CAPS_I);
        var reserva = reserva(a, diasAtras(2), 9, SituacaoAtendimento.AGENDADO, true);
        var falta = como(a.ator(), () -> comandosLocais.atualizarStatusIndividual(reserva.id(),
                SituacaoAtendimento.FALTOU, reserva.versao(), null));
        assertEquals(1, carregar(a).getCountFaltas());
        assertThrows(ConflictException.class, () -> como(a.ator(), () -> comandosLocais.atualizarStatusIndividual(
                reserva.id(), SituacaoAtendimento.CANCELADO, falta.versaoFato(), null)));
        var presente = como(a.ator(), () -> comandosLocais.atualizarStatusIndividual(reserva.id(),
                SituacaoAtendimento.PRESENTE, falta.versaoFato(), JUSTIFICATIVA));
        assertEquals(0, carregar(a).getCountFaltas());
        assertEquals(1L, total("select count(*) from tb_auditoria_correcao_frequencia where agendamento_id=?", reserva.id()));
        var repetido = como(a.ator(), () -> comandosLocais.atualizarStatusIndividual(reserva.id(),
                SituacaoAtendimento.PRESENTE, presente.versaoFato(), null));
        assertEquals(presente.versaoFato(), repetido.versaoFato());
    }

    @Test
    void criaSerieEProximaSessaoPorVinculoSemFrequenciaOuAcessoExterno() {
        var a = novoLocal(UnidadeAtuacao.CAPS_II);
        var externo = iniciar(a.paciente(), tx.execute(t -> usuario(UnidadeAtuacao.CAPS_I)));
        var data = LocalDate.now(clock).plusDays(1);
        var dto = new com.pet.buscaativa.entities.dto.CriarGrupoDTO("Grupo local", a.ator().getIdPublico(),
                RecorrenciaGrupo.SEMANAL, data, data.plusWeeks(1), LocalTime.of(10, 0), List.of(a.paciente().getIdPublico()));
        var colunas = LegacyMigrationFixture.columns(fonte);
        var antes = LegacyMigrationFixture.snapshot(fonte, colunas);
        var criada = como(a.ator(), () -> seriesLocais.criar(dto));
        assertEquals(2, criada.sessoes().size());
        var nova = new com.pet.buscaativa.entities.dto.NovaSessaoDTO(criada.grupo(), data.plusWeeks(2), null,
                List.of(a.paciente().getIdPublico()));
        assertThrows(ResourceNotFoundException.class, () -> como(externo.ator(), () -> seriesLocais.criarProxima(nova)));
        var proxima = como(a.ator(), () -> seriesLocais.criarProxima(nova));
        assertNotNull(proxima.sessao());
        assertThrows(ConflictException.class, () -> como(a.ator(), () -> seriesLocais.criarProxima(nova)));
        assertEquals(3L, total("select count(*) from tb_sessao_grupo where grupo_id=? and unidade_origem_chave='CAPS_II'", criada.grupo()));
        assertEquals(3L, total("select count(*) from tb_sessao_grupo_participante p join tb_sessao_grupo s on s.id=p.sessao_grupo_id where s.grupo_id=? and p.status_presenca='NAO_REGISTRADA'", criada.grupo()));
        var depois = LegacyMigrationFixture.snapshot(fonte, colunas);
        for (String tabela : List.of("TB_PACIENTE", "TB_VINCULO_ASSISTENCIAL", "TB_HISTORICO_PACIENTE"))
            assertEquals(antes.get(tabela), depois.get(tabela), tabela);
    }

    @Test
    void transfereGrupoSemAlterarResponsabilidadeHistoricaEInscreveRetroativamente() {
        var a = novoLocal(UnidadeAtuacao.CAPS_AD);
        var b = iniciar(novoPaciente(),a.ator());
        var externo = iniciar(b.paciente(),tx.execute(t -> usuario(UnidadeAtuacao.CAPS_I)));
        var novo = tx.execute(t -> usuario(UnidadeAtuacao.CAPS_AD));
        var passada = sessao(a.ator(),List.of(a),diasAtras(3),10);
        como(a.ator(), () -> coletivo.confirmar(passada.id(),passada.versao(),Map.of()));
        Long grupoId = total("select grupo_id from tb_sessao_grupo where id=?",passada.id());
        tx.executeWithoutResult(t -> {
            var g=em.find(GrupoTerapeutico.class,grupoId);
            g.setRecorrencia(RecorrenciaGrupo.SEMANAL);
            g.setHorarioPadrao(LocalTime.of(10,0));
        });
        var futura = como(a.ator(), () -> seriesLocais.criarProxima(new com.pet.buscaativa.entities.dto.NovaSessaoDTO(
                grupoId,LocalDate.now(clock).plusDays(2),null,List.of(a.paciente().getIdPublico()))));
        int versao = (int)total("select version from tb_grupo_terapeutico where id=?",grupoId);
        var atualizar = new com.pet.buscaativa.entities.dto.AtualizarGrupoTerapeuticoDTO("Transferido",novo.getIdPublico(),
                diasAtras(3),LocalTime.of(10,0),RecorrenciaGrupo.SEMANAL,null,versao);
        como(a.ator(), () -> seriesLocais.atualizar(grupoId,atualizar));
        var antes = como(novo, () -> dtosLocais.sessao(passada.id()));
        assertEquals(a.ator().getIdPublico(),antes.responsavelAtendimentoId());
        assertEquals(novo.getNome(),antes.nomeCoordenador());
        var dto = new com.pet.buscaativa.entities.dto.InscricaoRetroativaGrupoDTO(b.paciente().getIdPublico(),
                List.of(new com.pet.buscaativa.entities.dto.FrequenciaSessaoRetroativaDTO(passada.id(),StatusPresencaGrupo.FALTOU)));
        como(novo, () -> seriesLocais.inscreverRetroativamente(grupoId,dto));
        assertEquals(1,carregar(b).getCountFaltas());
        assertEquals(0,carregar(externo).getCountFaltas());
        assertEquals(1L,total("select count(*) from tb_inscricao_retroativa_grupo_auditoria where grupo_id=? and paciente_id=?",grupoId,b.paciente().getId()));
        var depois=como(novo, () -> dtosLocais.sessao(passada.id()));
        assertEquals(a.ator().getIdPublico(),depois.responsavelAtendimentoId());
        assertEquals(2,depois.quantidadeParticipantes());
        assertThrows(ResourceNotFoundException.class,() -> como(externo.ator(), () -> dtosLocais.sessao(passada.id())));
        como(novo, () -> seriesLocais.removerFuturas(grupoId,b.paciente().getIdPublico()));
        assertEquals(1L,total("select count(*) from tb_sessao_grupo_participante where sessao_grupo_id=? and paciente_id=?",passada.id(),b.paciente().getId()));
        assertEquals(0L,total("select count(*) from tb_sessao_grupo_participante where sessao_grupo_id=? and paciente_id=?",futura.sessao(),b.paciente().getId()));
        assertEquals(1,carregar(b).getCountFaltas());
        como(novo, () -> seriesLocais.inscreverFuturas(grupoId,new com.pet.buscaativa.entities.dto.InscricaoFuturaGrupoDTO(b.paciente().getIdPublico())));
        assertEquals(1L,total("select count(*) from tb_sessao_grupo_participante where sessao_grupo_id=? and paciente_id=?",futura.sessao(),b.paciente().getId()));
    }

    @Test
    void regeneraApenasSerieFuturaHomogeneaERecusaVersaoAntiga() {
        var a=novoLocal(UnidadeAtuacao.CAPS_II);
        var data=LocalDate.now(clock).plusDays(2);
        var dto=new com.pet.buscaativa.entities.dto.CriarGrupoDTO("Série futura",a.ator().getIdPublico(),
                RecorrenciaGrupo.SEMANAL,data,data.plusWeeks(1),LocalTime.of(10,0),List.of(a.paciente().getIdPublico()));
        var criada=como(a.ator(), () -> seriesLocais.criar(dto));
        var atualizar=new com.pet.buscaativa.entities.dto.AtualizarGrupoTerapeuticoDTO("Nova série",a.ator().getIdPublico(),
                data.plusDays(1),LocalTime.of(11,0),RecorrenciaGrupo.SEMANAL,data.plusDays(1).plusWeeks(2),criada.versaoGrupo());
        var nova=como(a.ator(), () -> seriesLocais.atualizar(criada.grupo(),atualizar));
        assertEquals(3,nova.sessoes().size());
        assertTrue(java.util.Collections.disjoint(criada.sessoes(),nova.sessoes()));
        assertThrows(ValidationException.class,() -> como(a.ator(), () -> seriesLocais.atualizar(criada.grupo(),atualizar)));
        assertEquals(0,carregar(a).getCountFaltas());
    }

    @Test
    void dtoIndividualUsaTipoLocalENegaUnidadeExterna() {
        var a=novoLocal(UnidadeAtuacao.CAPS_AD);
        var externo=iniciar(a.paciente(),tx.execute(t -> usuario(UnidadeAtuacao.CAPS_I)));
        var ag=reserva(a,diasAtras(2),9,SituacaoAtendimento.AGENDADO,true);
        var dto=como(a.ator(), () -> dtosLocais.atendimento(ag.id()));
        assertEquals(TipoAcompanhamento.AMBOS,dto.tipoAcompanhamento());
        assertEquals(a.paciente().getIdPublico(),dto.pacienteId());
        assertThrows(ResourceNotFoundException.class,() -> como(externo.ator(), () -> dtosLocais.atendimento(ag.id())));
    }

    @Test
    void inativacaoAdministrativaLocalEReativacaoNaoAfetamOutraUnidade() {
        var a=novoLocal(UnidadeAtuacao.CAPS_AD);
        var externo=iniciar(a.paciente(),tx.execute(t -> usuario(UnidadeAtuacao.CAPS_I)));
        var prof=tx.execute(t -> {
            var u=usuario(UnidadeAtuacao.CAPS_AD);
            u.setTipoUsuario(TipoUsuario.PROFISSIONAL);
            return u;
        });
        var versao=carregar(a).getVersion();
        assertThrows(org.springframework.security.access.AccessDeniedException.class,() -> como(prof,
                () -> comandosEstado.inativar(a.paciente().getIdPublico(),a.vinculo(),versao)));
        assertThrows(ValidationException.class,() -> como(a.ator(),() -> comandosEstado.encerrar(
                a.paciente().getIdPublico(),a.vinculo(),versao,new EncerramentoPacienteDTO(
                        MotivoEncerramento.OBITO,StatusPaciente.OBITO,null))));
        var colunas=LegacyMigrationFixture.columns(fonte);
        var antes=LegacyMigrationFixture.snapshot(fonte,colunas);
        var inativo=como(a.ator(),() -> comandosEstado.inativar(a.paciente().getIdPublico(),a.vinculo(),versao));
        assertEquals(StatusPaciente.INATIVO,inativo.status());
        assertEquals(ClassificacaoRisco.VERDE,carregar(a).getClassificacaoRisco());
        assertFalse(carregar(a).getGatilhoVisitaAcionado());
        assertEquals(StatusPaciente.ATIVO,carregar(externo).getStatusPaciente());
        como(a.ator(),() -> comandosEstado.reativar(a.paciente().getIdPublico(),a.vinculo(),inativo.versao(),
                new com.pet.buscaativa.entities.dto.ReativacaoPacienteDTO(JUSTIFICATIVA)));
        assertEquals(StatusPaciente.ATIVO,carregar(a).getStatusPaciente());
        var depois=LegacyMigrationFixture.snapshot(fonte,colunas);
        assertEquals(antes.get("TB_PACIENTE"),depois.get("TB_PACIENTE"));
        assertEquals(antes.get("TB_PACIENTE_AUD"),depois.get("TB_PACIENTE_AUD"));
    }

    @Test
    void historicoManualNaoCriaFrequenciaNemPermiteAcessoExterno() {
        var a=novoLocal(UnidadeAtuacao.CAPS_II);
        var externo=iniciar(a.paciente(),tx.execute(t -> usuario(UnidadeAtuacao.CAPS_I)));
        var versao=carregar(a).getVersion();
        var dto=new com.pet.buscaativa.entities.dto.RegistroHistoricoPacienteDTO(TipoEventoHistoricoPaciente.BUSCA_ATIVA,
                null,"Registro sintético de contato.");
        assertThrows(ResourceNotFoundException.class,() -> como(externo.ator(),
                () -> historicoManual.registrar(a.paciente().getIdPublico(),a.vinculo(),versao,dto)));
        var colunas=LegacyMigrationFixture.columns(fonte);
        var antes=LegacyMigrationFixture.snapshot(fonte,colunas);
        var id=como(a.ator(),() -> historicoManual.registrar(a.paciente().getIdPublico(),a.vinculo(),versao,dto));
        assertEquals(1L,total("select count(*) from tb_historico_paciente where id=? and unidade_origem_chave='CAPS_II' and agendamento_id is null and sessao_grupo_id is null",id));
        var depois=LegacyMigrationFixture.snapshot(fonte,colunas);
        for (var tabela:List.of("TB_PACIENTE","TB_VINCULO_ASSISTENCIAL","TB_AGENDAMENTO","TB_SESSAO_GRUPO_PARTICIPANTE"))
            assertEquals(antes.get(tabela),depois.get(tabela),tabela);
    }

    @Test
    void monitoramentoTecnicoRecalculaPorVinculoSemCriarFatoOuReabrirAlta() {
        var a=novoLocal(UnidadeAtuacao.CAPS_AD);
        var externo=iniciar(a.paciente(),tx.execute(t -> usuario(UnidadeAtuacao.CAPS_I)));
        var antigo=reserva(a,diasAtras(40),9,SituacaoAtendimento.AGENDADO,true);
        como(a.ator(),() -> individual.registrar(antigo.id(),antigo.versao(),FrequenciaAssistencial.PRESENTE));
        assertEquals(ClassificacaoRisco.VERMELHO,carregar(a).getClassificacaoRisco());
        tx.executeWithoutResult(t -> vinculos.findByIdPublico(a.vinculo()).orElseThrow().aplicarIndicadoresLocais(
                0,diasAtras(40),ClassificacaoRisco.VERDE,false));
        var fechado=como(externo.ator(),() -> estado.encerrar(externo.paciente().getIdPublico(),externo.vinculo(),carregar(externo).getVersion(),
                new EncerramentoPacienteDTO(MotivoEncerramento.ALTA_TERAPEUTICA,StatusPaciente.ALTA_TERAPEUTICA,null)));
        var colunas=LegacyMigrationFixture.columns(fonte);
        var antes=LegacyMigrationFixture.snapshot(fonte,colunas);
        assertThrows(org.springframework.security.access.AccessDeniedException.class,() -> como(a.ator(),monitorLocal::executar));
        var resultado=monitorLocal.executar();
        assertTrue(resultado.atualizados()>=1);
        assertEquals(0,resultado.falhas());
        assertEquals(ClassificacaoRisco.VERMELHO,carregar(a).getClassificacaoRisco());
        assertEquals(fechado.versao(),carregar(externo).getVersion());
        assertEquals(ClassificacaoRisco.VERDE,carregar(externo).getClassificacaoRisco());
        var depois=LegacyMigrationFixture.snapshot(fonte,colunas);
        for (var tabela:List.of("TB_PACIENTE","TB_PACIENTE_AUD","TB_AGENDAMENTO","TB_HISTORICO_PACIENTE"))
            assertEquals(antes.get(tabela),depois.get(tabela),tabela);
        var repetido=monitorLocal.executar();
        assertEquals(0,repetido.atualizados());
        assertEquals(0,repetido.falhas());
        assertEquals(depois,LegacyMigrationFixture.snapshot(fonte,colunas));
    }

    private static <T> T apos(CountDownLatch partir, Supplier<T> comando) throws InterruptedException {
        assertTrue(partir.await(10, TimeUnit.SECONDS));
        return comando.get();
    }

    private boolean tentativa(Local local, Agenda a, FrequenciaAssistencial frequencia) {
        try {
            como(local.ator(), () -> individual.registrar(a.id(), a.versao(), frequencia));
            return true;
        } catch (ConflictException | ValidationException concorrente) {
            return false;
        }
    }

    private void aguardarBloqueio() {
        long limite = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
        while (System.nanoTime() < limite) {
            if (total("select count(*) from information_schema.sessions where blocker_id = session_id()") > 0) return;
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                fail(e);
            }
        }
        fail("A transação concorrente não aguardou a trava da identidade.");
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
