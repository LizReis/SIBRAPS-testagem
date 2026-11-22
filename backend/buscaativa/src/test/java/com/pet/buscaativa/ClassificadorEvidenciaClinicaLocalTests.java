package com.pet.buscaativa;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import com.pet.buscaativa.migration.ClassificadorEvidenciaClinicaLocal;
import com.pet.buscaativa.migration.ClassificadorEvidenciaClinicaLocal.Evidencia;
import com.pet.buscaativa.migration.ClassificadorEvidenciaClinicaLocal.Fonte;
import com.pet.buscaativa.migration.ClassificadorEvidenciaClinicaLocal.Situacao;

/** Triagem de proveniencia, sem leitura do banco nem permissao de migrar. */
class ClassificadorEvidenciaClinicaLocalTests {
    private static final String USF = "USF:10", CAPS = "CAPS_AD";

    @Test
    void eventoLocalComFonteCoerenteExigeConferenciaMesmoAssim() {
        assertEquals(Situacao.COMPATIVEL_SOMENTE_PARA_CONFERENCIA,
                ClassificadorEvidenciaClinicaLocal.classificar(USF,
                        new Evidencia(Fonte.AGENDAMENTO, USF, USF, true, true)));
        assertEquals(Situacao.COMPATIVEL_SOMENTE_PARA_CONFERENCIA,
                ClassificadorEvidenciaClinicaLocal.classificar(CAPS,
                        new Evidencia(Fonte.SESSAO_GRUPO, CAPS, CAPS, true, true)));
    }

    @Test
    void referenciaOuOrigemAtualNaoPreenchemOrigemAntigaDesconhecida() {
        assertEquals(Situacao.REVISAR_ORIGEM_DESCONHECIDA,
                ClassificadorEvidenciaClinicaLocal.classificar(USF,
                        new Evidencia(Fonte.AGENDAMENTO, null, USF, true, true)));
    }

    @Test
    void fatoDeOutroCapsNaoEConsideradoLocal() {
        assertEquals(Situacao.FORA_DA_UNIDADE,
                ClassificadorEvidenciaClinicaLocal.classificar(USF,
                        new Evidencia(Fonte.SESSAO_GRUPO, CAPS, CAPS, true, true)));
    }

    @Test
    void origemFonteDesconhecidaNaoPermiteAtribuicao() {
        assertEquals(Situacao.REVISAR_FONTE_SEM_ORIGEM,
                ClassificadorEvidenciaClinicaLocal.classificar(USF,
                        new Evidencia(Fonte.AGENDAMENTO, USF, null, true, true)));
    }

    @Test
    void fonteLigadaAOutraUnidadePermanecePendente() {
        assertEquals(Situacao.REVISAR_ORIGENS_CONFLITANTES,
                ClassificadorEvidenciaClinicaLocal.classificar(USF,
                        new Evidencia(Fonte.AGENDAMENTO, USF, CAPS, true, true)));
    }

    @Test
    void pacienteDiferenteOuSemParticipacaoNaoComprovaGrupo() {
        assertEquals(Situacao.REVISAR_INTEGRIDADE_DA_FONTE,
                ClassificadorEvidenciaClinicaLocal.classificar(CAPS,
                        new Evidencia(Fonte.SESSAO_GRUPO, CAPS, CAPS, false, true)));
        assertEquals(Situacao.REVISAR_INTEGRIDADE_DA_FONTE,
                ClassificadorEvidenciaClinicaLocal.classificar(CAPS,
                        new Evidencia(Fonte.SESSAO_GRUPO, CAPS, CAPS, true, false)));
    }

    @Test
    void historicoManualLocalSemFonteEValidoApenasParaConferencia() {
        assertEquals(Situacao.COMPATIVEL_SOMENTE_PARA_CONFERENCIA,
                ClassificadorEvidenciaClinicaLocal.classificar(USF,
                        new Evidencia(Fonte.MANUAL, USF, null, true, false)));
        assertEquals(Situacao.REVISAR_INTEGRIDADE_DA_FONTE,
                ClassificadorEvidenciaClinicaLocal.classificar(USF,
                        new Evidencia(Fonte.MANUAL, USF, USF, true, true)));
    }

    @Test
    void faltaDeUnidadeAutenticadaERejeitada() {
        assertThrows(IllegalArgumentException.class,
                () -> ClassificadorEvidenciaClinicaLocal.classificar(" ",
                        new Evidencia(Fonte.MANUAL, USF, null, true, false)));
    }
}
