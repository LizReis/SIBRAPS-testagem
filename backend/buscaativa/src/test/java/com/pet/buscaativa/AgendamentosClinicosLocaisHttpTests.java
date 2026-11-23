package com.pet.buscaativa;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import com.pet.buscaativa.entities.Agendamento;
import com.pet.buscaativa.entities.Paciente;
import com.pet.buscaativa.entities.Usuario;
import com.pet.buscaativa.entities.VinculoAssistencial;
import com.pet.buscaativa.entities.enums.CapsEnum;
import com.pet.buscaativa.entities.enums.SituacaoAtendimento;
import com.pet.buscaativa.entities.enums.TipoUsuario;
import com.pet.buscaativa.entities.enums.UnidadeAtuacao;
import com.pet.buscaativa.repositories.AgendamentoRepository;
import com.pet.buscaativa.repositories.PacienteRepository;
import com.pet.buscaativa.repositories.UsuarioRepository;
import com.pet.buscaativa.repositories.VinculoAssistencialRepository;
import com.pet.buscaativa.services.TokenService;
import com.pet.buscaativa.services.impl.SusApiAdapter;

import tools.jackson.databind.ObjectMapper;

/**
 * H2 sintetico, filtro JWT real e transacoes de rollback: somente consultas.
 * Nao faz backfill de origem antiga nem usa lotacao atual para atribuir fato.
 */
@SpringBootTest
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@Transactional
class AgendamentosClinicosLocaisHttpTests extends SecurityTestSupport {
    @MockitoBean SusApiAdapter susApi;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired TokenService tokens;
    @Autowired PacienteRepository pacientes;
    @Autowired UsuarioRepository usuarios;
    @Autowired VinculoAssistencialRepository vinculos;
    @Autowired AgendamentoRepository agendamentos;

    private Paciente pessoa() {
        Paciente p = new Paciente();
        p.setNome("Paciente sintetico da agenda local");
        p.setNomeMae("Responsavel sintetica");
        p.setDataNascimento(LocalDate.of(1994, 5, 12));
        p.setCpf("83456789012");
        p.setCns("834567890123456");
        p.setCapsReferencia(CapsEnum.CAPS_AD);
        p.setCountFaltas(9); // cache GLOBAL nao e informacao local
        return pacientes.saveAndFlush(p);
    }

    private Usuario usuario(UnidadeAtuacao unidade, TipoUsuario perfil) {
        Usuario u = new Usuario();
        u.setNome("Executor sintetico " + unidade);
        u.setEmail(UUID.randomUUID() + "@test.local");
        u.setSenha("somente-teste");
        u.setTipoUsuario(perfil);
        u.setUnidadeAtuacao(unidade);
        return usuarios.saveAndFlush(u);
    }

    private Agendamento agendamento(Paciente p, Usuario responsavel,
            LocalDate data, boolean comOrigem) {
        Agendamento a = new Agendamento();
        a.setPaciente(p);
        a.setUsuario(responsavel);
        a.setDataAgendamento(data);
        a.setHoraAtendimento(LocalTime.of(9, 30));
        a.setSituacaoAtendimento(SituacaoAtendimento.AGENDADO);
        if (comOrigem) a.registrarUnidadeDeOrigem(responsavel);
        return agendamentos.saveAndFlush(a);
    }

    private org.springframework.mock.web.MockHttpServletResponse consultar(
            Paciente p, Usuario ator) throws Exception {
        var req = get("/api/pacientes/" + p.getIdPublico() + "/agendamentos-locais");
        if (ator != null)
            req.header("Authorization", "Bearer " + tokens.gerarToken(ator));
        return mvc.perform(req).andReturn().getResponse();
    }

    @Test
    void duasUnidadesVisualizamSomenteSeusAgendamentosEEstadoLocal() throws Exception {
        Paciente p = pessoa();
        Usuario ad = usuario(UnidadeAtuacao.CAPS_AD, TipoUsuario.PROFISSIONAL);
        Usuario ii = usuario(UnidadeAtuacao.CAPS_II, TipoUsuario.ADMINISTRADOR);
        vinculos.saveAndFlush(VinculoAssistencial.paraCaps(p, UnidadeAtuacao.CAPS_AD));
        vinculos.saveAndFlush(VinculoAssistencial.paraCaps(p, UnidadeAtuacao.CAPS_II));

        var compromissoAd = agendamento(p, ad, LocalDate.of(2026, 12, 11), true);
        var compromissoIi = agendamento(p, ii, LocalDate.of(2026, 12, 12), true);
        var desconhecido = agendamento(p, ad, LocalDate.of(2026, 12, 13), false);

        var respostaAd = consultar(p, ad);
        assertEquals(200, respostaAd.getStatus());
        assertTrue(respostaAd.getHeader("Cache-Control").contains("no-store"));
        var adJson = json.readTree(respostaAd.getContentAsString());
        assertEquals(1, adJson.get("totalAgendamentos").asInt());
        assertEquals(1, adJson.get("agendamentos").size());
        assertEquals(compromissoAd.getId(), adJson.get("agendamentos").get(0).get("id").asLong());
        // Comparar o campo numerico exato. Buscar "2" no JSON inteiro
        // gera falso positivo em datas como "2026-12-11".
        assertNotEquals(compromissoIi.getId(),
                adJson.get("agendamentos").get(0).get("id").asLong(),
                "Um atendimento do CAPS II nao pode aparecer na lista do CAPS AD.");
        assertFalse(respostaAd.getContentAsString().contains("2026-12-12"));
        assertFalse(respostaAd.getContentAsString().contains("2026-12-13"));
        assertFalse(adJson.has("countFaltas"));
        assertFalse(adJson.has("classificacaoRisco"));
        assertFalse(adJson.has("usfReferencia"));

        var respostaIi = consultar(p, ii);
        assertEquals(200, respostaIi.getStatus());
        var iiJson = json.readTree(respostaIi.getContentAsString());
        assertEquals(1, iiJson.get("totalAgendamentos").asInt());
        assertEquals(compromissoIi.getId(), iiJson.get("agendamentos").get(0).get("id").asLong());
        assertNotEquals(compromissoAd.getId(),
                iiJson.get("agendamentos").get(0).get("id").asLong(),
                "Um atendimento do CAPS AD nao pode aparecer na lista do CAPS II.");
        assertFalse(respostaIi.getContentAsString().contains("2026-12-11"));
        assertEquals(9, pacientes.findById(p.getId()).orElseThrow().getCountFaltas());
        assertNull(agendamentos.findById(desconhecido.getId()).orElseThrow()
                .getUnidadeOrigemChave());
    }

    @Test
    void remarcacaoLocalComPaiDeOutraUnidadeNaoApareceNaAgenda() throws Exception {
        Paciente p = pessoa();
        Usuario ad = usuario(UnidadeAtuacao.CAPS_AD, TipoUsuario.ADMINISTRADOR);
        Usuario ii = usuario(UnidadeAtuacao.CAPS_II, TipoUsuario.PROFISSIONAL);
        vinculos.saveAndFlush(VinculoAssistencial.paraCaps(p, UnidadeAtuacao.CAPS_AD));

        var origemEstrangeira = agendamento(p, ii, LocalDate.of(2026, 12, 10), true);
        var remarcacaoLocal = agendamento(p, ad, LocalDate.of(2026, 12, 11), true);
        remarcacaoLocal.setAgendamentoOriginal(origemEstrangeira);
        agendamentos.saveAndFlush(remarcacaoLocal);

        var resposta = consultar(p, ad);
        assertEquals(200, resposta.getStatus());
        var payload = json.readTree(resposta.getContentAsString());
        assertEquals(0, payload.get("totalAgendamentos").asInt());
        assertEquals(0, payload.get("agendamentos").size());
        assertEquals("CAPS_AD", agendamentos.findById(remarcacaoLocal.getId())
                .orElseThrow().getUnidadeOrigemChave());
        assertEquals("CAPS_II", agendamentos.findById(origemEstrangeira.getId())
                .orElseThrow().getUnidadeOrigemChave());
    }

    @Test
    void cadeiaDeRemarcacoesNaoTransfereOrigemAntigaDesconhecida() throws Exception {
        Paciente p = pessoa();
        Usuario ad = usuario(UnidadeAtuacao.CAPS_AD, TipoUsuario.ADMINISTRADOR);
        vinculos.saveAndFlush(VinculoAssistencial.paraCaps(p, UnidadeAtuacao.CAPS_AD));

        var originalSemOrigem = agendamento(p, ad, LocalDate.of(2026, 12, 9), false);
        var intermediario = agendamento(p, ad, LocalDate.of(2026, 12, 10), true);
        intermediario.setAgendamentoOriginal(originalSemOrigem);
        agendamentos.saveAndFlush(intermediario);
        var maisRecente = agendamento(p, ad, LocalDate.of(2026, 12, 11), true);
        maisRecente.setAgendamentoOriginal(intermediario);
        agendamentos.saveAndFlush(maisRecente);

        var resposta = consultar(p, ad);
        assertEquals(200, resposta.getStatus());
        var payload = json.readTree(resposta.getContentAsString());
        assertEquals(0, payload.get("totalAgendamentos").asInt());
        assertEquals(0, payload.get("agendamentos").size());
        assertNull(agendamentos.findById(originalSemOrigem.getId())
                .orElseThrow().getUnidadeOrigemChave(),
                "Nenhum backfill pode ser efetuado ao consultar a cadeia.");
    }

    @Test
    void remarcacaoComCadeiaInteiramenteLocalContinuaVisivel() throws Exception {
        Paciente p = pessoa();
        Usuario ad = usuario(UnidadeAtuacao.CAPS_AD, TipoUsuario.ADMINISTRADOR);
        vinculos.saveAndFlush(VinculoAssistencial.paraCaps(p, UnidadeAtuacao.CAPS_AD));

        var original = agendamento(p, ad, LocalDate.of(2026, 12, 9), true);
        var remarcado = agendamento(p, ad, LocalDate.of(2026, 12, 10), true);
        remarcado.setAgendamentoOriginal(original);
        agendamentos.saveAndFlush(remarcado);

        var resposta = consultar(p, ad);
        assertEquals(200, resposta.getStatus());
        var payload = json.readTree(resposta.getContentAsString());
        assertEquals(2, payload.get("totalAgendamentos").asInt());
        assertEquals(2, payload.get("agendamentos").size());
        var ids = new java.util.HashSet<Long>();
        payload.get("agendamentos").forEach(item -> ids.add(item.get("id").asLong()));
        assertEquals(java.util.Set.of(original.getId(), remarcado.getId()), ids);
    }

    @Test
    void referenciaTerritorialNaoSubstituiVinculoExplicito() throws Exception {
        Paciente p = pessoa();
        Usuario ad = usuario(UnidadeAtuacao.CAPS_AD, TipoUsuario.ADMINISTRADOR);
        vinculos.saveAndFlush(VinculoAssistencial.paraCaps(p, UnidadeAtuacao.CAPS_II));
        agendamento(p, ad, LocalDate.of(2026, 12, 13), true);
        var resposta = consultar(p, ad);
        assertEquals(404, resposta.getStatus());
        assertFalse(resposta.getContentAsString().contains(p.getNome()));
    }

    @Test
    void agendamentoSemOrigemComprovadaPermanecePendenteEInvisivel()
            throws Exception {
        Paciente p = pessoa();
        Usuario ad = usuario(UnidadeAtuacao.CAPS_AD, TipoUsuario.ADMINISTRADOR);
        vinculos.saveAndFlush(VinculoAssistencial.paraCaps(p, UnidadeAtuacao.CAPS_AD));
        var semOrigem = agendamento(p, ad, LocalDate.of(2026, 12, 13), false);

        var resposta = consultar(p, ad);
        assertEquals(200, resposta.getStatus());
        var payload = json.readTree(resposta.getContentAsString());
        assertEquals(0, payload.get("totalAgendamentos").asInt());
        assertEquals(0, payload.get("agendamentos").size());
        assertNull(agendamentos.findById(semOrigem.getId())
                .orElseThrow().getUnidadeOrigemChave());
    }

    @Test
    void pacienteSemVinculoLocalENaoAutenticadoNaoExpoeAgenda() throws Exception {
        Paciente p = pessoa();
        Usuario ad = usuario(UnidadeAtuacao.CAPS_AD, TipoUsuario.PROFISSIONAL);
        vinculos.saveAndFlush(VinculoAssistencial.paraCaps(p, UnidadeAtuacao.CAPS_II));
        assertEquals(401, consultar(p, null).getStatus());
        assertEquals(404, consultar(p, ad).getStatus());
    }
}
