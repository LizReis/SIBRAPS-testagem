package com.pet.buscaativa;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import com.pet.buscaativa.entities.Agendamento;
import com.pet.buscaativa.entities.Paciente;
import com.pet.buscaativa.entities.Usuario;
import com.pet.buscaativa.entities.VinculoAssistencial;
import com.pet.buscaativa.entities.PendenciaRevisaoProveniencia.Estado;
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
import com.pet.buscaativa.services.impl.SolicitacaoRevisaoProvenienciaService;
import com.pet.buscaativa.services.impl.SusApiAdapter;

import jakarta.persistence.EntityManager;

/**
 * Exercita o servico de solicitacao, os repositorios JPA reais e o banco H2
 * de teste, sem API ou alteracao em registros assistenciais persistidos.
 *
 * Apenas a identidade autenticada e simulada: verificacao de origem,
 * autorizacao por vinculo, regras de justificativa e gravacao sao reais.
 * Cada teste reverte a transacao; nenhuma migracao e executada em producao.
 */
@SpringBootTest
@Transactional
class SolicitacaoRevisaoProvenienciaPersistenciaTests extends SecurityTestSupport {
    private static final String JUSTIFICATIVA =
            "Necessaria conferencia documental do atendimento registrado nesta unidade.";

    @MockitoBean SusApiAdapter susApi;
    @MockitoBean EscopoUnidadeService escopo;

    @Autowired PacienteRepository pacientes;
    @Autowired UsuarioRepository usuarios;
    @Autowired VinculoAssistencialRepository vinculos;
    @Autowired AgendamentoRepository agendamentos;
    @Autowired PendenciaRevisaoProvenienciaRepository pendencias;
    @Autowired SolicitacaoRevisaoProvenienciaService solicitacoes;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager entityManager;

    private Paciente paciente() {
        Paciente pessoa = new Paciente();
        pessoa.setNome("Paciente sintetico pendencia com escopo local");
        pessoa.setCpf(UUID.randomUUID().toString());
        pessoa.setCns(UUID.randomUUID().toString());
        return pacientes.saveAndFlush(pessoa);
    }

    private Usuario profissional(UnidadeAtuacao unidade) {
        Usuario usuario = new Usuario();
        usuario.setNome("Profissional sintetico " + unidade);
        usuario.setEmail(UUID.randomUUID() + "@test.local");
        usuario.setSenha("somente-teste");
        usuario.setTipoUsuario(TipoUsuario.PROFISSIONAL);
        usuario.setUnidadeAtuacao(unidade);
        return usuarios.saveAndFlush(usuario);
    }

    private Agendamento agendar(Paciente pessoa, Usuario profissional, boolean origemComprovada) {
        Agendamento a = new Agendamento();
        a.setPaciente(pessoa);
        a.setUsuario(profissional);
        a.setDataAgendamento(LocalDate.of(2026, 12, 20));
        a.setHoraAtendimento(LocalTime.of(9, 30));
        a.setSituacaoAtendimento(SituacaoAtendimento.AGENDADO);
        if (origemComprovada) a.registrarUnidadeDeOrigem(profissional);
        return agendamentos.saveAndFlush(a);
    }

    @Test
    void gravacaoRealMantemTicketPendenteSemModificarAgendamentoEDescartaDuplicata() {
        Paciente p = paciente();
        Usuario ator = profissional(UnidadeAtuacao.CAPS_AD);
        vinculos.saveAndFlush(VinculoAssistencial.paraCaps(p, UnidadeAtuacao.CAPS_AD));
        Agendamento agendamento = agendar(p, ator, true);
        when(escopo.usuarioLogado()).thenReturn(ator);

        UUID id = solicitacoes.solicitar(
                p.getIdPublico(), TipoRegistro.AGENDAMENTO, agendamento.getId(), JUSTIFICATIVA);
        entityManager.flush();

        var ticket = pendencias.findByIdAndPacienteIdAndUnidadeChave(
                id, p.getId(), "CAPS_AD").orElseThrow();
        assertEquals(Estado.PENDENTE, ticket.getEstado());
        assertEquals(TipoRegistro.AGENDAMENTO, ticket.getTipoRegistro());
        assertEquals(p.getId(), ticket.getPacienteId());
        assertEquals(agendamento.getId(), ticket.getRegistroId());
        assertEquals(JUSTIFICATIVA, ticket.getJustificativa());
        assertEquals(1, jdbc.queryForObject(
                "select count(*) from tb_pendencia_revisao_proveniencia where id=?",
                Integer.class, id));
        assertTrue(pendencias.findByIdAndPacienteIdAndUnidadeChave(
                id, p.getId(), "CAPS_II").isEmpty());
        assertEquals(SituacaoAtendimento.AGENDADO,
                agendamentos.findById(agendamento.getId()).orElseThrow().getSituacaoAtendimento());
        assertEquals("CAPS_AD",
                agendamentos.findById(agendamento.getId()).orElseThrow().getUnidadeOrigemChave());

        assertThrows(ValidationException.class, () -> solicitacoes.solicitar(
                p.getIdPublico(), TipoRegistro.AGENDAMENTO, agendamento.getId(), JUSTIFICATIVA));
        assertEquals(1, jdbc.queryForObject(
                "select count(*) from tb_pendencia_revisao_proveniencia where paciente_id=? and unidade_chave='CAPS_AD'",
                Integer.class, p.getId()));
    }

    @Test
    void vinculoNoOutroCapsNaoConcedeAcessoAoFatoDaPrimeiraUnidade() {
        Paciente p = paciente();
        Usuario ad = profissional(UnidadeAtuacao.CAPS_AD);
        Usuario ii = profissional(UnidadeAtuacao.CAPS_II);
        vinculos.saveAndFlush(VinculoAssistencial.paraCaps(p, UnidadeAtuacao.CAPS_AD));
        vinculos.saveAndFlush(VinculoAssistencial.paraCaps(p, UnidadeAtuacao.CAPS_II));
        Agendamento atendimentoAd = agendar(p, ad, true);
        when(escopo.usuarioLogado()).thenReturn(ii);

        assertThrows(ResourceNotFoundException.class, () -> solicitacoes.solicitar(
                p.getIdPublico(), TipoRegistro.AGENDAMENTO, atendimentoAd.getId(), JUSTIFICATIVA));
        assertEquals(0, jdbc.queryForObject(
                "select count(*) from tb_pendencia_revisao_proveniencia where paciente_id=?",
                Integer.class, p.getId()));
    }

    @Test
    void fatoLegadoSemOrigemNaoGeraPendenciaNemInfereUnidade() {
        Paciente p = paciente();
        Usuario ad = profissional(UnidadeAtuacao.CAPS_AD);
        vinculos.saveAndFlush(VinculoAssistencial.paraCaps(p, UnidadeAtuacao.CAPS_AD));
        Agendamento legado = agendar(p, ad, false);
        when(escopo.usuarioLogado()).thenReturn(ad);

        assertThrows(ResourceNotFoundException.class, () -> solicitacoes.solicitar(
                p.getIdPublico(), TipoRegistro.AGENDAMENTO, legado.getId(), JUSTIFICATIVA));
        assertEquals(0, jdbc.queryForObject(
                "select count(*) from tb_pendencia_revisao_proveniencia where paciente_id=?",
                Integer.class, p.getId()));
        assertNull(agendamentos.findById(legado.getId()).orElseThrow().getUnidadeOrigemChave());
    }
}
