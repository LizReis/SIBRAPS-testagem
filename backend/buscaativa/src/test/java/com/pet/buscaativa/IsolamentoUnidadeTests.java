package com.pet.buscaativa;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

import java.io.ByteArrayInputStream;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import com.pet.buscaativa.entities.*;
import com.pet.buscaativa.entities.enums.*;
import com.pet.buscaativa.repositories.*;
import com.pet.buscaativa.services.TokenService;
import com.pet.buscaativa.services.PacienteService;
import com.pet.buscaativa.services.PoliticaClassificacaoPaciente;
import com.pet.buscaativa.entities.dto.PacienteDTO;
import com.pet.buscaativa.entities.dto.DisponibilidadeDTO;
import com.pet.buscaativa.entities.dto.DisponibilidadeExcecaoDTO;
import com.pet.buscaativa.entities.dto.BloqueioAgendaDTO;
import com.pet.buscaativa.entities.dto.CriarGrupoDTO;
import com.pet.buscaativa.entities.dto.AtualizarGrupoTerapeuticoDTO;
import com.pet.buscaativa.services.exceptions.RecursoDuplicadoException;
import com.pet.buscaativa.services.impl.SusApiAdapter;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Exercita o filtro JWT, controllers, services e consultas reais no H2. */
@SpringBootTest(properties = "logging.level.org.springframework.security=WARN")
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@Transactional
class IsolamentoUnidadeTests {
    @MockitoBean SusApiAdapter susApi;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired TokenService tokens;
    @Autowired PacienteService pacienteService;
    @Autowired PoliticaClassificacaoPaciente politica;
    @Autowired UsuarioRepository usuarios;
    @Autowired PacienteRepository pacientes;
    @Autowired UsfReferenciaRepository usfs;
    @Autowired AgendamentoRepository agendas;
    @Autowired GrupoTerapeuticoRepository grupos;
    @Autowired SessaoGrupoRepository sessoes;
    @Autowired SessaoGrupoParticipanteRepository participantes;

    Usuario ad, profissionalAd, recepcaoAd, capsI, usfA, usfB, legado;
    Usuario coordenadorAd, coordenadorI;
    Paciente pAd, pAdB, pI, pIA, pII;
    SessaoGrupo futura, realizada, estrangeira;
    Agendamento agendaAd, agendaI;

    @BeforeEach
    void preparar() {
        UsfReferencia a = usf("São Benedito");
        UsfReferencia b = usf("Andaiá");
        ad = usuario(UnidadeAtuacao.CAPS_AD, null, TipoUsuario.ADMINISTRADOR);
        profissionalAd = usuario(UnidadeAtuacao.CAPS_AD, null, TipoUsuario.PROFISSIONAL);
        recepcaoAd = usuario(UnidadeAtuacao.CAPS_AD, null, TipoUsuario.RECEPCAO);
        capsI = usuario(UnidadeAtuacao.CAPS_I, null, TipoUsuario.ADMINISTRADOR);
        usfA = usuario(UnidadeAtuacao.USF, a, TipoUsuario.ADMINISTRADOR);
        usfB = usuario(UnidadeAtuacao.USF, b, TipoUsuario.RECEPCAO);
        legado = usuario(UnidadeAtuacao.USF, null, TipoUsuario.ADMINISTRADOR);
        coordenadorAd = profissionalAd;
        coordenadorI = usuario(UnidadeAtuacao.CAPS_I, null, TipoUsuario.PROFISSIONAL);
        pAd = paciente("AD A", CapsEnum.CAPS_AD, a, 1);
        pAdB = paciente("AD B", CapsEnum.CAPS_AD, b, 2);
        pI = paciente("I B", CapsEnum.CAPS_I, b, 3);
        pIA = paciente("I A", CapsEnum.CAPS_I, a, 4);
        pII = paciente("II B", CapsEnum.CAPS_II, b, 5);
        // Uma referência de outra unidade nunca substitui capsReferencia/usfReferencia.
        pAd.setProfissionalReferencia(coordenadorI);
        pacientes.saveAndFlush(pAd);
        agendaAd = agenda(pAd, coordenadorAd, SituacaoAtendimento.AGENDADO, LocalDate.now());
        agendaI = agenda(pI, coordenadorI, SituacaoAtendimento.AGENDADO, LocalDate.now());
        agenda(pAdB, coordenadorAd, SituacaoAtendimento.FALTOU, LocalDate.now().minusDays(1));
        GrupoTerapeutico local = grupo(coordenadorAd, "Local");
        GrupoTerapeutico outro = grupo(coordenadorI, "Outra unidade");
        futura = sessao(local, LocalDate.now().plusDays(5), StatusSessaoGrupo.AGENDADA);
        realizada = sessao(local, LocalDate.now().minusDays(2), StatusSessaoGrupo.REALIZADA);
        estrangeira = sessao(outro, LocalDate.now().plusDays(5), StatusSessaoGrupo.AGENDADA);
        participante(futura, pAd, StatusPresencaGrupo.NAO_REGISTRADA);
        // Um grupo legado misto deve ocultar nomes e contagens de fora do escopo.
        participante(realizada, pAd, StatusPresencaGrupo.PRESENTE);
        participante(realizada, pI, StatusPresencaGrupo.PRESENTE);
        participante(estrangeira, pI, StatusPresencaGrupo.NAO_REGISTRADA);
    }

    private UsfReferencia usf(String nome) {
        var usf = new UsfReferencia();
        usf.setCnes(UUID.randomUUID().toString());
        usf.setNomeUsf("USF " + nome);
        return usfs.saveAndFlush(usf);
    }

    private Usuario usuario(UnidadeAtuacao unidade, UsfReferencia usf, TipoUsuario role) {
        var u = new Usuario();
        u.setNome("Isolamento usuário " + UUID.randomUUID());
        u.setEmail(UUID.randomUUID() + "@test.local");
        u.setSenha("senha-nao-utilizada-pelo-jwt");
        u.setTipoUsuario(role);
        u.setUnidadeAtuacao(unidade);
        u.setUsfAtuacao(usf);
        return usuarios.saveAndFlush(u);
    }

    private Paciente paciente(String nome, CapsEnum caps, UsfReferencia usf, int documento) {
        var p = new Paciente();
        p.setNome("Isolamento " + nome);
        p.setNomeMae("Mãe sintética");
        p.setDataNascimento(LocalDate.now().minusYears(30));
        p.setCpf("9000000000" + documento);
        p.setCns("90000000000000" + documento);
        p.setTelefone("00000000000");
        p.setTipoAcompanhamento(TipoAcompanhamento.INDIVIDUAL);
        p.setStatusPaciente(StatusPaciente.ATIVO);
        p.setClassificacaoRisco(ClassificacaoRisco.VERMELHO);
        p.setCountFaltas(3);
        p.setDataUltimaPresenca(LocalDate.now().minusDays(150));
        p.setCapsReferencia(caps);
        p.setUsfReferencia(usf);
        return pacientes.saveAndFlush(p);
    }

    private Agendamento agenda(Paciente p, Usuario u, SituacaoAtendimento status, LocalDate data) {
        var a = new Agendamento();
        a.setPaciente(p); a.setUsuario(u); a.setSituacaoAtendimento(status);
        a.setDataAgendamento(data); a.setHoraAtendimento(LocalTime.of(8, 0)); a.setTurnoAgendamento(TurnoEnum.MANHA);
        return agendas.saveAndFlush(a);
    }

    private GrupoTerapeutico grupo(Usuario u, String tema) {
        var g = new GrupoTerapeutico();
        g.setTema("Isolamento " + tema); g.setCoordenador(u);
        g.setRecorrencia(RecorrenciaGrupo.UNICA); g.setHorarioPadrao(LocalTime.of(9, 0));
        return grupos.saveAndFlush(g);
    }

    private SessaoGrupo sessao(GrupoTerapeutico g, LocalDate data, StatusSessaoGrupo status) {
        var s = new SessaoGrupo();
        s.setGrupo(g); s.setDataSessao(data); s.setHorario(LocalTime.of(9, 0)); s.setStatus(status);
        return sessoes.saveAndFlush(s);
    }

    private void participante(SessaoGrupo s, Paciente p, StatusPresencaGrupo status) {
        var item = new SessaoGrupoParticipante();
        item.setSessaoGrupo(s); item.setPaciente(p); item.setStatusPresenca(status);
        participantes.saveAndFlush(item);
        s.getParticipantes().add(item);
    }

    private MockHttpServletResponse getAs(Usuario usuario, String url) throws Exception {
        return mvc.perform(get(url).header("Authorization", "Bearer " + tokens.gerarToken(usuario)))
                .andReturn().getResponse();
    }

    private MockHttpServletResponse postAs(Usuario usuario, String url, String body) throws Exception {
        return mvc.perform(post(url).header("Authorization", "Bearer " + tokens.gerarToken(usuario))
                .contentType("application/json").content(body)).andReturn().getResponse();
    }

    private JsonNode ok(Usuario usuario, String url) throws Exception {
        var response = getAs(usuario, url);
        assertEquals(200, response.getStatus(), url);
        return json.readTree(response.getContentAsString());
    }

    private List<String> nomes(JsonNode lista) {
        return lista.valueStream().map(p -> p.get("nome").asString()).filter(n -> n.startsWith("Isolamento ")).sorted().toList();
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3})
    void unidadeDeterminaPacientesSemUsarProfissionalDeReferencia(int indice) throws Exception {
        Usuario usuario = List.of(ad, capsI, usfA, usfB).get(indice);
        List<List<String>> esperados = List.of(
                List.of(pAd.getNome(), pAdB.getNome()),
                List.of(pIA.getNome(), pI.getNome()),
                List.of(pAd.getNome(), pIA.getNome()),
                List.of(pAdB.getNome(), pI.getNome(), pII.getNome()));
        var resultado = ok(usuario, "/api/pacientes/pesquisa?q=Isolamento&size=50");
        assertEquals(esperados.get(indice).stream().sorted().toList(), nomes(resultado.get("pacientes")));
        for (Paciente p : List.of(pAd, pAdB, pI, pIA, pII)) {
            boolean permitido = esperados.get(indice).contains(p.getNome());
            assertEquals(permitido ? 200 : 404, getAs(usuario, "/api/pacientes/" + p.getIdPublico()).getStatus());
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2})
    void rolesNaoConcedemBypassGlobal(int indice) throws Exception {
        Usuario usuario = List.of(ad, profissionalAd, recepcaoAd).get(indice);
        assertEquals(List.of(pAd.getNome(), pAdB.getNome()).stream().sorted().toList(),
                nomes(ok(usuario, "/api/pacientes/pesquisa?q=Isolamento").get("pacientes")));
        assertEquals(404, getAs(usuario, "/api/pacientes/" + pI.getIdPublico()).getStatus());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "/detalhe", "/historico", "/agendamentos"})
    void uuidPermiteSomentePacientesDaUnidade(String sufixo) throws Exception {
        assertEquals(200, getAs(ad, "/api/pacientes/" + pAd.getIdPublico() + sufixo).getStatus());
        var fora = getAs(ad, "/api/pacientes/" + pI.getIdPublico() + sufixo);
        var ausente = getAs(ad, "/api/pacientes/" + UUID.randomUUID() + sufixo);
        assertEquals(404, fora.getStatus());
        assertEquals(404, ausente.getStatus());
        assertTrue(json.readTree(fora.getContentAsString()).get("message").asString()
                .startsWith("Resource not found. Id "));
        assertFalse(fora.getContentAsString().contains(pI.getNome()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"cpf", "cns"})
    void buscaDiretaPorDocumentoNaoRevelaExistencia(String campo) throws Exception {
        String proprio = campo.equals("cpf") ? pAd.getCpf() : pAd.getCns();
        String outro = campo.equals("cpf") ? pI.getCpf() : pI.getCns();
        assertEquals(pAd.getNome(), ok(ad, "/api/pacientes/busca/" + campo + "/" + proprio).get("nome").asString());
        var fora = getAs(ad, "/api/pacientes/busca/" + campo + "/" + outro);
        var ausente = getAs(ad, "/api/pacientes/busca/" + campo + "/111111111111111");
        assertEquals(404, fora.getStatus());
        assertEquals(404, ausente.getStatus());
        assertEquals(json.readTree(ausente.getContentAsString()).get("message"),
                json.readTree(fora.getContentAsString()).get("message"));
        assertEquals(0, ok(ad, "/api/pacientes/pesquisa?q=" + outro).get("totalRegistros").asInt());
        assertEquals(1, ok(ad, "/api/pacientes/pesquisa?q=" + proprio).get("totalRegistros").asInt());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/pacientes", "/api/pacientes/busca/nome?q=Isolamento", "/api/pacientes/busca-ativa"})
    void listasNaoRetornamDadosDeOutraUnidade(String url) throws Exception {
        var r = getAs(ad, url);
        assertEquals(200, r.getStatus());
        assertFalse(r.getContentAsString().contains(pI.getNome()));
        assertFalse(r.getContentAsString().contains(pII.getNome()));
        assertFalse(r.getContentAsString().contains(pIA.getNome()));
        assertTrue(r.getContentAsString().contains(pAd.getNome()));
    }

    @Test
    void resumoDaListaEInicioUsamApenasPacientesEAtendimentosAutorizados() throws Exception {
        long ativos = pacientes.findAll().stream().filter(p -> p.getStatusPaciente() == StatusPaciente.ATIVO
                && p.getCapsReferencia() == CapsEnum.CAPS_AD).count();
        long atencao = pacientes.findAll().stream().filter(p -> p.getStatusPaciente() == StatusPaciente.ATIVO
                && p.getCapsReferencia() == CapsEnum.CAPS_AD && p.getClassificacaoRisco() != ClassificacaoRisco.VERDE).count();
        var lista = ok(ad, "/api/pacientes/pesquisa?q=Isolamento").get("resumo");
        assertEquals(ativos, lista.get("pacientesAtivos").asLong());
        var permitidos = pacientes.findAll().stream().filter(p -> p.getStatusPaciente() == StatusPaciente.ATIVO
                && p.getCapsReferencia() == CapsEnum.CAPS_AD).toList();
        assertEquals(permitidos.stream().filter(p -> p.getClassificacaoRisco() == ClassificacaoRisco.AMARELO).count(), lista.get("emAtencao").asLong());
        assertEquals(permitidos.stream().filter(p -> p.getClassificacaoRisco() == ClassificacaoRisco.VERMELHO).count(), lista.get("buscaAtiva").asLong());
        assertEquals(permitidos.stream().filter(p -> politica.semPresencaRecente(p, LocalDate.now())).count(), lista.get("semPresencaRecente").asLong());
        var inicio = ok(ad, "/api/inicio/resumo");
        assertEquals(ativos, inicio.get("totalPacientesAtivos").asLong());
        assertEquals(atencao, inicio.get("totalPacientesAtencao").asLong());
        long hoje = agendas.findAll().stream().filter(a -> a.getPaciente().getCapsReferencia() == CapsEnum.CAPS_AD
                && a.getDataAgendamento().equals(LocalDate.now()) && a.getSituacaoAtendimento() == SituacaoAtendimento.AGENDADO).count();
        long faltas = agendas.findAll().stream().filter(a -> a.getPaciente().getCapsReferencia() == CapsEnum.CAPS_AD
                && !a.getDataAgendamento().isBefore(LocalDate.now().minusDays(6)) && !a.getDataAgendamento().isAfter(LocalDate.now())
                && a.getSituacaoAtendimento() == SituacaoAtendimento.FALTOU).count();
        assertEquals(hoje, inicio.get("totalAgendamentosHoje").asLong());
        assertEquals(faltas, inicio.get("totalFaltasUltimosSeteDias").asLong());
        assertFalse(inicio.toString().contains(pI.getNome()));
    }

    @Test
    void filtrosDePainelNuncaSubstituemAutorizacao() throws Exception {
        var painel = ok(ad, "/api/painel/resumo");
        assertEquals(1, painel.get("unidadesDisponiveis").size());
        assertEquals("CAPS:CAPS_AD", painel.get("unidadesDisponiveis").get(0).get("valor").asString());
        var outroCaps = ok(ad, "/api/painel/resumo?unidade=CAPS:CAPS_I");
        assertEquals(0, outroCaps.get("totalPacientesAtivos").asInt());
        var outraUsf = ok(usfA, "/api/painel/resumo?unidade=USF:" + usfB.getUsfAtuacao().getCnes());
        assertEquals(0, outraUsf.get("totalPacientesAtivos").asInt());
        assertEquals(0, ok(ad, "/api/pacientes/pesquisa?q=" + pI.getCpf() + "&unidade=CAPS_I").get("totalRegistros").asInt());
    }

    @Test
    void agendaRejeitaProfissionalExternoEFiltraPacientes() throws Exception {
        String url = "/api/agendamentos/agenda/" + LocalDate.now();
        var r = getAs(ad, url);
        assertEquals(200, r.getStatus());
        assertTrue(r.getContentAsString().contains(pAd.getNome()));
        assertFalse(r.getContentAsString().contains(pI.getNome()));
        assertEquals(404, getAs(ad, url + "?profissionalId=" + coordenadorI.getIdPublico()).getStatus());
        assertEquals(404, getAs(ad, "/api/agendamentos/" + agendaI.getId()).getStatus());
        assertEquals(404, getAs(ad, "/api/agendamentos/" + agendaI.getId() + "/remarcacao").getStatus());
        assertEquals(404, getAs(ad, "/api/agendamentos/vagas?usuarioId=" + coordenadorI.getIdPublico() + "&data=" + LocalDate.now()).getStatus());
        assertEquals(404, getAs(ad, "/api/disponibilidade-config/disponibilidade?usuarioId=" + coordenadorI.getIdPublico()).getStatus());
        var selecao = ok(ad, "/api/usuarios/profissionais-selecao");
        assertFalse(selecao.toString().contains(coordenadorI.getIdPublico().toString()));
        assertTrue(selecao.toString().contains(coordenadorAd.getIdPublico().toString()));
    }

    @Test
    void gruposRejeitamIdsExternosEFiltramParticipantesLegados() throws Exception {
        assertEquals(404, postAs(ad, "/api/grupos/sessoes/" + futura.getId() + "/participantes",
                "{\"pacienteId\":\"" + pI.getIdPublico() + "\"}").getStatus());
        assertEquals(404, getAs(ad, "/api/grupos/" + estrangeira.getGrupo().getId()).getStatus());
        assertEquals(404, postAs(ad, "/api/grupos/sessoes", "{\"grupoId\":" + estrangeira.getGrupo().getId()
                + ",\"dataSessao\":\"" + LocalDate.now().plusDays(10) + "\",\"horario\":\"09:00:00\",\"participantesIds\":[]}").getStatus());
        String url = "/api/grupos/sessoes?dataInicio=" + LocalDate.now().minusDays(3) + "&dataFim=" + LocalDate.now().plusDays(6);
        var lista = ok(ad, url);
        var atual = lista.valueStream().filter(s -> s.get("id").asLong() == realizada.getId()).findFirst().orElseThrow();
        assertEquals(1, atual.get("quantidadeParticipantes").asInt());
        assertEquals(1, atual.get("quantidadePresencasConfirmadas").asInt());
        assertFalse(lista.toString().contains(pI.getNome()));
        var membros = ok(ad, "/api/grupos/" + futura.getGrupo().getId() + "/participantes");
        assertFalse(membros.toString().contains(pI.getNome()));
        assertTrue(membros.toString().contains(pAd.getNome()));
    }

    @ParameterizedTest
    @CsvSource({"pdf,0", "excel,0", "pdf,1", "excel,1", "pdf,2", "excel,2"})
    void exportacoesMantemSomenteDadosAutorizados(String formato, int perfil) throws Exception {
        Usuario ator = List.of(ad, profissionalAd, recepcaoAd).get(perfil);
        for (String tipo : List.of("busca-ativa", "frequencia-grupos")) {
            var response = getAs(ator, "/api/relatorios/" + tipo + "/" + formato);
            assertEquals(200, response.getStatus());
            String texto = extrair(response.getContentAsByteArray(), formato).replaceAll("\\s+", " ");
            if (tipo.equals("busca-ativa") || formato.equals("excel")) {
                assertTrue(texto.contains(pAd.getNome()), tipo + "/" + formato + ": paciente autorizado ausente");
            } else {
                // O PDF de frequência existente exibe grupos e contagens, sem nomes de pacientes.
                assertTrue(texto.contains(realizada.getGrupo().getTema()));
                assertFalse(texto.contains(estrangeira.getGrupo().getTema()));
            }
            assertFalse(texto.contains(pI.getNome()));
            assertFalse(texto.contains(pII.getNome()));
        }
        assertEquals(404, getAs(ator, "/api/relatorios/busca-ativa/" + formato
                + "?profissionalId=" + coordenadorI.getIdPublico()).getStatus());
        assertEquals(404, getAs(ator, "/api/relatorios/frequencia-grupos/" + formato
                + "?grupoId=" + estrangeira.getGrupo().getId()).getStatus());
    }

    private String extrair(byte[] bytes, String formato) throws Exception {
        if (formato.equals("pdf")) {
            try (PDDocument documento = PDDocument.load(bytes)) {
                return new PDFTextStripper().getText(documento);
            }
        }
        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            StringBuilder texto = new StringBuilder();
            workbook.forEach(s -> s.forEach(r -> r.forEach(c -> texto.append(c.toString()).append('\n'))));
            return texto.toString();
        }
    }

    // Complementa a cobertura AD/I existente com CAPS II e as duas USFs específicas.
    private Usuario atorAdicional(int escopo) {
        return switch (escopo) {
            case 0 -> ad;
            case 1 -> usuario(UnidadeAtuacao.CAPS_II, null, TipoUsuario.ADMINISTRADOR);
            case 2 -> usfA;
            default -> usuario(UnidadeAtuacao.USF, usfB.getUsfAtuacao(), TipoUsuario.ADMINISTRADOR);
        };
    }

    private Usuario coordenadorExterno(int escopo) {
        return switch (escopo) {
            case 0 -> usuario(UnidadeAtuacao.CAPS_II, null, TipoUsuario.PROFISSIONAL);
            case 1 -> coordenadorAd;
            case 2 -> usuario(UnidadeAtuacao.USF, usfB.getUsfAtuacao(), TipoUsuario.PROFISSIONAL);
            default -> usuario(UnidadeAtuacao.USF, usfA.getUsfAtuacao(), TipoUsuario.PROFISSIONAL);
        };
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3})
    void gruposContinuamIsoladosParaCapsIIEUsfsDistintas(int escopo) throws Exception {
        Usuario ator = atorAdicional(escopo);
        Usuario proprio = usuario(ator.getUnidadeAtuacao(), ator.getUsfAtuacao(), TipoUsuario.PROFISSIONAL);
        var local = grupo(proprio, "Autorizado adicional");
        var outro = grupo(coordenadorExterno(escopo), "Externo adicional");
        var sessaoLocal = sessao(local, LocalDate.now().plusDays(1), StatusSessaoGrupo.AGENDADA);
        var sessaoExterna = sessao(outro, LocalDate.now().plusDays(1), StatusSessaoGrupo.AGENDADA);
        var ids = ok(ator, "/api/grupos").valueStream().map(g -> g.get("id").asLong()).toList();
        assertTrue(ids.contains(local.getId()));
        assertFalse(ids.contains(outro.getId()));
        assertEquals(200, getAs(ator, "/api/grupos/" + local.getId()).getStatus());
        assertEquals(404, getAs(ator, "/api/grupos/" + outro.getId()).getStatus());
        assertEquals(404, getAs(ator, "/api/grupos/" + outro.getId() + "/participantes").getStatus());
        String periodo = "/api/grupos/sessoes?dataInicio=" + LocalDate.now() + "&dataFim=" + LocalDate.now().plusDays(2);
        var sessoesVisiveis = ok(ator, periodo).valueStream().map(s -> s.get("id").asLong()).toList();
        assertTrue(sessoesVisiveis.contains(sessaoLocal.getId()));
        assertFalse(sessoesVisiveis.contains(sessaoExterna.getId()));
        assertEquals(404, postAs(ator, "/api/grupos/sessoes/" + sessaoExterna.getId()
                + "/confirmacao-ocorrencia", "{\"ocorreu\":false,\"motivoCancelamento\":\"Teste\"}").getStatus());
        assertEquals(StatusSessaoGrupo.AGENDADA, sessaoExterna.getStatus());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3})
    void agendaContinuaIsoladaParaCapsIIEUsfsDistintas(int escopo) throws Exception {
        Usuario ator = atorAdicional(escopo);
        Usuario proprio = usuario(ator.getUnidadeAtuacao(), ator.getUsfAtuacao(), TipoUsuario.PROFISSIONAL);
        Usuario externo = coordenadorExterno(escopo);
        Paciente permitido = List.of(pAd, pII, pIA, pI).get(escopo);
        Paciente proibido = List.of(pII, pAd, pI, pIA).get(escopo);
        var local = agenda(permitido, proprio, SituacaoAtendimento.AGENDADO, LocalDate.now());
        var outro = agenda(proibido, externo, SituacaoAtendimento.AGENDADO, LocalDate.now());
        String periodo = "/api/agendamentos/agenda?dataInicio=" + LocalDate.now() + "&dataFim=" + LocalDate.now();
        var ids = ok(ator, periodo).valueStream().map(a -> a.get("id").asLong()).toList();
        assertTrue(ids.contains(local.getId()));
        assertFalse(ids.contains(outro.getId()));
        assertEquals(404, getAs(ator, periodo + "&profissionalId=" + externo.getIdPublico()).getStatus());
        assertEquals(404, getAs(ator, "/api/agendamentos/" + outro.getId()).getStatus());
        assertEquals(404, getAs(ator, "/api/agendamentos/" + outro.getId() + "/remarcacao").getStatus());
        String parametros = "?usuarioId=" + externo.getIdPublico() + "&data=" + LocalDate.now();
        assertEquals(404, getAs(ator, "/api/agendamentos/vagas" + parametros).getStatus());
        assertEquals(404, getAs(ator, "/api/agendamentos/horarios-disponiveis" + parametros + "&turno=MANHA").getStatus());
        var alteracao = mvc.perform(patch("/api/agendamentos/" + outro.getId() + "/status")
                .param("novoStatus", "CANCELADO").header("Authorization", "Bearer " + tokens.gerarToken(ator)))
                .andReturn().getResponse();
        assertEquals(404, alteracao.getStatus());
        assertEquals(SituacaoAtendimento.AGENDADO, outro.getSituacaoAtendimento());
    }

    @Test
    void profissionalContinuaVendoSomenteSuaAgendaMesmoNaMesmaUnidade() throws Exception {
        Usuario colega = usuario(UnidadeAtuacao.CAPS_AD, null, TipoUsuario.PROFISSIONAL);
        var outraAgenda = agenda(pAdB, colega, SituacaoAtendimento.AGENDADO, LocalDate.now());
        String url = "/api/agendamentos/agenda/" + LocalDate.now();
        var lista = ok(profissionalAd, url);
        assertTrue(lista.valueStream().anyMatch(a -> a.get("id").asLong() == agendaAd.getId()));
        assertTrue(lista.valueStream().allMatch(a -> a.get("usuarioId").asString().equals(profissionalAd.getIdPublico().toString())));
        assertEquals(422, getAs(profissionalAd, url + "?profissionalId=" + colega.getIdPublico()).getStatus());
        assertEquals(422, getAs(profissionalAd, "/api/agendamentos/" + outraAgenda.getId()).getStatus());
        for (Usuario ator : List.of(ad, recepcaoAd)) {
            assertTrue(ok(ator, url).valueStream().anyMatch(a -> a.get("id").asLong() == outraAgenda.getId()));
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3})
    void relatoriosContinuamIsoladosParaCapsIIEUsfsDistintas(int escopo) throws Exception {
        Usuario ator = atorAdicional(escopo);
        Usuario proprio = usuario(ator.getUnidadeAtuacao(), ator.getUsfAtuacao(), TipoUsuario.PROFISSIONAL);
        Usuario externo = coordenadorExterno(escopo);
        Paciente permitido = List.of(pAd, pII, pIA, pI).get(escopo);
        Paciente proibido = List.of(pII, pAd, pI, pIA).get(escopo);
        var local = grupo(proprio, "Relatório autorizado");
        var outro = grupo(externo, "Relatório externo");
        participante(sessao(local, LocalDate.now().minusDays(2), StatusSessaoGrupo.REALIZADA), permitido, StatusPresencaGrupo.PRESENTE);
        participante(sessao(outro, LocalDate.now().minusDays(2), StatusSessaoGrupo.REALIZADA), proibido, StatusPresencaGrupo.PRESENTE);
        for (String formato : List.of("pdf", "excel")) {
            for (String tipo : List.of("busca-ativa", "frequencia-grupos")) {
                String url = "/api/relatorios/" + tipo + "/" + formato;
                var response = getAs(ator, url);
                assertEquals(200, response.getStatus());
                String texto = extrair(response.getContentAsByteArray(), formato).replaceAll("\\s+", " ");
                assertFalse(texto.contains(proibido.getNome()));
                assertFalse(texto.contains(outro.getTema()));
                if (tipo.equals("frequencia-grupos") && formato.equals("pdf")) {
                    assertTrue(texto.contains(local.getTema()));
                } else {
                    assertTrue(texto.contains(permitido.getNome()), url);
                }
            }
            assertEquals(404, getAs(ator, "/api/relatorios/busca-ativa/" + formato + "?profissionalId=" + externo.getIdPublico()).getStatus());
            assertEquals(404, getAs(ator, "/api/relatorios/frequencia-grupos/" + formato + "?grupoId=" + outro.getId()).getStatus());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/pacientes", "/api/pacientes/pesquisa", "/api/painel/resumo",
            "/api/inicio/resumo", "/api/grupos", "/api/usuarios", "/api/usuarios/profissionais-selecao", "/api/relatorios/busca-ativa/excel"})
    void usfLegadaSemAssociacaoFalhaFechada(String url) throws Exception {
        var r = getAs(legado, url);
        assertEquals(422, r.getStatus(), url);
        assertTrue(r.getContentAsString().contains("A unidade USF específica deste usuário ainda não foi configurada."));
        assertFalse(r.getContentAsString().contains(pAd.getNome()));
        assertEquals(200, getAs(legado, "/api/usuarios/me").getStatus());
    }

    @Test
    void edicaoAdministrativaNaoAssociaLegadoDeOutraUnidade() throws Exception {
        String body = json.writeValueAsString(java.util.Map.of(
                "nome", legado.getNome(), "email", legado.getEmail(), "tipoUsuario", "ADMINISTRADOR",
                "unidadeAtuacao", "USF", "usfAtuacaoId", usfA.getUsfAtuacao().getId()));
        var response = mvc.perform(put("/api/usuarios/" + legado.getIdPublico())
                .header("Authorization", "Bearer " + tokens.gerarToken(ad))
                .contentType("application/json").content(body)).andReturn().getResponse();
        assertEquals(404, response.getStatus());
        assertNull(usuarios.findByIdPublico(legado.getIdPublico()).orElseThrow().getUsfAtuacao());
        assertEquals(422, getAs(legado, "/api/pacientes/pesquisa").getStatus());
        assertEquals(200, getAs(legado, "/api/usuarios/me").getStatus());
        assertFalse(response.getContentAsString().contains("senha"));
    }

    @Test
    void agendamentoManualNaoAceitaPacienteDeOutraUnidade() throws Exception {
        String body = "{\"usuarioId\":\"" + coordenadorAd.getIdPublico() + "\",\"pacienteId\":\"" + pI.getIdPublico()
                + "\",\"dataAgendamento\":\"" + LocalDate.now().plusDays(2) + "\",\"turnoAgendamento\":\"MANHA\",\"horaAtendimento\":\"08:00:00\"}";
        assertEquals(404, postAs(ad, "/api/agendamentos", body).getStatus());
    }

    @Test
    void gruposNaoAlteramPacientesDeOutraUnidadeEmInscricaoOuConfirmacao() throws Exception {
        Long grupo = futura.getGrupo().getId();
        String paciente = "{\"pacienteId\":\"" + pI.getIdPublico() + "\"}";
        assertEquals(404, postAs(ad, "/api/grupos/" + grupo + "/inscricoes-futuras", paciente).getStatus());
        String retroativa = "{\"pacienteId\":\"" + pI.getIdPublico()
                + "\",\"frequenciasPassadas\":[{\"sessaoId\":" + realizada.getId() + ",\"statusPresenca\":\"PRESENTE\"}]}";
        assertEquals(404, postAs(ad, "/api/grupos/" + grupo + "/inscricoes-retroativas", retroativa).getStatus());
        participante(futura, pI, StatusPresencaGrupo.NAO_REGISTRADA);
        String cancelamento = "{\"ocorreu\":false,\"motivoCancelamento\":\"Teste de isolamento\"}";
        assertEquals(404, postAs(ad, "/api/grupos/sessoes/" + futura.getId() + "/confirmacao-ocorrencia", cancelamento).getStatus());
        assertEquals(StatusSessaoGrupo.AGENDADA, futura.getStatus());
        var remover = mvc.perform(delete("/api/grupos/sessoes/" + futura.getId() + "/participantes/" + pI.getIdPublico())
                .header("Authorization", "Bearer " + tokens.gerarToken(ad))).andReturn().getResponse();
        assertEquals(404, remover.getStatus());
        assertTrue(participantes.findBySessaoGrupoAndPaciente(futura, pI).isPresent());
    }

    @Test
    void filtrosPaginacaoEOrdenacaoExistentesContinuamDentroDoEscopo() throws Exception {
        var primeira = ok(ad, "/api/pacientes/pesquisa?q=Isolamento&size=1&page=0");
        var segunda = ok(ad, "/api/pacientes/pesquisa?q=Isolamento&size=1&page=1");
        assertEquals(2, primeira.get("totalRegistros").asInt());
        assertEquals(pAd.getNome(), primeira.get("pacientes").get(0).get("nome").asString());
        assertEquals(pAdB.getNome(), segunda.get("pacientes").get(0).get("nome").asString());
        pAdB.setClassificacaoRisco(ClassificacaoRisco.AMARELO);
        pAdB.setTipoAcompanhamento(TipoAcompanhamento.AMBOS);
        pacientes.saveAndFlush(pAdB);
        assertEquals(1, ok(ad, "/api/pacientes/pesquisa?q=Isolamento&classificacao=VERMELHO").get("totalRegistros").asInt());
        assertEquals(1, ok(ad, "/api/pacientes/pesquisa?q=Isolamento&tipoAcompanhamento=AMBOS").get("totalRegistros").asInt());
    }


    private JsonNode configurar(Usuario ator, String url, Object dto) throws Exception {
        var response = postAs(ator, url, json.writeValueAsString(dto));
        assertEquals(200, response.getStatus(), response.getContentAsString());
        return json.readTree(response.getContentAsString());
    }

    private void excluirComo(Usuario ator, String url, long id) throws Exception {
        var response = mvc.perform(delete(url + "/" + id)
                .header("Authorization", "Bearer " + tokens.gerarToken(ator))).andReturn().getResponse();
        assertEquals(204, response.getStatus(), response.getContentAsString());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2})
    void administradorEProfissionalPossuemAgendaEGestoresContinuamGerenciando(int alvoIndice) throws Exception {
        Usuario alvo = List.of(ad, profissionalAd,
                usuario(UnidadeAtuacao.CAPS_AD, null, TipoUsuario.ADMINISTRADOR)).get(alvoIndice);
        LocalDate data = LocalDate.now().plusDays(10);
        String disponibilidade = "/api/disponibilidade-config/disponibilidade";
        String bloqueio = "/api/bloqueioAgenda-config";
        String excecao = "/api/disponibilidade-config/excecoes";
        for (Usuario ator : List.of(ad, recepcaoAd, alvo)) {
            var d = configurar(ator, disponibilidade, new DisponibilidadeDTO(
                    null, alvo.getIdPublico(), data.getDayOfWeek(), TurnoEnum.MANHA, 2));
            long dId = d.get("id").asLong();
            assertEquals(alvo.getIdPublico().toString(), d.get("usuarioId").asString());
            assertEquals(3, configurar(ator, disponibilidade, new DisponibilidadeDTO(
                    dId, alvo.getIdPublico(), data.getDayOfWeek(), TurnoEnum.MANHA, 3)).get("capacidade").asInt());
            assertTrue(ok(ator, disponibilidade + "?usuarioId=" + alvo.getIdPublico()).valueStream()
                    .anyMatch(item -> item.get("id").asLong() == dId));
            var b = configurar(ator, bloqueio, new BloqueioAgendaDTO(
                    null, alvo.getIdPublico(), data, data, "Férias"));
            long bId = b.get("id").asLong();
            assertEquals("Curso", configurar(ator, bloqueio, new BloqueioAgendaDTO(
                    bId, alvo.getIdPublico(), data, data, "Curso")).get("motivoBloqueio").asString());
            excluirComo(ator, bloqueio, bId);
            var e = configurar(ator, excecao, new DisponibilidadeExcecaoDTO(
                    null, alvo.getIdPublico(), data, TurnoEnum.MANHA, 1));
            long eId = e.get("id").asLong();
            assertEquals(2, configurar(ator, excecao, new DisponibilidadeExcecaoDTO(
                    eId, alvo.getIdPublico(), data, TurnoEnum.MANHA, 2)).get("capacidade").asInt());
            excluirComo(ator, excecao, eId);
            excluirComo(ator, disponibilidade, dId);
        }
    }

    @Test
    void configuracaoDeAgendaRejeitaRecepcaoComoDonaEUsuariosExternos() throws Exception {
        LocalDate data = LocalDate.now().plusDays(10);
        for (Usuario alvo : List.of(recepcaoAd, capsI, coordenadorI)) {
            int esperado = alvo == recepcaoAd ? 422 : 404;
            for (String url : List.of("/api/disponibilidade-config/disponibilidade", "/api/bloqueioAgenda-config",
                    "/api/disponibilidade-config/excecoes")) {
                var dto = java.util.Map.of("usuarioId", alvo.getIdPublico(), "diaSemana", data.getDayOfWeek(),
                        "turno", "MANHA", "capacidade", 2, "data", data,
                        "dataInicio", data, "dataFim", data, "motivoBloqueio", "Teste");
                assertEquals(esperado, postAs(ad, url, json.writeValueAsString(dto)).getStatus(), url);
                assertEquals(esperado, getAs(ad, url + "?usuarioId=" + alvo.getIdPublico()).getStatus(), url);
            }
        }
    }

    private String novoAtendimento(Usuario alvo, Paciente paciente, LocalDate data, String hora) {
        return json.writeValueAsString(java.util.Map.of("usuarioId", alvo.getIdPublico(),
                "pacienteId", paciente.getIdPublico(), "dataAgendamento", data,
                "turnoAgendamento", "MANHA", "horaAtendimento", hora));
    }

    private JsonNode horarios(Usuario alvo, LocalDate data) throws Exception {
        return ok(ad, "/api/agendamentos/horarios-disponiveis?usuarioId=" + alvo.getIdPublico()
                + "&data=" + data + "&turno=MANHA");
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1})
    void administradorEProfissionalRecebemAtendimentosComConflitosECapacidadePreservados(int alvoIndice) throws Exception {
        Usuario alvo = List.of(ad, profissionalAd).get(alvoIndice);
        LocalDate data = LocalDate.now().plusDays(10);
        String disponibilidade = "/api/disponibilidade-config/disponibilidade";
        var d = configurar(ad, disponibilidade, new DisponibilidadeDTO(
                null, alvo.getIdPublico(), data.getDayOfWeek(), TurnoEnum.MANHA, 1));
        assertEquals(1, horarios(alvo, data).get("vagasRestantesTurno").asInt());
        var criado = postAs(ad, "/api/agendamentos", novoAtendimento(alvo, pAd, data, "08:00:00"));
        assertEquals(201, criado.getStatus(), criado.getContentAsString());
        assertEquals(alvo.getIdPublico().toString(), json.readTree(criado.getContentAsString()).get("usuarioId").asString());
        assertEquals(409, postAs(ad, "/api/agendamentos", novoAtendimento(alvo, pAdB, data, "08:00:00")).getStatus());
        assertEquals(409, postAs(ad, "/api/agendamentos", novoAtendimento(alvo, pAdB, data, "08:30:00")).getStatus());
        assertEquals("CAPACIDADE_ESGOTADA", horarios(alvo, data).get("motivoIndisponibilidade").asString());
        var bloqueio = postAs(ad, "/api/bloqueioAgenda-config", json.writeValueAsString(
                new BloqueioAgendaDTO(null, alvo.getIdPublico(), data, data, "Teste")));
        assertEquals(409, bloqueio.getStatus());
        var zero = postAs(ad, "/api/disponibilidade-config/excecoes", json.writeValueAsString(
                new DisponibilidadeExcecaoDTO(null, alvo.getIdPublico(), data, TurnoEnum.MANHA, 0)));
        assertEquals(409, zero.getStatus());
        assertEquals(409, mvc.perform(delete(disponibilidade + "/" + d.get("id").asLong())
                .header("Authorization", "Bearer " + tokens.gerarToken(ad))).andReturn().getResponse().getStatus());
    }

    @Test
    void administradorRespeitaBloqueiosExcecoesEPacienteDaUnidade() throws Exception {
        LocalDate data = LocalDate.now().plusDays(10);
        assertEquals("TURNO_NAO_CONFIGURADO", horarios(ad, data).get("motivoIndisponibilidade").asString());
        assertEquals(409, postAs(ad, "/api/agendamentos", novoAtendimento(ad, pAd, data, "08:00:00")).getStatus());
        configurar(ad, "/api/disponibilidade-config/excecoes", new DisponibilidadeExcecaoDTO(
                null, ad.getIdPublico(), data, TurnoEnum.MANHA, 2));
        var bloqueio = configurar(ad, "/api/bloqueioAgenda-config", new BloqueioAgendaDTO(
                null, ad.getIdPublico(), data, data, "Curso"));
        assertEquals("AGENDA_BLOQUEADA", horarios(ad, data).get("motivoIndisponibilidade").asString());
        assertEquals(409, postAs(ad, "/api/agendamentos", novoAtendimento(ad, pAd, data, "08:00:00")).getStatus());
        excluirComo(ad, "/api/bloqueioAgenda-config", bloqueio.get("id").asLong());
        assertEquals(2, horarios(ad, data).get("vagasRestantesTurno").asInt());
        assertEquals(404, postAs(ad, "/api/agendamentos", novoAtendimento(ad, pI, data, "08:00:00")).getStatus());
        assertEquals(201, postAs(ad, "/api/agendamentos", novoAtendimento(ad, pAd, data, "08:00:00")).getStatus());
        var restantes = horarios(ad, data);
        assertEquals(1, restantes.get("vagasRestantesTurno").asInt());
        assertFalse(restantes.get("horarios").valueStream().filter(h -> h.get("hora").asString().equals("08:00:00"))
                .findFirst().orElseThrow().get("disponivel").asBoolean());
    }

    @Test
    void recepcaoEUsuariosExternosNaoPodemReceberAtendimento() throws Exception {
        LocalDate data = LocalDate.now().plusDays(10);
        for (Usuario alvo : List.of(recepcaoAd, capsI, coordenadorI)) {
            int esperado = alvo == recepcaoAd ? 422 : 404;
            assertEquals(esperado, postAs(ad, "/api/agendamentos",
                    novoAtendimento(alvo, pAd, data, "08:00:00")).getStatus());
            assertEquals(esperado, getAs(ad, "/api/agendamentos/horarios-disponiveis?usuarioId="
                    + alvo.getIdPublico() + "&data=" + data + "&turno=MANHA").getStatus());
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3, 4})
    void coordenadorAdministradorOuProfissionalFuncionaSemAceitarRecepcaoOuExternos(int indice) throws Exception {
        Usuario coordenador = List.of(ad, profissionalAd, recepcaoAd, capsI, coordenadorI).get(indice);
        LocalDate data = LocalDate.now().plusDays(10);
        var dto = new CriarGrupoDTO("Grupo assistencial", coordenador.getIdPublico(),
                RecorrenciaGrupo.UNICA, data, null, LocalTime.of(9, 0), List.of());
        var criado = postAs(ad, "/api/grupos", json.writeValueAsString(dto));
        int esperado = indice < 2 ? 200 : indice == 2 ? 422 : 404;
        assertEquals(esperado, criado.getStatus(), criado.getContentAsString());
        var local = futura.getGrupo();
        var editado = mvc.perform(put("/api/grupos/" + local.getId())
                .header("Authorization", "Bearer " + tokens.gerarToken(ad)).contentType("application/json")
                .content(json.writeValueAsString(new AtualizarGrupoTerapeuticoDTO(
                        "Tema atualizado", coordenador.getIdPublico(), realizada.getDataSessao(),
                        realizada.getHorario(), local.getRecorrencia(), local.getDataFimRecorrencia(), local.getVersion()))))
                .andReturn().getResponse();
        assertEquals(esperado, editado.getStatus(), editado.getContentAsString());
        if (esperado == 200) {
            assertEquals(coordenador.getIdPublico().toString(),
                    json.readTree(criado.getContentAsString()).get("coordenadorId").asString());
            assertEquals(coordenador.getIdPublico().toString(),
                    json.readTree(editado.getContentAsString()).get("coordenadorId").asString());
        }
    }

    @Test
    void validacaoDeCpfDuplicadoContinuaAntesDaAtualizacao() {
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(ad, null, ad.getAuthorities()));
        try {
            var dados = json.convertValue(new PacienteDTO(pAd), java.util.Map.class);
            dados.put("countFaltas", null);
            dados.put("cpf", pAdB.getCpf());
            PacienteDTO dto = json.convertValue(dados, PacienteDTO.class);
            assertThrows(RecursoDuplicadoException.class, () -> pacienteService.save(dto, false));
        } finally {
            SecurityContextHolder.clearContext();
        }
    }
}
