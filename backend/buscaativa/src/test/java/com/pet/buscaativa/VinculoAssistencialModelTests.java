package com.pet.buscaativa;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import com.pet.buscaativa.entities.Paciente;
import com.pet.buscaativa.entities.UsfReferencia;
import com.pet.buscaativa.entities.VinculoAssistencial;
import com.pet.buscaativa.entities.enums.CapsEnum;
import com.pet.buscaativa.entities.enums.UnidadeAtuacao;
import com.pet.buscaativa.repositories.PacienteRepository;
import com.pet.buscaativa.repositories.UsfReferenciaRepository;
import com.pet.buscaativa.repositories.VinculoAssistencialRepository;
import com.pet.buscaativa.services.impl.SusApiAdapter;

/** Caracteriza somente o modelo ADITIVO; os endpoints legados ainda nao usam esta entidade. */
@SpringBootTest
@Transactional
class VinculoAssistencialModelTests extends SecurityTestSupport {

    @MockitoBean SusApiAdapter susApi;
    @Autowired PacienteRepository pacientes;
    @Autowired UsfReferenciaRepository usfs;
    @Autowired VinculoAssistencialRepository vinculos;

    private Paciente paciente(String nome) {
        Paciente p = new Paciente();
        p.setNome(nome);
        p.setCpf(UUID.randomUUID().toString());
        p.setCns(UUID.randomUUID().toString());
        return pacientes.saveAndFlush(p);
    }

    private UsfReferencia usf(String nome) {
        UsfReferencia usf = new UsfReferencia();
        usf.setNomeUsf(nome);
        usf.setCnes(UUID.randomUUID().toString());
        return usfs.saveAndFlush(usf);
    }

    @Test
    void referenciaCapsNaoCriaAutomaticamenteVinculoClinico() {
        Paciente p = paciente("Referenciado sem atendimento CAPS");
        p.setCapsReferencia(CapsEnum.CAPS_AD);
        pacientes.saveAndFlush(p);

        assertTrue(vinculos.findByPacienteId(p.getId()).isEmpty());
    }

    @Test
    void mesmaIdentidadePossuiVinculosIndependentesEmUsfECaps() {
        Paciente p = paciente("Identidade unica com dois acompanhamentos");
        UsfReferencia usf = usf("USF A");

        VinculoAssistencial localUsf = vinculos.saveAndFlush(VinculoAssistencial.paraUsf(p, usf));
        VinculoAssistencial localCaps = vinculos.saveAndFlush(VinculoAssistencial.paraCaps(p, UnidadeAtuacao.CAPS_AD));

        assertNotEquals(localUsf.getId(), localCaps.getId());
        assertNotEquals(localUsf.getIdPublico(), localCaps.getIdPublico());
        assertEquals("USF:" + usf.getId(), localUsf.getUnidadeChave());
        assertEquals("CAPS_AD", localCaps.getUnidadeChave());
        assertEquals(p.getId(), localUsf.getPaciente().getId());
        assertEquals(p.getId(), localCaps.getPaciente().getId());
        assertEquals(2, vinculos.findByPacienteId(p.getId()).size());
        assertEquals(localUsf.getId(), vinculos
                .findByPacienteIdAndUnidadeChave(p.getId(), "USF:" + usf.getId()).orElseThrow().getId());
        assertEquals(localCaps.getId(), vinculos
                .findByPacienteIdAndUnidadeChave(p.getId(), "CAPS_AD").orElseThrow().getId());
    }

    @Test
    void duasUsfsPossuemChavesDistintasParaMesmaIdentidade() {
        Paciente p = paciente("Transferencia sem mesclar acompanhamentos");
        UsfReferencia origem = usf("USF origem");
        UsfReferencia destino = usf("USF destino");

        VinculoAssistencial a = vinculos.saveAndFlush(VinculoAssistencial.paraUsf(p, origem));
        VinculoAssistencial b = vinculos.saveAndFlush(VinculoAssistencial.paraUsf(p, destino));

        assertNotEquals(a.getUnidadeChave(), b.getUnidadeChave());
        assertEquals(2, vinculos.findByPacienteId(p.getId()).size());
    }

    @Test
    void naoPermiteCapsComUsfNemVinculoUsfSemUnidadePersistida() {
        Paciente p = paciente("Validacao de vinculo");
        assertThrows(IllegalArgumentException.class,
                () -> VinculoAssistencial.paraCaps(p, UnidadeAtuacao.USF));
        assertThrows(IllegalArgumentException.class,
                () -> VinculoAssistencial.paraUsf(p, new UsfReferencia()));
        assertThrows(NullPointerException.class,
                () -> VinculoAssistencial.paraCaps(null, UnidadeAtuacao.CAPS_I));
    }

    @Test
    void constraintUnicaImpedeDoisVinculosParaMesmaUnidadeEPaciente() {
        Paciente p = paciente("Vinculo duplicado");
        vinculos.saveAndFlush(VinculoAssistencial.paraCaps(p, UnidadeAtuacao.CAPS_II));

        assertThrows(DataIntegrityViolationException.class,
                () -> vinculos.saveAndFlush(VinculoAssistencial.paraCaps(p, UnidadeAtuacao.CAPS_II)));
    }
}
