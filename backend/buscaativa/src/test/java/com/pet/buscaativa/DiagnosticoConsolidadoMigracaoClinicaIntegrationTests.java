package com.pet.buscaativa;

import static com.pet.buscaativa.services.impl.DiagnosticoConsolidadoMigracaoClinicaService.TipoRegistro.AGENDAMENTO;
import static com.pet.buscaativa.services.impl.DiagnosticoConsolidadoMigracaoClinicaService.TipoRegistro.GRUPO;
import static com.pet.buscaativa.services.impl.DiagnosticoConsolidadoMigracaoClinicaService.TipoRegistro.HISTORICO;
import static com.pet.buscaativa.services.impl.DiagnosticoConsolidadoMigracaoClinicaService.TipoRegistro.PARTICIPACAO;
import static com.pet.buscaativa.services.impl.DiagnosticoConsolidadoMigracaoClinicaService.TipoRegistro.SESSAO;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.UUID;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

import com.pet.buscaativa.entities.Agendamento;
import com.pet.buscaativa.entities.GrupoTerapeutico;
import com.pet.buscaativa.entities.HistoricoPaciente;
import com.pet.buscaativa.entities.Paciente;
import com.pet.buscaativa.entities.PendenciaRevisaoProveniencia.TipoRegistro;
import com.pet.buscaativa.entities.SessaoGrupo;
import com.pet.buscaativa.entities.SessaoGrupoParticipante;
import com.pet.buscaativa.entities.UsfReferencia;
import com.pet.buscaativa.entities.Usuario;
import com.pet.buscaativa.entities.VinculoAssistencial;
import com.pet.buscaativa.entities.enums.SituacaoAtendimento;
import com.pet.buscaativa.entities.enums.StatusPresencaGrupo;
import com.pet.buscaativa.entities.enums.StatusSessaoGrupo;
import com.pet.buscaativa.entities.enums.TipoEventoHistoricoPaciente;
import com.pet.buscaativa.entities.enums.TipoUsuario;
import com.pet.buscaativa.entities.enums.UnidadeAtuacao;
import com.pet.buscaativa.migration.AgendaMigrationTool;
import com.pet.buscaativa.migration.CadastroUnicoPreflight;
import com.pet.buscaativa.services.exceptions.ResourceNotFoundException;
import com.pet.buscaativa.services.impl.DiagnosticoConsolidadoMigracaoClinicaService;
import com.pet.buscaativa.services.impl.DiagnosticoConsolidadoMigracaoClinicaService.Proveniencia;
import com.pet.buscaativa.services.impl.DiagnosticoConsolidadoMigracaoClinicaService.ProntidaoTecnica;
import com.pet.buscaativa.services.impl.DiagnosticoFaltasConsecutivasVinculoService;
import com.pet.buscaativa.services.impl.EscopoUnidadeService;
import com.pet.buscaativa.services.impl.InventarioFrequenciasVinculoService;
import com.pet.buscaativa.services.impl.LinhaDoTempoFrequenciasVinculoService;
import com.pet.buscaativa.services.impl.SusApiAdapter;
import com.pet.buscaativa.services.impl.VerificacaoFatoRevisaoProvenienciaService;

import jakarta.persistence.EntityManager;

@ActiveProfiles("diagnostico-migracao")
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:ensaio-prontidao-63;DB_CLOSE_DELAY=-1")
class DiagnosticoConsolidadoMigracaoClinicaIntegrationTests extends SecurityTestSupport {
    private static final LocalDate DATA = LocalDate.of(2026, 11, 3);

    @Autowired DataSource fonte;
    @Autowired EntityManager em;
    @Autowired TransactionTemplate transacoes;
    @Autowired DiagnosticoConsolidadoMigracaoClinicaService consolidado;
    @Autowired LinhaDoTempoFrequenciasVinculoService linhaDoTempo;
    @Autowired InventarioFrequenciasVinculoService inventario;
    @Autowired DiagnosticoFaltasConsecutivasVinculoService faltas;
    @Autowired VerificacaoFatoRevisaoProvenienciaService verificador;
    @MockitoBean EscopoUnidadeService escopo;
    @MockitoBean SusApiAdapter susApi;
    @TempDir Path temp;

    private record Cenario(Paciente paciente, Usuario usfA, Usuario usfB, Usuario capsAd, Usuario capsII,
            Long agendaA, Long agendaB, Long agendaAd, Long agendaII, Long cadeiaConflitante,
            Long historicoSessaoAgendada, Long sessaoSemResponsavel, Long historicoManualLocal) {}

    @Test
    void preservaBancoAuditoriaEIsolamentoAoEnsaiarDiagnosticoEReabrirBackup() throws Exception {
        var c = transacoes.execute(status -> prepararCenario());
        var colunas = LegacyMigrationFixture.columns(fonte);
        var antes = LegacyMigrationFixture.snapshot(fonte, colunas);
        assertFalse(antes.get("REVINFO").isEmpty());
        for (String tabela : new String[]{"TB_PACIENTE", "TB_VINCULO_ASSISTENCIAL", "TB_AGENDAMENTO",
                "TB_GRUPO_TERAPEUTICO", "TB_SESSAO_GRUPO", "TB_SESSAO_GRUPO_PARTICIPANTE", "TB_HISTORICO_PACIENTE"}) {
            assertFalse(antes.get(tabela + "_AUD").isEmpty(), tabela);
        }

        var relatorio = consolidado.analisar();

        assertEquals(new Proveniencia(4, 2, 2), relatorio.proveniencia().get(AGENDAMENTO));
        assertEquals(new Proveniencia(1, 1, 0), relatorio.proveniencia().get(GRUPO));
        assertEquals(new Proveniencia(4, 0, 1), relatorio.proveniencia().get(SESSAO));
        assertEquals(new Proveniencia(2, 0, 3), relatorio.proveniencia().get(PARTICIPACAO));
        assertEquals(new Proveniencia(1, 1, 3), relatorio.proveniencia().get(HISTORICO));
        assertEquals(6, relatorio.frequenciasExcluidasDoEnsaio());
        assertEquals(4, relatorio.prontidaoPorVinculo().values().stream().mapToLong(Long::longValue).sum());
        assertEquals(ProntidaoTecnica.REQUER_CONFERENCIA, relatorio.prontidaoTecnica());
        assertFalse(relatorio.migracaoAutorizada());
        assertFalse(relatorio.toString().contains(c.paciente().getCpf()));
        assertFalse(relatorio.toString().contains(c.paciente().getIdPublico().toString()));

        UUID pessoa = c.paciente().getIdPublico();
        when(escopo.usuarioLogado()).thenReturn(c.usfA());
        assertEquals(c.agendaA(), verificador.verificar(pessoa, TipoRegistro.AGENDAMENTO, c.agendaA()).registroId());
        assertThrows(ResourceNotFoundException.class, () -> verificador.verificar(pessoa, TipoRegistro.AGENDAMENTO, c.agendaB()));
        assertThrows(ResourceNotFoundException.class,
                () -> verificador.verificar(pessoa, TipoRegistro.HISTORICO, c.historicoSessaoAgendada()));
        assertThrows(ResourceNotFoundException.class,
                () -> verificador.verificar(pessoa, TipoRegistro.SESSAO_GRUPO, c.sessaoSemResponsavel()));
        var usfA = linhaDoTempo.analisar(pessoa);
        assertEquals(2, usfA.eventosComOrigemLocal().size()); // atendimento e frequência coletiva válidos
        assertEquals(1, usfA.agendamentosSemOrigemComFrequencia()); // remarcação com elo sem origem
        assertEquals(3, usfA.frequenciasInconsistentes()); // sem frequência, agendada e sem responsável
        assertEquals(new InventarioFrequenciasVinculoService.Inventario(1, 0, 1, 0, 1),
                inventario.inventariar(pessoa));
        assertTrue(faltas.analisar(pessoa).faltasConsecutivasAnalisaveis().isEmpty());

        when(escopo.usuarioLogado()).thenReturn(c.usfB());
        assertEquals(c.agendaB(), verificador.verificar(pessoa, TipoRegistro.AGENDAMENTO, c.agendaB()).registroId());
        assertThrows(ResourceNotFoundException.class, () -> verificador.verificar(pessoa, TipoRegistro.AGENDAMENTO, c.agendaA()));
        assertEquals(1, faltas.analisar(pessoa).faltasConsecutivasAnalisaveis().orElseThrow());

        when(escopo.usuarioLogado()).thenReturn(c.capsAd());
        assertEquals(c.agendaAd(), verificador.verificar(pessoa, TipoRegistro.AGENDAMENTO, c.agendaAd()).registroId());
        assertThrows(ResourceNotFoundException.class, () -> verificador.verificar(pessoa, TipoRegistro.AGENDAMENTO, c.agendaII()));
        assertThrows(ResourceNotFoundException.class, () -> verificador.verificar(pessoa, TipoRegistro.AGENDAMENTO, c.cadeiaConflitante()));
        assertEquals(1, linhaDoTempo.analisar(pessoa).frequenciasInconsistentes());

        when(escopo.usuarioLogado()).thenReturn(c.capsII());
        assertEquals(c.agendaII(), verificador.verificar(pessoa, TipoRegistro.AGENDAMENTO, c.agendaII()).registroId());
        assertThrows(ResourceNotFoundException.class, () -> verificador.verificar(pessoa, TipoRegistro.AGENDAMENTO, c.agendaAd()));
        assertEquals(1, linhaDoTempo.analisar(pessoa).frequenciasInconsistentes());
        assertEquals(c.historicoManualLocal(),
                verificador.verificar(pessoa, TipoRegistro.HISTORICO, c.historicoManualLocal()).registroId());

        // Falha posterior à leitura aborta o ensaio sem criar revisão ou modificar um fato.
        assertThrows(IllegalStateException.class, () -> transacoes.executeWithoutResult(status -> {
            assertEquals(relatorio, consolidado.analisar());
            throw new IllegalStateException("Interrupção sintética depois da conferência");
        }));
        assertEquals(antes, LegacyMigrationFixture.snapshot(fonte, colunas));

        var script = temp.resolve("backup.sql");
        LegacyMigrationFixture.backup(fonte, script);
        var copia = LegacyMigrationFixture.restore(temp.resolve("restaurada"), script);
        assertEquals(colunas, LegacyMigrationFixture.columns(copia));
        assertEquals(antes, LegacyMigrationFixture.snapshot(copia, colunas));
        assertEquals(relatorio.cadastro(), CadastroUnicoPreflight.analisar(copia));
        assertEquals(antes, LegacyMigrationFixture.snapshot(copia, colunas));
    }

    @Test
    void classificaLegadoComEsquemaV13EmArquivoRestauradoESomenteLeitura() throws Exception {
        var base = LegacyMigrationFixture.legacy(temp.resolve("legado"));
        assertEquals(13, AgendaMigrationTool.migrar(base).migrationsExecuted);
        var colunas = LegacyMigrationFixture.columns(base);
        var antes = LegacyMigrationFixture.snapshot(base, colunas);
        var script = temp.resolve("legado-v13.sql");
        LegacyMigrationFixture.backup(base, script);
        var copia = LegacyMigrationFixture.restore(temp.resolve("legado-restaurado"), script);
        assertEquals(antes, LegacyMigrationFixture.snapshot(copia, colunas));

        var somenteLeitura = new DriverManagerDataSource(copia.getUrl() + ";ACCESS_MODE_DATA=r",
                copia.getUsername(), copia.getPassword());
        CorrectiveMigrationApplication.application(somenteLeitura)
                .withPropertyValues("spring.profiles.active=test,diagnostico-migracao")
                .run(contexto -> {
                    assertNull(contexto.getStartupFailure());
                    var relatorio = contexto.getBean(DiagnosticoConsolidadoMigracaoClinicaService.class).analisar();
                    assertEquals(new Proveniencia(0, 12, 0), relatorio.proveniencia().get(AGENDAMENTO));
                    assertEquals(new Proveniencia(0, 6, 0), relatorio.proveniencia().get(HISTORICO));
                    assertTrue(relatorio.prontidaoPorVinculo().isEmpty());
                    assertTrue(relatorio.frequenciasExcluidasDoEnsaio() > 0);
                    assertEquals(ProntidaoTecnica.REQUER_CONFERENCIA, relatorio.prontidaoTecnica());
                    assertFalse(relatorio.migracaoAutorizada());
                });
        assertEquals(antes, LegacyMigrationFixture.snapshot(copia, colunas));
        assertEquals(antes, LegacyMigrationFixture.snapshot(base, colunas));
    }

    private Cenario prepararCenario() {
        var p = new Paciente();
        p.setNome("Identidade sintética");
        p.setCpf("00000000001");
        p.setCns("000000000000001");
        em.persist(p);

        var usfA = profissional(UnidadeAtuacao.USF, usf("A"));
        var usfB = profissional(UnidadeAtuacao.USF, usf("B"));
        var capsAd = profissional(UnidadeAtuacao.CAPS_AD, null);
        var capsII = profissional(UnidadeAtuacao.CAPS_II, null);
        em.persist(VinculoAssistencial.paraUsf(p, usfA.getUsfAtuacao()));
        em.persist(VinculoAssistencial.paraUsf(p, usfB.getUsfAtuacao()));
        em.persist(VinculoAssistencial.paraCaps(p, UnidadeAtuacao.CAPS_AD));
        em.persist(VinculoAssistencial.paraCaps(p, UnidadeAtuacao.CAPS_II));

        var agendaA = agenda(p, usfA, 8, SituacaoAtendimento.PRESENTE, true, null);
        var agendaB = agenda(p, usfB, 9, SituacaoAtendimento.FALTOU, true, null);
        var agendaAd = agenda(p, capsAd, 10, SituacaoAtendimento.PRESENTE, true, null);
        var agendaII = agenda(p, capsII, 11, SituacaoAtendimento.FALTOU, true, null);
        var antiga = agenda(p, usfA, 12, SituacaoAtendimento.CANCELADO, false, null);
        agenda(p, usfA, 13, SituacaoAtendimento.PRESENTE, true, antiga);
        var cadeiaConflitante = agenda(p, capsAd, 14, SituacaoAtendimento.FALTOU, true, agendaII);
        var semElo = agenda(p, capsII, 15, SituacaoAtendimento.REMARCADO, true, null);
        semElo.setSituacaoAtendimento(SituacaoAtendimento.FALTOU);

        var grupo = grupo(usfA, true);
        grupo(capsAd, false);
        sessao(p, grupo, usfA, 17, StatusSessaoGrupo.REALIZADA, StatusPresencaGrupo.PRESENTE, true);
        sessao(p, grupo, usfA, 18, StatusSessaoGrupo.REALIZADA, StatusPresencaGrupo.NAO_REGISTRADA, true);
        var agendada = sessao(p, grupo, usfA, 19, StatusSessaoGrupo.AGENDADA, StatusPresencaGrupo.FALTOU, false);
        sessao(p, grupo, usfA, 20, StatusSessaoGrupo.CANCELADA, StatusPresencaGrupo.NAO_REGISTRADA, false);
        var semResponsavel = sessao(p, grupo, usfA, 21,
                StatusSessaoGrupo.REALIZADA, StatusPresencaGrupo.PRESENTE, false);

        var manualLocal = historico(p, capsII, TipoEventoHistoricoPaciente.BUSCA_ATIVA);
        manualLocal.registrarOrigemDeAcaoLocal(capsII);
        em.persist(manualLocal);
        em.persist(historico(p, capsII, TipoEventoHistoricoPaciente.BUSCA_ATIVA));
        var manualSemFonteObrigatoria = historico(p, capsII, TipoEventoHistoricoPaciente.FALTA);
        manualSemFonteObrigatoria.registrarOrigemDeAcaoLocal(capsII);
        em.persist(manualSemFonteObrigatoria);
        var contraditorio = historico(p, capsAd, TipoEventoHistoricoPaciente.FALTA);
        contraditorio.setAgendamento(cadeiaConflitante);
        contraditorio.registrarOrigemDoAgendamento(cadeiaConflitante);
        em.persist(contraditorio);
        var antesDaRealizacao = historico(p, usfA, TipoEventoHistoricoPaciente.PARTICIPACAO_GRUPO_TERAPEUTICO);
        antesDaRealizacao.setSessaoGrupo(agendada);
        antesDaRealizacao.registrarOrigemDaSessao(agendada);
        em.persist(antesDaRealizacao);

        return new Cenario(p, usfA, usfB, capsAd, capsII,
                agendaA.getId(), agendaB.getId(), agendaAd.getId(), agendaII.getId(), cadeiaConflitante.getId(),
                antesDaRealizacao.getId(), semResponsavel.getId(), manualLocal.getId());
    }

    private UsfReferencia usf(String nome) {
        var usf = new UsfReferencia();
        usf.setNomeUsf("USF sintética " + nome);
        usf.setCnes(nome);
        em.persist(usf);
        return usf;
    }

    private Usuario profissional(UnidadeAtuacao unidade, UsfReferencia usf) {
        var usuario = new Usuario();
        usuario.setNome("Profissional sintético");
        usuario.setEmail(UUID.randomUUID() + "@test.local");
        usuario.setSenha("fixture");
        usuario.setTipoUsuario(TipoUsuario.PROFISSIONAL);
        usuario.setUnidadeAtuacao(unidade);
        usuario.setUsfAtuacao(usf);
        em.persist(usuario);
        return usuario;
    }

    private Agendamento agenda(Paciente p, Usuario usuario, int hora, SituacaoAtendimento frequencia,
            boolean registrarOrigem, Agendamento anterior) {
        var agenda = new Agendamento();
        agenda.setPaciente(p);
        agenda.setUsuario(usuario);
        agenda.setDataAgendamento(DATA);
        agenda.setHoraAtendimento(LocalTime.of(hora, 0));
        agenda.setAgendamentoOriginal(anterior);
        agenda.setSituacaoAtendimento(frequencia);
        if (registrarOrigem) agenda.registrarUnidadeDeOrigem(usuario);
        em.persist(agenda);
        return agenda;
    }

    private GrupoTerapeutico grupo(Usuario usuario, boolean registrarOrigem) {
        var grupo = new GrupoTerapeutico();
        grupo.setTema("Grupo sintético");
        grupo.setCoordenador(usuario);
        if (registrarOrigem) grupo.registrarUnidadeDeOrigem(usuario);
        em.persist(grupo);
        return grupo;
    }

    private SessaoGrupo sessao(Paciente p, GrupoTerapeutico grupo, Usuario usuario, int hora,
            StatusSessaoGrupo status, StatusPresencaGrupo frequencia, boolean confirmar) {
        var sessao = new SessaoGrupo();
        sessao.setGrupo(grupo);
        sessao.setDataSessao(DATA);
        sessao.setHorario(LocalTime.of(hora, 0));
        sessao.registrarUnidadeDeOrigemDoGrupo();
        if (confirmar) {
            sessao.setStatus(StatusSessaoGrupo.AGENDADA);
            sessao.confirmarRealizacao(usuario);
        } else {
            sessao.setStatus(status);
        }
        em.persist(sessao);

        var participacao = new SessaoGrupoParticipante();
        participacao.setSessaoGrupo(sessao);
        participacao.setPaciente(p);
        participacao.setStatusPresenca(frequencia);
        em.persist(participacao);
        return sessao;
    }

    private HistoricoPaciente historico(Paciente p, Usuario usuario, TipoEventoHistoricoPaciente tipo) {
        var historico = new HistoricoPaciente();
        historico.setPaciente(p);
        historico.setProfissional(usuario);
        historico.setTipo(tipo);
        historico.setOcorridoEm(LocalDateTime.of(DATA, LocalTime.NOON));
        return historico;
    }
}
