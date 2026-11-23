package com.pet.buscaativa;

import static org.junit.jupiter.api.Assertions.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

import org.junit.jupiter.api.Test;

import com.pet.buscaativa.entities.Agendamento;
import com.pet.buscaativa.entities.Paciente;
import com.pet.buscaativa.entities.Usuario;
import com.pet.buscaativa.entities.enums.SituacaoAtendimento;
import com.pet.buscaativa.entities.enums.UnidadeAtuacao;
import com.pet.buscaativa.services.PoliticaVisitaDomiciliar;
import com.pet.buscaativa.services.impl.ComparadorPoliticaVisitaVinculo;
import com.pet.buscaativa.services.impl.DiagnosticoFaltasConsecutivasVinculoService;

/** Caracteriza a política existente sem persistir dados nem disparar alertas. */
class ComparadorPoliticaVisitaVinculoTests {
    private final PoliticaVisitaDomiciliar politica = new PoliticaVisitaDomiciliar();

    private Paciente pessoa(long id) {
        Paciente p = new Paciente();
        p.setId(id);
        return p;
    }

    private Usuario usuario(long id, UnidadeAtuacao unidade) {
        Usuario u = new Usuario();
        u.setId(id);
        u.setUnidadeAtuacao(unidade);
        return u;
    }

    private Agendamento falta(Paciente paciente, Usuario profissional, int dia, boolean comOrigem) {
        Agendamento a = new Agendamento();
        a.setPaciente(paciente);
        a.setUsuario(profissional);
        if (comOrigem) a.registrarUnidadeDeOrigem(profissional);
        a.setDataAgendamento(LocalDate.of(2026, 11, dia));
        a.setHoraAtendimento(LocalTime.of(9, 0));
        a.setSituacaoAtendimento(SituacaoAtendimento.FALTOU);
        return a;
    }

    private Agendamento remarcacao(Agendamento original, Paciente pessoa, Usuario profissional,
                                  int dia, boolean comOrigem) {
        Agendamento a = falta(pessoa, profissional, dia, comOrigem);
        a.setAgendamentoOriginal(original);
        a.setRemarcacaoAposFalta(true);
        return a;
    }

    private DiagnosticoFaltasConsecutivasVinculoService.Resultado diagnostico(
            LocalDateTime ultimaPresenca, boolean incerto) {
        return new DiagnosticoFaltasConsecutivasVinculoService.Resultado(
                incerto ? OptionalInt.empty() : OptionalInt.of(2),
                Optional.ofNullable(ultimaPresenca), 2, incerto, incerto ? 1 : 0, 0, 0, false);
    }

    private Optional<Boolean> avaliar(List<Agendamento> agenda,
                                      DiagnosticoFaltasConsecutivasVinculoService.Resultado d) {
        return ComparadorPoliticaVisitaVinculo.comparar(agenda, "CAPS_AD", d, politica);
    }

    @Test
    void duasFaltasNoMesmoCapsOriginalERemarcacaoConfirmamGatilho() {
        Paciente pessoa = pessoa(7);
        Usuario ad = usuario(2, UnidadeAtuacao.CAPS_AD);
        Agendamento original = falta(pessoa, ad, 10, true);
        Agendamento remarcacao = remarcacao(original, pessoa, ad, 12, true);

        assertEquals(Optional.of(true), avaliar(List.of(original, remarcacao), diagnostico(null, false)));
    }

    @Test
    void presencaPosteriorDescaracterizaGatilhoConformePoliticaLegada() {
        Paciente pessoa = pessoa(7);
        Usuario ad = usuario(2, UnidadeAtuacao.CAPS_AD);
        Agendamento original = falta(pessoa, ad, 10, true);
        Agendamento remarcacao = remarcacao(original, pessoa, ad, 12, true);

        assertEquals(Optional.of(false), avaliar(List.of(original, remarcacao),
                diagnostico(LocalDateTime.of(2026, 11, 13, 9, 0), false)));
    }

    @Test
    void remarcacaoDeOutraUnidadeNaoPodeSerContadaNemComoGatilhoLocal() {
        Paciente pessoa = pessoa(7);
        Usuario ad = usuario(2, UnidadeAtuacao.CAPS_AD);
        Usuario ii = usuario(3, UnidadeAtuacao.CAPS_II);
        Agendamento original = falta(pessoa, ad, 10, true);
        Agendamento remarcacao = remarcacao(original, pessoa, ii, 12, true);

        // CAPS AD nao possui sequencia de remarcacao local; CAPS II
        // tampouco pode herdar a falta da unidade original.
        assertEquals(Optional.of(false), avaliar(List.of(original, remarcacao), diagnostico(null, false)));
        assertEquals(Optional.empty(), ComparadorPoliticaVisitaVinculo.comparar(
                List.of(original, remarcacao), "CAPS_II", diagnostico(null, false), politica));
    }

    @Test
    void fatoAntigoSemOrigemRequerRevisaoSemInventarUnidade() {
        Paciente pessoa = pessoa(7);
        Usuario ad = usuario(2, UnidadeAtuacao.CAPS_AD);
        Agendamento original = falta(pessoa, ad, 10, false);
        Agendamento remarcacao = remarcacao(original, pessoa, ad, 12, true);

        assertEquals(Optional.empty(), avaliar(List.of(original, remarcacao), diagnostico(null, false)));
        assertEquals(Optional.empty(), avaliar(List.of(original, remarcacao), diagnostico(null, true)));
    }

    @Test
    void origemRemarcadaSemOriginalOuPacienteDiferenteNaoAutorizaConclusao() {
        Paciente pessoa = pessoa(7);
        Usuario ad = usuario(2, UnidadeAtuacao.CAPS_AD);
        Agendamento faltaSemOriginal = falta(pessoa, ad, 12, true);
        faltaSemOriginal.setRemarcacaoAposFalta(true);
        assertEquals(Optional.empty(), avaliar(List.of(faltaSemOriginal), diagnostico(null, false)));

        Agendamento originalOutraPessoa = falta(pessoa(8), ad, 10, true);
        Agendamento remarcacao = remarcacao(originalOutraPessoa, pessoa, ad, 12, true);
        assertEquals(Optional.empty(), avaliar(List.of(originalOutraPessoa, remarcacao),
                diagnostico(null, false)));
    }

    @Test
    void eloMaisAntigoDeOutroCapsImpedeGatilhoMesmoComDoisElosLocais() {
        Paciente pessoa = pessoa(7);
        Usuario ad = usuario(2, UnidadeAtuacao.CAPS_AD);
        Usuario ii = usuario(3, UnidadeAtuacao.CAPS_II);
        Agendamento origem = falta(pessoa, ii, 10, true);
        Agendamento intermediario = remarcacao(origem, pessoa, ad, 11, true);
        Agendamento ultimo = remarcacao(intermediario, pessoa, ad, 12, true);

        assertEquals(Optional.empty(), avaliar(List.of(origem, intermediario, ultimo), diagnostico(null, false)));
    }

    @Test
    void diagnosticoInconclusivoNaoAtivaOuDesativaGatilho() {
        Paciente pessoa = pessoa(7);
        Usuario ad = usuario(2, UnidadeAtuacao.CAPS_AD);
        Agendamento original = falta(pessoa, ad, 10, true);
        Agendamento remarcacao = remarcacao(original, pessoa, ad, 12, true);

        assertEquals(Optional.empty(), avaliar(List.of(original, remarcacao), diagnostico(null, true)));
    }
}
