package com.pet.buscaativa;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

import com.pet.buscaativa.entities.PendenciaRevisaoProveniencia;
import com.pet.buscaativa.entities.PendenciaRevisaoProveniencia.Estado;
import com.pet.buscaativa.entities.Usuario;
import com.pet.buscaativa.entities.UsfReferencia;
import com.pet.buscaativa.entities.enums.TipoUsuario;
import com.pet.buscaativa.entities.enums.UnidadeAtuacao;
import com.pet.buscaativa.repositories.FilaPendenciasRevisaoProvenienciaRepository;
import com.pet.buscaativa.services.PoliticaJustificativaCorrecao;
import com.pet.buscaativa.services.exceptions.ResourceNotFoundException;
import com.pet.buscaativa.services.exceptions.ValidationException;
import com.pet.buscaativa.services.impl.EscopoUnidadeService;
import com.pet.buscaativa.services.impl.PreparacaoDecisaoRevisaoProvenienciaService;
import com.pet.buscaativa.services.impl.PreparacaoDecisaoRevisaoProvenienciaService.Decisao;
import com.pet.buscaativa.services.impl.PreparacaoDecisaoRevisaoProvenienciaService.EstadoRascunho;

/**
 * Propostas de decisao sem persistencia: autorizacao pelo contexto real
 * do ator e consulta por ID+unidade+PENDENTE (nenhuma rota clinica e ativada).
 */
class PreparacaoDecisaoRevisaoProvenienciaServiceTests {
    private final UUID id = UUID.randomUUID();
    private EscopoUnidadeService escopo;
    private FilaPendenciasRevisaoProvenienciaRepository fila;
    private PoliticaJustificativaCorrecao politica;
    private PreparacaoDecisaoRevisaoProvenienciaService service;

    @BeforeEach
    void preparar() {
        escopo = mock(EscopoUnidadeService.class);
        fila = mock(FilaPendenciasRevisaoProvenienciaRepository.class);
        politica = mock(PoliticaJustificativaCorrecao.class);
        service = new PreparacaoDecisaoRevisaoProvenienciaService(escopo, fila, politica);
    }

    private Usuario administrador(UnidadeAtuacao unidade) {
        Usuario usuario = mock(Usuario.class);
        when(usuario.getTipoUsuario()).thenReturn(TipoUsuario.ADMINISTRADOR);
        when(usuario.getUnidadeAtuacao()).thenReturn(unidade);
        when(escopo.usuarioLogado()).thenReturn(usuario);
        return usuario;
    }

    private void ticketLocal(String unidade) {
        PendenciaRevisaoProveniencia ticket = mock(PendenciaRevisaoProveniencia.class);
        when(ticket.getId()).thenReturn(id);
        when(ticket.getUnidadeChave()).thenReturn(unidade);
        when(ticket.getEstado()).thenReturn(Estado.PENDENTE);
        when(fila.findByIdAndUnidadeChaveAndEstado(id, unidade, Estado.PENDENTE))
                .thenReturn(Optional.of(ticket));
    }

    @Test
    void administradorLocalPodePrepararAprovacaoMasNaoGravaNada() {
        administrador(UnidadeAtuacao.CAPS_AD);
        ticketLocal("CAPS_AD");
        when(politica.validar("Documento assistencial verificado pela equipe."))
                .thenReturn("Documento assistencial verificado pela equipe.");

        var rascunho = service.preparar(id, Decisao.APROVAR,
                "Documento assistencial verificado pela equipe.");
        assertEquals(EstadoRascunho.RASCUNHO_NAO_PERSISTIDO, rascunho.estado());
        assertEquals(id, rascunho.pendenciaId());
        assertEquals("CAPS_AD", rascunho.unidadeChave());
        assertEquals(Decisao.APROVAR, rascunho.decisaoProposta());
        verify(fila).findByIdAndUnidadeChaveAndEstado(id, "CAPS_AD", Estado.PENDENTE);
        verifyNoMoreInteractions(fila);
    }

    @Test
    void administradorDaOutraUnidadeNaoDescobreTicketPorUuid() {
        administrador(UnidadeAtuacao.CAPS_II);
        assertThrows(ResourceNotFoundException.class,
                () -> service.preparar(id, Decisao.REJEITAR,
                        "Registro deve retornar para nova conferencia documental."));
        verify(fila).findByIdAndUnidadeChaveAndEstado(id, "CAPS_II", Estado.PENDENTE);
        verifyNoInteractions(politica);
    }

    @Test
    void usfEspecificaUsaChaveCanonicaDoUsuarioEPermiteRejeicao() {
        Usuario admin = administrador(UnidadeAtuacao.USF);
        UsfReferencia usf = mock(UsfReferencia.class);
        when(usf.getId()).thenReturn(73L);
        when(admin.getUsfAtuacao()).thenReturn(usf);
        ticketLocal("USF:73");
        when(politica.validar("Evidencias apresentadas nao comprovam a informacao registrada."))
                .thenReturn("Evidencias apresentadas nao comprovam a informacao registrada.");

        var rascunho = service.preparar(id, Decisao.REJEITAR,
                "Evidencias apresentadas nao comprovam a informacao registrada.");
        assertEquals(Decisao.REJEITAR, rascunho.decisaoProposta());
        assertEquals("USF:73", rascunho.unidadeChave());
    }

    @Test
    void profissionalERecepcaoNaoConsultamTicketNemValidamJustificativa() {
        for (var perfil : new TipoUsuario[]{TipoUsuario.PROFISSIONAL, TipoUsuario.RECEPCAO}) {
            Usuario usuario = mock(Usuario.class);
            when(usuario.getTipoUsuario()).thenReturn(perfil);
            when(escopo.usuarioLogado()).thenReturn(usuario);
            assertThrows(AccessDeniedException.class,
                    () -> service.preparar(id, Decisao.APROVAR,
                            "Solicitacao com documento comprobatório."));
        }
        verifyNoInteractions(fila, politica);
    }

    @Test
    void justificativaInvalidaImpedeMesmoRascunhoDeDecisao() {
        administrador(UnidadeAtuacao.CAPS_AD);
        ticketLocal("CAPS_AD");
        when(politica.validar("sem motivo"))
                .thenThrow(new ValidationException("Justificativa invalida."));
        assertThrows(ValidationException.class,
                () -> service.preparar(id, Decisao.APROVAR, "sem motivo"));
    }

    @Test
    void nuloOuRegistroDeOutraUnidadeNaoGeraRascunho() {
        administrador(UnidadeAtuacao.CAPS_AD);
        assertThrows(ResourceNotFoundException.class,
                () -> service.preparar(null, Decisao.REJEITAR, "Justificativa registrada."));
        assertThrows(IllegalArgumentException.class,
                () -> service.preparar(id, null, "Justificativa registrada."));
        PendenciaRevisaoProveniencia forjado = mock(PendenciaRevisaoProveniencia.class);
        when(forjado.getId()).thenReturn(id);
        when(forjado.getUnidadeChave()).thenReturn("CAPS_II");
        when(fila.findByIdAndUnidadeChaveAndEstado(id, "CAPS_AD", Estado.PENDENTE))
                .thenReturn(Optional.of(forjado));
        assertThrows(ResourceNotFoundException.class,
                () -> service.preparar(id, Decisao.REJEITAR, "Justificativa registrada."));
        verifyNoInteractions(politica);
    }

    @Test
    void pendenciaNaoPendenteNaoGeraRascunho() {
        administrador(UnidadeAtuacao.CAPS_AD);
        PendenciaRevisaoProveniencia invalida = mock(PendenciaRevisaoProveniencia.class);
        when(invalida.getId()).thenReturn(id);
        when(invalida.getUnidadeChave()).thenReturn("CAPS_AD");
        // A consulta obrigatoriamente solicita PENDENTE; estado nulo ou incoerente falha fechado.
        when(fila.findByIdAndUnidadeChaveAndEstado(id, "CAPS_AD", Estado.PENDENTE))
                .thenReturn(Optional.of(invalida));
        assertThrows(ResourceNotFoundException.class,
                () -> service.preparar(id, Decisao.APROVAR, "Conferir estado."));
        verifyNoInteractions(politica);
    }
}
