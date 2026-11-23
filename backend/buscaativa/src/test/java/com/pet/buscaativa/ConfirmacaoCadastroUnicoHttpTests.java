package com.pet.buscaativa;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import com.pet.buscaativa.entities.Paciente;
import com.pet.buscaativa.entities.Usuario;
import com.pet.buscaativa.entities.enums.CapsEnum;
import com.pet.buscaativa.entities.enums.TipoUsuario;
import com.pet.buscaativa.entities.enums.UnidadeAtuacao;
import com.pet.buscaativa.repositories.PacienteRepository;
import com.pet.buscaativa.repositories.UsuarioRepository;
import com.pet.buscaativa.repositories.VinculoAssistencialRepository;
import com.pet.buscaativa.services.TokenService;
import com.pet.buscaativa.services.impl.SusApiAdapter;

import tools.jackson.databind.ObjectMapper;

/**
 * Confirma vinculo EXCLUSIVAMENTE na unidade do JWT.
 * Usa filtro JWT real, @PreAuthorize, servicos de validacao e banco H2 reais.
 * O formulario Angular e os endpoints clinicos legados nao sao modificados.
 */
@SpringBootTest
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@Transactional
class ConfirmacaoCadastroUnicoHttpTests extends SecurityTestSupport {
    private static final String ROTA = "/api/pacientes/cadastro-unico/confirmar-vinculo";
    private static final LocalDate NASCIMENTO = LocalDate.of(1995, 2, 3);

    @MockitoBean SusApiAdapter susApi;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired TokenService tokens;
    @Autowired UsuarioRepository usuarios;
    @Autowired PacienteRepository pacientes;
    @Autowired VinculoAssistencialRepository vinculos;

    private Paciente pessoa(String cpf, String cns) {
        Paciente p = new Paciente();
        p.setNome("Pessoa sintetica de conferencia");
        p.setNomeMae("Responsavel sintetica");
        p.setDataNascimento(NASCIMENTO);
        p.setCpf(cpf);
        p.setCns(cns);
        p.setCapsReferencia(CapsEnum.CAPS_II);
        p.setCountFaltas(8); // Clinica legada: nunca alterada por vinculacao.
        return pacientes.saveAndFlush(p);
    }

    private Usuario ator(TipoUsuario tipo, UnidadeAtuacao unidade) {
        Usuario u = new Usuario();
        u.setNome("Operador sintetico de cadastro");
        u.setEmail(UUID.randomUUID() + "@test.local");
        u.setSenha("somente-teste");
        u.setTipoUsuario(tipo);
        u.setUnidadeAtuacao(unidade);
        return usuarios.saveAndFlush(u);
    }

    private String corpo(String cpf, String cns, String nome, String mae,
            String nascimento) throws Exception {
        var payload = new java.util.LinkedHashMap<String, Object>();
        if (cpf != null) payload.put("cpf", cpf);
        if (cns != null) payload.put("cns", cns);
        if (nome != null) payload.put("nome", nome);
        if (mae != null) payload.put("nomeMae", mae);
        if (nascimento != null) payload.put("dataNascimento", nascimento);
        return json.writeValueAsString(payload);
    }

    private org.springframework.mock.web.MockHttpServletResponse enviar(
            String body, Usuario usuario) throws Exception {
        var req = post(ROTA).contentType(MediaType.APPLICATION_JSON).content(body);
        if (usuario != null) req.header("Authorization", "Bearer " + tokens.gerarToken(usuario));
        return mvc.perform(req).andReturn().getResponse();
    }

    @Test
    void profissionalConfirmaSemReceberIdGlobalNemModificarDadosClinicos() throws Exception {
        Paciente p = pessoa("12312312312", "100000000000123");
        Usuario profissional = ator(TipoUsuario.PROFISSIONAL, UnidadeAtuacao.CAPS_AD);
        var body = corpo(p.getCpf(), p.getCns(), p.getNome(), p.getNomeMae(),
                NASCIMENTO.toString());
        var resposta = enviar(body, profissional);
        assertEquals(204, resposta.getStatus());
        assertTrue(resposta.getHeader("Cache-Control").contains("no-store"));
        assertEquals("no-cache", resposta.getHeader("Pragma"));
        assertTrue(resposta.getContentAsString().isBlank());
        assertEquals(1, pacientes.count());
        assertEquals(1, vinculos.findByPacienteId(p.getId()).size());
        assertTrue(vinculos.findByPacienteIdAndUnidadeChave(
                p.getId(), "CAPS_AD").isPresent());
        assertTrue(vinculos.findByPacienteIdAndUnidadeChave(
                p.getId(), "CAPS_II").isEmpty());
        assertEquals(CapsEnum.CAPS_II, pacientes.findById(p.getId())
                .orElseThrow().getCapsReferencia());
        assertEquals(8, pacientes.findById(p.getId()).orElseThrow().getCountFaltas());

        // Reenvio por engano nao cria segundo vinculo.
        assertEquals(204, enviar(body, profissional).getStatus());
        assertEquals(1, vinculos.findByPacienteId(p.getId()).size());
    }

    @Test
    void mesmaIdentidadePodeConfirmarUnidadesSeparadasSemCompartilharVinculos()
            throws Exception {
        Paciente p = pessoa("23423423423", "100000000000234");
        Usuario ad = ator(TipoUsuario.PROFISSIONAL, UnidadeAtuacao.CAPS_AD);
        Usuario ii = ator(TipoUsuario.ADMINISTRADOR, UnidadeAtuacao.CAPS_II);
        var body = corpo(p.getCpf(), null, p.getNome(), p.getNomeMae(),
                NASCIMENTO.toString());
        assertEquals(204, enviar(body, ad).getStatus());
        assertEquals(204, enviar(body, ii).getStatus());
        assertEquals(1, pacientes.count());
        assertEquals(2, vinculos.findByPacienteId(p.getId()).size());
        assertTrue(vinculos.findByPacienteIdAndUnidadeChave(
                p.getId(), "CAPS_AD").isPresent());
        assertTrue(vinculos.findByPacienteIdAndUnidadeChave(
                p.getId(), "CAPS_II").isPresent());
    }

    @Test
    void semJwtNaoConfirmaOuVinculaIdentidade() throws Exception {
        Paciente p = pessoa("34534534534", "100000000000345");
        var resposta = enviar(corpo(p.getCpf(), null, p.getNome(), p.getNomeMae(),
                NASCIMENTO.toString()), null);
        assertEquals(401, resposta.getStatus());
        assertTrue(vinculos.findByPacienteId(p.getId()).isEmpty());
    }

    @Test
    void usfSemUnidadeEspecificaNaoVincula() throws Exception {
        Paciente p = pessoa("45645645645", "100000000000456");
        Usuario usuario = ator(TipoUsuario.PROFISSIONAL, UnidadeAtuacao.USF);
        var resposta = enviar(corpo(p.getCpf(), null, p.getNome(), p.getNomeMae(),
                NASCIMENTO.toString()), usuario);
        assertEquals(422, resposta.getStatus());
        assertTrue(vinculos.findByPacienteId(p.getId()).isEmpty());
    }

    @Test
    void divergenciasDeIdentidadeOuDocumentoNaoCriamVinculo() throws Exception {
        Paciente primeira = pessoa("56756756756", "100000000000567");
        Paciente outra = pessoa("67867867867", "100000000000678");
        Usuario admin = ator(TipoUsuario.ADMINISTRADOR, UnidadeAtuacao.CAPS_AD);
        var diferente = enviar(corpo(primeira.getCpf(), null, "Outra Pessoa",
                primeira.getNomeMae(), NASCIMENTO.toString()), admin);
        assertEquals(422, diferente.getStatus());
        var conflitante = enviar(corpo(primeira.getCpf(), outra.getCns(),
                primeira.getNome(), primeira.getNomeMae(), NASCIMENTO.toString()), admin);
        assertEquals(422, conflitante.getStatus());
        assertEquals(2, pacientes.count());
        assertTrue(vinculos.findByPacienteId(primeira.getId()).isEmpty());
        assertTrue(vinculos.findByPacienteId(outra.getId()).isEmpty());
    }

    @Test
    void ausenciaDeConfirmacaoCompletaNaoCriaVinculo() throws Exception {
        Paciente p = pessoa("78978978978", "100000000000789");
        Usuario admin = ator(TipoUsuario.ADMINISTRADOR, UnidadeAtuacao.CAPS_AD);
        var resposta = enviar(corpo(p.getCpf(), null, p.getNome(), null,
                NASCIMENTO.toString()), admin);
        assertEquals(422, resposta.getStatus());
        assertTrue(vinculos.findByPacienteId(p.getId()).isEmpty());
    }
}
