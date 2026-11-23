package com.pet.buscaativa;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.pet.buscaativa.entities.Agendamento;
import com.pet.buscaativa.entities.HistoricoPaciente;
import com.pet.buscaativa.entities.Paciente;
import com.pet.buscaativa.entities.SessaoGrupo;
import com.pet.buscaativa.entities.Usuario;
import com.pet.buscaativa.entities.VinculoAssistencial;
import com.pet.buscaativa.entities.enums.UnidadeAtuacao;
import com.pet.buscaativa.entities.enums.SituacaoAtendimento;
import com.pet.buscaativa.repositories.AgendamentoRepository;
import com.pet.buscaativa.repositories.PacienteRepository;
import com.pet.buscaativa.repositories.SessaoGrupoParticipanteRepository;
import com.pet.buscaativa.repositories.VinculoAssistencialRepository;
import com.pet.buscaativa.services.exceptions.ResourceNotFoundException;
import com.pet.buscaativa.services.impl.AutorizacaoVinculoAssistencialService;
import com.pet.buscaativa.services.impl.EscopoUnidadeService;

/**
 * Caracterizacao do autorizador interno fail-closed.
 * Nao substitui testes HTTP de isolamento; endpoints ainda nao usam este servico.
 */
class AutorizacaoVinculoAssistencialServiceTests {
    private EscopoUnidadeService escopo;
    private PacienteRepository pacientes;
    private AgendamentoRepository agendamentos;
    private VinculoAssistencialRepository vinculos;
    private SessaoGrupoParticipanteRepository participantes;
    private AutorizacaoVinculoAssistencialService service;
    private Usuario capsAd;
    private Usuario capsII;
    private Paciente pessoa;
    private UUID idPublico;
    private VinculoAssistencial vinculoAd;

    @BeforeEach
    void preparar() {
        escopo = mock(EscopoUnidadeService.class);
        pacientes = mock(PacienteRepository.class);
        agendamentos = mock(AgendamentoRepository.class);
        vinculos = mock(VinculoAssistencialRepository.class);
        participantes = mock(SessaoGrupoParticipanteRepository.class);
        service = new AutorizacaoVinculoAssistencialService(
                escopo, pacientes, agendamentos, vinculos, participantes);

        capsAd = new Usuario();
        capsAd.setUnidadeAtuacao(UnidadeAtuacao.CAPS_AD);
        capsII = new Usuario();
        capsII.setUnidadeAtuacao(UnidadeAtuacao.CAPS_II);
        when(escopo.usuarioLogado()).thenReturn(capsAd);

        pessoa = new Paciente();
        pessoa.setId(81L);
        idPublico = UUID.randomUUID();
        pessoa.setIdPublico(idPublico);
        when(pacientes.findByIdPublico(idPublico)).thenReturn(Optional.of(pessoa));

        vinculoAd = mock(VinculoAssistencial.class);
        when(vinculoAd.getPaciente()).thenReturn(pessoa);
        when(vinculoAd.getUnidadeChave()).thenReturn("CAPS_AD");
        when(vinculoAd.getId()).thenReturn(45L);
        when(vinculos.findByPacienteIdAndUnidadeChave(81L, "CAPS_AD"))
                .thenReturn(Optional.of(vinculoAd));
    }

    @Test
    void vinculoRetornadoComPacienteDiferenteNaoAutorizaDados() {
        Paciente outro = new Paciente();
        outro.setId(999L);
        when(vinculoAd.getPaciente()).thenReturn(outro);
        assertThrows(ResourceNotFoundException.class, () -> service.exigirVinculoLocal(idPublico));
    }

    @Test
    void agendamentoDaUnidadeLocalComVinculoExplicitoEAutorizado() {
        Usuario profissional = new Usuario();
        profissional.setId(7L);
        profissional.setUnidadeAtuacao(UnidadeAtuacao.CAPS_AD);
        Agendamento a = new Agendamento();
        a.setPaciente(pessoa);
        a.setUsuario(profissional);
        a.registrarUnidadeDeOrigem(profissional);
        a.setSituacaoAtendimento(SituacaoAtendimento.AGENDADO);
        assertDoesNotThrow(() -> service.validarAgendamentoLocal(a));
    }

    @Test
    void historicoDaUnidadeLocalComPacienteDaFonteCoerenteEAutorizado() {
        Usuario profissional = new Usuario();
        profissional.setId(7L);
        profissional.setUnidadeAtuacao(UnidadeAtuacao.CAPS_AD);
        Agendamento a = new Agendamento();
        a.setPaciente(pessoa);
        a.setUsuario(profissional);
        a.registrarUnidadeDeOrigem(profissional);
        a.setSituacaoAtendimento(SituacaoAtendimento.AGENDADO);
        HistoricoPaciente h = new HistoricoPaciente();
        h.setPaciente(pessoa);
        h.setAgendamento(a);
        h.registrarOrigemDoAgendamento(a);
        assertDoesNotThrow(() -> service.validarHistoricoLocal(h));
    }

    @Test
    void eloAnteriorSemEstadoCanonicoNaoAutorizaAgendaNemHistoricoLocal() {
        Usuario profissional = new Usuario();
        profissional.setId(25L);
        profissional.setUnidadeAtuacao(UnidadeAtuacao.CAPS_AD);

        Agendamento origem = new Agendamento();
        origem.setDataAgendamento(LocalDate.of(2026, 11, 10));
        origem.setHoraAtendimento(LocalTime.of(9, 0));
        origem.setPaciente(pessoa);
        origem.setUsuario(profissional);
        origem.registrarUnidadeDeOrigem(profissional);
        origem.setId(10L);
        // A origem assistencial existe, mas o estado canonico da raiz e incerto.

        Agendamento remarcado = new Agendamento();
        remarcado.setDataAgendamento(LocalDate.of(2026, 11, 11));
        remarcado.setHoraAtendimento(LocalTime.of(9, 0));
        remarcado.setPaciente(pessoa);
        remarcado.setUsuario(profissional);
        remarcado.registrarUnidadeDeOrigem(profissional);
        remarcado.setSituacaoAtendimento(SituacaoAtendimento.AGENDADO);
        remarcado.setAgendamentoOriginal(origem);
        remarcado.setId(11L);

        HistoricoPaciente historico = new HistoricoPaciente();
        historico.setPaciente(pessoa);
        historico.setAgendamento(remarcado);
        historico.registrarOrigemDoAgendamento(remarcado);

        assertThrows(ResourceNotFoundException.class,
                () -> service.validarAgendamentoLocal(remarcado));
        assertThrows(ResourceNotFoundException.class,
                () -> service.validarHistoricoLocal(historico));

        origem.setSituacaoAtendimento(SituacaoAtendimento.REMARCADO_ORIGEM);
        assertDoesNotThrow(() -> service.validarAgendamentoLocal(remarcado));
        assertDoesNotThrow(() -> service.validarHistoricoLocal(historico));

        when(agendamentos.existsByAgendamentoOriginal_IdAndIdNot(10L, 11L)).thenReturn(true);
        assertThrows(ResourceNotFoundException.class,
                () -> service.validarAgendamentoLocal(remarcado));
        assertThrows(ResourceNotFoundException.class,
                () -> service.validarHistoricoLocal(historico));
    }

    @Test
    void historicoDeAgendamentoNaoPodeApontarParaOutroPacienteMesmoComOrigemLocal() {
        Paciente outro = new Paciente();
        outro.setId(999L);
        Usuario profissional = new Usuario();
        profissional.setId(7L);
        profissional.setUnidadeAtuacao(UnidadeAtuacao.CAPS_AD);
        Agendamento a = new Agendamento();
        a.setPaciente(outro);
        a.setUsuario(profissional);
        a.registrarUnidadeDeOrigem(profissional);
        HistoricoPaciente h = new HistoricoPaciente();
        h.setPaciente(pessoa);
        h.setAgendamento(a);
        // Uma origem local nao basta: a fonte precisa apontar para a mesma pessoa.
        // O teste usa uma instancia de historico sem persistencia intencionalmente.
        assertThrows(IllegalArgumentException.class, () -> h.registrarOrigemDoAgendamento(a));
    }

    @Test
    void mesmaPessoaEmCapsIIEInvisivelSemVinculoDoCapsII() {
        assertSame(vinculoAd, service.exigirVinculoLocal(idPublico));

        when(escopo.usuarioLogado()).thenReturn(capsII);
        assertThrows(ResourceNotFoundException.class, () -> service.exigirVinculoLocal(idPublico));
        verify(vinculos).findByPacienteIdAndUnidadeChave(81L, "CAPS_II");
    }

    @Test
    void referenciaCadastralNaoSubstituiVinculoExplicito() {
        pessoa.setCapsReferencia(com.pet.buscaativa.entities.enums.CapsEnum.CAPS_II);
        when(escopo.usuarioLogado()).thenReturn(capsII);
        assertThrows(ResourceNotFoundException.class, () -> service.exigirVinculoLocal(idPublico));
    }

    @Test
    void origemDoAgendamentoDeOutraUnidadeENegadaApesarDaIdentidadeLocal() {
        Agendamento a = new Agendamento();
        a.setPaciente(pessoa);
        Usuario profissional = new Usuario();
        profissional.setId(1L);
        profissional.setUnidadeAtuacao(UnidadeAtuacao.CAPS_II);
        a.setUsuario(profissional);
        a.registrarUnidadeDeOrigem(profissional);

        assertThrows(ResourceNotFoundException.class, () -> service.validarAgendamentoLocal(a));
        when(escopo.usuarioLogado()).thenReturn(capsII);
        assertThrows(ResourceNotFoundException.class, () -> service.validarAgendamentoLocal(a));
    }

    @Test
    void agendamentoAntigoSemOrigemComVinculoDaPessoaERecusado() {
        Agendamento antigo = new Agendamento();
        antigo.setPaciente(pessoa);
        assertThrows(ResourceNotFoundException.class, () -> service.validarAgendamentoLocal(antigo));
        assertThrows(ResourceNotFoundException.class, () -> service.validarAgendamentoLocal(null));
    }

    @Test
    void historicoNaoPodeTerOrigemDiferenteDoAtendimento() {
        Usuario profissional = new Usuario();
        profissional.setId(2L);
        profissional.setUnidadeAtuacao(UnidadeAtuacao.CAPS_II);
        Agendamento a = new Agendamento();
        a.setPaciente(pessoa);
        a.setUsuario(profissional);
        a.registrarUnidadeDeOrigem(profissional);

        HistoricoPaciente h = new HistoricoPaciente();
        h.setPaciente(pessoa);
        h.setAgendamento(a);
        // O histórico novo deve herdar a origem do agendamento, não da unidade atual.
        h.registrarOrigemDoAgendamento(a);
        assertEquals("CAPS_II", h.getUnidadeOrigemChave());
        assertThrows(ResourceNotFoundException.class, () -> service.validarHistoricoLocal(h));
    }

    @Test
    void historicoSemOrigemENegadoMesmoSePessoaTiverVinculo() {
        HistoricoPaciente historico = new HistoricoPaciente();
        historico.setPaciente(pessoa);
        assertThrows(ResourceNotFoundException.class, () -> service.validarHistoricoLocal(historico));
    }

    @Test
    void sessaoDaUnidadeSoEVisivelParaPacienteParticipante() {
        Usuario coordenador = new Usuario();
        coordenador.setId(3L);
        coordenador.setUnidadeAtuacao(UnidadeAtuacao.CAPS_AD);
        var grupo = new com.pet.buscaativa.entities.GrupoTerapeutico();
        grupo.setCoordenador(coordenador);
        grupo.registrarUnidadeDeOrigem(coordenador);
        var sessao = new SessaoGrupo();
        sessao.setGrupo(grupo);
        sessao.registrarUnidadeDeOrigemDoGrupo();

        assertThrows(ResourceNotFoundException.class, () -> service.validarSessaoLocal(sessao, pessoa));
        var participante = new com.pet.buscaativa.entities.SessaoGrupoParticipante();
        participante.setPaciente(pessoa);
        participante.setSessaoGrupo(sessao);
        when(participantes.findBySessaoGrupoAndPaciente(sessao, pessoa))
                .thenReturn(Optional.of(participante));

        assertDoesNotThrow(() -> service.validarSessaoLocal(sessao, pessoa));
    }

    @Test
    void sessaoDaOutraUnidadeNaoEVisivelComVinculoLocal() {
        Usuario coordenador = new Usuario();
        coordenador.setId(3L);
        coordenador.setUnidadeAtuacao(UnidadeAtuacao.CAPS_II);
        var grupo = new com.pet.buscaativa.entities.GrupoTerapeutico();
        grupo.setCoordenador(coordenador);
        grupo.registrarUnidadeDeOrigem(coordenador);
        var sessao = new SessaoGrupo();
        sessao.setGrupo(grupo);
        sessao.registrarUnidadeDeOrigemDoGrupo();

        assertThrows(ResourceNotFoundException.class, () -> service.validarSessaoLocal(sessao, pessoa));
    }
}
