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
import com.pet.buscaativa.services.exceptions.ResourceNotFoundException;
import com.pet.buscaativa.services.exceptions.ValidationException;
import com.pet.buscaativa.services.impl.SusApiAdapter;
import com.pet.buscaativa.services.impl.VinculacaoIdentidadeExistenteService;

/**
 * Integra o reuso da identidade global com a criacao real do vinculo local
 * em H2 sintetico, sem expor rota e sem alterar dados clinicos legados.
 * CPF/CNS artificiais sao usados apenas para exercitar a busca exata.
 */
@SpringBootTest
@Transactional
class VinculacaoIdentidadeExistenteServiceTests extends SecurityTestSupport {
    @MockitoBean SusApiAdapter susApi;
    @Autowired PacienteRepository pacientes;
    @Autowired UsuarioRepository usuarios;
    @Autowired UsfReferenciaRepository usfs;
    @Autowired VinculoAssistencialRepository vinculos;
    @Autowired VinculacaoIdentidadeExistenteService service;

    @AfterEach
    void limparContexto() {
        SecurityContextHolder.clearContext();
    }

    private Paciente pessoa(String cpf, String cns) {
        Paciente p = new Paciente();
        p.setNome("Identidade sintetica compartilhada");
        p.setCpf(cpf);
        p.setCns(cns);
        p.setCapsReferencia(CapsEnum.CAPS_II); // referencia NAO cria vinculo
        return pacientes.saveAndFlush(p);
    }

    private Usuario operador(UnidadeAtuacao unidade, UsfReferencia usf, TipoUsuario perfil) {
        Usuario u = new Usuario();
        u.setNome("Operador sintetico " + unidade);
        u.setEmail(UUID.randomUUID() + "@test.local");
        u.setSenha("somente-para-teste");
        u.setUnidadeAtuacao(unidade);
        u.setUsfAtuacao(usf);
        u.setTipoUsuario(perfil);
        return usuarios.saveAndFlush(u);
    }

    private UsfReferencia usf(String nome) {
        UsfReferencia u = new UsfReferencia();
        u.setCnes(UUID.randomUUID().toString());
        u.setNomeUsf(nome);
        return usfs.saveAndFlush(u);
    }

    private void autenticar(Usuario usuario) {
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(
                        usuario, null, usuario.getAuthorities()));
    }

    @Test
    void profissionalReutilizaCpfSemDuplicarPacienteEmDoisCaps() {
        Paciente p = pessoa("11122233344", "123456789012345");
        Usuario ad = operador(UnidadeAtuacao.CAPS_AD, null, TipoUsuario.PROFISSIONAL);
        Usuario ii = operador(UnidadeAtuacao.CAPS_II, null, TipoUsuario.ADMINISTRADOR);

        autenticar(ad);
        var primeiro = service.vincularPorDocumentos("111.222.333-44", "12345 67890 12345");
        assertEquals(p.getIdPublico(), primeiro.pacienteIdPublico());
        assertEquals("CAPS_AD", primeiro.unidadeChave());
        assertEquals(primeiro.vinculoLocalIdPublico(),
                service.vincularPorDocumentos("11122233344", null).vinculoLocalIdPublico());

        autenticar(ii);
        var segundo = service.vincularPorDocumentos(null, "123456789012345");
        assertEquals(p.getIdPublico(), segundo.pacienteIdPublico());
        assertEquals("CAPS_II", segundo.unidadeChave());
        assertNotEquals(primeiro.vinculoLocalIdPublico(), segundo.vinculoLocalIdPublico());
        assertEquals(1, pacientes.count());
        assertEquals(2, vinculos.findByPacienteId(p.getId()).size());
    }

    @Test
    void profissionalPodeVincularNaSuaUsfMasNaoCriaVinculoDeOutraUsf() {
        Paciente p = pessoa("44433322211", "987654321012345");
        UsfReferencia a = usf("USF Sintetica A");
        UsfReferencia b = usf("USF Sintetica B");
        Usuario adminA = operador(UnidadeAtuacao.USF, a, TipoUsuario.ADMINISTRADOR);
        Usuario profissionalB = operador(UnidadeAtuacao.USF, b, TipoUsuario.PROFISSIONAL);

        autenticar(adminA);
        var primeiro = service.vincularPorDocumentos("44433322211", null);
        assertEquals("USF:" + a.getId(), primeiro.unidadeChave());
        assertEquals(1, vinculos.findByPacienteId(p.getId()).size());

        autenticar(profissionalB);
        var segundo = service.vincularPorDocumentos(null, "987654321012345");
        assertEquals("USF:" + b.getId(), segundo.unidadeChave());
        assertNotEquals(primeiro.vinculoLocalIdPublico(), segundo.vinculoLocalIdPublico());
        assertEquals(1, pacientes.count());
        assertEquals(2, vinculos.findByPacienteId(p.getId()).size());
    }

    @Test
    void documentosDeDuasIdentidadesNaoFundemCadastrosNemCriamVinculo() {
        Paciente primeira = pessoa("11100000001", "100000000000001");
        Paciente segunda = pessoa("22200000002", "200000000000002");
        autenticar(operador(UnidadeAtuacao.CAPS_AD, null, TipoUsuario.ADMINISTRADOR));

        assertThrows(ValidationException.class, () -> service.vincularPorDocumentos(
                primeira.getCpf(), segunda.getCns()));
        assertThrows(ValidationException.class, () -> service.vincularPorDocumentos(
                primeira.getCpf(), "300000000000003"));
        assertEquals(0, vinculos.findByPacienteId(primeira.getId()).size());
        assertEquals(0, vinculos.findByPacienteId(segunda.getId()).size());
    }

    @Test
    void semDocumentoValidoOuSemCorrespondenciaNaoCriaVinculo() {
        Paciente p = pessoa("55500000005", "500000000000005");
        autenticar(operador(UnidadeAtuacao.CAPS_AD, null, TipoUsuario.RECEPCAO));

        assertThrows(ValidationException.class, () -> service.vincularPorDocumentos(null, null));
        assertThrows(ValidationException.class, () -> service.vincularPorDocumentos("123", null));
        assertThrows(ValidationException.class, () -> service.vincularPorDocumentos("CPF:55500000005", null));
        assertThrows(ResourceNotFoundException.class, () -> service.vincularPorDocumentos(
                "66600000006", null));
        assertEquals(0, vinculos.findByPacienteId(p.getId()).size());
    }

    @Test
    void usfSemIdentidadeDeUnidadeFalhaSemPesquisarOuCriarVinculo() {
        Paciente p = pessoa("77700000007", "700000000000007");
        autenticar(operador(UnidadeAtuacao.USF, null, TipoUsuario.PROFISSIONAL));

        assertThrows(ValidationException.class, () -> service.vincularPorDocumentos(
                p.getCpf(), null));
        assertTrue(vinculos.findByPacienteId(p.getId()).isEmpty());
    }
}
