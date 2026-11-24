package com.pet.buscaativa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionTemplate;

import com.pet.buscaativa.config.DevConfig;
import com.pet.buscaativa.entities.Agendamento;
import com.pet.buscaativa.entities.ChaveUnidadeAssistencial;
import com.pet.buscaativa.entities.GrupoTerapeutico;
import com.pet.buscaativa.entities.HistoricoPaciente;
import com.pet.buscaativa.entities.Paciente;
import com.pet.buscaativa.entities.PendenciaRevisaoProveniencia.TipoRegistro;
import com.pet.buscaativa.entities.SessaoGrupo;
import com.pet.buscaativa.entities.SessaoGrupoParticipante;
import com.pet.buscaativa.entities.Usuario;
import com.pet.buscaativa.entities.VinculoAssistencial;
import com.pet.buscaativa.entities.dto.AlertaBuscaAtivaDTO;
import com.pet.buscaativa.entities.enums.ClassificacaoRisco;
import com.pet.buscaativa.entities.enums.FrequenciaAssistencial;
import com.pet.buscaativa.entities.enums.SituacaoAtendimento;
import com.pet.buscaativa.entities.enums.SituacaoReserva;
import com.pet.buscaativa.entities.enums.StatusPaciente;
import com.pet.buscaativa.entities.enums.StatusPresencaGrupo;
import com.pet.buscaativa.entities.enums.StatusSessaoGrupo;
import com.pet.buscaativa.entities.enums.TipoAcompanhamento;
import com.pet.buscaativa.entities.enums.TipoEventoHistoricoPaciente;
import com.pet.buscaativa.repositories.UsuarioRepository;
import com.pet.buscaativa.services.PacienteService;
import com.pet.buscaativa.services.exceptions.ResourceNotFoundException;
import com.pet.buscaativa.services.impl.DiagnosticoConsolidadoMigracaoClinicaService;
import com.pet.buscaativa.services.impl.DiagnosticoConsolidadoMigracaoClinicaService.Proveniencia;
import com.pet.buscaativa.services.impl.VerificacaoFatoRevisaoProvenienciaService;

import jakarta.persistence.EntityManager;

class DevDemonstracaoIntegrationTests {
    private static final LocalDate DATA = LocalDate.of(2026, 11, 24);

    @TestConfiguration(proxyBeanMethods = false)
    static class RelogioFixo {
        @Bean
        @Primary
        Clock relogioDemonstracao() {
            var zona = ZoneId.of("America/Bahia");
            return Clock.fixed(DATA.atTime(16, 0).atZone(zona).toInstant(), zona);
        }
    }

    private record FatosEmDuasUnidades(UUID pessoa, Long agenda, Long sessao,
            Usuario profissionalDaAgenda, Usuario profissionalDaSessao) {}

    @Test
    void inicializacaoLimpaProduzOrigensComprovadasERf22SemForcarGatilho() {
        new WebApplicationContextRunner()
                .withInitializer(context -> {
                    TestEnvironmentIsolation.apply(context.getEnvironment());
                    new ConfigDataApplicationContextInitializer().initialize(context);
                })
                .withUserConfiguration(BuscaativaApplication.class, H2ProfileSecurityTests.ExternalServices.class,
                        RelogioFixo.class)
                .withPropertyValues("spring.config.location=file:src/main/resources/",
                        "spring.profiles.active=dev,diagnostico-migracao",
                        "spring.datasource.url=jdbc:h2:mem:demo-" + UUID.randomUUID(),
                        "JWT_SECRET_BASE64=" + Base64.getEncoder().encodeToString(SecurityTestSupport.randomBytes()),
                        "DEV_DB_PASSWORD=" + UUID.randomUUID(), "logging.level.root=ERROR")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    // O runner de contexto não executa CommandLineRunner automaticamente.
                    context.getBean(DevConfig.class).run();
                    conferirInventario(context);
                    var fatos = context.getBean(TransactionTemplate.class).execute(tx ->
                            conferirFatos(context, context.getBean(EntityManager.class)));
                    conferirIsolamento(context.getBean(VerificacaoFatoRevisaoProvenienciaService.class), fatos);
                });
    }

    private void conferirInventario(ApplicationContext context) {
        var banco = context.getBean(DataSource.class);
        var colunas = LegacyMigrationFixture.columns(banco);
        var antes = LegacyMigrationFixture.snapshot(banco, colunas);
        var relatorio = context.getBean(DiagnosticoConsolidadoMigracaoClinicaService.class).analisar();
        assertThat(relatorio.proveniencia()).containsExactlyInAnyOrderEntriesOf(Map.of(
                DiagnosticoConsolidadoMigracaoClinicaService.TipoRegistro.AGENDAMENTO, new Proveniencia(23, 0, 0),
                DiagnosticoConsolidadoMigracaoClinicaService.TipoRegistro.GRUPO, new Proveniencia(3, 0, 0),
                DiagnosticoConsolidadoMigracaoClinicaService.TipoRegistro.SESSAO, new Proveniencia(13, 0, 0),
                DiagnosticoConsolidadoMigracaoClinicaService.TipoRegistro.PARTICIPACAO, new Proveniencia(23, 0, 0),
                DiagnosticoConsolidadoMigracaoClinicaService.TipoRegistro.HISTORICO, new Proveniencia(14, 0, 0)));
        assertThat(relatorio.cadastro().totalPacientes()).isEqualTo(10);
        assertThat(relatorio.cadastro().documentosCpfDuplicados()).isZero();
        assertThat(relatorio.cadastro().documentosCnsDuplicados()).isZero();
        assertThat(relatorio.cadastro().vinculosAssistenciaisRegistrados()).isEqualTo(14);
        assertThat(relatorio.vinculosInconsistentes()).isZero();
        assertThat(relatorio.frequenciasExcluidasDoEnsaio()).isZero();
        assertThat(LegacyMigrationFixture.columns(banco)).isEqualTo(colunas);
        assertThat(LegacyMigrationFixture.snapshot(banco, colunas)).isEqualTo(antes);
    }

    private FatosEmDuasUnidades conferirFatos(ApplicationContext context, EntityManager em) {
        var vinculos = em.createQuery("select v from VinculoAssistencial v", VinculoAssistencial.class)
                .getResultList().stream().collect(Collectors.groupingBy(v -> v.getPaciente().getId(),
                        Collectors.mapping(VinculoAssistencial::getUnidadeChave, Collectors.toSet())));
        var agenda = em.createQuery("select a from Agendamento a", Agendamento.class).getResultList();
        agenda.forEach(a -> {
            assertThat(a.getUnidadeOrigemChave()).isEqualTo(ChaveUnidadeAssistencial.doUsuarioAtual(a.getUsuario()));
            assertThat(vinculos.get(a.getPaciente().getId())).contains(a.getUnidadeOrigemChave());
        });
        conferirRemarcacoes(agenda);

        var grupos = em.createQuery("select g from GrupoTerapeutico g", GrupoTerapeutico.class).getResultList();
        grupos.forEach(g -> assertThat(g.getUnidadeOrigemChave())
                .isEqualTo(ChaveUnidadeAssistencial.doUsuarioAtual(g.getCoordenador())));
        var sessoes = em.createQuery("select s from SessaoGrupo s", SessaoGrupo.class).getResultList();
        sessoes.forEach(s -> {
            assertThat(s.getUnidadeOrigemChave()).isEqualTo(s.getGrupo().getUnidadeOrigemChave());
            assertThat(s.getDataSessao()).isNotNull();
            assertThat(s.getHorario()).isNotNull();
            if (s.getStatus() == StatusSessaoGrupo.REALIZADA) {
                assertThat(s.getResponsavelAtendimento().getId()).isEqualTo(s.getGrupo().getCoordenador().getId());
                assertThat(s.getOrigemResponsabilidade()).isEqualTo("CONFIRMACAO_REALIZACAO");
                assertThat(s.isResponsabilidadePendente()).isFalse();
            } else {
                assertThat(s.getResponsavelAtendimento()).isNull();
                assertThat(s.getParticipantes()).allMatch(p -> p.getStatusPresenca() == StatusPresencaGrupo.NAO_REGISTRADA);
            }
            s.getParticipantes().forEach(p -> {
                assertThat(vinculos.get(p.getPaciente().getId())).contains(s.getUnidadeOrigemChave());
                assertThat(p.getPaciente().getTipoAcompanhamento()).isIn(
                        TipoAcompanhamento.AMBOS, TipoAcompanhamento.GRUPO_TERAPEUTICO);
            });
        });
        conferirHistoricos(em);
        conferirBuscaAtiva(context, em, agenda);

        // A mesma identidade tem fatos de agenda no CAPS AD e de grupo no CAPS I.
        var sessao = sessoes.stream().filter(s -> "CAPS_I".equals(s.getUnidadeOrigemChave())
                && s.getStatus() == StatusSessaoGrupo.REALIZADA).findFirst().orElseThrow();
        var dupla = sessao.getParticipantes().stream().map(SessaoGrupoParticipante::getPaciente)
                .filter(p -> agenda.stream().anyMatch(a -> a.getPaciente().getId().equals(p.getId())))
                .findFirst().orElseThrow();
        assertThat(vinculos.get(dupla.getId())).containsExactlyInAnyOrder("CAPS_AD", "CAPS_I");
        var atendimento = agenda.stream().filter(a -> a.getPaciente().getId().equals(dupla.getId()))
                .findFirst().orElseThrow();
        var usuarios = context.getBean(UsuarioRepository.class);
        return new FatosEmDuasUnidades(dupla.getIdPublico(), atendimento.getId(), sessao.getId(),
                usuarios.findById(atendimento.getUsuario().getId()).orElseThrow(),
                usuarios.findById(sessao.getResponsavelAtendimento().getId()).orElseThrow());
    }

    private void conferirRemarcacoes(List<Agendamento> agenda) {
        var sucessores = agenda.stream().filter(a -> a.getAgendamentoOriginal() != null).toList();
        assertThat(sucessores).hasSize(4);
        assertThat(sucessores).extracting(a -> a.getAgendamentoOriginal().getId()).doesNotHaveDuplicates();
        sucessores.forEach(sucessor -> {
            var original = sucessor.getAgendamentoOriginal();
            assertThat(original.getSituacaoReserva()).isEqualTo(SituacaoReserva.SUBSTITUIDA);
            assertThat(sucessor.getSituacaoReserva()).isEqualTo(SituacaoReserva.REMARCADA);
            assertThat(sucessor.getPaciente().getId()).isEqualTo(original.getPaciente().getId());
            assertThat(sucessor.getUnidadeOrigemChave()).isEqualTo(original.getUnidadeOrigemChave());
            assertThat(instante(sucessor)).isAfter(instante(original));
            assertThat(sucessor.isRemarcacaoAposFalta())
                    .isEqualTo(original.getFrequenciaAssistencial() == FrequenciaAssistencial.FALTOU);
        });
        assertThat(sucessores.stream().filter(a -> a.getDataAgendamento().isAfter(DATA)))
                .hasSize(2).allMatch(a -> a.isRemarcacaoAposFalta()
                        && a.getFrequenciaAssistencial() == FrequenciaAssistencial.NAO_REGISTRADA);
    }

    private void conferirHistoricos(EntityManager em) {
        var historicos = em.createQuery("select h from HistoricoPaciente h", HistoricoPaciente.class).getResultList();
        assertThat(historicos.stream().filter(h -> h.getSessaoGrupo() != null)).hasSize(2)
                .extracting(h -> List.of(h.getPaciente().getId(), h.getSessaoGrupo().getId())).doesNotHaveDuplicates();
        historicos.forEach(h -> {
            if (h.getAgendamento() != null) {
                var a = h.getAgendamento();
                assertThat(h.getSessaoGrupo()).isNull();
                assertThat(h.getPaciente().getId()).isEqualTo(a.getPaciente().getId());
                assertThat(h.getProfissional().getId()).isEqualTo(a.getUsuario().getId());
                assertThat(h.getUnidadeOrigemChave()).isEqualTo(a.getUnidadeOrigemChave());
                if (h.getTipo() == TipoEventoHistoricoPaciente.FALTA) {
                    assertThat(a.getFrequenciaAssistencial()).isEqualTo(FrequenciaAssistencial.FALTOU);
                }
            } else if (h.getSessaoGrupo() != null) {
                var s = h.getSessaoGrupo();
                assertThat(s.getStatus()).isEqualTo(StatusSessaoGrupo.REALIZADA);
                assertThat(h.getProfissional().getId()).isEqualTo(s.getResponsavelAtendimento().getId());
                assertThat(h.getUnidadeOrigemChave()).isEqualTo(s.getUnidadeOrigemChave());
                assertThat(s.getParticipantes()).filteredOn(p -> p.getPaciente().getId().equals(h.getPaciente().getId()))
                        .singleElement().satisfies(p -> conferirFrequenciaGrupo(h, p));
            } else {
                assertThat(h.getTipo()).isEqualTo(TipoEventoHistoricoPaciente.SITUACAO_ATUALIZADA);
                assertThat(h.getPaciente().getStatusPaciente()).isEqualTo(StatusPaciente.ALTA_TERAPEUTICA);
                assertThat(h.getUnidadeOrigemChave()).isEqualTo(ChaveUnidadeAssistencial.doUsuarioAtual(h.getProfissional()));
            }
        });
    }

    private void conferirFrequenciaGrupo(HistoricoPaciente historico, SessaoGrupoParticipante participacao) {
        boolean presente = participacao.getStatusPresenca() == StatusPresencaGrupo.PRESENTE;
        assertThat(historico.getTipo()).isEqualTo(presente
                ? TipoEventoHistoricoPaciente.PARTICIPACAO_GRUPO_TERAPEUTICO : TipoEventoHistoricoPaciente.FALTA);
        assertThat(historico.getSituacaoAtendimento()).isEqualTo(presente
                ? SituacaoAtendimento.PRESENTE : SituacaoAtendimento.FALTOU);
    }

    private void conferirBuscaAtiva(ApplicationContext context, EntityManager em, List<Agendamento> agenda) {
        var pacientes = em.createQuery("select p from Paciente p", Paciente.class).getResultList();
        var rua = pacientes.stream().filter(Paciente::isSituacaoRua).findFirst().orElseThrow();
        var faltas = agenda.stream().filter(a -> a.getPaciente().getId().equals(rua.getId()))
                .filter(a -> a.getFrequenciaAssistencial() == FrequenciaAssistencial.FALTOU).toList();
        assertThat(faltas).hasSize(2);
        var remarcado = faltas.stream().filter(Agendamento::isRemarcacaoAposFalta).findFirst().orElseThrow();
        assertThat(faltas).contains(remarcado.getAgendamentoOriginal());
        assertThat(rua.getDataUltimaPresenca()).isBefore(remarcado.getAgendamentoOriginal().getDataAgendamento());
        assertThat(rua.getCountFaltas()).isEqualTo(2);
        assertThat(rua.getClassificacaoRisco()).isEqualTo(ClassificacaoRisco.AMARELO);
        assertThat(rua.getGatilhoVisitaAcionado()).isTrue();

        var usuarios = context.getBean(UsuarioRepository.class);
        var buscaAtiva = context.getBean(PacienteService.class);
        var profissionalAd = usuarios.findByEmail("profissional@pet.com").orElseThrow();
        var profissionalIi = usuarios.findByEmail("maria.santos@pet.com").orElseThrow();
        como(profissionalAd, () -> {
            assertThat(buscaAtiva.listarPacientesEmBuscaAtiva()).filteredOn(a -> a.idPublico().equals(rua.getIdPublico()))
                    .singleElement().satisfies(a -> {
                        assertThat(a.alerta()).isEqualTo("Busca em ponto de referência necessária");
                        assertThat(a.avisoAdicional()).contains("Nenhum ponto de referência cadastrado");
                    });
            return null;
        });

        var alertasIi = como(profissionalIi, () -> buscaAtiva.listarPacientesEmBuscaAtiva());
        assertThat(alertasIi).extracting(AlertaBuscaAtivaDTO::idPublico).doesNotContain(rua.getIdPublico());
        assertThat(alertasIi).anySatisfy(a -> {
            assertThat(a.classificacaoRisco()).isEqualTo(ClassificacaoRisco.VERMELHO);
            assertThat(a.alerta()).isNull();
            assertThat(pacientes.stream().filter(p -> p.getIdPublico().equals(a.idPublico())))
                    .singleElement().satisfies(p -> assertThat(p.getGatilhoVisitaAcionado()).isFalse());
        });
    }

    private void conferirIsolamento(VerificacaoFatoRevisaoProvenienciaService verificacao, FatosEmDuasUnidades fatos) {
        var agendaLocal = como(fatos.profissionalDaAgenda(),
                () -> verificacao.verificar(fatos.pessoa(), TipoRegistro.AGENDAMENTO, fatos.agenda()));
        assertThat(agendaLocal.unidadeChave()).isEqualTo("CAPS_AD");
        assertThrows(ResourceNotFoundException.class, () -> como(fatos.profissionalDaAgenda(),
                () -> verificacao.verificar(fatos.pessoa(), TipoRegistro.SESSAO_GRUPO, fatos.sessao())));

        var sessaoLocal = como(fatos.profissionalDaSessao(),
                () -> verificacao.verificar(fatos.pessoa(), TipoRegistro.SESSAO_GRUPO, fatos.sessao()));
        assertThat(sessaoLocal.unidadeChave()).isEqualTo("CAPS_I");
        assertThrows(ResourceNotFoundException.class, () -> como(fatos.profissionalDaSessao(),
                () -> verificacao.verificar(fatos.pessoa(), TipoRegistro.AGENDAMENTO, fatos.agenda())));
    }

    private static LocalDateTime instante(Agendamento a) {
        return a.getDataAgendamento().atTime(a.getHoraAtendimento());
    }

    private static <T> T como(Usuario usuario, Supplier<T> acao) {
        var anterior = SecurityContextHolder.getContext();
        var contexto = SecurityContextHolder.createEmptyContext();
        contexto.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(usuario, null, usuario.getAuthorities()));
        SecurityContextHolder.setContext(contexto);
        try {
            return acao.get();
        } finally {
            SecurityContextHolder.setContext(anterior);
        }
    }
}
