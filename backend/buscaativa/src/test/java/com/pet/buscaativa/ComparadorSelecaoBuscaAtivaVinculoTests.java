package com.pet.buscaativa;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.pet.buscaativa.entities.enums.ClassificacaoRisco;
import com.pet.buscaativa.entities.enums.StatusPaciente;
import com.pet.buscaativa.services.impl.ComparadorSelecaoBuscaAtivaVinculo;
import com.pet.buscaativa.services.impl.ComparadorSelecaoBuscaAtivaVinculo.Decisao;
import com.pet.buscaativa.services.impl.ComparadorSelecaoBuscaAtivaVinculo.Motivo;

/** Caracteriza elegibilidade local sem ler ou alterar prontuario e relatorios. */
class ComparadorSelecaoBuscaAtivaVinculoTests {
    private ComparadorSelecaoBuscaAtivaVinculo.Resultado decidir(
            StatusPaciente status, ClassificacaoRisco risco,
            Boolean pendente, Boolean visita) {
        return ComparadorSelecaoBuscaAtivaVinculo.comparar(
                status, Optional.ofNullable(risco), pendente, Optional.ofNullable(visita));
    }

    @Test
    void vermelhoAtivoEntraMesmoSemVisita() {
        var r = decidir(StatusPaciente.ATIVO, ClassificacaoRisco.VERMELHO, false, false);
        assertEquals(Decisao.INCLUIR, r.decisao());
        assertEquals(Motivo.RISCO_VERMELHO, r.motivo());
    }

    @Test
    void verdeComVisitaConfirmadaEntraSemMudarRisco() {
        var r = decidir(StatusPaciente.ATIVO, ClassificacaoRisco.VERDE, false, true);
        assertEquals(Decisao.INCLUIR, r.decisao());
        assertEquals(Motivo.VISITA_CONFIRMADA, r.motivo());
    }

    @Test
    void criterioConfirmadoBastaQuandoOOutroEstaInconclusivo() {
        assertEquals(new ComparadorSelecaoBuscaAtivaVinculo.Resultado(Decisao.INCLUIR, Motivo.RISCO_VERMELHO),
                decidir(StatusPaciente.ATIVO, ClassificacaoRisco.VERMELHO, false, null));
        assertEquals(new ComparadorSelecaoBuscaAtivaVinculo.Resultado(Decisao.INCLUIR, Motivo.VISITA_CONFIRMADA),
                decidir(StatusPaciente.ATIVO, null, false, true));
    }

    @Test
    void amareloSemVisitaNaoEntra() {
        var r = decidir(StatusPaciente.ATIVO, ClassificacaoRisco.AMARELO, false, false);
        assertEquals(Decisao.NAO_INCLUIR, r.decisao());
        assertEquals(Motivo.SEM_CRITERIO, r.motivo());
    }

    @Test
    void pendenciaTemPrioridadeMesmoComRiscoOuVisitaAindaIndeterminados() {
        var r = decidir(StatusPaciente.ATIVO, null, true, null);
        assertEquals(Decisao.INCLUIR, r.decisao());
        assertEquals(Motivo.REVISAO_HISTORICA, r.motivo());

        var outro = decidir(StatusPaciente.ATIVO, ClassificacaoRisco.VERMELHO, true, true);
        assertEquals(Motivo.REVISAO_HISTORICA, outro.motivo());
    }

    @Test
    void vinculoNaoAtivoNuncaEntraMesmoVermelho() {
        var r = decidir(StatusPaciente.TRANSFERIDO, ClassificacaoRisco.VERMELHO, false, true);
        assertEquals(Decisao.NAO_INCLUIR, r.decisao());
        assertEquals(Motivo.ACOMPANHAMENTO_NAO_ATIVO, r.motivo());
        assertEquals(Decisao.NAO_INCLUIR,
                decidir(StatusPaciente.INATIVO, ClassificacaoRisco.VERMELHO, true, true).decisao());
    }

    @Test
    void dadosDesconhecidosNaoCriamFalsoAlertaNemFalsoNormal() {
        assertEquals(Decisao.INDETERMINADA, decidir(null, ClassificacaoRisco.VERMELHO, false, true).decisao());
        assertEquals(Decisao.INDETERMINADA, decidir(StatusPaciente.ATIVO, null, false, false).decisao());
        assertEquals(Decisao.INDETERMINADA, decidir(StatusPaciente.ATIVO, ClassificacaoRisco.VERDE, false, null).decisao());
        assertEquals(Decisao.INDETERMINADA, decidir(StatusPaciente.ATIVO, ClassificacaoRisco.VERDE, null, false).decisao());
        assertEquals(Decisao.INDETERMINADA, decidir(StatusPaciente.ATIVO, ClassificacaoRisco.VERMELHO, null, true).decisao());
    }
}
