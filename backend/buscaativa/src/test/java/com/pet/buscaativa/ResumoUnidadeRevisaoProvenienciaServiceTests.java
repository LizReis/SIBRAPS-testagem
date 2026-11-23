package com.pet.buscaativa;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.annotation.Transactional;

import com.pet.buscaativa.entities.PendenciaRevisaoProveniencia.Estado;
import com.pet.buscaativa.entities.Usuario;
import com.pet.buscaativa.entities.UsfReferencia;
import com.pet.buscaativa.entities.enums.TipoUsuario;
import com.pet.buscaativa.entities.enums.UnidadeAtuacao;
import com.pet.buscaativa.repositories.DecisaoRevisaoProvenienciaRepository;
import com.pet.buscaativa.repositories.FilaPendenciasRevisaoProvenienciaRepository;
import com.pet.buscaativa.services.exceptions.ValidationException;
import com.pet.buscaativa.services.impl.EscopoUnidadeService;
import com.pet.buscaativa.services.impl.ResumoUnidadeRevisaoProvenienciaService;

/**
 * Guarda de autorizacao do resumo administrativo interno: validar perfil,
 * configuracao e chave da unidade ANTES de executar qualquer COUNT.
 * As consultas com H2 e isolamento real ficam na classe de integracao.
 */
class ResumoUnidadeRevisaoProvenienciaServiceTests {
    private EscopoUnidadeService escopo;
    private FilaPendenciasRevisaoProvenienciaRepository fila;
    private DecisaoRevisaoProvenienciaRepository decisoes;
    private ResumoUnidadeRevisaoProvenienciaService service;

    @BeforeEach
    void setup() {
        escopo = mock(EscopoUnidadeService.class);
        fila = mock(FilaPendenciasRevisaoProvenienciaRepository.class);
        decisoes = mock(DecisaoRevisaoProvenienciaRepository.class);
        service = new ResumoUnidadeRevisaoProvenienciaService(escopo, fila, decisoes);
    }

    private Usuario ator(TipoUsuario perfil, UnidadeAtuacao unidade) {
        Usuario usuario = mock(Usuario.class);
        when(usuario.getTipoUsuario()).thenReturn(perfil);
        if (perfil == TipoUsuario.ADMINISTRADOR)
            when(usuario.getUnidadeAtuacao()).thenReturn(unidade);
        when(escopo.usuarioLogado()).thenReturn(usuario);
        return usuario;
    }

    @Test
    void administradorCapsRecebeApenasContagemDaChaveAutenticada() {
        ator(TipoUsuario.ADMINISTRADOR, UnidadeAtuacao.CAPS_AD);
        when(fila.contarAbertasDaUnidade("CAPS_AD", Estado.PENDENTE)).thenReturn(3L);
        when(decisoes.contarDecisoesDaUnidade("CAPS_AD")).thenReturn(2L);

        var resumo = service.consultar();

        assertEquals(3L, resumo.pendenciasAbertas());
        assertEquals(2L, resumo.decisoesRegistradas());
        verify(escopo).validarUnidadeConfigurada();
        verify(fila).contarAbertasDaUnidade("CAPS_AD", Estado.PENDENTE);
        verify(decisoes).contarDecisoesDaUnidade("CAPS_AD");
        verifyNoMoreInteractions(fila, decisoes);
        assertTrue(ResumoUnidadeRevisaoProvenienciaService.class
                .getAnnotation(Transactional.class).readOnly());
    }

    @Test
    void administradorUsfUsaIdPersistidoEmVezDeReferenciaTerritorial() {
        Usuario admin = ator(TipoUsuario.ADMINISTRADOR, UnidadeAtuacao.USF);
        UsfReferencia usf = mock(UsfReferencia.class);
        when(usf.getId()).thenReturn(75L);
        when(admin.getUsfAtuacao()).thenReturn(usf);

        var resultado = service.consultar();

        assertEquals(0, resultado.pendenciasAbertas());
        assertEquals(0, resultado.decisoesRegistradas());
        verify(fila).contarAbertasDaUnidade("USF:75", Estado.PENDENTE);
        verify(decisoes).contarDecisoesDaUnidade("USF:75");
        verifyNoMoreInteractions(fila, decisoes);
    }

    @Test
    void recepcaoProfissionalEAnonimoNaoConsultamContagens() {
        for (TipoUsuario perfil : new TipoUsuario[]{
                TipoUsuario.PROFISSIONAL, TipoUsuario.RECEPCAO}) {
            ator(perfil, UnidadeAtuacao.CAPS_AD);
            assertThrows(AccessDeniedException.class, () -> service.consultar());
        }
        when(escopo.usuarioLogado()).thenReturn(null);
        assertThrows(AccessDeniedException.class, () -> service.consultar());

        verify(escopo, never()).validarUnidadeConfigurada();
        verifyNoInteractions(fila, decisoes);
    }

    @Test
    void unidadeNaoConfiguradaFalhaAntesDeQualquerConsulta() {
        ator(TipoUsuario.ADMINISTRADOR, UnidadeAtuacao.CAPS_AD);
        doThrow(new ValidationException("Unidade de atuacao nao configurada."))
                .when(escopo).validarUnidadeConfigurada();

        assertThrows(ValidationException.class, () -> service.consultar());
        verifyNoInteractions(fila, decisoes);
    }

    @Test
    void usfSemIdPersistidoNaoPermiteInferirOutraUnidade() {
        Usuario admin = ator(TipoUsuario.ADMINISTRADOR, UnidadeAtuacao.USF);
        UsfReferencia usf = mock(UsfReferencia.class);
        when(admin.getUsfAtuacao()).thenReturn(usf);

        assertThrows(IllegalArgumentException.class, () -> service.consultar());
        verifyNoInteractions(fila, decisoes);
    }
}
