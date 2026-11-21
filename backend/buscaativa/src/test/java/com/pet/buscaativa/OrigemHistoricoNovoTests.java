package com.pet.buscaativa;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import com.pet.buscaativa.entities.Agendamento;
import com.pet.buscaativa.entities.GrupoTerapeutico;
import com.pet.buscaativa.entities.HistoricoPaciente;
import com.pet.buscaativa.entities.Paciente;
import com.pet.buscaativa.entities.SessaoGrupo;
import com.pet.buscaativa.entities.Usuario;
import com.pet.buscaativa.entities.enums.UnidadeAtuacao;

/** Prova apenas proveniência do evento: ainda não habilita leitura clínica por vínculo. */
class OrigemHistoricoNovoTests {

    private Usuario usuario(long id, UnidadeAtuacao unidade) {
        Usuario u = new Usuario();
        u.setId(id);
        u.setUnidadeAtuacao(unidade);
        return u;
    }

    private Paciente paciente(long id) {
        Paciente p = new Paciente();
        p.setId(id);
        return p;
    }

    @Test
    void historicoDeAgendamentoNovoMantemOrigemDepoisDeTrocaDeLotacao() {
        Usuario original = usuario(11L, UnidadeAtuacao.CAPS_AD);
        Paciente pessoa = paciente(7L);
        Agendamento a = new Agendamento();
        a.setPaciente(pessoa);
        a.setUsuario(original);
        a.registrarUnidadeDeOrigem(original);

        HistoricoPaciente h = new HistoricoPaciente();
        h.setPaciente(pessoa);
        h.setAgendamento(a);
        h.registrarOrigemDoAgendamento(a);
        assertEquals("CAPS_AD", h.getUnidadeOrigemChave());

        original.setUnidadeAtuacao(UnidadeAtuacao.CAPS_II);
        assertEquals("CAPS_AD", a.getUnidadeOrigemChave());
        assertEquals("CAPS_AD", h.getUnidadeOrigemChave());
        assertThrows(IllegalStateException.class, () -> h.registrarOrigemDoAgendamento(a));
    }

    @Test
    void fonteLegadaSemOrigemNaoGeraFalsaProveniencia() {
        Usuario profissional = usuario(11L, UnidadeAtuacao.CAPS_AD);
        Paciente pessoa = paciente(7L);
        Agendamento antigo = new Agendamento();
        antigo.setPaciente(pessoa);
        antigo.setUsuario(profissional);

        HistoricoPaciente h = new HistoricoPaciente();
        h.setPaciente(pessoa);
        h.setAgendamento(antigo);
        h.registrarOrigemDoAgendamento(antigo);
        assertNull(h.getUnidadeOrigemChave());

        HistoricoPaciente outro = new HistoricoPaciente();
        outro.setPaciente(pessoa);
        assertThrows(IllegalArgumentException.class, () -> outro.registrarOrigemDoAgendamento(antigo));
        assertNull(outro.getUnidadeOrigemChave());
    }

    @Test
    void eventoDeGrupoNovoHerdaOrigemDaSessaoNaoDoProfissionalAtual() {
        Usuario responsavel = usuario(11L, UnidadeAtuacao.CAPS_I);
        GrupoTerapeutico grupo = new GrupoTerapeutico();
        grupo.setCoordenador(responsavel);
        grupo.registrarUnidadeDeOrigem(responsavel);
        SessaoGrupo sessao = new SessaoGrupo();
        sessao.setGrupo(grupo);
        sessao.registrarUnidadeDeOrigemDoGrupo();

        HistoricoPaciente h = new HistoricoPaciente();
        h.setPaciente(paciente(7L));
        h.setSessaoGrupo(sessao);
        h.registrarOrigemDaSessao(sessao);
        assertEquals("CAPS_I", h.getUnidadeOrigemChave());
        assertThrows(IllegalArgumentException.class, () -> h.registrarOrigemDeAcaoLocal(responsavel));
    }

    @Test
    void eventoManualNovoUsaUnidadeAutenticadaESemUnidadeFalhaFechada() {
        HistoricoPaciente h = new HistoricoPaciente();
        h.setPaciente(paciente(7L));
        Usuario ator = usuario(11L, UnidadeAtuacao.CAPS_II);
        h.registrarOrigemDeAcaoLocal(ator);
        assertEquals("CAPS_II", h.getUnidadeOrigemChave());

        HistoricoPaciente invalido = new HistoricoPaciente();
        invalido.setPaciente(paciente(8L));
        assertThrows(NullPointerException.class,
                () -> invalido.registrarOrigemDeAcaoLocal(usuario(12L, null)));
        assertNull(invalido.getUnidadeOrigemChave());
    }
}
