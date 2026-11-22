package com.pet.buscaativa;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

import com.pet.buscaativa.entities.Agendamento;
import com.pet.buscaativa.entities.GrupoTerapeutico;
import com.pet.buscaativa.entities.HistoricoPaciente;
import com.pet.buscaativa.entities.Paciente;
import com.pet.buscaativa.entities.PendenciaRevisaoProveniencia.TipoRegistro;
import com.pet.buscaativa.entities.SessaoGrupo;
import com.pet.buscaativa.entities.Usuario;
import com.pet.buscaativa.entities.VinculoAssistencial;
import com.pet.buscaativa.entities.enums.TipoUsuario;
import com.pet.buscaativa.repositories.AgendamentoRepository;
import com.pet.buscaativa.repositories.HistoricoPacienteRepository;
import com.pet.buscaativa.repositories.SessaoGrupoRepository;
import com.pet.buscaativa.services.exceptions.ResourceNotFoundException;
import com.pet.buscaativa.services.impl.AutorizacaoVinculoAssistencialService;
import com.pet.buscaativa.services.impl.EscopoUnidadeService;
import com.pet.buscaativa.services.impl.VerificacaoFatoRevisaoProvenienciaService;

/**
 * Fase somente consultiva: prova que uma futura solicitacao nao pode
 * confiar em identificador adivinhado, origem nula ou referencia territorial.
 * Nenhum teste efetua INSERT, muda a origem clinica ou abre endpoint.
 */
class VerificacaoFatoRevisaoProvenienciaServiceTests {
    private final UUID pacienteIdPublico = UUID.randomUUID();
    private EscopoUnidadeService escopo;
    private AutorizacaoVinculoAssistencialService autorizacao;
    private AgendamentoRepository agendamentos;
    private HistoricoPacienteRepository historicos;
    private SessaoGrupoRepository sessoes;
    private VerificacaoFatoRevisaoProvenienciaService service;
    private Paciente paciente;

    @BeforeEach
    void preparar() {
        escopo = mock(EscopoUnidadeService.class);
        autorizacao = mock(AutorizacaoVinculoAssistencialService.class);
        agendamentos = mock(AgendamentoRepository.class);
        historicos = mock(HistoricoPacienteRepository.class);
        sessoes = mock(SessaoGrupoRepository.class);
        service = new VerificacaoFatoRevisaoProvenienciaService(
                escopo, autorizacao, agendamentos, historicos, sessoes);
    }

    private void perfil(TipoUsuario role) {
        Usuario u = mock(Usuario.class);
        when(u.getTipoUsuario()).thenReturn(role);
        when(escopo.usuarioLogado()).thenReturn(u);
    }

    private void vinculoLocal() {
        paciente = mock(Paciente.class);
        when(paciente.getId()).thenReturn(17L);
        when(paciente.getIdPublico()).thenReturn(pacienteIdPublico);
        VinculoAssistencial v = mock(VinculoAssistencial.class);
        when(v.getPaciente()).thenReturn(paciente);
        when(v.getUnidadeChave()).thenReturn("CAPS_AD");
        when(autorizacao.exigirVinculoLocal(pacienteIdPublico)).thenReturn(v);
    }

    @Test
    void profissionalComAgendamentoLocalVerificadoRecebeSomenteMetadados() {
        perfil(TipoUsuario.PROFISSIONAL);
        vinculoLocal();
        Agendamento a = mock(Agendamento.class);
        when(a.getPaciente()).thenReturn(paciente);
        when(a.getUnidadeOrigemChave()).thenReturn("CAPS_AD");
        when(agendamentos.findById(7L)).thenReturn(Optional.of(a));

        var r = service.verificar(pacienteIdPublico, TipoRegistro.AGENDAMENTO, 7L);
        assertEquals(TipoRegistro.AGENDAMENTO, r.tipoRegistro());
        assertEquals(7L, r.registroId());
        assertEquals(17L, r.pacienteId());
        assertEquals("CAPS_AD", r.unidadeChave());
        verify(autorizacao).validarAgendamentoLocal(a);
        verifyNoInteractions(historicos, sessoes);
    }

    @Test
    void mesmoPacienteEmOutraUnidadeNaoAutorizaFatoDeOutroCaps() {
        perfil(TipoUsuario.ADMINISTRADOR);
        vinculoLocal();
        Agendamento outroCaps = mock(Agendamento.class);
        when(outroCaps.getPaciente()).thenReturn(paciente);
        when(outroCaps.getUnidadeOrigemChave()).thenReturn("CAPS_II");
        when(agendamentos.findById(9L)).thenReturn(Optional.of(outroCaps));

        assertThrows(ResourceNotFoundException.class,
                () -> service.verificar(pacienteIdPublico, TipoRegistro.AGENDAMENTO, 9L));
        verify(autorizacao, never()).validarAgendamentoLocal(any());
    }

    @Test
    void historicoLegadoSemOrigemComprovadaNaoPodeSerSolicitadoPorEstaVia() {
        perfil(TipoUsuario.PROFISSIONAL);
        vinculoLocal();
        HistoricoPaciente historico = mock(HistoricoPaciente.class);
        when(historico.getPaciente()).thenReturn(paciente);
        when(historicos.findById(11L)).thenReturn(Optional.of(historico));

        assertThrows(ResourceNotFoundException.class,
                () -> service.verificar(pacienteIdPublico, TipoRegistro.HISTORICO, 11L));
        verify(autorizacao, never()).validarHistoricoLocal(any());
    }

    @Test
    void historicoLocalExigeAindaCoerenciaDaFonteNoAutorizador() {
        perfil(TipoUsuario.PROFISSIONAL);
        vinculoLocal();
        HistoricoPaciente historico = mock(HistoricoPaciente.class);
        when(historico.getPaciente()).thenReturn(paciente);
        when(historico.getUnidadeOrigemChave()).thenReturn("CAPS_AD");
        when(historicos.findById(12L)).thenReturn(Optional.of(historico));
        doThrow(new ResourceNotFoundException("Historico nao encontrado."))
                .when(autorizacao).validarHistoricoLocal(historico);

        assertThrows(ResourceNotFoundException.class,
                () -> service.verificar(pacienteIdPublico, TipoRegistro.HISTORICO, 12L));
    }

    @Test
    void sessaoDaMesmaUnidadeExigeParticipacaoConfirmada() {
        perfil(TipoUsuario.ADMINISTRADOR);
        vinculoLocal();
        SessaoGrupo sessao = mock(SessaoGrupo.class);
        GrupoTerapeutico grupo = mock(GrupoTerapeutico.class);
        when(sessao.getUnidadeOrigemChave()).thenReturn("CAPS_AD");
        when(sessao.getGrupo()).thenReturn(grupo);
        when(grupo.getUnidadeOrigemChave()).thenReturn("CAPS_AD");
        when(sessoes.findById(21L)).thenReturn(Optional.of(sessao));
        doThrow(new ResourceNotFoundException("Sessao nao encontrada."))
                .when(autorizacao).validarSessaoLocal(sessao, paciente);

        assertThrows(ResourceNotFoundException.class,
                () -> service.verificar(pacienteIdPublico, TipoRegistro.SESSAO_GRUPO, 21L));
        verify(autorizacao).validarSessaoLocal(sessao, paciente);
    }

    @Test
    void recepcaoNaoConsultaFatosNemVinculos() {
        perfil(TipoUsuario.RECEPCAO);
        assertThrows(AccessDeniedException.class,
                () -> service.verificar(pacienteIdPublico, TipoRegistro.AGENDAMENTO, 3L));
        verifyNoInteractions(autorizacao, agendamentos, historicos, sessoes);
    }

    @Test
    void faltaDeVinculoLocalBloqueiaAntesDeBuscarFato() {
        perfil(TipoUsuario.PROFISSIONAL);
        when(autorizacao.exigirVinculoLocal(pacienteIdPublico))
                .thenThrow(new ResourceNotFoundException("Paciente nao encontrado."));
        assertThrows(ResourceNotFoundException.class,
                () -> service.verificar(pacienteIdPublico, TipoRegistro.AGENDAMENTO, 3L));
        verifyNoInteractions(agendamentos, historicos, sessoes);
    }
}
