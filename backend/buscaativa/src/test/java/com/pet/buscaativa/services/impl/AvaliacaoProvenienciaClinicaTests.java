package com.pet.buscaativa.services.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.LocalDate;
import java.time.LocalTime;

import org.junit.jupiter.api.Test;

import com.pet.buscaativa.entities.Agendamento;
import com.pet.buscaativa.entities.Paciente;
import com.pet.buscaativa.entities.Usuario;
import com.pet.buscaativa.entities.enums.SituacaoAtendimento;
import com.pet.buscaativa.entities.enums.UnidadeAtuacao;

/**
 * Diagnostico puro da cadeia de remarcacoes: nenhuma escrita ou politica nova.
 * A integridade de sucessores paralelos vem de consulta somente leitura.
 */
class AvaliacaoProvenienciaClinicaTests {
    private final Paciente pessoa = pessoa(10L);
    private final Usuario capsAd = usuario(20L, UnidadeAtuacao.CAPS_AD);
    private final Usuario capsIi = usuario(21L, UnidadeAtuacao.CAPS_II);

    private static Paciente pessoa(long id) {
        var p = new Paciente();
        p.setId(id);
        return p;
    }

    private static Usuario usuario(long id, UnidadeAtuacao unidade) {
        var u = new Usuario();
        u.setId(id);
        u.setUnidadeAtuacao(unidade);
        return u;
    }

    private Agendamento agendamento(long id, int dia, Usuario profissional, boolean origem) {
        var a = new Agendamento();
        a.setPaciente(pessoa);
        a.setUsuario(profissional);
        if (origem) a.registrarUnidadeDeOrigem(profissional);
        a.setId(id);
        a.setDataAgendamento(LocalDate.of(2026, 11, dia));
        a.setHoraAtendimento(LocalTime.of(9, 0));
        a.setSituacaoAtendimento(SituacaoAtendimento.FALTOU);
        return a;
    }

    private Agendamento sucessor(long id, int dia, Agendamento original) {
        var a = agendamento(id, dia, capsAd, true);
        a.setAgendamentoOriginal(original);
        a.setRemarcacaoAposFalta(true);
        return a;
    }

    private AvaliacaoProvenienciaClinica.Situacao verificar(Agendamento a) {
        return AvaliacaoProvenienciaClinica.cadeiaAgendamento(
                a, pessoa.getId(), "CAPS_AD", (original, sucessor) -> false);
    }

    @Test
    void cadeiaLegitimaComDoisSucessoresEmOrdemMantemProvenienciaComprovada() {
        var raiz = agendamento(1, 10, capsAd, true);
        var primeiro = sucessor(2, 11, raiz);
        raiz.setSituacaoAtendimento(SituacaoAtendimento.REMARCADO_ORIGEM);
        var segundo = sucessor(3, 12, primeiro);
        primeiro.setSituacaoAtendimento(SituacaoAtendimento.REMARCADO_ORIGEM);
        assertEquals(AvaliacaoProvenienciaClinica.Situacao.COMPROVADA, verificar(segundo));
    }

    @Test
    void sucessorAnteriorOuNoMesmoInstanteDaOrigemNaoComprovaCadeia() {
        for (int dia : new int[]{9, 10}) {
            var raiz = agendamento(1, 10, capsAd, true);
            var filho = sucessor(2, dia, raiz);
            raiz.setSituacaoAtendimento(SituacaoAtendimento.REMARCADO_ORIGEM);
            assertEquals(AvaliacaoProvenienciaClinica.Situacao.INCONSISTENTE, verificar(filho));
        }
    }

    @Test
    void origemNaoSubstituidaOuSemDataNaoComprovaRemarcacao() {
        var raiz = agendamento(1, 10, capsAd, true);
        var filho = sucessor(2, 11, raiz);
        assertEquals(AvaliacaoProvenienciaClinica.Situacao.INCONSISTENTE, verificar(filho));
        raiz.setSituacaoAtendimento(SituacaoAtendimento.REMARCADO_ORIGEM);
        raiz.setHoraAtendimento(null);
        assertEquals(AvaliacaoProvenienciaClinica.Situacao.INCONSISTENTE, verificar(filho));
    }

    @Test
    void sucessorSemOrigemRelacionalNaoComprovaCadeia() {
        var isolado = agendamento(2, 11, capsAd, true);
        isolado.setRemarcacaoAposFalta(true);
        assertEquals(AvaliacaoProvenienciaClinica.Situacao.INCONSISTENTE, verificar(isolado));
        isolado.setRemarcacaoAposFalta(false);
        isolado.setSituacaoAtendimento(SituacaoAtendimento.REMARCADO);
        assertEquals(AvaliacaoProvenienciaClinica.Situacao.INCONSISTENTE, verificar(isolado));
    }

    @Test
    void consultaDeSucessorParaleloReprovaCadeiaSemAlterarFatos() {
        var raiz = agendamento(1, 10, capsAd, true);
        var filho = sucessor(2, 11, raiz);
        raiz.setSituacaoAtendimento(SituacaoAtendimento.REMARCADO_ORIGEM);
        assertEquals(AvaliacaoProvenienciaClinica.Situacao.COMPROVADA, verificar(filho));
        assertEquals(AvaliacaoProvenienciaClinica.Situacao.INCONSISTENTE,
                AvaliacaoProvenienciaClinica.cadeiaAgendamento(
                        filho, pessoa.getId(), "CAPS_AD",
                        (original, sucessor) -> original == raiz && sucessor == filho));
        assertEquals(SituacaoAtendimento.REMARCADO_ORIGEM, raiz.getSituacaoAtendimento());
        assertEquals(raiz, filho.getAgendamentoOriginal());
    }

    @Test
    void ancestralDeOutroCapsOuPacienteDiferenteNuncaViraFatoLocal() {
        var raizExterna = agendamento(1, 10, capsIi, true);
        var filho = sucessor(2, 11, raizExterna);
        raizExterna.setSituacaoAtendimento(SituacaoAtendimento.REMARCADO_ORIGEM);
        assertEquals(AvaliacaoProvenienciaClinica.Situacao.INCONSISTENTE, verificar(filho));
        var raizOutroPaciente = agendamento(3, 10, capsAd, true);
        raizOutroPaciente.setPaciente(pessoa(99L));
        var filhoCruzado = sucessor(4, 11, raizOutroPaciente);
        raizOutroPaciente.setSituacaoAtendimento(SituacaoAtendimento.REMARCADO_ORIGEM);
        assertEquals(AvaliacaoProvenienciaClinica.Situacao.INCONSISTENTE, verificar(filhoCruzado));
    }

    @Test
    void cicloEntreElosDaMesmaUnidadeInterrompeAvaliacao() {
        var primeiro = agendamento(1, 10, capsAd, true);
        var segundo = sucessor(2, 11, primeiro);
        primeiro.setAgendamentoOriginal(segundo);
        primeiro.setSituacaoAtendimento(SituacaoAtendimento.REMARCADO_ORIGEM);
        segundo.setSituacaoAtendimento(SituacaoAtendimento.REMARCADO_ORIGEM);

        assertEquals(AvaliacaoProvenienciaClinica.Situacao.INCONSISTENTE, verificar(segundo));
        assertEquals(primeiro, segundo.getAgendamentoOriginal());
        assertEquals(segundo, primeiro.getAgendamentoOriginal());
    }

    @Test
    void origemAntigaAusentePermaneceSemComprovacaoMesmoComCadeiaIrregular() {
        var raiz = agendamento(1, 10, capsAd, false);
        var filho = sucessor(2, 11, raiz);
        assertEquals(AvaliacaoProvenienciaClinica.Situacao.SEM_ORIGEM_COMPROVADA, verificar(filho));
        assertEquals(null, raiz.getUnidadeOrigemChave());
    }
}
