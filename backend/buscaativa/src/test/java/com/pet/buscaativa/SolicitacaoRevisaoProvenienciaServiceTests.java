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

import com.pet.buscaativa.entities.Paciente;
import com.pet.buscaativa.entities.PendenciaRevisaoProveniencia;
import com.pet.buscaativa.entities.PendenciaRevisaoProveniencia.TipoRegistro;
import com.pet.buscaativa.entities.Usuario;
import com.pet.buscaativa.entities.VinculoAssistencial;
import com.pet.buscaativa.entities.enums.TipoUsuario;
import com.pet.buscaativa.repositories.PendenciaRevisaoProvenienciaComandoRepository;
import com.pet.buscaativa.repositories.VinculoAssistencialRepository;
import com.pet.buscaativa.services.PoliticaJustificativaCorrecao;
import com.pet.buscaativa.services.exceptions.ResourceNotFoundException;
import com.pet.buscaativa.services.exceptions.ValidationException;
import com.pet.buscaativa.services.impl.AutorizacaoVinculoAssistencialService;
import com.pet.buscaativa.services.impl.EscopoUnidadeService;
import com.pet.buscaativa.services.impl.SolicitacaoRevisaoProvenienciaService;
import com.pet.buscaativa.services.impl.VerificacaoFatoRevisaoProvenienciaService;
import com.pet.buscaativa.services.impl.VerificacaoFatoRevisaoProvenienciaService.FatoLocalVerificado;

/** Testes sem endpoints ou gravacao real; o banco sera exercitado na CI separadamente. */
class SolicitacaoRevisaoProvenienciaServiceTests {
    private final UUID pacienteIdPublico = UUID.randomUUID();
    private static final String EXPLICACAO =
            "Revisao documental necessaria para conferir o atendimento registrado.";

    private EscopoUnidadeService escopo;
    private AutorizacaoVinculoAssistencialService autorizacao;
    private VerificacaoFatoRevisaoProvenienciaService verificador;
    private PoliticaJustificativaCorrecao politica;
    private VinculoAssistencialRepository vinculos;
    private PendenciaRevisaoProvenienciaComandoRepository pendencias;
    private SolicitacaoRevisaoProvenienciaService service;
    private Usuario ator;
    private VinculoAssistencial vinculo;

    @BeforeEach
    void preparar() {
        escopo = mock(EscopoUnidadeService.class);
        autorizacao = mock(AutorizacaoVinculoAssistencialService.class);
        verificador = mock(VerificacaoFatoRevisaoProvenienciaService.class);
        politica = mock(PoliticaJustificativaCorrecao.class);
        vinculos = mock(VinculoAssistencialRepository.class);
        pendencias = mock(PendenciaRevisaoProvenienciaComandoRepository.class);
        var clock = Clock.fixed(Instant.parse("2026-11-22T12:00:00Z"), ZoneOffset.UTC);
        service = new SolicitacaoRevisaoProvenienciaService(
                escopo, autorizacao, verificador, politica, vinculos, pendencias, clock);
        ator = mock(Usuario.class);
        when(escopo.usuarioLogado()).thenReturn(ator);
    }

    private void autorizado() {
        when(ator.getTipoUsuario()).thenReturn(TipoUsuario.PROFISSIONAL);
    }

    private void fatoComprovado() {
        autorizado();
        when(ator.getId()).thenReturn(8L);
        Paciente paciente = mock(Paciente.class);
        when(paciente.getId()).thenReturn(51L);
        vinculo = mock(VinculoAssistencial.class);
        when(vinculo.getId()).thenReturn(17L);
        when(vinculo.getPaciente()).thenReturn(paciente);
        when(vinculo.getUnidadeChave()).thenReturn("CAPS_AD");
        when(verificador.verificar(pacienteIdPublico, TipoRegistro.AGENDAMENTO, 101L))
                .thenReturn(new FatoLocalVerificado(TipoRegistro.AGENDAMENTO, 101L, 51L, "CAPS_AD"));
        when(politica.validar(EXPLICACAO)).thenReturn(EXPLICACAO);
        when(autorizacao.exigirVinculoLocal(pacienteIdPublico)).thenReturn(vinculo);
        when(vinculos.findByIdForPendenciaUpdate(17L)).thenReturn(Optional.of(vinculo));
    }

    @Test
    void somenteFatoComprovadoComPerfilEJustificativaCriaTicketPendente() {
        fatoComprovado();
        var id = service.solicitar(pacienteIdPublico, TipoRegistro.AGENDAMENTO, 101L, EXPLICACAO);
        assertNotNull(id);
        var captor = org.mockito.ArgumentCaptor.forClass(PendenciaRevisaoProveniencia.class);
        verify(pendencias).save(captor.capture());
        var p = captor.getValue();
        assertEquals(id, p.getId());
        assertEquals(51L, p.getPacienteId());
        assertEquals("CAPS_AD", p.getUnidadeChave());
        assertEquals(EXPLICACAO, p.getJustificativa());
        assertEquals(TipoUsuario.PROFISSIONAL, p.getPerfilSolicitante());
        assertEquals(PendenciaRevisaoProveniencia.Estado.PENDENTE, p.getEstado());
        assertEquals(com.pet.buscaativa.migration.ClassificadorEvidenciaClinicaLocal.Situacao.COMPATIVEL_SOMENTE_PARA_CONFERENCIA,
                p.getSituacaoEvidencia());
    }

    @Test
    void recepcaoNaoChegaANenhumaConsultaClinica() {
        when(ator.getTipoUsuario()).thenReturn(TipoUsuario.RECEPCAO);
        assertThrows(AccessDeniedException.class,
                () -> service.solicitar(pacienteIdPublico, TipoRegistro.AGENDAMENTO, 101L, EXPLICACAO));
        verifyNoInteractions(verificador, autorizacao, politica, vinculos, pendencias);
    }

    @Test
    void origemNaoComprovadaNaoCriaTicket() {
        autorizado();
        when(verificador.verificar(pacienteIdPublico, TipoRegistro.AGENDAMENTO, 101L))
                .thenThrow(new ResourceNotFoundException("Registro nao encontrado."));
        assertThrows(ResourceNotFoundException.class,
                () -> service.solicitar(pacienteIdPublico, TipoRegistro.AGENDAMENTO, 101L, EXPLICACAO));
        verifyNoInteractions(politica, vinculos, pendencias);
    }

    @Test
    void justificativaInvalidaNaoCriaTicket() {
        autorizado();
        when(verificador.verificar(pacienteIdPublico, TipoRegistro.AGENDAMENTO, 101L))
                .thenReturn(new FatoLocalVerificado(TipoRegistro.AGENDAMENTO, 101L, 51L, "CAPS_AD"));
        when(politica.validar(EXPLICACAO)).thenThrow(new ValidationException("Justificativa invalida."));
        assertThrows(ValidationException.class,
                () -> service.solicitar(pacienteIdPublico, TipoRegistro.AGENDAMENTO, 101L, EXPLICACAO));
        verifyNoInteractions(vinculos, pendencias);
    }

    @Test
    void alteracaoDoVinculoEntreVerificacaoEGravacaoBloqueia() {
        fatoComprovado();
        when(vinculo.getUnidadeChave()).thenReturn("CAPS_II");
        assertThrows(ResourceNotFoundException.class,
                () -> service.solicitar(pacienteIdPublico, TipoRegistro.AGENDAMENTO, 101L, EXPLICACAO));
        verifyNoInteractions(vinculos, pendencias);
    }

    @Test
    void naoPermiteSegundoTicketDoMesmoRegistro() {
        fatoComprovado();
        when(pendencias.existsByPacienteIdAndUnidadeChaveAndTipoRegistroAndRegistroId(
                51L, "CAPS_AD", TipoRegistro.AGENDAMENTO, 101L)).thenReturn(true);
        assertThrows(ValidationException.class,
                () -> service.solicitar(pacienteIdPublico, TipoRegistro.AGENDAMENTO, 101L, EXPLICACAO));
        verify(pendencias, never()).save(any());
    }
}
