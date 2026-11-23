package com.pet.buscaativa;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.time.LocalDate;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import com.pet.buscaativa.entities.Paciente;
import com.pet.buscaativa.entities.UsfReferencia;
import com.pet.buscaativa.entities.Usuario;
import com.pet.buscaativa.entities.VinculoAssistencial;
import com.pet.buscaativa.entities.enums.CapsEnum;
import com.pet.buscaativa.entities.enums.StatusPaciente;
import com.pet.buscaativa.entities.enums.TipoUsuario;
import com.pet.buscaativa.entities.enums.UnidadeAtuacao;
import com.pet.buscaativa.repositories.PacienteRepository;
import com.pet.buscaativa.repositories.UsuarioRepository;
import com.pet.buscaativa.repositories.UsfReferenciaRepository;
import com.pet.buscaativa.repositories.VinculoAssistencialRepository;
import com.pet.buscaativa.services.TokenService;
import com.pet.buscaativa.services.impl.SusApiAdapter;

/**
 * Regressao DE SEGURANCA para o proximo corte do cadastro unico.
 *
 * Demonstra o requisito ainda pendente: capsReferencia / usfReferencia sao
 * dados territoriais, NUNCA uma permissao para consultar fatos clinicos.
 * Uma identidade com vinculo somente em USF A nao pode aparecer no CAPS II
 * apenas porque o seu campo de referencia menciona CAPS II (e vice-versa).
 *
 * Estes testes devem sinalizar a divergencia do escopo clinico legado.
 * Nao se deve "corrigir" o teste criando vinculos por referencia nem
 * atribuir origem historica por dados territoriais.
 */
@SpringBootTest
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@Transactional
class CadastroUnicoIsolamentoReferenciaHttpTests extends SecurityTestSupport {
    @MockitoBean SusApiAdapter susApi;
    @Autowired MockMvc mvc;
    @Autowired TokenService tokens;
    @Autowired UsuarioRepository usuarios;
    @Autowired PacienteRepository pacientes;
    @Autowired UsfReferenciaRepository usfs;
    @Autowired VinculoAssistencialRepository vinculos;

    private UsfReferencia usf() {
        UsfReferencia u = new UsfReferencia();
        u.setCnes(UUID.randomUUID().toString());
        u.setNomeUsf("USF sintetica vinculada");
        return usfs.saveAndFlush(u);
    }

    private Usuario ator(UnidadeAtuacao unidade, UsfReferencia local) {
        Usuario u = new Usuario();
        u.setNome("Ator de privacidade");
        u.setEmail(UUID.randomUUID() + "@test.local");
        u.setSenha("somente-teste");
        u.setTipoUsuario(TipoUsuario.ADMINISTRADOR);
        u.setUnidadeAtuacao(unidade);
        u.setUsfAtuacao(local);
        return usuarios.saveAndFlush(u);
    }

    private Paciente paciente(UsfReferencia referenciaUsf, CapsEnum referenciaCaps) {
        Paciente p = new Paciente();
        p.setNome("Identidade sintetica isolada " + UUID.randomUUID());
        p.setNomeMae("Mae sintetica");
        p.setCpf("81234567890");
        p.setCns("812345678901234");
        p.setDataNascimento(LocalDate.of(1994, 5, 12));
        p.setStatusPaciente(StatusPaciente.ATIVO);
        p.setCountFaltas(7); // Cache clinico GLOBAL legado: nao transferir.
        p.setUsfReferencia(referenciaUsf);
        p.setCapsReferencia(referenciaCaps);
        return pacientes.saveAndFlush(p);
    }

    private org.springframework.mock.web.MockHttpServletResponse consultar(
            Usuario ator, String path) throws Exception {
        return mvc.perform(get(path).header(
                "Authorization", "Bearer " + tokens.gerarToken(ator)))
                .andReturn().getResponse();
    }

    @Test
    void referenciaCapsNaoAutorizaLeituraDoPacienteVinculadoSomenteAUsf()
            throws Exception {
        UsfReferencia a = usf();
        Paciente p = paciente(a, CapsEnum.CAPS_II);
        vinculos.saveAndFlush(VinculoAssistencial.paraUsf(p, a));

        Usuario capsII = ator(UnidadeAtuacao.CAPS_II, null);
        var detalhe = consultar(capsII, "/api/pacientes/" + p.getIdPublico());
        assertEquals(404, detalhe.getStatus(),
                "CAPS de referencia nao equivale a vinculo CAPS; o GET legado nao pode liberar dados globais.");
        assertFalse(detalhe.getContentAsString().contains(p.getNome()));

        var pesquisa = consultar(capsII, "/api/pacientes/pesquisa?q=isolada");
        assertEquals(200, pesquisa.getStatus(), pesquisa.getContentAsString());
        assertFalse(pesquisa.getContentAsString().contains(p.getNome()),
                "A listagem tambem nao pode vazar a identidade sem vinculo CAPS.");
    }

    @Test
    void doisVinculosNaoLiberamCacheClinicoGlobalNasRotasAntigas() throws Exception {
        UsfReferencia a = usf();
        Paciente p = paciente(a, CapsEnum.CAPS_AD);
        // No modelo antigo, as referencias territoriais bastariam para os
        // dois atores lerem countFaltas/indicadores globais desta identidade.
        vinculos.saveAndFlush(VinculoAssistencial.paraUsf(p, a));
        vinculos.saveAndFlush(VinculoAssistencial.paraCaps(p, UnidadeAtuacao.CAPS_AD));
        Usuario usfA = ator(UnidadeAtuacao.USF, a);
        Usuario capsAd = ator(UnidadeAtuacao.CAPS_AD, null);

        for (Usuario usuario : java.util.List.of(usfA, capsAd)) {
            var detalhe = consultar(usuario, "/api/pacientes/" + p.getIdPublico());
            assertEquals(404, detalhe.getStatus(),
                    "Historico e indicadores globais nao sao clinica local comprobada.");
            assertFalse(detalhe.getContentAsString().contains(p.getNome()));

            var lista = consultar(usuario,
                    "/api/pacientes/pesquisa?q=isolada");
            assertEquals(200, lista.getStatus(), lista.getContentAsString());
            assertFalse(lista.getContentAsString().contains(p.getNome()));
        }
        assertEquals(7, pacientes.findById(p.getId()).orElseThrow().getCountFaltas(),
                "A restricao de leitura nao pode recalcular nem sobrescrever dados legados.");
    }

    /**
     * As rotas clinicas antigas nao podem contornar a mesma barreira por
     * caminhos alternativos: UUID, documento, historico ou agendamentos.
     * A identidade continua global; a resposta nao pode trazer o registro
     * clinico nem referencias de outra unidade.
     */
    private void verificarRotasClinicasNegadas(Usuario ator, Paciente pessoa)
            throws Exception {
        String raiz = "/api/pacientes/" + pessoa.getIdPublico();
        for (String caminho : java.util.List.of(
                raiz,
                raiz + "/detalhe",
                raiz + "/historico",
                raiz + "/agendamentos",
                "/api/pacientes/busca/cpf/" + pessoa.getCpf(),
                "/api/pacientes/busca/cns/" + pessoa.getCns())) {
            var resposta = consultar(ator, caminho);
            assertEquals(404, resposta.getStatus(),
                    "Rota legada nao pode conceder acesso sem clinica local comprovada: " + caminho);
            assertFalse(resposta.getContentAsString().contains(pessoa.getNome()),
                    "Resposta nao pode incluir identidade de paciente fora do escopo: " + caminho);
        }

        for (String caminho : java.util.List.of(
                "/api/pacientes",
                "/api/pacientes/busca/nome?q=isolada",
                "/api/pacientes/pesquisa?q=isolada")) {
            var resposta = consultar(ator, caminho);
            assertEquals(200, resposta.getStatus(),
                    "Listagens autorizadas devem permanecer disponiveis: " + caminho);
            assertFalse(resposta.getContentAsString().contains(pessoa.getNome()),
                    "Busca nao pode revelar identidade por referencia territorial: " + caminho);
        }
    }

    @Test
    void rotasClinicasLegadasNaoVazamPacienteVinculadoSomenteAOutraUnidade()
            throws Exception {
        UsfReferencia a = usf();
        Paciente p = paciente(a, CapsEnum.CAPS_II);
        vinculos.saveAndFlush(VinculoAssistencial.paraUsf(p, a));

        Usuario capsII = ator(UnidadeAtuacao.CAPS_II, null);
        verificarRotasClinicasNegadas(capsII, p);
    }

    @Test
    void rotasClinicasLegadasNaoVazamPacienteComVinculosMultiplos()
            throws Exception {
        UsfReferencia a = usf();
        Paciente p = paciente(a, CapsEnum.CAPS_AD);
        vinculos.saveAndFlush(VinculoAssistencial.paraUsf(p, a));
        vinculos.saveAndFlush(VinculoAssistencial.paraCaps(p, UnidadeAtuacao.CAPS_AD));

        verificarRotasClinicasNegadas(ator(UnidadeAtuacao.USF, a), p);
        verificarRotasClinicasNegadas(ator(UnidadeAtuacao.CAPS_AD, null), p);
        assertEquals(7, pacientes.findById(p.getId()).orElseThrow().getCountFaltas(),
                "Verificacoes de leitura nao devem modificar indicadores globais.");
    }

    @Test
    void referenciaUsfNaoAutorizaLeituraDoPacienteVinculadoSomenteACaps()
            throws Exception {
        UsfReferencia a = usf();
        Paciente p = paciente(a, CapsEnum.CAPS_AD);
        vinculos.saveAndFlush(VinculoAssistencial.paraCaps(p, UnidadeAtuacao.CAPS_AD));

        Usuario usfA = ator(UnidadeAtuacao.USF, a);
        var detalhe = consultar(usfA, "/api/pacientes/" + p.getIdPublico());
        assertEquals(404, detalhe.getStatus(),
                "USF de referencia territorial nao equivale a vinculo assistencial local.");
        assertFalse(detalhe.getContentAsString().contains(p.getNome()));

        var pesquisa = consultar(usfA, "/api/pacientes/pesquisa?q=isolada");
        assertEquals(200, pesquisa.getStatus(), pesquisa.getContentAsString());
        assertFalse(pesquisa.getContentAsString().contains(p.getNome()));
    }
}
