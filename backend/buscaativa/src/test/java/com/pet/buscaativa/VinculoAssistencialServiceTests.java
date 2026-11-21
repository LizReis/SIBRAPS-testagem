package com.pet.buscaativa;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import com.pet.buscaativa.entities.Paciente;
import com.pet.buscaativa.entities.UsfReferencia;
import com.pet.buscaativa.entities.Usuario;
import com.pet.buscaativa.entities.enums.CapsEnum;
import com.pet.buscaativa.entities.enums.TipoUsuario;
import com.pet.buscaativa.entities.enums.UnidadeAtuacao;
import com.pet.buscaativa.repositories.PacienteRepository;
import com.pet.buscaativa.repositories.UsfReferenciaRepository;
import com.pet.buscaativa.repositories.UsuarioRepository;
import com.pet.buscaativa.repositories.VinculoAssistencialRepository;
import com.pet.buscaativa.services.impl.SusApiAdapter;
import com.pet.buscaativa.services.impl.VinculoAssistencialService;

/**
 * Caracterizacao do servico interno; NENHUM endpoint passa a conceder
 * acesso clinico aos vinculos nesta entrega.
 */
@SpringBootTest
@Transactional
class VinculoAssistencialServiceTests extends SecurityTestSupport {
    @MockitoBean SusApiAdapter susApi;

    @Autowired PacienteRepository pacientes;
    @Autowired UsfReferenciaRepository usfs;
    @Autowired UsuarioRepository usuarios;
    @Autowired VinculoAssistencialRepository vinculos;
    @Autowired VinculoAssistencialService service;

    @AfterEach
    void limparAutenticacao() {
        SecurityContextHolder.clearContext();
    }

    private Paciente pessoa() {
        Paciente p = new Paciente();
        p.setNome("Paciente sintético para vínculo local");
        p.setCpf(UUID.randomUUID().toString());
        p.setCns(UUID.randomUUID().toString());
        p.setCapsReferencia(CapsEnum.CAPS_AD);
        return pacientes.saveAndFlush(p);
    }

    private Usuario ator(UnidadeAtuacao unidade, UsfReferencia usf, TipoUsuario role) {
        Usuario u = new Usuario();
        u.setNome("Operador sintético");
        u.setEmail(UUID.randomUUID() + "@test.local");
        u.setSenha("nao-usada");
        u.setTipoUsuario(role);
        u.setUnidadeAtuacao(unidade);
        u.setUsfAtuacao(usf);
        return usuarios.saveAndFlush(u);
    }

    private UsfReferencia usf(String nome) {
        UsfReferencia unidade = new UsfReferencia();
        unidade.setCnes(UUID.randomUUID().toString());
        unidade.setNomeUsf(nome);
        return usfs.saveAndFlush(unidade);
    }

    private void autenticar(Usuario ator) {
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(ator, null, ator.getAuthorities()));
    }

    @Test
    void referenciaCapsNaoConcedeVinculoEPrecisarSerCriadoExplicitamente() {
        Paciente p = pessoa();
        Usuario caps = ator(UnidadeAtuacao.CAPS_AD, null, TipoUsuario.PROFISSIONAL);
        autenticar(caps);

        assertTrue(service.consultarVinculoDaUnidade(p.getIdPublico()).isEmpty());

        var criado = service.vincularExplicitamente(p.getIdPublico());

        assertEquals("CAPS_AD", criado.getUnidadeChave());
        assertEquals(p.getId(), criado.getPaciente().getId());
        assertEquals(criado.getId(), service.consultarVinculoDaUnidade(p.getIdPublico()).orElseThrow().getId());
    }

    @Test
    void umaIdentidadePodeTerDoisCapsIndependentesESemDuplicarVinculoLocal() {
        Paciente p = pessoa();
        Usuario ad = ator(UnidadeAtuacao.CAPS_AD, null, TipoUsuario.PROFISSIONAL);
        Usuario ii = ator(UnidadeAtuacao.CAPS_II, null, TipoUsuario.ADMINISTRADOR);

        autenticar(ad);
        var primeiro = service.vincularExplicitamente(p.getIdPublico());
        assertEquals(primeiro.getId(), service.vincularExplicitamente(p.getIdPublico()).getId());

        autenticar(ii);
        assertTrue(service.consultarVinculoDaUnidade(p.getIdPublico()).isEmpty());
        var segundo = service.vincularExplicitamente(p.getIdPublico());
        assertNotEquals(primeiro.getId(), segundo.getId());
        assertEquals(2, vinculos.findByPacienteId(p.getId()).size());

        autenticar(ad);
        assertEquals(primeiro.getId(), service.consultarVinculoDaUnidade(p.getIdPublico()).orElseThrow().getId());
    }

    @Test
    void duasUsfsNaoConsultamVinculoUmaDaOutra() {
        Paciente p = pessoa();
        Usuario origem = ator(UnidadeAtuacao.USF, usf("USF Origem"), TipoUsuario.RECEPCAO);
        Usuario destino = ator(UnidadeAtuacao.USF, usf("USF Destino"), TipoUsuario.PROFISSIONAL);

        autenticar(origem);
        var original = service.vincularExplicitamente(p.getIdPublico());

        autenticar(destino);
        assertTrue(service.consultarVinculoDaUnidade(p.getIdPublico()).isEmpty());
        var novo = service.vincularExplicitamente(p.getIdPublico());

        assertNotEquals(original.getId(), novo.getId());
        assertNotEquals(original.getUnidadeChave(), novo.getUnidadeChave());
        assertEquals(2, vinculos.findByPacienteId(p.getId()).size());
    }

    @Test
    void usfSemUnidadeEspecificaFalhaFechadaSemCriarVinculo() {
        Paciente p = pessoa();
        Usuario invalido = ator(UnidadeAtuacao.USF, null, TipoUsuario.PROFISSIONAL);
        autenticar(invalido);

        assertThrows(com.pet.buscaativa.services.exceptions.ValidationException.class,
                () -> service.vincularExplicitamente(p.getIdPublico()));
        assertTrue(vinculos.findByPacienteId(p.getId()).isEmpty());
    }
}
