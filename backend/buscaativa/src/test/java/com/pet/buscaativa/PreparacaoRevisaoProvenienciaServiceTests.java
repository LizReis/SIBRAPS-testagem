package com.pet.buscaativa;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.pet.buscaativa.entities.VinculoAssistencial;
import com.pet.buscaativa.migration.ClassificadorEvidenciaClinicaLocal.Evidencia;
import com.pet.buscaativa.migration.ClassificadorEvidenciaClinicaLocal.Fonte;
import com.pet.buscaativa.migration.ClassificadorEvidenciaClinicaLocal.Situacao;
import com.pet.buscaativa.migration.PreparacaoRevisaoProvenienciaService;
import com.pet.buscaativa.migration.PreparacaoRevisaoProvenienciaService.Estado;
import com.pet.buscaativa.services.PoliticaJustificativaCorrecao;
import com.pet.buscaativa.services.exceptions.ResourceNotFoundException;
import com.pet.buscaativa.services.impl.AutorizacaoVinculoAssistencialService;

/**
 * Caracterizacao do RASCUNHO de revisao; nao cria tabela, ticket ou permissao
 * de alterar proveniencia clinica historica. Toda evidencia e sintetica.
 */
class PreparacaoRevisaoProvenienciaServiceTests {
    private final UUID pacienteId = UUID.randomUUID();
    private AutorizacaoVinculoAssistencialService autorizacao;
    private PoliticaJustificativaCorrecao politica;
    private PreparacaoRevisaoProvenienciaService service;

    @BeforeEach
    void preparar() {
        autorizacao = mock(AutorizacaoVinculoAssistencialService.class);
        politica = mock(PoliticaJustificativaCorrecao.class);
        service = new PreparacaoRevisaoProvenienciaService(autorizacao, politica);
    }

    private void vinculo(String unidade) {
        VinculoAssistencial v = mock(VinculoAssistencial.class);
        when(v.getUnidadeChave()).thenReturn(unidade);
        when(autorizacao.exigirVinculoLocal(pacienteId)).thenReturn(v);
    }

    @Test
    void origemManualLocalCriaSomenteRascunhoComJustificativaNormalizada() {
        vinculo("CAPS_AD");
        when(politica.validar("Registro confirmado na unidade por documento assinado."))
                .thenReturn("Registro confirmado na unidade por documento assinado.");
        var resultado = service.preparar(pacienteId,
                new Evidencia(Fonte.MANUAL, "CAPS_AD", null, true, false),
                "Registro confirmado na unidade por documento assinado.");
        assertEquals(Estado.RASCUNHO_NAO_PERSISTIDO, resultado.estado());
        assertEquals(Situacao.COMPATIVEL_SOMENTE_PARA_CONFERENCIA, resultado.evidenciasParaRevisar());
        assertEquals("CAPS_AD", resultado.unidadeDaSolicitacao());
        assertEquals("Registro confirmado na unidade por documento assinado.", resultado.justificativaNormalizada());
    }

    @Test
    void origemAntigaDesconhecidaPermanecePendenteMesmoComJustificativa() {
        vinculo("USF:10");
        when(politica.validar(anyString())).thenReturn("Revisar fonte original e unidade assistencial.");
        var resultado = service.preparar(pacienteId,
                new Evidencia(Fonte.AGENDAMENTO, null, "USF:10", true, true),
                "Revisar fonte original e unidade assistencial.");
        assertEquals(Estado.RASCUNHO_NAO_PERSISTIDO, resultado.estado());
        assertEquals(Situacao.REVISAR_ORIGEM_DESCONHECIDA, resultado.evidenciasParaRevisar());
    }

    @Test
    void fatoDeOutraUnidadeOuOrigemCruzadaNaoGeraRascunhoLocal() {
        vinculo("USF:10");
        assertThrows(ResourceNotFoundException.class, () -> service.preparar(pacienteId,
                new Evidencia(Fonte.SESSAO_GRUPO, "CAPS_AD", "CAPS_AD", true, true),
                "Analisar origem externa do registro assistencial."));
        assertThrows(ResourceNotFoundException.class, () -> service.preparar(pacienteId,
                new Evidencia(Fonte.AGENDAMENTO, "USF:10", "CAPS_AD", true, true),
                "Analisar origem conflitante do registro assistencial."));
        verifyNoInteractions(politica);
    }

    @Test
    void semVinculoAutorizadoNaoClassificaNemValidaJustificativa() {
        when(autorizacao.exigirVinculoLocal(pacienteId))
                .thenThrow(new ResourceNotFoundException("Paciente não encontrado."));
        assertThrows(ResourceNotFoundException.class, () -> service.preparar(pacienteId,
                new Evidencia(Fonte.AGENDAMENTO, "USF:10", "USF:10", true, true),
                "Registro associado ao acompanhamento da unidade."));
        verifyNoInteractions(politica);
    }

    @Test
    void justificativaInvalidaImpedePreparacao() {
        vinculo("CAPS_AD");
        when(politica.validar("sem motivo"))
                .thenThrow(new com.pet.buscaativa.services.exceptions.ValidationException(
                        "Informe uma justificativa explicativa entre 10 e 2000 caracteres; textos genéricos não são aceitos."));
        assertThrows(com.pet.buscaativa.services.exceptions.ValidationException.class,
                () -> service.preparar(pacienteId,
                        new Evidencia(Fonte.MANUAL, "CAPS_AD", null, true, false), "sem motivo"));
    }
}
