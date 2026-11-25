package com.pet.buscaativa;

import static br.sibraps.ensaiohttp.EscritoresHttpIsolados.BANCO;
import static br.sibraps.ensaiohttp.EscritoresHttpIsolados.RAIZ;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import br.sibraps.ensaiohttp.EscritoresHttpIsolados;
import br.sibraps.modelovinculo.EscritoresVinculoController;
import com.pet.buscaativa.alertas.EmailService;
import com.pet.buscaativa.entities.DisponibilidadeExcecao;
import com.pet.buscaativa.entities.UsfReferencia;
import com.pet.buscaativa.entities.Usuario;
import com.pet.buscaativa.entities.enums.TipoUsuario;
import com.pet.buscaativa.entities.enums.TurnoEnum;
import com.pet.buscaativa.entities.enums.UnidadeAtuacao;
import com.pet.buscaativa.repositories.PacienteRepository;
import com.pet.buscaativa.repositories.VinculoAssistencialRepository;
import com.pet.buscaativa.services.impl.SusApiAdapter;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.scheduling.config.TaskManagementConfigUtils;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(properties = {"spring.datasource.url=" + BANCO + ";DB_CLOSE_DELAY=-1",
        "app.alertas.email.habilitado=false"})
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@ActiveProfiles({"ensaio-estado-local", "escritores-http-local", "relogio-coordenacao"})
@Import({EscritoresHttpIsolados.class, PersistenciaIndicadoresLocaisIntegrationTests.RelogioEnsaio.class})
class EscritoresLocaisHttpTests extends SecurityTestSupport {
    private static final LocalDate HOJE = LocalDate.of(2026, 11, 24);
    private static final String SENHA = "credencial exclusivamente sintetica";
    private static final String JUSTIFICATIVA = "Correção comprovada no ensaio sintético.";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired EntityManager em;
    @Autowired TransactionTemplate tx;
    @Autowired PasswordEncoder encoder;
    @Autowired PacienteRepository pacientes;
    @Autowired VinculoAssistencialRepository vinculos;
    @Autowired ApplicationContext contexto;
    @MockitoBean SusApiAdapter sus;
    @MockitoBean EmailService email;

    private UsfReferencia usfA;
    private UsfReferencia usfB;
    private Usuario profissional;
    private Usuario outroProfissional;
    private Usuario administracao;
    private Usuario recepcao;
    private Usuario profissionalB;
    private Usuario recepcaoB;

    @BeforeEach
    void prepararUnidades() {
        tx.executeWithoutResult(ignorado -> {
            usfA = usf();
            usfB = usf();
            profissional = usuario(TipoUsuario.PROFISSIONAL, usfA);
            outroProfissional = usuario(TipoUsuario.PROFISSIONAL, usfA);
            administracao = usuario(TipoUsuario.ADMINISTRADOR, usfA);
            recepcao = usuario(TipoUsuario.RECEPCAO, usfA);
            profissionalB = usuario(TipoUsuario.PROFISSIONAL, usfB);
            recepcaoB = usuario(TipoUsuario.RECEPCAO, usfB);
        });
    }

    @AfterEach
    void conferirQueNaoEnviouEmail() {
        PersistenciaIndicadoresLocaisIntegrationTests.RelogioHistorico.HISTORICO.remove();
        verifyNoInteractions(email, sus);
    }

    @Test
    void cadastraConfirmaEmOutraUsfEEditaSomenteDadosCompartilhados() throws Exception {
        String clinicoA = login(profissional);
        String operadorA = login(recepcao);
        String operadorB = login(recepcaoB);
        var dados = cadastro("52998224725", "700000000000005", "Primeira", usfA);
        enviar(post(RAIZ + "/pacientes"), null, dados).andExpect(status().isUnauthorized());
        enviar(post("/api/pacientes"), clinicoA, dados).andExpect(status().isNotFound());
        enviar(get("/api/pacientes/busca-ativa"), clinicoA, null).andExpect(status().isNotFound());
        var criada = criarCadastro(operadorA, dados);
        UUID pessoa = uuid(criada, "paciente");
        UUID localA = uuid(criada, "vinculo");
        var confirmacao = confirmacao(dados);
        var conferencia = Map.of("cpf", dados.get("cpf"), "cns", dados.get("cns"));
        enviar(post(RAIZ + "/pacientes/cadastro-unico/conferencia"), operadorB, conferencia)
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.nome").value(dados.get("nome")))
                .andExpect(jsonPath("$.idPublico").doesNotExist());
        enviar(get(RAIZ + "/pacientes/{paciente}/detalhe", pessoa), operadorB, null).andExpect(status().isNotFound());
        for (int i = 0; i < 2; i++) {
            enviar(post(RAIZ + "/pacientes/cadastro-unico/confirmar-vinculo"), operadorB, confirmacao)
                    .andExpect(status().isNoContent()).andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(content().string(""));
        }
        var global = pacientes.findByIdPublico(pessoa).orElseThrow();
        var locais = vinculos.findByPacienteId(global.getId());
        assertThat(locais).hasSize(2);
        var localB = locais.stream().filter(v -> !v.getIdPublico().equals(localA)).findFirst().orElseThrow();
        assertThat(localB.getTipoAcompanhamento().name()).isEqualTo("GRUPO_TERAPEUTICO");
        dados.put("telefone", "81999990002");
        dados.put("statusPaciente", "INATIVO");
        dados.remove("tipoAcompanhamento");
        enviar(put(caminho(localA, pessoa) + "/cadastro").param("versaoCadastro", "0"), operadorB, dados)
                .andExpect(status().isNotFound());
        enviar(put(caminho(localB.getIdPublico(), pessoa) + "/cadastro").param("versaoCadastro", "0"), operadorB, dados)
                .andExpect(status().isOk()).andExpect(jsonPath("$.versaoCadastro").value(1))
                .andExpect(jsonPath("$.versaoVinculo").value(localB.getVersion()));
        assertThat(pacientes.findByIdPublico(pessoa).orElseThrow().getTelefone()).isEqualTo("81999990002");
        enviar(get(RAIZ + "/pacientes/{paciente}/detalhe", pessoa), operadorB, null)
                .andExpect(status().isOk()).andExpect(jsonPath("$.paciente.vinculo").value(localB.getIdPublico().toString()))
                .andExpect(jsonPath("$.paciente.versaoCadastro").value(1))
                .andExpect(jsonPath("$.paciente.indicadores.tipoAcompanhamento").value("GRUPO_TERAPEUTICO"));
        dados.put("telefone", "81999990004");
        for (var ator : List.of(administracao, profissional)) {
            String token = login(ator);
            int versao = pacientes.findByIdPublico(pessoa).orElseThrow().getVersion();
            enviar(put(caminho(localA, pessoa) + "/cadastro").param("versaoCadastro", Integer.toString(versao)), token, dados)
                    .andExpect(status().isOk());
            enviar(patch(caminho(localB.getIdPublico(), pessoa) + "/acompanhamento").param("version", "0"),
                    operadorB, Map.of("tipoAcompanhamento", "AMBOS")).andExpect(status().isForbidden());
            dados.put("telefone", "81999990005");
        }
        assertClinicaGlobalAusente(pessoa);
        assertThat(vinculos.findByIdPublico(localA).orElseThrow().getVersion()).isEqualTo(criada.path("versaoVinculo").asInt());
        assertThat(vinculos.findByIdPublico(localB.getIdPublico()).orElseThrow().getVersion()).isEqualTo(localB.getVersion());

        var invalido = cadastro("11144477735", "800000000000001", "Rollback", usfA);
        invalido.put("profissionalReferenciaId", profissionalB.getIdPublico());
        enviar(post(RAIZ + "/pacientes"), clinicoA, invalido).andExpect(status().is4xxClientError());
        assertThat(pacientes.findByCpf("11144477735")).isEmpty();
        assertThat(contexto.containsBean(TaskManagementConfigUtils.SCHEDULED_ANNOTATION_PROCESSOR_BEAN_NAME)).isFalse();
        assertThat(contexto.containsBean("agendaMigratedIndicatorsInitializer")).isFalse();

        var adminB = tx.execute(ignorado -> usuario(TipoUsuario.ADMINISTRADOR, em.find(UsfReferencia.class, usfB.getId())));
        var perfisOrigem = List.of(profissional, administracao);
        var perfisDestino = List.of(profissionalB, adminB);
        var cpfs = List.of("41687237042", "87121863090");
        var cartoes = List.of("707000000000010", "907000000000013");
        for (int i = 0; i < perfisOrigem.size(); i++) {
            var novo = cadastro(cpfs.get(i), cartoes.get(i), "Papel " + i, usfA);
            var criado = criarCadastro(login(perfisOrigem.get(i)), novo);
            var tokenDestino = login(perfisDestino.get(i));
            enviar(post(RAIZ + "/pacientes/cadastro-unico/conferencia"), tokenDestino,
                    Map.of("cpf", novo.get("cpf"), "cns", novo.get("cns"))).andExpect(status().isOk());
            enviar(post(RAIZ + "/pacientes/cadastro-unico/confirmar-vinculo"), tokenDestino, confirmacao(novo))
                    .andExpect(status().isNoContent());
            enviar(get(RAIZ + "/pacientes/{paciente}/detalhe", uuid(criado, "paciente")), tokenDestino, null)
                    .andExpect(status().isOk()).andExpect(jsonPath("$.paciente.indicadores.tipoAcompanhamento")
                            .value("GRUPO_TERAPEUTICO"));
        }

    }

    @Test
    void frequenciaIndividualUsaJwtDaRecepcaoSomenteNoPrimeiroRegistroECorrecaoDoResponsavel() throws Exception {
        var dados = cadastro("12345678909", "900000000000008", "Agenda", usfA);
        var criada = criarCadastro(login(profissional), dados);
        UUID pessoa = uuid(criada, "paciente");
        String operador = login(recepcao);
        String clinico = login(profissional);
        tx.executeWithoutResult(ignorado -> {
            var oferta = new DisponibilidadeExcecao();
            oferta.setUsuario(em.find(Usuario.class, profissional.getId()));
            oferta.setData(HOJE);
            oferta.setTurno(TurnoEnum.MANHA);
            oferta.setCapacidade(10);
            em.persist(oferta);
        });
        var marcada = marcar(pessoa, operador, "09:00");
        String rota = RAIZ + "/agendamentos/" + marcada.path("id").asLong();
        var faltou = resposta(enviar(statusIndividual(rota, marcada, "FALTOU"), operador, null)
                .andExpect(status().isOk()).andExpect(jsonPath("$.frequenciaAssistencial").value("FALTOU")));
        enviar(statusIndividual(rota, faltou, "PRESENTE").param("justificativa", JUSTIFICATIVA), operador, null)
                .andExpect(status().isForbidden());
        var correcao = Map.of("novaFrequencia", "PRESENTE", "version", faltou.path("version").asInt(),
                "justificativa", JUSTIFICATIVA);
        enviar(patch(rota + "/frequencia/correcao"), login(outroProfissional), correcao).andExpect(status().isForbidden());
        var outraFrequencia = marcar(pessoa, operador, "09:30");
        var outraRotaFrequencia = RAIZ + "/agendamentos/" + outraFrequencia.path("id").asLong();
        String edicao = caminho(uuid(criada, "vinculo"), pessoa) + "/cadastro";
        dados.put("telefone", "81999990003");
        var pronto = new CountDownLatch(3);
        try (var executor = Executors.newFixedThreadPool(3)) {
            var ajuste = executor.submit(() -> concorrer(patch(rota + "/frequencia/correcao"), clinico, correcao, pronto));
            var frequencia = executor.submit(() -> concorrer(
                    statusIndividual(outraRotaFrequencia, outraFrequencia, "FALTOU"), operador, null, pronto));
            var cadastro = executor.submit(() -> concorrer(put(edicao).param("versaoCadastro", "0"), operador, dados, pronto));
            assertThat(List.of(ajuste.get(10, TimeUnit.SECONDS), frequencia.get(10, TimeUnit.SECONDS),
                    cadastro.get(10, TimeUnit.SECONDS))).containsExactly(200, 200, 200);
        }
        assertThat(pacientes.findByIdPublico(pessoa).orElseThrow().getTelefone()).isEqualTo("81999990003");
        enviar(patch(rota + "/frequencia/correcao"), clinico, correcao).andExpect(status().isUnprocessableContent());

        var outra = marcar(pessoa, operador, "10:00");
        String outraRota = RAIZ + "/agendamentos/" + outra.path("id").asLong();
        var cancelada = resposta(enviar(statusIndividual(outraRota, outra, "CANCELADO"), operador, null)
                .andExpect(status().isOk()));
        var regularizacao = Map.of("novaFrequencia", "FALTOU", "version", cancelada.path("version").asInt(),
                "justificativa", JUSTIFICATIVA);
        enviar(post(outraRota + "/regularizacao-cancelamento/frequencia"), clinico, regularizacao)
                .andExpect(status().isOk()).andExpect(jsonPath("$.frequenciaAssistencial").value("FALTOU"));
        var lista = get(RAIZ + "/agendamentos/agenda/{dia}", HOJE);
        enviar(lista, operador, null).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(3));
        enviar(get(RAIZ + "/agendamentos/agenda/{dia}", HOJE), login(profissionalB), null)
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        enviar(get(RAIZ + "/pacientes/{paciente}/prontuario", pessoa), clinico, null)
                .andExpect(status().isOk()).andExpect(jsonPath("$.faltasComprovadas").value(2))
                .andExpect(jsonPath("$.paciente.indicadores.statusPaciente").value("ATIVO"));
        enviar(get(RAIZ + "/painel/resumo"), clinico, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.distribuicaoClassificacao").exists());
        enviar(get(RAIZ + "/inicio/resumo"), clinico, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalFaltasUltimosSeteDias").value(2));
        var idVinculo = uuid(criada, "vinculo");
        tx.executeWithoutResult(ignorado -> vinculos.findByIdPublico(idVinculo).orElseThrow().sinalizarIndicadoresLocaisPendentes());
        var antes = vinculos.findByIdPublico(idVinculo).orElseThrow();
        String revisoes = caminho(idVinculo, pessoa) + "/revisoes";
        String versaoLocal = antes.getVersion().toString();
        String ticket = resposta(enviar(post(revisoes).param("version", versaoLocal), clinico,
                Map.of("tipo", "AGENDAMENTO", "registro", outraFrequencia.path("id").asLong(), "justificativa", JUSTIFICATIVA))
                .andExpect(status().isOk())).asString();
        String admin = login(administracao);
        enviar(get(RAIZ + "/relatorios/pendencias").param("estado", "PENDENTE"), admin, null)
                .andExpect(status().isOk()).andExpect(jsonPath("$.itens[0].ticket").value(ticket))
                .andExpect(jsonPath("$.itens[0].justificativaSolicitacao").value(JUSTIFICATIVA));
        enviar(post(revisoes + "/" + ticket + "/decisao").param("version", versaoLocal), admin,
                Map.of("decisao", "APROVAR", "justificativa", JUSTIFICATIVA)).andExpect(status().isOk());
        enviar(get(RAIZ + "/relatorios/pendencias").param("estado", "APROVADA"), admin, null)
                .andExpect(status().isOk()).andExpect(jsonPath("$.itens[0].justificativaDecisao").value(JUSTIFICATIVA));
        var apos = vinculos.findByIdPublico(idVinculo).orElseThrow();
        assertThat(apos.getVersion()).isEqualTo(antes.getVersion());
        assertThat(apos.getIndicadoresPendentes()).isTrue();
        assertThat(apos.getCountFaltas()).isEqualTo(antes.getCountFaltas());
        assertClinicaGlobalAusente(pessoa);
    }

    @Test
    void grupoEEstadoClinicoEscrevemApenasNoVinculoDaUnidade() throws Exception {
        String clinico = login(profissional);
        String admin = login(administracao);
        String externo = login(profissionalB);
        var dados = cadastro("98765432100", "700000000000013", "Grupo", usfA);
        var criada = criarCadastro(clinico, dados);
        UUID pessoa = uuid(criada, "paciente");
        UUID vinculo = uuid(criada, "vinculo");
        enviar(post(RAIZ + "/pacientes/cadastro-unico/confirmar-vinculo"), externo, confirmacao(dados))
                .andExpect(status().isNoContent());
        var externoAntes = vinculos.findByPacienteId(pacientes.findByIdPublico(pessoa).orElseThrow().getId())
                .stream().filter(local -> !local.getIdPublico().equals(vinculo)).findFirst().orElseThrow();
        var grupo = resposta(enviar(post(RAIZ + "/grupos"), login(recepcao), Map.of(
                "tema", "Oficina sintética", "coordenadorId", profissional.getIdPublico(),
                "recorrencia", "UNICA", "dataPrimeiraSessao", HOJE.plusDays(1),
                "horario", "09:00", "participantesIds", List.of(pessoa))).andExpect(status().isOk()));
        long grupoId = grupo.path("grupo").asLong();
        enviar(get(RAIZ + "/grupos/{id}", grupoId), clinico, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(grupoId));
        enviar(get(RAIZ + "/grupos/{id}", grupoId), externo, null).andExpect(status().isNotFound());
        String sessao = RAIZ + "/grupos/sessoes/" + grupo.path("sessoes").get(0).asLong();
        var inicial = resposta(enviar(patch(sessao + "/status").param("novoStatus", "AGENDADA"), clinico, null)
                .andExpect(status().isOk()));
        PersistenciaIndicadoresLocaisIntegrationTests.RelogioHistorico.HISTORICO
                .set(Instant.parse("2026-11-25T14:00:00Z"));
        var presenca = Map.of("ocorreu", true, "version", inicial.path("version").asInt(),
                "frequencias", List.of(Map.of("pacienteId", pessoa, "statusPresenca", "PRESENTE")));
        enviar(post(sessao + "/confirmacao-ocorrencia"), externo, presenca).andExpect(status().isNotFound());
        var ocorrida = resposta(enviar(post(sessao + "/confirmacao-ocorrencia"), clinico, presenca)
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REALIZADA")));
        enviar(patch(sessao + "/frequencias"), clinico, Map.of("version", ocorrida.path("version").asInt(),
                "frequencias", List.of(Map.of("pacienteId", pessoa, "statusPresenca", "FALTOU")),
                "justificativa", JUSTIFICATIVA))
                .andExpect(status().isOk()).andExpect(jsonPath("$.participantes[0].statusPresenca").value("FALTOU"));

        String local = caminho(vinculo, pessoa);
        String versao = vinculos.findByIdPublico(vinculo).orElseThrow().getVersion().toString();
        enviar(post(local + "/historico").param("version", versao), clinico,
                Map.of("tipo", "BUSCA_ATIVA", "descricao", "Contato sintético.")).andExpect(status().isNoContent());
        var encerramento = Map.of("motivo", "ALTA_TERAPEUTICA", "statusPaciente", "ALTA_TERAPEUTICA");
        var fechado = resposta(enviar(patch(local + "/encerrar").param("version", versao), clinico, encerramento)
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ALTA_TERAPEUTICA")));
        enviar(patch(local + "/reativar").param("version", fechado.path("versao").asString()), admin,
                Map.of("motivo", JUSTIFICATIVA)).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ATIVO"));
        var externoDepois = vinculos.findByIdPublico(externoAntes.getIdPublico()).orElseThrow();
        assertThat(externoDepois.getVersion()).isEqualTo(externoAntes.getVersion());
        assertThat(externoDepois.getStatusPaciente()).isEqualTo(externoAntes.getStatusPaciente());
        assertThat(externoDepois.getCountFaltas()).isEqualTo(externoAntes.getCountFaltas());
        assertThat(externoDepois.getClassificacaoRisco()).isEqualTo(externoAntes.getClassificacaoRisco());
        assertClinicaGlobalAusente(pessoa);
    }

    @Test
    void doisCadastrosSimultaneosComOsMesmosDocumentosGeramUmaIdentidade() throws Exception {
        var dados = cadastro("39053344705", "800000000000028", "Concorrência", usfA);
        String primeiro = login(profissional);
        String segundo = login(administracao);
        var pronto = new CountDownLatch(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var um = executor.submit(() -> concorrer(post(RAIZ + "/pacientes"), primeiro, dados, pronto));
            var dois = executor.submit(() -> concorrer(post(RAIZ + "/pacientes"), segundo, dados, pronto));
            assertThat(List.of(um.get(10, TimeUnit.SECONDS), dois.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(201, 409);
        }
        var identificada = pacientes.findByCpf("39053344705").orElseThrow();
        assertThat(vinculos.findByPacienteId(identificada.getId())).hasSize(1);
    }

    @Test
    void relatoriosExportamEAuditoriaDaListaPermaneceLocal() throws Exception {
        String admin = login(administracao);
        String operador = login(recepcao);
        var requisicao = UUID.randomUUID().toString();
        for (int i = 0; i < 2; i++) {
            enviar(get(RAIZ + "/pacientes/busca-ativa").param("requisicao", requisicao), operador, null)
                    .andExpect(status().isOk()).andExpect(jsonPath("$.selecaoIndeterminada").exists());
        }
        enviar(get(RAIZ + "/auditoria/busca-ativa"), admin, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));
        enviar(get(RAIZ + "/auditoria/busca-ativa"), operador, null).andExpect(status().isForbidden());
        enviar(get(RAIZ + "/relatorios/pendencias"), admin, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.pendentes").value(0));
        enviar(get(RAIZ + "/relatorios/pendencias"), operador, null).andExpect(status().isForbidden());
        enviar(get(RAIZ + "/relatorios/busca-ativa/pdf"), operador, null).andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_PDF));
        enviar(get(RAIZ + "/relatorios/frequencia-grupos/excel"), admin, null).andExpect(status().isOk())
                .andExpect(content().contentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));
    }

    @Test
    void montagemIncompletaOuComDevNaoPublicaRotasEBancoPersistenteFalhaAntesDaConexao() {
        var runner = new ApplicationContextRunner().withUserConfiguration(EscritoresHttpIsolados.class);
        runner.withPropertyValues("spring.profiles.active=test,ensaio-estado-local")
                .run(app -> assertThat(app).doesNotHaveBean(EscritoresVinculoController.Cadastro.class));
        runner.withPropertyValues("spring.profiles.active=test,ensaio-estado-local,escritores-http-local,dev")
                .run(app -> assertThat(app).doesNotHaveBean(EscritoresVinculoController.Cadastro.class));
        runner.withPropertyValues("spring.profiles.active=test,ensaio-estado-local,escritores-http-local",
                "spring.datasource.url=jdbc:h2:file:invalido")
                .run(app -> assertThat(app.getStartupFailure()).hasMessageContaining("banco H2 em memória"));
    }

    private int concorrer(MockHttpServletRequestBuilder req, String token, Object dados, CountDownLatch pronto)
            throws Exception {
        pronto.countDown();
        assertThat(pronto.await(5, TimeUnit.SECONDS)).isTrue();
        return enviar(req, token, dados).andReturn().getResponse().getStatus();
    }

    private UsfReferencia usf() {
        var usf = new UsfReferencia();
        usf.setNomeUsf("USF sintética");
        usf.setCnes(UUID.randomUUID().toString());
        em.persist(usf);
        return usf;
    }

    private Usuario usuario(TipoUsuario perfil, UsfReferencia usf) {
        var ator = new Usuario();
        ator.setNome("Ator sintético");
        ator.setEmail(UUID.randomUUID() + "@test.local");
        ator.setSenha(encoder.encode(SENHA));
        ator.setTipoUsuario(perfil);
        ator.setUnidadeAtuacao(UnidadeAtuacao.USF);
        ator.setUsfAtuacao(usf);
        em.persist(ator);
        return ator;
    }

    private static Map<String, Object> cadastro(String cpf, String cns, String nome, UsfReferencia usf) {
        return new java.util.HashMap<>(Map.ofEntries(Map.entry("cpf", cpf), Map.entry("cns", cns),
                Map.entry("nome", nome + " Pessoa Sintética"), Map.entry("nomeMae", nome + " Responsável Sintética"),
                Map.entry("dataNascimento", "1984-12-05"), Map.entry("sexo", "FEMININO"), Map.entry("racacor", "PARDA"),
                Map.entry("telefone", "81999990001"), Map.entry("situacaoRua", true),
                Map.entry("tipoAcompanhamento", "AMBOS"), Map.entry("usfReferencia", Map.of("id", usf.getId())),
                Map.entry("capsReferencia", "CAPS_AD")));
    }

    private static Map<String, Object> confirmacao(Map<String, Object> dados) {
        return Map.of("cpf", dados.get("cpf"), "cns", dados.get("cns"), "nome", dados.get("nome"),
                "nomeMae", dados.get("nomeMae"), "dataNascimento", dados.get("dataNascimento"),
                "tipoAcompanhamento", "GRUPO_TERAPEUTICO");
    }

    private JsonNode criarCadastro(String token, Map<String, Object> dados) throws Exception {
        return resposta(enviar(post(RAIZ + "/pacientes").param("ignorarSimilaridade", "true"), token, dados)
                .andExpect(status().isCreated()));
    }

    private JsonNode marcar(UUID pessoa, String token, String hora) throws Exception {
        return resposta(enviar(post(RAIZ + "/agendamentos"), token, Map.of("pacienteId", pessoa,
                "usuarioId", profissional.getIdPublico(), "dataAgendamento", HOJE, "horaAtendimento", hora,
                "turnoAgendamento", "MANHA")).andExpect(status().isCreated()));
    }

    private static MockHttpServletRequestBuilder statusIndividual(String rota, JsonNode agenda, String status) {
        return patch(rota + "/status").param("version", agenda.path("version").asString()).param("novoStatus", status);
    }

    private void assertClinicaGlobalAusente(UUID pessoa) {
        var global = pacientes.findByIdPublico(pessoa).orElseThrow();
        assertThat(global.getStatusPaciente()).isNull();
        assertThat(global.getTipoAcompanhamento()).isNull();
        assertThat(global.getClassificacaoRisco()).isNull();
        assertThat(global.getIndicadoresPendentes()).isNull();
        assertThat(global.getGatilhoVisitaAcionado()).isNull();
        assertThat(global.getProfissionalReferencia()).isNull();
        assertThat(global.getDataUltimaPresenca()).isNull();
        assertThat(global.getCountFaltas()).isZero();
    }

    private String login(Usuario ator) throws Exception {
        return resposta(enviar(post("/api/auth/login"), null, Map.of("email", ator.getEmail(), "senha", SENHA))
                .andExpect(status().isOk())).path("token").asString();
    }

    private ResultActions enviar(MockHttpServletRequestBuilder req, String token, Object body) throws Exception {
        if (body != null) req.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        if (token != null) req.header("Authorization", "Bearer " + token);
        return mvc.perform(req);
    }

    private JsonNode resposta(ResultActions envio) throws Exception {
        return json.readTree(envio.andReturn().getResponse().getContentAsByteArray());
    }

    private static UUID uuid(JsonNode resposta, String campo) {
        return UUID.fromString(resposta.path(campo).asString());
    }

    private static String caminho(UUID vinculo, UUID pessoa) {
        return RAIZ + "/pacientes/" + pessoa + "/vinculos/" + vinculo;
    }
}
