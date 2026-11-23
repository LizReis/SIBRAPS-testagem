package com.pet.buscaativa;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.time.LocalDate;
import java.time.LocalDateTime;
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
import com.pet.buscaativa.entities.HistoricoPaciente;
import com.pet.buscaativa.entities.Paciente;
import com.pet.buscaativa.entities.Usuario;
import com.pet.buscaativa.entities.VinculoAssistencial;
import com.pet.buscaativa.entities.enums.CapsEnum;
import com.pet.buscaativa.entities.enums.SituacaoAtendimento;
import com.pet.buscaativa.entities.enums.TipoEventoHistoricoPaciente;
import com.pet.buscaativa.entities.enums.TipoUsuario;
import com.pet.buscaativa.entities.enums.UnidadeAtuacao;
import com.pet.buscaativa.repositories.AgendamentoRepository;
import com.pet.buscaativa.repositories.HistoricoPacienteRepository;
import com.pet.buscaativa.repositories.PacienteRepository;
import com.pet.buscaativa.repositories.UsuarioRepository;
import com.pet.buscaativa.repositories.VinculoAssistencialRepository;
import com.pet.buscaativa.services.TokenService;
import com.pet.buscaativa.services.impl.SusApiAdapter;

import tools.jackson.databind.ObjectMapper;

/**
 * Testa a nova via local com JWT real, H2 sintetico e fatos de CAPS distintos.
 * Nao transfere fatos clinicos, nao realiza backfill, e nao altera a tela.
 */
@SpringBootTest
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@Transactional
class HistoricoClinicoLocalHttpTests extends SecurityTestSupport {
    @MockitoBean SusApiAdapter susApi;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired TokenService tokens;
    @Autowired UsuarioRepository usuarios;
    @Autowired PacienteRepository pacientes;
    @Autowired VinculoAssistencialRepository vinculos;
    @Autowired HistoricoPacienteRepository historicos;
    @Autowired AgendamentoRepository agendamentos;

    private Paciente paciente() {
        Paciente p = new Paciente();
        p.setNome("Pessoa sintetica compartilhada");
        p.setNomeMae("Responsavel sintetica");
        p.setDataNascimento(LocalDate.of(1991, 4, 5));
        p.setCpf("72345678901");
        p.setCns("723456789012345");
        p.setCapsReferencia(CapsEnum.CAPS_AD);
        p.setCountFaltas(9); // Indicador GLOBAL legado: proibido na resposta local
        return pacientes.saveAndFlush(p);
    }

    private Usuario usuario(UnidadeAtuacao unidade, TipoUsuario perfil) {
        Usuario u = new Usuario();
        u.setNome("Executor sintetico " + unidade);
        u.setEmail(UUID.randomUUID() + "@test.local");
        u.setSenha("somente-teste");
        u.setUnidadeAtuacao(unidade);
        u.setTipoUsuario(perfil);
        return usuarios.saveAndFlush(u);
    }

    private HistoricoPaciente evento(Paciente p, Usuario origem,
            TipoEventoHistoricoPaciente tipo, String descricao,
            boolean origemComprovada) {
        HistoricoPaciente h = new HistoricoPaciente();
        h.setPaciente(p);
        h.setProfissional(origem);
        h.setTipo(tipo);
        h.setDescricao(descricao);
        h.setOcorridoEm(LocalDateTime.of(2026, 11, 1, 10, 0));
        if (tipo == TipoEventoHistoricoPaciente.PRESENCA)
            h.setSituacaoAtendimento(SituacaoAtendimento.PRESENTE);
        if (origemComprovada) h.registrarOrigemDeAcaoLocal(origem);
        return historicos.saveAndFlush(h);
    }

    private org.springframework.mock.web.MockHttpServletResponse consultar(
            Paciente p, Usuario ator) throws Exception {
        var req = get("/api/pacientes/" + p.getIdPublico() + "/historico-local");
        if (ator != null)
            req.header("Authorization", "Bearer " + tokens.gerarToken(ator));
        return mvc.perform(req).andReturn().getResponse();
    }

    @Test
    void vinculosEmDoisCapsRetornamSomenteFatosLocaisEContadoresLocais()
            throws Exception {
        Paciente p = paciente();
        Usuario ad = usuario(UnidadeAtuacao.CAPS_AD, TipoUsuario.PROFISSIONAL);
        Usuario ii = usuario(UnidadeAtuacao.CAPS_II, TipoUsuario.ADMINISTRADOR);
        vinculos.saveAndFlush(VinculoAssistencial.paraCaps(p, UnidadeAtuacao.CAPS_AD));
        vinculos.saveAndFlush(VinculoAssistencial.paraCaps(p, UnidadeAtuacao.CAPS_II));

        evento(p, ad, TipoEventoHistoricoPaciente.PRESENCA,
                "Presenca comprovada no CAPS AD", true);
        evento(p, ad, TipoEventoHistoricoPaciente.BUSCA_ATIVA,
                "Busca ativa local CAPS AD", true);
        evento(p, ii, TipoEventoHistoricoPaciente.FALTA,
                "Falta clinica exclusiva do CAPS II", true);
        evento(p, ad, TipoEventoHistoricoPaciente.BUSCA_ATIVA,
                "Origem do evento ainda desconhecida", false);

        var response = consultar(p, ad);
        assertEquals(200, response.getStatus());
        assertTrue(response.getHeader("Cache-Control").contains("no-store"));
        var payload = json.readTree(response.getContentAsString());
        assertEquals(p.getNome(), payload.get("nomePaciente").asText());
        assertEquals(2, payload.get("totalEventos").asInt());
        assertEquals(1, payload.get("totalPresencas").asInt());
        assertEquals(0, payload.get("totalFaltas").asInt());
        assertEquals(1, payload.get("totalRegistrosBuscaAtiva").asInt());
        assertEquals(2, payload.get("eventos").size());
        assertFalse(payload.has("quantidadeFaltasAtual"));
        assertFalse(payload.has("classificacaoAtual"));
        assertFalse(payload.has("statusPaciente"));
        assertFalse(payload.has("dataUltimaPresenca"));
        assertFalse(response.getContentAsString().contains("Falta clinica exclusiva do CAPS II"));
        assertFalse(response.getContentAsString().contains("Origem do evento ainda desconhecida"));
        assertEquals(9, pacientes.findById(p.getId()).orElseThrow().getCountFaltas());

        var respostaIi = consultar(p, ii);
        assertEquals(200, respostaIi.getStatus());
        var iiJson = json.readTree(respostaIi.getContentAsString());
        assertEquals(1, iiJson.get("totalEventos").asInt());
        assertEquals(1, iiJson.get("totalFaltas").asInt());
        assertFalse(respostaIi.getContentAsString().contains("Presenca comprovada no CAPS AD"));
    }

    @Test
    void historicoLocalNaoExibeRemarcacaoComRaizDeOutroCapsOuSemOrigem()
            throws Exception {
        Paciente p = paciente();
        Usuario ad = usuario(UnidadeAtuacao.CAPS_AD, TipoUsuario.ADMINISTRADOR);
        Usuario ii = usuario(UnidadeAtuacao.CAPS_II, TipoUsuario.PROFISSIONAL);
        vinculos.saveAndFlush(VinculoAssistencial.paraCaps(p, UnidadeAtuacao.CAPS_AD));
        vinculos.saveAndFlush(VinculoAssistencial.paraCaps(p, UnidadeAtuacao.CAPS_II));

        Agendamento raizIi = agendamento(p, ii, LocalDate.of(2026, 12, 5), true);
        Agendamento filhoAd = agendamento(p, ad, LocalDate.of(2026, 12, 6), true);
        filhoAd.setAgendamentoOriginal(raizIi);
        agendamentos.saveAndFlush(filhoAd);
        var irregularCaps = historicoAgendamento(p, filhoAd, "Nao divulgar remarcacao CAPS II");

        Agendamento raizSemOrigem = agendamento(p, ad, LocalDate.of(2026, 12, 7), false);
        Agendamento filhoComRaizIncerta = agendamento(p, ad, LocalDate.of(2026, 12, 8), true);
        filhoComRaizIncerta.setAgendamentoOriginal(raizSemOrigem);
        agendamentos.saveAndFlush(filhoComRaizIncerta);
        var irregularAntigo = historicoAgendamento(p, filhoComRaizIncerta,
                "Nao divulgar remarcacao com origem desconhecida");

        Agendamento raizLocal = agendamento(p, ad, LocalDate.of(2026, 12, 9), true);
        Agendamento remarcacaoLocal = agendamento(p, ad, LocalDate.of(2026, 12, 10), true);
        remarcacaoLocal.setAgendamentoOriginal(raizLocal);
        agendamentos.saveAndFlush(remarcacaoLocal);
        var valido = historicoAgendamento(p, remarcacaoLocal, "Fato local com cadeia comprovada");

        var resposta = consultar(p, ad);
        assertEquals(200, resposta.getStatus());
        var payload = json.readTree(resposta.getContentAsString());
        assertEquals(1, payload.get("totalEventos").asInt());
        assertEquals(valido.getId(), payload.get("eventos").get(0).get("id").asLong());
        assertFalse(resposta.getContentAsString().contains(irregularCaps.getDescricao()));
        assertFalse(resposta.getContentAsString().contains(irregularAntigo.getDescricao()));
        assertEquals("CAPS_AD", historicos.findById(irregularCaps.getId())
                .orElseThrow().getUnidadeOrigemChave());
        assertEquals("CAPS_II", agendamentos.findById(raizIi.getId())
                .orElseThrow().getUnidadeOrigemChave());
        assertNull(agendamentos.findById(raizSemOrigem.getId())
                .orElseThrow().getUnidadeOrigemChave());
        assertEquals(9, pacientes.findById(p.getId()).orElseThrow().getCountFaltas());
    }

    private Agendamento agendamento(Paciente p, Usuario responsavel,
            LocalDate data, boolean origemComprovada) {
        Agendamento a = new Agendamento();
        a.setPaciente(p);
        a.setUsuario(responsavel);
        a.setDataAgendamento(data);
        a.setHoraAtendimento(java.time.LocalTime.of(9, 30));
        a.setSituacaoAtendimento(SituacaoAtendimento.AGENDADO);
        if (origemComprovada) a.registrarUnidadeDeOrigem(responsavel);
        return agendamentos.saveAndFlush(a);
    }

    private HistoricoPaciente historicoAgendamento(Paciente p, Agendamento a, String descricao) {
        HistoricoPaciente h = new HistoricoPaciente();
        h.setPaciente(p);
        h.setAgendamento(a);
        h.setProfissional(a.getUsuario());
        h.setTipo(TipoEventoHistoricoPaciente.CONSULTA_AGENDADA);
        h.setDescricao(descricao);
        h.setOcorridoEm(LocalDateTime.of(2026, 12, 10, 9, 30));
        h.registrarOrigemDoAgendamento(a);
        return historicos.saveAndFlush(h);
    }

    @Test
    void historicoNaoContaFatoLocalComRaizDeRemarcacaoPendente() throws Exception {
        Paciente p = paciente();
        Usuario ad = usuario(UnidadeAtuacao.CAPS_AD, TipoUsuario.ADMINISTRADOR);
        vinculos.saveAndFlush(VinculoAssistencial.paraCaps(p, UnidadeAtuacao.CAPS_AD));

        Agendamento raizPendente = new Agendamento();
        raizPendente.setPaciente(p);
        raizPendente.setUsuario(ad);
        raizPendente.setDataAgendamento(LocalDate.of(2026, 12, 14));
        raizPendente.setHoraAtendimento(java.time.LocalTime.of(9, 30));
        raizPendente.registrarUnidadeDeOrigem(ad);
        raizPendente = agendamentos.saveAndFlush(raizPendente);

        Agendamento filho = agendamento(p, ad, LocalDate.of(2026, 12, 15), true);
        filho.setAgendamentoOriginal(raizPendente);
        agendamentos.saveAndFlush(filho);
        var inconsistente = historicoAgendamento(p, filho,
                "Nao expor historico com remarcacao clinica pendente");

        Agendamento correto = agendamento(p, ad, LocalDate.of(2026, 12, 16), true);
        var legitimo = historicoAgendamento(p, correto, "Historico local comprovado");

        var resposta = consultar(p, ad);
        assertEquals(200, resposta.getStatus());
        var dados = json.readTree(resposta.getContentAsString());
        assertEquals(1, dados.get("totalEventos").asInt());
        assertEquals(1, dados.get("totalConsultas").asInt());
        assertEquals(legitimo.getId(), dados.get("eventos").get(0).get("id").asLong());
        assertFalse(resposta.getContentAsString().contains(inconsistente.getDescricao()));
        assertTrue(agendamentos.findById(raizPendente.getId()).orElseThrow()
                .isMigracaoPendente());
        assertEquals("CAPS_AD", historicos.findById(inconsistente.getId())
                .orElseThrow().getUnidadeOrigemChave());
        assertEquals(9, pacientes.findById(p.getId()).orElseThrow().getCountFaltas());
    }

    @Test
    void outroCapsDeReferenciaNaoTemLeituraSemVinculoExplicito() throws Exception {
        Paciente p = paciente();
        Usuario ad = usuario(UnidadeAtuacao.CAPS_AD, TipoUsuario.ADMINISTRADOR);
        vinculos.saveAndFlush(VinculoAssistencial.paraCaps(p, UnidadeAtuacao.CAPS_II));
        evento(p, ad, TipoEventoHistoricoPaciente.BUSCA_ATIVA,
                "Evento sintetico de referencia sem vinculo", true);

        var response = consultar(p, ad);
        assertEquals(404, response.getStatus());
        assertFalse(response.getContentAsString().contains("Evento sintetico de referencia"));
    }

    @Test
    void origemDesconhecidaNaoEhAtribuidaAoVinculoNemExposta() throws Exception {
        Paciente p = paciente();
        Usuario ad = usuario(UnidadeAtuacao.CAPS_AD, TipoUsuario.ADMINISTRADOR);
        vinculos.saveAndFlush(VinculoAssistencial.paraCaps(p, UnidadeAtuacao.CAPS_AD));
        var desconhecido = evento(p, ad, TipoEventoHistoricoPaciente.BUSCA_ATIVA,
                "Nao atribuir esta origem desconhecida", false);

        var response = consultar(p, ad);
        assertEquals(200, response.getStatus());
        var payload = json.readTree(response.getContentAsString());
        assertEquals(0, payload.get("totalEventos").asInt());
        assertEquals(0, payload.get("eventos").size());
        assertEquals(1, historicos.findByPacienteIdPublicoOrderByOcorridoEmDesc(
                p.getIdPublico()).size());
        assertNull(historicos.findById(desconhecido.getId())
                .orElseThrow().getUnidadeOrigemChave());
    }

    @Test
    void rotaExigeAutenticacaoEProtegePacienteSemVinculoLocal() throws Exception {
        Paciente p = paciente();
        Usuario ii = usuario(UnidadeAtuacao.CAPS_II, TipoUsuario.RECEPCAO);
        vinculos.saveAndFlush(VinculoAssistencial.paraCaps(p, UnidadeAtuacao.CAPS_AD));
        assertEquals(401, consultar(p, null).getStatus());
        assertEquals(404, consultar(p, ii).getStatus());
        assertEquals(9, pacientes.findById(p.getId()).orElseThrow().getCountFaltas());
    }
}
