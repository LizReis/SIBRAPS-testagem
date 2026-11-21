package com.pet.buscaativa;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import com.pet.buscaativa.entities.ChaveUnidadeAssistencial;
import com.pet.buscaativa.entities.UsfReferencia;
import com.pet.buscaativa.entities.Usuario;
import com.pet.buscaativa.entities.enums.UnidadeAtuacao;

/** Testes puros: nenhuma escrita ou leitura em banco, nenhuma mudanca clinica. */
class ChaveUnidadeAssistencialTests {
    @Test
    void diferenciaUsfsPorIdPersistidoEmVezDeUsarNomeOuCnes() {
        UsfReferencia a = new UsfReferencia();
        a.setId(7L);
        a.setCnes("7777777");
        UsfReferencia b = new UsfReferencia();
        b.setId(8L);
        b.setCnes("7777777");

        assertEquals("USF:7", ChaveUnidadeAssistencial.daUsf(a));
        assertEquals("USF:8", ChaveUnidadeAssistencial.daUsf(b));
        assertNotEquals(ChaveUnidadeAssistencial.daUsf(a), ChaveUnidadeAssistencial.daUsf(b));
        assertThrows(IllegalArgumentException.class,
                () -> ChaveUnidadeAssistencial.daUsf(new UsfReferencia()));
    }

    @Test
    void diferenciaCapsEDesautorizaUsfNoConstrutorCaps() {
        assertEquals("CAPS_AD", ChaveUnidadeAssistencial.doCaps(UnidadeAtuacao.CAPS_AD));
        assertEquals("CAPS_I", ChaveUnidadeAssistencial.doCaps(UnidadeAtuacao.CAPS_I));
        assertEquals("CAPS_II", ChaveUnidadeAssistencial.doCaps(UnidadeAtuacao.CAPS_II));
        assertThrows(IllegalArgumentException.class,
                () -> ChaveUnidadeAssistencial.doCaps(UnidadeAtuacao.USF));
        assertThrows(IllegalArgumentException.class,
                () -> ChaveUnidadeAssistencial.doVinculo(UnidadeAtuacao.CAPS_AD, new UsfReferencia()));
    }

    @Test
    void unidadeNaoConfiguradaNaoGeraChave() {
        Usuario semUnidade = new Usuario();
        assertThrows(NullPointerException.class,
                () -> ChaveUnidadeAssistencial.doUsuarioAtual(semUnidade));

        Usuario usfSemReferencia = new Usuario();
        usfSemReferencia.setUnidadeAtuacao(UnidadeAtuacao.USF);
        assertThrows(IllegalArgumentException.class,
                () -> ChaveUnidadeAssistencial.doUsuarioAtual(usfSemReferencia));
    }
}
