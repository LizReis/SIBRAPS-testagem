package com.pet.buscaativa;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import com.pet.buscaativa.entities.GrupoTerapeutico;
import com.pet.buscaativa.entities.SessaoGrupo;
import com.pet.buscaativa.entities.UsfReferencia;
import com.pet.buscaativa.entities.Usuario;
import com.pet.buscaativa.entities.enums.UnidadeAtuacao;

/** Caracterização de proveniência nova; nenhum backfill dos grupos legados. */
class OrigemGrupoSessaoTests {
    private Usuario coordenador(Long id, UnidadeAtuacao unidade, UsfReferencia usf) {
        Usuario u = new Usuario();
        u.setId(id);
        u.setUnidadeAtuacao(unidade);
        u.setUsfAtuacao(usf);
        return u;
    }

    @Test
    void grupoESessaoNovosConservamOrigemAposTrocaDeCoordenador() {
        Usuario original = coordenador(12L, UnidadeAtuacao.CAPS_AD, null);
        Usuario novo = coordenador(15L, UnidadeAtuacao.CAPS_II, null);
        GrupoTerapeutico grupo = new GrupoTerapeutico();
        grupo.setCoordenador(original);
        grupo.registrarUnidadeDeOrigem(original);

        SessaoGrupo sessao = new SessaoGrupo();
        sessao.setGrupo(grupo);
        sessao.registrarUnidadeDeOrigemDoGrupo();

        assertEquals("CAPS_AD", grupo.getUnidadeOrigemChave());
        assertEquals("CAPS_AD", sessao.getUnidadeOrigemChave());

        grupo.setCoordenador(novo);
        assertEquals("CAPS_AD", grupo.getUnidadeOrigemChave());
        assertEquals("CAPS_AD", sessao.getUnidadeOrigemChave());
        assertThrows(IllegalStateException.class, () -> grupo.registrarUnidadeDeOrigem(novo));
        assertThrows(IllegalStateException.class, sessao::registrarUnidadeDeOrigemDoGrupo);
    }

    @Test
    void coordenadorInvalidoNaoCriaOrigem() {
        Usuario original = coordenador(12L, UnidadeAtuacao.CAPS_AD, null);
        Usuario outro = coordenador(13L, UnidadeAtuacao.CAPS_I, null);
        GrupoTerapeutico grupo = new GrupoTerapeutico();
        grupo.setCoordenador(original);
        assertThrows(IllegalArgumentException.class, () -> grupo.registrarUnidadeDeOrigem(outro));
        assertNull(grupo.getUnidadeOrigemChave());
    }

    @Test
    void origemDeSessaoDeGrupoLegadoNaoPodeSerInventada() {
        GrupoTerapeutico legado = new GrupoTerapeutico();
        legado.setCoordenador(coordenador(12L, UnidadeAtuacao.CAPS_AD, null));
        SessaoGrupo sessao = new SessaoGrupo();
        sessao.setGrupo(legado);
        assertThrows(IllegalStateException.class, sessao::registrarUnidadeDeOrigemDoGrupo);
        assertNull(sessao.getUnidadeOrigemChave());
    }

    @Test
    void usfRegistradaUsaIdDaUnidadeSemConfundirCnes() {
        UsfReferencia usf = new UsfReferencia();
        usf.setId(74L);
        usf.setCnes("0000001");
        Usuario original = coordenador(44L, UnidadeAtuacao.USF, usf);
        GrupoTerapeutico grupo = new GrupoTerapeutico();
        grupo.setCoordenador(original);
        grupo.registrarUnidadeDeOrigem(original);
        assertEquals("USF:74", grupo.getUnidadeOrigemChave());
    }
}
