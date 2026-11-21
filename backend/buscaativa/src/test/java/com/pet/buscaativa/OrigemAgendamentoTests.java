package com.pet.buscaativa;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import com.pet.buscaativa.entities.Agendamento;
import com.pet.buscaativa.entities.Usuario;
import com.pet.buscaativa.entities.UsfReferencia;
import com.pet.buscaativa.entities.enums.UnidadeAtuacao;

/** Testes puros: a origem nova e definida uma vez, nunca por referencia do paciente. */
class OrigemAgendamentoTests {
    private Usuario profissional(Long id, UnidadeAtuacao tipo, UsfReferencia usf) {
        Usuario u = new Usuario();
        u.setId(id);
        u.setUnidadeAtuacao(tipo);
        u.setUsfAtuacao(usf);
        return u;
    }

    @Test
    void novoAgendamentoGravaUnidadeDoProfissionalAutorizado() {
        Usuario caps = profissional(11L, UnidadeAtuacao.CAPS_II, null);
        Agendamento a = new Agendamento();
        a.setUsuario(caps);
        a.registrarUnidadeDeOrigem(caps);
        assertEquals("CAPS_II", a.getUnidadeOrigemChave());
        assertThrows(IllegalStateException.class, () -> a.registrarUnidadeDeOrigem(caps));
    }

    @Test
    void duasUsfsSaoDistinguidasMesmoQueTenhamMesmoCnes() {
        UsfReferencia um = new UsfReferencia();
        um.setId(1L);
        um.setCnes("1234567");
        UsfReferencia dois = new UsfReferencia();
        dois.setId(2L);
        dois.setCnes("1234567");
        Usuario a = profissional(11L, UnidadeAtuacao.USF, um);
        Usuario b = profissional(12L, UnidadeAtuacao.USF, dois);

        Agendamento primeiro = new Agendamento();
        primeiro.setUsuario(a);
        primeiro.registrarUnidadeDeOrigem(a);
        Agendamento segundo = new Agendamento();
        segundo.setUsuario(b);
        segundo.registrarUnidadeDeOrigem(b);

        assertEquals("USF:1", primeiro.getUnidadeOrigemChave());
        assertEquals("USF:2", segundo.getUnidadeOrigemChave());
    }

    @Test
    void usuarioDiferenteOuUnidadeNaoConfiguradaNaoPodeRegistrarOrigem() {
        Usuario caps = profissional(11L, UnidadeAtuacao.CAPS_AD, null);
        Usuario outro = profissional(12L, UnidadeAtuacao.CAPS_I, null);
        Agendamento a = new Agendamento();
        a.setUsuario(caps);
        assertThrows(IllegalArgumentException.class, () -> a.registrarUnidadeDeOrigem(outro));
        assertNull(a.getUnidadeOrigemChave());

        Usuario semUnidade = profissional(13L, UnidadeAtuacao.USF, null);
        Agendamento b = new Agendamento();
        b.setUsuario(semUnidade);
        assertThrows(IllegalArgumentException.class, () -> b.registrarUnidadeDeOrigem(semUnidade));
        assertNull(b.getUnidadeOrigemChave());
    }
}
