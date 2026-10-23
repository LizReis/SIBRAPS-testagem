package com.pet.buscaativa;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.pet.buscaativa.entities.Usuario;
import com.pet.buscaativa.entities.UsfReferencia;
import com.pet.buscaativa.entities.enums.TipoUsuario;
import com.pet.buscaativa.entities.enums.UnidadeAtuacao;
import com.pet.buscaativa.repositories.UsuarioRepository;
import com.pet.buscaativa.repositories.UsfReferenciaRepository;
import com.pet.buscaativa.services.TokenService;
import com.pet.buscaativa.services.impl.SusApiAdapter;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(properties = "logging.level.org.springframework.security=WARN")
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@Transactional
class ProfissionaisEscopoTests {
    @MockitoBean SusApiAdapter susApi;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired TokenService tokens;
    @Autowired UsuarioRepository usuarios;
    @Autowired UsfReferenciaRepository usfs;
    @Autowired PasswordEncoder senhas;
    List<Usuario> admins, profissionais, recepcoes;

    @BeforeEach
    void preparar() {
        admins = new ArrayList<>();
        profissionais = new ArrayList<>();
        recepcoes = new ArrayList<>();
        List<UnidadeAtuacao> unidades = List.of(UnidadeAtuacao.CAPS_AD, UnidadeAtuacao.CAPS_I,
                UnidadeAtuacao.CAPS_II, UnidadeAtuacao.USF, UnidadeAtuacao.USF);
        for (int i = 0; i < unidades.size(); i++) {
            UsfReferencia usf = null;
            if (i >= 3) {
                usf = new UsfReferencia();
                usf.setCnes("00" + UUID.randomUUID());
                usf.setNomeUsf(i == 3 ? "USF Teste Centro" : "USF Andaiá");
                usf = usfs.saveAndFlush(usf);
            }
            admins.add(usuario("Admin " + i, unidades.get(i), usf, TipoUsuario.ADMINISTRADOR));
            profissionais.add(usuario("Profissional " + i, unidades.get(i), usf, TipoUsuario.PROFISSIONAL));
            recepcoes.add(usuario("Recepção " + i, unidades.get(i), usf, TipoUsuario.RECEPCAO));
        }
    }

    private Usuario usuario(String nome, UnidadeAtuacao unidade, UsfReferencia usf, TipoUsuario tipo) {
        var u = new Usuario();
        u.setNome(nome); u.setEmail(UUID.randomUUID() + "@test.local"); u.setSenha("senha-fixture");
        u.setUnidadeAtuacao(unidade); u.setUsfAtuacao(usf); u.setTipoUsuario(tipo);
        return usuarios.saveAndFlush(u);
    }

    private String bearer(Usuario usuario) {
        return "Bearer " + tokens.gerarToken(usuario);
    }

    private MockHttpServletResponse getAs(Usuario usuario, String path) throws Exception {
        return mvc.perform(get(path).header("Authorization", bearer(usuario))).andReturn().getResponse();
    }

    private JsonNode ok(Usuario usuario, String path) throws Exception {
        var response = getAs(usuario, path);
        assertEquals(200, response.getStatus(), path);
        return json.readTree(response.getContentAsString());
    }

    private void verificarLista(Usuario ator, int escopo) throws Exception {
        var lista = ok(ator, "/api/usuarios");
        assertFalse(lista.isEmpty());
        var ids = lista.valueStream().map(u -> u.get("idPublico").asString()).toList();
        assertTrue(ids.contains(admins.get(escopo).getIdPublico().toString()));
        assertTrue(ids.contains(profissionais.get(escopo).getIdPublico().toString()));
        assertTrue(ids.contains(recepcoes.get(escopo).getIdPublico().toString()));
        for (var u : lista) {
            assertEquals(ator.getUnidadeAtuacao().name(), u.get("unidadeAtuacao").asString());
            if (ator.getUnidadeAtuacao() == UnidadeAtuacao.USF) {
                assertEquals(ator.getUsfAtuacao().getId(), u.get("usfAtuacaoId").asLong());
                assertEquals(ator.getUsfAtuacao().getCnes(), u.get("usfAtuacao").get("cnes").asString());
            }
            assertFalse(u.has("senha"));
        }
        var nomes = lista.valueStream().map(u -> u.get("nome").asString()).toList();
        assertEquals(nomes.stream().sorted().toList(), nomes);
        for (int i = 0; i < admins.size(); i++) {
            if (i != escopo) {
                assertFalse(ids.contains(admins.get(i).getIdPublico().toString()));
                assertFalse(ids.contains(profissionais.get(i).getIdPublico().toString()));
                assertFalse(ids.contains(recepcoes.get(i).getIdPublico().toString()));
            }
        }
    }

    private Usuario externo(int escopo) {
        return profissionais.get(escopo == 3 ? 4 : escopo == 4 ? 3 : (escopo + 1) % 3);
    }

    private String payload(Usuario alvo, Usuario unidade, String nome) {
        var dados = new HashMap<String, Object>();
        dados.put("nome", nome);
        dados.put("email", alvo.getEmail());
        dados.put("tipoUsuario", alvo.getTipoUsuario());
        dados.put("unidadeAtuacao", unidade.getUnidadeAtuacao());
        dados.put("usfAtuacaoId", unidade.getUsfAtuacao() == null ? null : unidade.getUsfAtuacao().getId());
        return json.writeValueAsString(dados);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3, 4})
    void administradorListaSomenteSuaInstituicao(int escopo) throws Exception {
        verificarLista(admins.get(escopo), escopo);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3, 4})
    void recepcaoListaSomenteSuaInstituicao(int escopo) throws Exception {
        verificarLista(recepcoes.get(escopo), escopo);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3, 4})
    void uuidExternoRetornaMesmoNaoEncontradoSemDados(int escopo) throws Exception {
        for (Usuario ator : List.of(admins.get(escopo), recepcoes.get(escopo))) {
            Usuario alvo = externo(escopo);
            var fora = getAs(ator, "/api/usuarios/" + alvo.getIdPublico());
            UUID inexistente = UUID.randomUUID();
            var ausente = getAs(ator, "/api/usuarios/" + inexistente);
            assertEquals(404, fora.getStatus());
            assertEquals(404, ausente.getStatus());
            assertEquals("Resource not found. Id " + alvo.getIdPublico(),
                    json.readTree(fora.getContentAsString()).get("message").asString());
            assertEquals("Resource not found. Id " + inexistente,
                    json.readTree(ausente.getContentAsString()).get("message").asString());
            assertFalse(fora.getContentAsString().contains(alvo.getEmail()));
            assertFalse(fora.getContentAsString().contains(alvo.getNome()));
            assertEquals(profissionais.get(escopo).getNome(),
                    ok(ator, "/api/usuarios/" + profissionais.get(escopo).getIdPublico()).get("nome").asString());
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3, 4})
    void putNaoAceitaUsuarioExternoMesmoDisfarcadoComUnidadeDoAtor(int escopo) throws Exception {
        Usuario ator = admins.get(escopo), alvo = externo(escopo);
        String nomeAnterior = alvo.getNome();
        UnidadeAtuacao unidadeAnterior = alvo.getUnidadeAtuacao();
        Long usfAnterior = alvo.getUsfAtuacao() == null ? null : alvo.getUsfAtuacao().getId();
        var response = mvc.perform(put("/api/usuarios/" + alvo.getIdPublico())
                .header("Authorization", bearer(ator)).contentType("application/json")
                .content(payload(alvo, ator, "Alteração indevida"))).andReturn().getResponse();
        assertEquals(404, response.getStatus());
        Usuario persistido = usuarios.findByIdPublico(alvo.getIdPublico()).orElseThrow();
        assertEquals(nomeAnterior, persistido.getNome());
        assertEquals(unidadeAnterior, persistido.getUnidadeAtuacao());
        assertEquals(usfAnterior, persistido.getUsfAtuacao() == null ? null : persistido.getUsfAtuacao().getId());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3, 4})
    void deleteNaoRemoveUsuarioExterno(int escopo) throws Exception {
        Usuario alvo = externo(escopo);
        var response = mvc.perform(delete("/api/usuarios/" + alvo.getIdPublico())
                .header("Authorization", bearer(admins.get(escopo)))).andReturn().getResponse();
        assertEquals(404, response.getStatus());
        assertTrue(usuarios.findByIdPublico(alvo.getIdPublico()).isPresent());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3, 4})
    void editarERemoverDaMesmaUnidadeContinuamPermitidos(int escopo) throws Exception {
        Usuario ator = admins.get(escopo), alvo = profissionais.get(escopo);
        var editado = mvc.perform(put("/api/usuarios/" + alvo.getIdPublico())
                .header("Authorization", bearer(ator)).contentType("application/json")
                .content(payload(alvo, alvo, "Nome atualizado"))).andReturn().getResponse();
        assertEquals(200, editado.getStatus());
        assertEquals("Nome atualizado", usuarios.findByIdPublico(alvo.getIdPublico()).orElseThrow().getNome());
        var removido = mvc.perform(delete("/api/usuarios/" + alvo.getIdPublico())
                .header("Authorization", bearer(ator))).andReturn().getResponse();
        assertEquals(204, removido.getStatus());
        assertTrue(usuarios.findByIdPublico(alvo.getIdPublico()).isEmpty());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3, 4})
    void meESelecoesContinuamFuncionandoComEscopo(int escopo) throws Exception {
        for (Usuario ator : List.of(admins.get(escopo), profissionais.get(escopo), recepcoes.get(escopo))) {
            assertEquals(ator.getIdPublico().toString(), ok(ator, "/api/usuarios/me").get("idPublico").asString());
            for (String selecao : List.of("profissionais-selecao", "referencia-selecao")) {
                var lista = ok(ator, "/api/usuarios/" + selecao);
                assertTrue(lista.valueStream().anyMatch(u -> u.get("idPublico").asString()
                        .equals(profissionais.get(escopo).getIdPublico().toString())));
                assertTrue(lista.valueStream().anyMatch(u -> u.get("idPublico").asString()
                        .equals(admins.get(escopo).getIdPublico().toString())));
                var nomes = lista.valueStream().map(u -> u.get("nome").asString()).toList();
                assertEquals(nomes.stream().sorted().toList(), nomes);
                for (var u : lista) {
                    assertEquals(ator.getUnidadeAtuacao().name(), u.get("unidadeAtuacao").asString());
                    for (int i = 0; i < profissionais.size(); i++) {
                        if (i != escopo) {
                            assertNotEquals(profissionais.get(i).getIdPublico().toString(), u.get("idPublico").asString());
                            assertNotEquals(admins.get(i).getIdPublico().toString(), u.get("idPublico").asString());
                        }
                    }
                    assertNotEquals(recepcoes.get(escopo).getIdPublico().toString(), u.get("idPublico").asString());
                }
            }
        }
        assertEquals(403, getAs(profissionais.get(escopo), "/api/usuarios").getStatus());
        assertEquals(403, getAs(profissionais.get(escopo), "/api/usuarios/" + profissionais.get(escopo).getIdPublico()).getStatus());
    }

    @Test
    void legadoPermaneceFailClosedSemQuebrarMe() throws Exception {
        Usuario legado = usuario("USF legada", UnidadeAtuacao.USF, null, TipoUsuario.ADMINISTRADOR);
        assertEquals(200, getAs(legado, "/api/usuarios/me").getStatus());
        var lista = getAs(legado, "/api/usuarios");
        assertEquals(422, lista.getStatus());
        assertTrue(lista.getContentAsString().contains("A unidade USF específica deste usuário ainda não foi configurada."));
    }

    private String cadastro(Usuario unidade, TipoUsuario tipo, String email, String senha) {
        var dados = json.readValue(payload(unidade, unidade, "Novo usuário"), HashMap.class);
        dados.put("email", email);
        dados.put("tipoUsuario", tipo);
        dados.put("senha", senha);
        return json.writeValueAsString(dados);
    }

    private MockHttpServletResponse criar(Usuario ator, String body) throws Exception {
        return mvc.perform(post("/api/usuarios").header("Authorization", bearer(ator))
                .contentType("application/json").content(body)).andReturn().getResponse();
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3, 4})
    void cadastroDaPropriaInstituicaoAceitaOsTresRolesEPreservaSenha(int escopo) throws Exception {
        Usuario ator = admins.get(escopo);
        for (TipoUsuario tipo : TipoUsuario.values()) {
            String email = UUID.randomUUID() + "@test.local";
            var response = criar(ator, cadastro(ator, tipo, email, "senha-nova"));
            assertEquals(201, response.getStatus(), response.getContentAsString());
            assertFalse(json.readTree(response.getContentAsString()).has("senha"));
            Usuario salvo = usuarios.findByEmail(email).orElseThrow();
            assertEquals(tipo, salvo.getTipoUsuario());
            assertEquals(ator.getUnidadeAtuacao(), salvo.getUnidadeAtuacao());
            assertEquals(ator.getUsfAtuacao(), salvo.getUsfAtuacao());
            assertTrue(senhas.matches("senha-nova", salvo.getSenha()));
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3, 4})
    void payloadManualNaoCriaNemMoveUsuarioParaOutraInstituicao(int escopo) throws Exception {
        Usuario ator = admins.get(escopo), alvo = profissionais.get(escopo);
        for (int i = 0; i < admins.size(); i++) {
            if (i == escopo) continue;
            String email = UUID.randomUUID() + "@test.local";
            var response = criar(ator, cadastro(admins.get(i), TipoUsuario.PROFISSIONAL, email, "senha-nova"));
            assertEquals(422, response.getStatus());
            assertTrue(response.getContentAsString().contains("Só é possível salvar usuários para a sua própria unidade."));
            assertTrue(usuarios.findByEmail(email).isEmpty());
            var editado = mvc.perform(put("/api/usuarios/" + alvo.getIdPublico())
                    .header("Authorization", bearer(ator)).contentType("application/json")
                    .content(payload(alvo, admins.get(i), "Mudança indevida"))).andReturn().getResponse();
            assertEquals(422, editado.getStatus());
            assertEquals(ator.getUnidadeAtuacao(), alvo.getUnidadeAtuacao());
            assertEquals(ator.getUsfAtuacao(), alvo.getUsfAtuacao());
            assertEquals("Profissional " + escopo, alvo.getNome());
        }
    }

    @Test
    void cadastroMantemValidacaoDeSenhaEmailEPermissoesHttp() throws Exception {
        assertEquals(422, criar(admins.get(0), cadastro(admins.get(0), TipoUsuario.PROFISSIONAL,
                "sem-senha@test.local", "")).getStatus());
        assertEquals(409, criar(admins.get(0), cadastro(admins.get(0), TipoUsuario.PROFISSIONAL,
                profissionais.get(0).getEmail(), "senha-nova")).getStatus());
        for (Usuario ator : List.of(profissionais.get(0), recepcoes.get(0))) {
            assertEquals(403, criar(ator, cadastro(ator, TipoUsuario.PROFISSIONAL,
                    UUID.randomUUID() + "@test.local", "senha-nova")).getStatus());
        }
        var anonimo = mvc.perform(post("/api/usuarios").contentType("application/json")
                .content(cadastro(admins.get(0), TipoUsuario.PROFISSIONAL,
                        UUID.randomUUID() + "@test.local", "senha-nova"))).andReturn().getResponse();
        assertEquals(401, anonimo.getStatus());
    }
}
