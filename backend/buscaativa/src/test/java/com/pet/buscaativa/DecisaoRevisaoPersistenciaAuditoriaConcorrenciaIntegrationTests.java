package com.pet.buscaativa;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.pet.buscaativa.entities.Agendamento;
import com.pet.buscaativa.entities.Paciente;
import com.pet.buscaativa.entities.Usuario;
import com.pet.buscaativa.entities.VinculoAssistencial;
import com.pet.buscaativa.entities.DecisaoRevisaoProveniencia.Decisao;
import com.pet.buscaativa.entities.PendenciaRevisaoProveniencia.TipoRegistro;
import com.pet.buscaativa.entities.enums.SituacaoAtendimento;
import com.pet.buscaativa.entities.enums.TipoUsuario;
import com.pet.buscaativa.entities.enums.UnidadeAtuacao;
import com.pet.buscaativa.repositories.AgendamentoRepository;
import com.pet.buscaativa.repositories.PacienteRepository;
import com.pet.buscaativa.repositories.PendenciaRevisaoProvenienciaRepository;
import com.pet.buscaativa.repositories.UsuarioRepository;
import com.pet.buscaativa.repositories.VinculoAssistencialRepository;
import com.pet.buscaativa.services.exceptions.ResourceNotFoundException;
import com.pet.buscaativa.services.exceptions.ValidationException;
import com.pet.buscaativa.services.impl.EscopoUnidadeService;
import com.pet.buscaativa.services.impl.RegistroDecisaoRevisaoProvenienciaService;
import com.pet.buscaativa.services.impl.ConsultaDecisaoRevisaoProvenienciaService;
import com.pet.buscaativa.services.impl.FilaRevisaoProvenienciaService;
import com.pet.buscaativa.services.impl.SolicitacaoRevisaoProvenienciaService;
import com.pet.buscaativa.services.impl.SusApiAdapter;

/**
 * Exercita decisoes V13 com transacoes REAIS e H2 sintetico isolado:
 * revisao Envers apos COMMIT, escopo CAPS e duas tentativas concorrentes.
 * Nao executa migracao offline em dados reais, nem abre rota HTTP.
 *
 * Somente a identidade de usuario autenticado e simulada. Servicos de
 * autorizacao por vinculo, verificacao do fato, justificativa e Spring Data
 * usam implementacoes reais, sem mocks nas regras clinicas.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:decisao-v13-auditoria-concorrencia;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=12000",
        "spring.datasource.hikari.maximum-pool-size=12"
})
class DecisaoRevisaoPersistenciaAuditoriaConcorrenciaIntegrationTests
        extends SecurityTestSupport {

    private static final String MOTIVO_SOLICITACAO =
            "Conferir documentacao do atendimento original desta unidade de saude.";
    private static final String MOTIVO_DECISAO =
            "Documentacao assistencial verificada pelo administrador da unidade.";

    @MockitoBean SusApiAdapter susApi;
    @MockitoBean EscopoUnidadeService escopo;

    @Autowired PacienteRepository pacientes;
    @Autowired UsuarioRepository usuarios;
    @Autowired VinculoAssistencialRepository vinculos;
    @Autowired AgendamentoRepository agendamentos;
    @Autowired SolicitacaoRevisaoProvenienciaService solicitacoes;
    @Autowired RegistroDecisaoRevisaoProvenienciaService decisoes;
    @Autowired ConsultaDecisaoRevisaoProvenienciaService consultaDecisoes;
    @Autowired FilaRevisaoProvenienciaService fila;
    @Autowired PendenciaRevisaoProvenienciaRepository pendenciasAbertas;
    @Autowired JdbcTemplate jdbc;

    private record Cenario(UUID pacientePublico, Long pacienteId,
            Long agendamentoId, UUID pendenciaId, Usuario administrador) {}

    private Usuario administrador(UnidadeAtuacao unidade) {
        Usuario u = new Usuario();
        u.setNome("Revisor sintetico " + unidade);
        u.setEmail(UUID.randomUUID() + "@test.local");
        u.setSenha("somente-para-testes");
        u.setTipoUsuario(TipoUsuario.ADMINISTRADOR);
        u.setUnidadeAtuacao(unidade);
        return usuarios.saveAndFlush(u);
    }

    private Cenario criarCenario() {
        Paciente p = new Paciente();
        p.setNome("Paciente sintetico de decisao V13");
        p.setCpf(UUID.randomUUID().toString());
        p.setCns(UUID.randomUUID().toString());
        p = pacientes.saveAndFlush(p);

        Usuario admin = administrador(UnidadeAtuacao.CAPS_AD);
        vinculos.saveAndFlush(VinculoAssistencial.paraCaps(p, UnidadeAtuacao.CAPS_AD));

        Agendamento a = new Agendamento();
        a.setPaciente(p);
        a.setUsuario(admin);
        a.setDataAgendamento(LocalDate.of(2026, 12, 20));
        a.setHoraAtendimento(LocalTime.of(10, 30));
        a.setSituacaoAtendimento(SituacaoAtendimento.AGENDADO);
        a.registrarUnidadeDeOrigem(admin);
        a = agendamentos.saveAndFlush(a);

        when(escopo.usuarioLogado()).thenReturn(admin);
        UUID pendenciaId = solicitacoes.solicitar(
                p.getIdPublico(), TipoRegistro.AGENDAMENTO,
                a.getId(), MOTIVO_SOLICITACAO);
        return new Cenario(p.getIdPublico(), p.getId(),
                a.getId(), pendenciaId, admin);
    }

    @Test
    void decisaoRealGeraAuditoriaEnversEConservaPendenciaEAtendimento() {
        Cenario c = criarCenario();

        UUID id = decisoes.registrar(
                c.pacientePublico(), c.pendenciaId(), Decisao.APROVAR, MOTIVO_DECISAO);

        assertNotNull(id);
        var leitura = consultaDecisoes.detalhar(c.pacientePublico(), c.pendenciaId());
        assertEquals(id, leitura.id());
        assertEquals(c.pendenciaId(), leitura.pendenciaId());
        assertEquals(Decisao.APROVAR, leitura.decisao());
        assertEquals(MOTIVO_DECISAO, leitura.justificativa());
        assertNotNull(leitura.decididaEm());
        assertEquals(5, ConsultaDecisaoRevisaoProvenienciaService.Detalhe.class
                .getRecordComponents().length);
        assertEquals(1, jdbc.queryForObject("""
                select count(*) from tb_decisao_revisao_proveniencia
                where id=? and pendencia_id=? and paciente_id=?
                  and unidade_chave='CAPS_AD'
                  and decisao='APROVAR' and perfil_revisor='ADMINISTRADOR'
                """, Integer.class, id, c.pendenciaId(), c.pacienteId()));
        assertEquals(1, jdbc.queryForObject("""
                select count(*) from tb_decisao_revisao_proveniencia_aud
                where id=? and revtype=0 and pendencia_id=?
                  and unidade_chave='CAPS_AD' and decisao='APROVAR'
                """, Integer.class, id, c.pendenciaId()));
        assertEquals("PENDENTE", jdbc.queryForObject(
                "select estado from tb_pendencia_revisao_proveniencia where id=?",
                String.class, c.pendenciaId()));
        assertEquals("CAPS_AD", agendamentos.findById(c.agendamentoId())
                .orElseThrow().getUnidadeOrigemChave());
        assertEquals(SituacaoAtendimento.AGENDADO, agendamentos.findById(c.agendamentoId())
                .orElseThrow().getSituacaoAtendimento());

        // Fluxo REAL da decisao APROVAR: a pendencia V12 continua auditavel,
        // mas deixa de aparecer nas duas consultas de tickets abertos.
        assertThrows(ResourceNotFoundException.class,
                () -> fila.detalharPendente(c.pendenciaId()));
        assertTrue(fila.listarPendentes(PageRequest.of(0, 100)).getContent().stream()
                .noneMatch(p -> p.id().equals(c.pendenciaId())));
        assertTrue(pendenciasAbertas.findByIdAndPacienteIdAndUnidadeChave(
                c.pendenciaId(), c.pacienteId(), "CAPS_AD").isEmpty());
        assertEquals(0, pendenciasAbertas
                .findByPacienteIdAndUnidadeChaveAndEstadoOrderBySolicitadoEmDesc(
                        c.pacienteId(), "CAPS_AD",
                        com.pet.buscaativa.entities.PendenciaRevisaoProveniencia.Estado.PENDENTE,
                        PageRequest.of(0, 10)).getTotalElements());

        assertThrows(ValidationException.class, () -> decisoes.registrar(
                c.pacientePublico(), c.pendenciaId(), Decisao.REJEITAR, MOTIVO_DECISAO));
        assertEquals(1, jdbc.queryForObject(
                "select count(*) from tb_decisao_revisao_proveniencia where pendencia_id=?",
                Integer.class, c.pendenciaId()));
    }

    @Test
    void rejeicaoRealRetiraPendenciaDaFilaAbertaSemApagarTrilhaOuFatoClinico() {
        Cenario c = criarCenario();
        assertEquals(c.pendenciaId(), fila.detalharPendente(c.pendenciaId()).id());
        assertEquals(1, pendenciasAbertas
                .findByPacienteIdAndUnidadeChaveAndEstadoOrderBySolicitadoEmDesc(
                        c.pacienteId(), "CAPS_AD",
                        com.pet.buscaativa.entities.PendenciaRevisaoProveniencia.Estado.PENDENTE,
                        PageRequest.of(0, 10)).getTotalElements());

        UUID id = decisoes.registrar(
                c.pacientePublico(), c.pendenciaId(), Decisao.REJEITAR, MOTIVO_DECISAO);

        assertNotNull(id);
        var leitura = consultaDecisoes.detalhar(c.pacientePublico(), c.pendenciaId());
        assertEquals(Decisao.REJEITAR, leitura.decisao());
        assertEquals(MOTIVO_DECISAO, leitura.justificativa());
        assertThrows(ResourceNotFoundException.class,
                () -> fila.detalharPendente(c.pendenciaId()));
        assertTrue(fila.listarPendentes(PageRequest.of(0, 100)).getContent().stream()
                .noneMatch(p -> p.id().equals(c.pendenciaId())));
        assertTrue(pendenciasAbertas.findByIdAndPacienteIdAndUnidadeChave(
                c.pendenciaId(), c.pacienteId(), "CAPS_AD").isEmpty());
        assertEquals(0, pendenciasAbertas
                .findByPacienteIdAndUnidadeChaveAndEstadoOrderBySolicitadoEmDesc(
                        c.pacienteId(), "CAPS_AD",
                        com.pet.buscaativa.entities.PendenciaRevisaoProveniencia.Estado.PENDENTE,
                        PageRequest.of(0, 10)).getTotalElements());

        assertEquals("PENDENTE", jdbc.queryForObject(
                "select estado from tb_pendencia_revisao_proveniencia where id=?",
                String.class, c.pendenciaId()));
        assertEquals(1, jdbc.queryForObject("""
                select count(*) from tb_decisao_revisao_proveniencia_aud
                where id=? and pendencia_id=? and decisao='REJEITAR' and revtype=0
                """, Integer.class, id, c.pendenciaId()));
        assertEquals("CAPS_AD", agendamentos.findById(c.agendamentoId())
                .orElseThrow().getUnidadeOrigemChave());
        assertEquals(SituacaoAtendimento.AGENDADO, agendamentos.findById(c.agendamentoId())
                .orElseThrow().getSituacaoAtendimento());
    }

    @Test
    void administradorDeOutroCapsNaoDecidePendenciaDeIdentidadeCompartilhada() {
        Cenario c = criarCenario();
        Usuario outro = administrador(UnidadeAtuacao.CAPS_II);
        Paciente p = pacientes.findByIdPublico(c.pacientePublico()).orElseThrow();
        vinculos.saveAndFlush(VinculoAssistencial.paraCaps(p, UnidadeAtuacao.CAPS_II));
        when(escopo.usuarioLogado()).thenReturn(outro);

        assertThrows(ResourceNotFoundException.class, () -> decisoes.registrar(
                c.pacientePublico(), c.pendenciaId(), Decisao.REJEITAR, MOTIVO_DECISAO));
        assertThrows(ResourceNotFoundException.class,
                () -> consultaDecisoes.detalhar(c.pacientePublico(), c.pendenciaId()));
        assertEquals(0, jdbc.queryForObject(
                "select count(*) from tb_decisao_revisao_proveniencia where pendencia_id=?",
                Integer.class, c.pendenciaId()));
        assertEquals("PENDENTE", jdbc.queryForObject(
                "select estado from tb_pendencia_revisao_proveniencia where id=?",
                String.class, c.pendenciaId()));
    }

    @Test
    void somenteAdministradorDaUnidadeComVinculoConsultaDecisaoRegistrada() {
        Cenario c = criarCenario();
        UUID decisaoId = decisoes.registrar(
                c.pacientePublico(), c.pendenciaId(), Decisao.REJEITAR, MOTIVO_DECISAO);
        assertEquals(decisaoId,
                consultaDecisoes.detalhar(c.pacientePublico(), c.pendenciaId()).id());

        Usuario profissional = new Usuario();
        profissional.setTipoUsuario(TipoUsuario.PROFISSIONAL);
        profissional.setUnidadeAtuacao(UnidadeAtuacao.CAPS_AD);
        when(escopo.usuarioLogado()).thenReturn(profissional);
        assertThrows(AccessDeniedException.class,
                () -> consultaDecisoes.detalhar(c.pacientePublico(), c.pendenciaId()));

        when(escopo.usuarioLogado()).thenReturn(c.administrador());
        assertThrows(ResourceNotFoundException.class,
                () -> consultaDecisoes.detalhar(c.pacientePublico(), UUID.randomUUID()));
        assertThrows(ResourceNotFoundException.class,
                () -> consultaDecisoes.detalhar(null, c.pendenciaId()));
        assertThrows(ResourceNotFoundException.class,
                () -> consultaDecisoes.detalhar(c.pacientePublico(), null));

        assertEquals("PENDENTE", jdbc.queryForObject(
                "select estado from tb_pendencia_revisao_proveniencia where id=?",
                String.class, c.pendenciaId()));
        assertEquals(1, jdbc.queryForObject(
                "select count(*) from tb_decisao_revisao_proveniencia where id=?",
                Integer.class, decisaoId));
    }

    @Test
    void duasDecisoesSimultaneasGeramUmUnicoEventoEUmaUnicaAuditoria() throws Exception {
        Cenario c = criarCenario();
        var prontas = new CountDownLatch(2);
        var partida = new CountDownLatch(1);

        Callable<Boolean> tarefa = () -> {
            prontas.countDown();
            if (!partida.await(15, TimeUnit.SECONDS))
                throw new IllegalStateException("Sincronizacao das decisoes excedeu tempo.");
            try {
                decisoes.registrar(
                        c.pacientePublico(), c.pendenciaId(), Decisao.REJEITAR, MOTIVO_DECISAO);
                return true;
            } catch (ValidationException duplicada) {
                return false;
            }
        };

        try (var executor = Executors.newFixedThreadPool(2)) {
            var primeira = executor.submit(tarefa);
            var segunda = executor.submit(tarefa);
            assertTrue(prontas.await(15, TimeUnit.SECONDS));
            partida.countDown();

            int gravadas = (primeira.get(30, TimeUnit.SECONDS) ? 1 : 0)
                    + (segunda.get(30, TimeUnit.SECONDS) ? 1 : 0);
            assertEquals(1, gravadas);
        }

        assertEquals(1, jdbc.queryForObject(
                "select count(*) from tb_decisao_revisao_proveniencia where pendencia_id=?",
                Integer.class, c.pendenciaId()));
        assertEquals(1, jdbc.queryForObject("""
                select count(*) from tb_decisao_revisao_proveniencia_aud
                where pendencia_id=? and revtype=0
                """, Integer.class, c.pendenciaId()));
        assertEquals("PENDENTE", jdbc.queryForObject(
                "select estado from tb_pendencia_revisao_proveniencia where id=?",
                String.class, c.pendenciaId()));
        // Uma decisao vencedora em corrida nao deixa um ticket novamente
        // disponivel para outra deliberacao na fila aberta.
        assertThrows(ResourceNotFoundException.class,
                () -> fila.detalharPendente(c.pendenciaId()));
    }
}
