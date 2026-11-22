package com.pet.buscaativa;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

import com.pet.buscaativa.entities.DecisaoRevisaoProveniencia;
import com.pet.buscaativa.entities.DecisaoRevisaoProveniencia.Decisao;
import com.pet.buscaativa.entities.Paciente;
import com.pet.buscaativa.entities.PendenciaRevisaoProveniencia;
import com.pet.buscaativa.entities.PendenciaRevisaoProveniencia.Estado;
import com.pet.buscaativa.entities.PendenciaRevisaoProveniencia.TipoRegistro;
import com.pet.buscaativa.entities.Usuario;
import com.pet.buscaativa.entities.VinculoAssistencial;
import com.pet.buscaativa.entities.enums.TipoUsuario;
import com.pet.buscaativa.entities.enums.UnidadeAtuacao;
import com.pet.buscaativa.repositories.DecisaoRevisaoProvenienciaComandoRepository;
import com.pet.buscaativa.repositories.FilaPendenciasRevisaoProvenienciaRepository;
import com.pet.buscaativa.repositories.VinculoAssistencialRepository;
import com.pet.buscaativa.services.PoliticaJustificativaCorrecao;
import com.pet.buscaativa.services.exceptions.ResourceNotFoundException;
import com.pet.buscaativa.services.exceptions.ValidationException;
import com.pet.buscaativa.services.impl.AutorizacaoVinculoAssistencialService;
import com.pet.buscaativa.services.impl.EscopoUnidadeService;
import com.pet.buscaativa.services.impl.RegistroDecisaoRevisaoProvenienciaService;
import com.pet.buscaativa.services.impl.VerificacaoFatoRevisaoProvenienciaService;
import com.pet.buscaativa.services.impl.VerificacaoFatoRevisaoProvenienciaService.FatoLocalVerificado;

/** Confere o fluxo de comando antes da integracao persistente da V13. */
class RegistroDecisaoRevisaoProvenienciaServiceTests {
    private static final String MOTIVO =
            "Documentacao assistencial revisada pela equipe da unidade local.";
    private final UUID pacientePublico = UUID.randomUUID(), ticketId = UUID.randomUUID();
    private EscopoUnidadeService escopo;
    private AutorizacaoVinculoAssistencialService autorizacao;
    private VerificacaoFatoRevisaoProvenienciaService verificador;
    private PoliticaJustificativaCorrecao politica;
    private VinculoAssistencialRepository vinculos;
    private FilaPendenciasRevisaoProvenienciaRepository fila;
    private DecisaoRevisaoProvenienciaComandoRepository comandos;
    private RegistroDecisaoRevisaoProvenienciaService service;
    private Usuario admin;
    private VinculoAssistencial vinculo;

    @BeforeEach
    void iniciar() {
        escopo = mock(EscopoUnidadeService.class);
        autorizacao = mock(AutorizacaoVinculoAssistencialService.class);
        verificador = mock(VerificacaoFatoRevisaoProvenienciaService.class);
        politica = mock(PoliticaJustificativaCorrecao.class);
        vinculos = mock(VinculoAssistencialRepository.class);
        fila = mock(FilaPendenciasRevisaoProvenienciaRepository.class);
        comandos = mock(DecisaoRevisaoProvenienciaComandoRepository.class);
        service = new RegistroDecisaoRevisaoProvenienciaService(
                escopo, autorizacao, verificador, politica, vinculos, fila, comandos,
                Clock.fixed(Instant.parse("2026-11-22T15:00:00Z"), ZoneOffset.UTC));
        admin = mock(Usuario.class);
        when(escopo.usuarioLogado()).thenReturn(admin);
    }

    private void perfilAdmin() {
        when(admin.getTipoUsuario()).thenReturn(TipoUsuario.ADMINISTRADOR);
        when(admin.getUnidadeAtuacao()).thenReturn(UnidadeAtuacao.CAPS_AD);
    }

    private void prepararFatoLocal() {
        perfilAdmin();
        when(admin.getId()).thenReturn(99L);
        Paciente p = mock(Paciente.class);
        when(p.getId()).thenReturn(51L);
        vinculo = mock(VinculoAssistencial.class);
        when(vinculo.getId()).thenReturn(17L);
        when(vinculo.getUnidadeChave()).thenReturn("CAPS_AD");
        when(vinculo.getPaciente()).thenReturn(p);
        when(autorizacao.exigirVinculoLocal(pacientePublico)).thenReturn(vinculo);
        when(vinculos.findByIdForPendenciaUpdate(17L)).thenReturn(Optional.of(vinculo));
        PendenciaRevisaoProveniencia ticket = mock(PendenciaRevisaoProveniencia.class);
        when(ticket.getId()).thenReturn(ticketId);
        when(ticket.getPacienteId()).thenReturn(51L);
        when(ticket.getUnidadeChave()).thenReturn("CAPS_AD");
        when(ticket.getVinculo()).thenReturn(vinculo);
        when(ticket.getTipoRegistro()).thenReturn(TipoRegistro.AGENDAMENTO);
        when(ticket.getRegistroId()).thenReturn(101L);
        when(ticket.getEstado()).thenReturn(Estado.PENDENTE);
        when(fila.findByIdAndUnidadeChaveAndEstado(ticketId, "CAPS_AD", Estado.PENDENTE))
                .thenReturn(Optional.of(ticket));
        when(verificador.verificar(pacientePublico, TipoRegistro.AGENDAMENTO, 101L))
                .thenReturn(new FatoLocalVerificado(TipoRegistro.AGENDAMENTO, 101L, 51L, "CAPS_AD"));
        when(politica.validar(MOTIVO)).thenReturn(MOTIVO);
    }

    @Test
    void administradorComFatoComprovadoRegistraSomenteEventoImutavel() {
        prepararFatoLocal();
        UUID id = service.registrar(pacientePublico, ticketId, Decisao.REJEITAR, MOTIVO);
        assertNotNull(id);
        var captor = org.mockito.ArgumentCaptor.forClass(DecisaoRevisaoProveniencia.class);
        verify(comandos).save(captor.capture());
        var registro = captor.getValue();
        assertEquals(id, registro.getId());
        assertEquals(51L, registro.getPacienteId());
        assertEquals("CAPS_AD", registro.getUnidadeChave());
        assertEquals(Decisao.REJEITAR, registro.getDecisao());
        assertEquals(MOTIVO, registro.getJustificativa());
        assertEquals(TipoUsuario.ADMINISTRADOR, registro.getPerfilRevisor());
        verify(comandos).existsByPendenciaIdAndPacienteIdAndUnidadeChave(
                ticketId, 51L, "CAPS_AD");
    }

    @Test
    void profissionalNaoPodeDecidirNemConsultarTickets() {
        when(admin.getTipoUsuario()).thenReturn(TipoUsuario.PROFISSIONAL);
        assertThrows(AccessDeniedException.class,
                () -> service.registrar(pacientePublico, ticketId, Decisao.APROVAR, MOTIVO));
        verifyNoInteractions(autorizacao, verificador, vinculos, fila, comandos);
    }

    @Test
    void ticketDeOutroCapsNaoPodeSerRegistrado() {
        perfilAdmin();
        Paciente p = mock(Paciente.class);
        when(p.getId()).thenReturn(51L);
        VinculoAssistencial v = mock(VinculoAssistencial.class);
        when(v.getId()).thenReturn(17L);
        when(v.getPaciente()).thenReturn(p);
        when(v.getUnidadeChave()).thenReturn("CAPS_AD");
        when(autorizacao.exigirVinculoLocal(pacientePublico)).thenReturn(v);
        when(vinculos.findByIdForPendenciaUpdate(17L)).thenReturn(Optional.of(v));
        assertThrows(ResourceNotFoundException.class,
                () -> service.registrar(pacientePublico, ticketId, Decisao.APROVAR, MOTIVO));
        verifyNoInteractions(verificador, comandos);
    }

    @Test
    void evidenciaDeOrigemIncongruenteImpedeDecisao() {
        prepararFatoLocal();
        when(verificador.verificar(pacientePublico, TipoRegistro.AGENDAMENTO, 101L))
                .thenReturn(new FatoLocalVerificado(TipoRegistro.AGENDAMENTO, 101L, 51L, "CAPS_II"));
        assertThrows(ResourceNotFoundException.class,
                () -> service.registrar(pacientePublico, ticketId, Decisao.APROVAR, MOTIVO));
        verifyNoInteractions(comandos);
    }

    @Test
    void decisaoAnteriorImpedeGravacaoDuplicada() {
        prepararFatoLocal();
        when(comandos.existsByPendenciaIdAndPacienteIdAndUnidadeChave(
                ticketId, 51L, "CAPS_AD")).thenReturn(true);
        assertThrows(ValidationException.class,
                () -> service.registrar(pacientePublico, ticketId, Decisao.APROVAR, MOTIVO));
        verify(comandos, never()).save(any());
    }

    @Test
    void justificativaInvalidaImpedeGravacao() {
        prepararFatoLocal();
        when(politica.validar(MOTIVO)).thenThrow(new ValidationException("Justificativa invalida."));
        assertThrows(ValidationException.class,
                () -> service.registrar(pacientePublico, ticketId, Decisao.REJEITAR, MOTIVO));
        verifyNoInteractions(comandos);
    }
}
