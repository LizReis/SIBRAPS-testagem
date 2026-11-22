package com.pet.buscaativa;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.AccessDeniedException;

import com.pet.buscaativa.entities.Paciente;
import com.pet.buscaativa.entities.PendenciaRevisaoProveniencia;
import com.pet.buscaativa.entities.PendenciaRevisaoProveniencia.Estado;
import com.pet.buscaativa.entities.PendenciaRevisaoProveniencia.TipoRegistro;
import com.pet.buscaativa.entities.Usuario;
import com.pet.buscaativa.entities.VinculoAssistencial;
import com.pet.buscaativa.entities.enums.TipoUsuario;
import com.pet.buscaativa.migration.ClassificadorEvidenciaClinicaLocal.Situacao;
import com.pet.buscaativa.repositories.PendenciaRevisaoProvenienciaRepository;
import com.pet.buscaativa.services.exceptions.ResourceNotFoundException;
import com.pet.buscaativa.services.impl.AutorizacaoVinculoAssistencialService;
import com.pet.buscaativa.services.impl.ConsultaPendenciasRevisaoProvenienciaService;
import com.pet.buscaativa.services.impl.EscopoUnidadeService;

/**
 * Confere a autorizacao aplicada ANTES de toda consulta JPA.
 * Nao ativa endpoints, operacoes de escrita ou compartilhamento entre unidades.
 */
class ConsultaPendenciasRevisaoProvenienciaServiceTests {
    private final UUID pacienteId = UUID.randomUUID();
    private EscopoUnidadeService escopo;
    private AutorizacaoVinculoAssistencialService autorizacao;
    private PendenciaRevisaoProvenienciaRepository repository;
    private ConsultaPendenciasRevisaoProvenienciaService service;

    @BeforeEach
    void setup() {
        escopo = mock(EscopoUnidadeService.class);
        autorizacao = mock(AutorizacaoVinculoAssistencialService.class);
        repository = mock(PendenciaRevisaoProvenienciaRepository.class);
        service = new ConsultaPendenciasRevisaoProvenienciaService(escopo, autorizacao, repository);
    }

    private void ator(TipoUsuario perfil) {
        Usuario u = mock(Usuario.class);
        when(u.getTipoUsuario()).thenReturn(perfil);
        when(escopo.usuarioLogado()).thenReturn(u);
    }

    private void vinculoLocal(long id, String unidade) {
        Paciente p = mock(Paciente.class);
        when(p.getId()).thenReturn(id);
        VinculoAssistencial vinculo = mock(VinculoAssistencial.class);
        when(vinculo.getPaciente()).thenReturn(p);
        when(vinculo.getUnidadeChave()).thenReturn(unidade);
        when(autorizacao.exigirVinculoLocal(pacienteId)).thenReturn(vinculo);
    }

    @Test
    void profissionalListaSomentePendenciasPendentesDoProprioVinculo() {
        ator(TipoUsuario.PROFISSIONAL);
        vinculoLocal(44L, "CAPS_AD");
        var pagina = PageRequest.of(0, 10);
        when(repository.findByPacienteIdAndUnidadeChaveAndEstadoOrderBySolicitadoEmDesc(
                44L, "CAPS_AD", Estado.PENDENTE, pagina))
                .thenReturn(new PageImpl<>(List.of(), pagina, 0));

        var resultado = service.listar(pacienteId, pagina);

        assertTrue(resultado.isEmpty());
        verify(repository).findByPacienteIdAndUnidadeChaveAndEstadoOrderBySolicitadoEmDesc(
                44L, "CAPS_AD", Estado.PENDENTE, pagina);
        verifyNoMoreInteractions(repository);
    }

    @Test
    void detalhePermiteAdministradorLocalMasNaoBuscaForaDoVinculo() {
        ator(TipoUsuario.ADMINISTRADOR);
        vinculoLocal(44L, "USF:8");
        UUID id = UUID.randomUUID();
        when(repository.findByIdAndPacienteIdAndUnidadeChave(id, 44L, "USF:8"))
                .thenReturn(Optional.empty());
        assertThrows(ResourceNotFoundException.class, () -> service.detalhar(pacienteId, id));
        verify(repository).findByIdAndPacienteIdAndUnidadeChave(id, 44L, "USF:8");
        verifyNoMoreInteractions(repository);
    }

    @Test
    void recepcaoNaoConsegueConsultarNemDescobrirExistencia() {
        ator(TipoUsuario.RECEPCAO);
        assertThrows(AccessDeniedException.class,
                () -> service.listar(pacienteId, PageRequest.of(0, 10)));
        assertThrows(AccessDeniedException.class,
                () -> service.detalhar(pacienteId, UUID.randomUUID()));
        verifyNoInteractions(autorizacao, repository);
    }

    @Test
    void semVinculoNaoConsultaBanco() {
        ator(TipoUsuario.PROFISSIONAL);
        when(autorizacao.exigirVinculoLocal(pacienteId))
                .thenThrow(new ResourceNotFoundException("Paciente nao encontrado."));
        assertThrows(ResourceNotFoundException.class,
                () -> service.listar(pacienteId, PageRequest.of(0, 10)));
        verifyNoInteractions(repository);
    }

    @Test
    void paginacaoIlimitadaOuSemTicketNaoFazConsulta() {
        ator(TipoUsuario.ADMINISTRADOR);
        assertThrows(IllegalArgumentException.class,
                () -> service.listar(pacienteId, PageRequest.of(0, 101)));
        assertThrows(ResourceNotFoundException.class,
                () -> service.detalhar(pacienteId, null));
        verifyNoInteractions(autorizacao, repository);
    }

    @Test
    void respostaNaoIncluiAutorOuReferenciaDeOutraUnidade() {
        ator(TipoUsuario.PROFISSIONAL);
        vinculoLocal(44L, "CAPS_AD");
        UUID id = UUID.randomUUID();
        PendenciaRevisaoProveniencia p = mock(PendenciaRevisaoProveniencia.class);
        when(p.getId()).thenReturn(id);
        when(p.getTipoRegistro()).thenReturn(TipoRegistro.AGENDAMENTO);
        when(p.getRegistroId()).thenReturn(51L);
        when(p.getSituacaoEvidencia()).thenReturn(Situacao.REVISAR_ORIGEM_DESCONHECIDA);
        when(p.getJustificativa()).thenReturn("Solicitacao sintetica para conferencia documental");
        when(p.getSolicitadoEm()).thenReturn(Instant.parse("2026-11-22T12:00:00Z"));
        when(p.getEstado()).thenReturn(Estado.PENDENTE);
        when(repository.findByIdAndPacienteIdAndUnidadeChave(id, 44L, "CAPS_AD"))
                .thenReturn(Optional.of(p));
        var resultado = service.detalhar(pacienteId, id);
        assertEquals(id, resultado.id());
        assertEquals(Estado.PENDENTE, resultado.estado());
        assertEquals(51L, resultado.registroId());
        assertEquals(7, ConsultaPendenciasRevisaoProvenienciaService.Resumo.class.getRecordComponents().length);
        verify(p, never()).getSolicitante();
        verify(p, never()).getVinculo();
    }
}
