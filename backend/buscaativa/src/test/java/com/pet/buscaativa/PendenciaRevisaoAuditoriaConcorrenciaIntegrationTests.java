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
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.pet.buscaativa.entities.Agendamento;
import com.pet.buscaativa.entities.Paciente;
import com.pet.buscaativa.entities.Usuario;
import com.pet.buscaativa.entities.VinculoAssistencial;
import com.pet.buscaativa.entities.PendenciaRevisaoProveniencia.TipoRegistro;
import com.pet.buscaativa.entities.enums.SituacaoAtendimento;
import com.pet.buscaativa.entities.enums.TipoUsuario;
import com.pet.buscaativa.entities.enums.UnidadeAtuacao;
import com.pet.buscaativa.repositories.AgendamentoRepository;
import com.pet.buscaativa.repositories.PacienteRepository;
import com.pet.buscaativa.repositories.UsuarioRepository;
import com.pet.buscaativa.repositories.VinculoAssistencialRepository;
import com.pet.buscaativa.services.exceptions.ValidationException;
import com.pet.buscaativa.services.impl.EscopoUnidadeService;
import com.pet.buscaativa.services.impl.SolicitacaoRevisaoProvenienciaService;
import com.pet.buscaativa.services.impl.SusApiAdapter;

/**
 * Valida COMMIT real, revisao Envers e concorrencia do ticket V12
 * em banco H2 de teste separado dos outros contextos.
 *
 * Nao executa migracoes offline nem altera qualquer prontuario real.
 * Nao abre endpoints de solicitacao ou de decisao.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:pendencia-v12-audit-concurrency;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=12000",
        "spring.datasource.hikari.maximum-pool-size=12"
})
class PendenciaRevisaoAuditoriaConcorrenciaIntegrationTests extends SecurityTestSupport {
    private static final String MOTIVO =
            "Conferir a documentacao do atendimento original antes de qualquer revisao.";

    @MockitoBean SusApiAdapter susApi;
    @MockitoBean EscopoUnidadeService escopo;

    @Autowired PacienteRepository pacientes;
    @Autowired UsuarioRepository usuarios;
    @Autowired VinculoAssistencialRepository vinculos;
    @Autowired AgendamentoRepository agendamentos;
    @Autowired SolicitacaoRevisaoProvenienciaService solicitacoes;
    @Autowired JdbcTemplate jdbc;

    private record Cenario(UUID pacientePublico, Long pacienteId, Long agendamentoId) {}

    private Cenario criarCenarioSintetico() {
        Paciente p = new Paciente();
        p.setNome("Paciente sintetico para auditoria de pendencia");
        p.setCpf(UUID.randomUUID().toString());
        p.setCns(UUID.randomUUID().toString());
        p = pacientes.saveAndFlush(p);

        Usuario profissional = new Usuario();
        profissional.setNome("Profissional local teste de auditoria");
        profissional.setEmail(UUID.randomUUID() + "@test.local");
        profissional.setSenha("somente-para-teste");
        profissional.setTipoUsuario(TipoUsuario.PROFISSIONAL);
        profissional.setUnidadeAtuacao(UnidadeAtuacao.CAPS_AD);
        profissional = usuarios.saveAndFlush(profissional);
        vinculos.saveAndFlush(VinculoAssistencial.paraCaps(p, UnidadeAtuacao.CAPS_AD));

        Agendamento a = new Agendamento();
        a.setPaciente(p);
        a.setUsuario(profissional);
        a.setDataAgendamento(LocalDate.of(2026, 12, 20));
        a.setHoraAtendimento(LocalTime.of(9, 30));
        a.setSituacaoAtendimento(SituacaoAtendimento.AGENDADO);
        a.registrarUnidadeDeOrigem(profissional);
        a = agendamentos.saveAndFlush(a);

        when(escopo.usuarioLogado()).thenReturn(profissional);
        return new Cenario(p.getIdPublico(), p.getId(), a.getId());
    }

    @Test
    void commitDaSolicitacaoGeraRevisaoEnversSemModificarAtendimento() {
        Cenario c = criarCenarioSintetico();
        UUID id = solicitacoes.solicitar(
                c.pacientePublico(), TipoRegistro.AGENDAMENTO, c.agendamentoId(), MOTIVO);

        assertEquals(1, jdbc.queryForObject(
                "select count(*) from tb_pendencia_revisao_proveniencia where id=? and paciente_id=? and unidade_chave='CAPS_AD' and estado='PENDENTE'",
                Integer.class, id, c.pacienteId()));
        assertEquals(1, jdbc.queryForObject(
                "select count(*) from tb_pendencia_revisao_proveniencia_aud where id=? and revtype=0 and perfil_solicitante='PROFISSIONAL' and unidade_chave='CAPS_AD'",
                Integer.class, id));
        assertEquals("CAPS_AD", agendamentos.findById(c.agendamentoId())
                .orElseThrow().getUnidadeOrigemChave());
        assertEquals(SituacaoAtendimento.AGENDADO, agendamentos.findById(c.agendamentoId())
                .orElseThrow().getSituacaoAtendimento());
    }

    @Test
    void duasSolicitacoesSimultaneasParaMesmoFatoNaoDuplicamTicket() throws Exception {
        Cenario c = criarCenarioSintetico();
        var inicio = new CountDownLatch(1);
        var prontas = new CountDownLatch(2);
        Callable<Boolean> tarefa = () -> {
            prontas.countDown();
            if (!inicio.await(15, TimeUnit.SECONDS))
                throw new IllegalStateException("Sincronizacao de teste excedeu tempo.");
            try {
                solicitacoes.solicitar(c.pacientePublico(),
                        TipoRegistro.AGENDAMENTO, c.agendamentoId(), MOTIVO);
                return true;
            } catch (ValidationException duplicado) {
                return false;
            }
        };

        try (var executor = Executors.newFixedThreadPool(2)) {
            var primeira = executor.submit(tarefa);
            var segunda = executor.submit(tarefa);
            assertTrue(prontas.await(15, TimeUnit.SECONDS));
            inicio.countDown();
            int aceitas = (primeira.get(30, TimeUnit.SECONDS) ? 1 : 0)
                    + (segunda.get(30, TimeUnit.SECONDS) ? 1 : 0);
            assertEquals(1, aceitas);
        }

        assertEquals(1, jdbc.queryForObject(
                "select count(*) from tb_pendencia_revisao_proveniencia where paciente_id=? and unidade_chave='CAPS_AD' and tipo_registro='AGENDAMENTO' and registro_id=?",
                Integer.class, c.pacienteId(), c.agendamentoId()));
        assertEquals(1, jdbc.queryForObject(
                "select count(*) from tb_pendencia_revisao_proveniencia_aud where paciente_id=? and unidade_chave='CAPS_AD' and tipo_registro='AGENDAMENTO' and registro_id=? and revtype=0",
                Integer.class, c.pacienteId(), c.agendamentoId()));
    }
}
