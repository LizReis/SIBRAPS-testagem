package com.pet.buscaativa;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

import java.time.LocalDate;
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
 * Testes HTTP com filtro JWT real e H2 sintetico: nenhum documento na URL,
 * somente identidade minima no JSON e nenhuma escrita ou acesso clinico.
 */
@SpringBootTest
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@Transactional
class ConsultaCadastroUnicoHttpTests extends SecurityTestSupport {
    private static final String ROTA = "/api/pacientes/cadastro-unico/conferencia";

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
        p.setDataNascimento(LocalDate.of(1995, 2, 3));
        p.setCpf(cpf);
        p.setCns(cns);
        p.setCapsReferencia(CapsEnum.CAPS_II);
        p.setCountFaltas(8); // dado clinico de outra unidade: PROIBIDO na resposta
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

    private org.springframework.test.web.servlet.ResultActions enviar(
            String corpo, Usuario ator) throws Exception {
        var req = post(ROTA).contentType(MediaType.APPLICATION_JSON).content(corpo);
        if (ator != null) req.header("Authorization", "Bearer " + tokens.gerarToken(ator));
        return mvc.perform(req);
    }

    @Test
    void profissionalConfereSemReceberHistoricoOuIndicadores() throws Exception {
        Paciente p = pessoa("12312312312", "100000000000123");
        Usuario profissional = ator(TipoUsuario.PROFISSIONAL, UnidadeAtuacao.CAPS_AD);
        var resposta = enviar(json.writeValueAsString(
                new java.util.HashMap<>(java.util.Map.of("cpf", "123.123.123-12", "cns", "100000000000123"))),
                profissional).andReturn().getResponse();

        assertEquals(200, resposta.getStatus());
        assertTrue(resposta.getHeader("Cache-Control").contains("no-store"));
        assertEquals("no-cache", resposta.getHeader("Pragma"));
        var body = json.readTree(resposta.getContentAsString());
        assertEquals(3, body.size());
        assertEquals(p.getNome(), body.get("nome").asText());
        assertEquals(p.getNomeMae(), body.get("nomeMae").asText());
        assertEquals("1995-02-03", body.get("dataNascimento").asText());
        assertFalse(body.has("idPublico"));
        assertFalse(body.has("countFaltas"));
        assertFalse(body.has("capsReferencia"));
        assertFalse(body.has("vinculos"));
        assertEquals(1, pacientes.count());
        assertTrue(vinculos.findByPacienteId(p.getId()).isEmpty());
    }

    @Test
    void semAutenticacaoNaoLocalizaPessoa() throws Exception {
        pessoa("23423423423", "100000000000234");
        var response = enviar("{\"cpf\":\"23423423423\"}", null)
                .andReturn().getResponse();
        assertEquals(401, response.getStatus());
    }

    @Test
    void unidadeNaoConfiguradaBloqueiaConsultaMesmoAutenticada() throws Exception {
        pessoa("34534534534", "100000000000345");
        Usuario usuario = ator(TipoUsuario.PROFISSIONAL, UnidadeAtuacao.USF);
        var response = enviar("{\"cpf\":\"34534534534\"}", usuario)
                .andReturn().getResponse();
        assertEquals(422, response.getStatus());
    }

    @Test
    void documentosDePessoasDiferentesNaoRetornamDados() throws Exception {
        Paciente a = pessoa("45645645645", "100000000000456");
        Paciente b = pessoa("56756756756", "100000000000567");
        Usuario usuario = ator(TipoUsuario.ADMINISTRADOR, UnidadeAtuacao.CAPS_AD);
        var response = enviar(json.writeValueAsString(
                java.util.Map.of("cpf", a.getCpf(), "cns", b.getCns())), usuario)
                .andReturn().getResponse();
        assertEquals(422, response.getStatus());
        assertEquals(2, pacientes.count());
        assertTrue(vinculos.findByPacienteId(a.getId()).isEmpty());
        assertTrue(vinculos.findByPacienteId(b.getId()).isEmpty());
    }

    @Test
    void recepcaoPodeConsultarPorCnsMasNaoCriarVinculo() throws Exception {
        Paciente p = pessoa("67867867867", "100000000000678");
        Usuario recepcao = ator(TipoUsuario.RECEPCAO, UnidadeAtuacao.CAPS_AD);
        var response = enviar("{\"cns\":\"100000000000678\"}", recepcao)
                .andReturn().getResponse();
        assertEquals(200, response.getStatus());
        assertEquals(p.getNome(), json.readTree(response.getContentAsString())
                .get("nome").asText());
        assertTrue(vinculos.findByPacienteId(p.getId()).isEmpty());
    }
}
