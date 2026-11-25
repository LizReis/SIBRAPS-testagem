package com.pet.buscaativa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import com.pet.buscaativa.migration.AgendaMigrationTool;
import com.pet.buscaativa.services.PacienteService;
import com.pet.buscaativa.services.impl.MonitoramentoVinculosEnsaioService;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class ModeloVinculoOperacionalTests {
    private static final LocalDate HOJE = LocalDate.of(2026, 11, 24);

    @TestConfiguration(proxyBeanMethods = false)
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
            name = "sibraps.test.relogio-operacional", havingValue = "true")
    static class Relogio {
        @Bean @Primary Clock clockOperacionalSintetico(
                @org.springframework.beans.factory.annotation.Value("${sibraps.test.instante:2026-11-24T22:00:00Z}") String instante) {
            return Clock.fixed(Instant.parse(instante), ZoneId.of("America/Bahia"));
        }
    }

    @TempDir Path temp;
    private final String segredo = Base64.getEncoder().encodeToString(SecurityTestSupport.randomBytes());
    private final String senha = UUID.randomUUID().toString();
    private final HttpClient cliente = HttpClient.newHttpClient();

    @Test
    void inicializacaoRecusaV16EProtecaoV17SemRestricao() throws Exception {
        var banco = LegacyMigrationFixture.legacy(temp.resolve("incompleto"));
        AgendaMigrationTool.migrar(banco);
        var antes = LegacyMigrationFixture.columns(banco);
        assertThatThrownBy(() -> iniciar(banco)).hasRootCauseMessage("Coluna documental calculada ausente.");
        assertThat(LegacyMigrationFixture.columns(banco)).isEqualTo(antes);
        AgendaMigrationTool.migrarIdentidade(banco);
        var sql = new JdbcTemplate(banco);
        sql.execute("alter table tb_paciente drop constraint uk_paciente_cpf_normalizado");
        assertThatThrownBy(() -> iniciar(banco)).hasRootCauseMessage("Restrição de unicidade documental ausente.");
    }

    @Test
    void instalacaoPadraoPublicaSomenteVinculoECoordenaDuasInstancias() throws Exception {
        var banco = LegacyMigrationFixture.legacy(temp.resolve("operacional"));
        var sql = new JdbcTemplate(banco);
        sql.update("update tb_paciente set cpf=?, cns=? where id=1", "529.982.247-25", "70000 0000000005");
        AgendaMigrationTool.migrar(banco);
        AgendaMigrationTool.migrarIdentidade(banco);
        long unidadeA = usf(sql, "A");
        long unidadeB = usf(sql, "B");
        String recepcao = ator(sql, unidadeA, 3, "recepcao");
        String profissional = ator(sql, unidadeA, 2, "profissional");
        String administrador = ator(sql, unidadeB, 1, "administrador");

        try (var primeira = iniciar(banco); var segunda = iniciar(banco)) {
            for (String bean : List.of("pacienteController", "agendamentoController", "grupoTerapeuticoController",
                    "agendaMigratedIndicatorsInitializer", "monitoramentoBuscaAtiva", "alertaPacientesVermelhosScheduler",
                    "alertaPacientesVermelhosService", "emailService"))
                assertThat(primeira.containsBean(bean)).as(bean).isFalse();
            assertThat(primeira.getBean(MonitoramentoVinculosEnsaioService.class)).isNotNull();
            assertThat(primeira.containsBean("monitoramentoVinculoAgendado")).isTrue();
            var tarefas = primeira.getBean(org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor.class)
                    .getScheduledTasks();
            var tarefa = tarefas.stream().filter(t -> t.toString().contains("MonitoramentoVinculoAgendado"))
                    .findFirst().orElseThrow();
            tarefa.getTask().getRunnable().run();
            assertThatThrownBy(() -> primeira.getBean(PacienteService.class).listarPacientesEmBuscaAtiva())
                    .isInstanceOf(IllegalStateException.class).hasMessage("Operação clínica global desativada.");

            var tokenRecepcao = login(primeira, recepcao);
            var tokenProfissional = login(segunda, profissional);
            var tokenAdmin = login(segunda, administrador);
            assertThat(http(primeira, "GET", "/api/pacientes/pesquisa", null, null).statusCode()).isEqualTo(401);
            assertThat(http(primeira, "GET", "/api/ensaio-escritores/pacientes/pesquisa", tokenRecepcao, null)
                    .statusCode()).isEqualTo(404);
            var antiga = http(primeira, "POST", "/api/pacientes/cadastro-unico/conferencia", tokenRecepcao,
                    Map.of("cpf", "52998224725", "cns", "700000000000005"));
            assertThat(antiga.statusCode()).isEqualTo(200);
            assertThat(json(primeira, antiga).has("idPublico")).isFalse();

            Map<String, Object> dados = cadastro("11144477735", "800000000000001", "Concorrência", unidadeA);
            var iniciar = new CountDownLatch(1);
            HttpResponse<String> um;
            HttpResponse<String> dois;
            try (var executor = Executors.newFixedThreadPool(2)) {
                var pedidoA = executor.submit(() -> concorrer(primeira, tokenRecepcao, dados, iniciar));
                var pedidoB = executor.submit(() -> concorrer(segunda, tokenProfissional, dados, iniciar));
                iniciar.countDown();
                um = pedidoA.get(15, TimeUnit.SECONDS);
                dois = pedidoB.get(15, TimeUnit.SECONDS);
            }
            assertThat(List.of(um.statusCode(), dois.statusCode())).containsExactlyInAnyOrder(201, 409);
            var conflito = um.statusCode() == 409 ? um : dois;
            assertThat(conflito.body()).doesNotContain("11144477735", "800000000000001", "TB_PACIENTE", "Concorrência");
            var novo = json(primeira, um.statusCode() == 201 ? um : dois);
            var pessoa = novo.path("paciente").asString();
            var localA = novo.path("vinculo").asString();
            assertThat(http(segunda, "GET", "/api/pacientes/" + pessoa, tokenAdmin, null).statusCode()).isEqualTo(404);
            var confirmacao = Map.of("cpf", dados.get("cpf"), "cns", dados.get("cns"), "nome", dados.get("nome"),
                    "nomeMae", dados.get("nomeMae"), "dataNascimento", dados.get("dataNascimento"),
                    "tipoAcompanhamento", "GRUPO_TERAPEUTICO");
            assertThat(http(segunda, "POST", "/api/pacientes/cadastro-unico/confirmar-vinculo", tokenAdmin, confirmacao)
                    .statusCode()).isEqualTo(204);

            var detalheB = http(segunda, "GET", "/api/pacientes/" + pessoa + "/detalhe", tokenAdmin, null);
            assertThat(detalheB.statusCode()).isEqualTo(200);
            var localB = json(segunda, detalheB).path("paciente").path("vinculo").asString();
            assertThat(localB).isNotEqualTo(localA);
            var edicao = new java.util.HashMap<>(dados);
            edicao.put("telefone", "81900000002");
            assertThat(http(segunda, "PUT", "/api/pacientes/" + pessoa + "/vinculos/" + localA
                    + "/cadastro?versaoCadastro=0", tokenProfissional, edicao).statusCode()).isEqualTo(200);
            sql.update("update tb_paciente set cpf=? where id_publico=?", "111.444.777-35", UUID.fromString(pessoa));
            var pesquisa = http(segunda, "GET", "/api/pacientes/pesquisa?q=11144477735", tokenAdmin, null);
            assertThat(pesquisa.statusCode()).isEqualTo(200);
            assertThat(json(segunda, pesquisa).path("pacientes").get(0).path("vinculo").asString()).isEqualTo(localB);
            assertThat(sql.queryForObject("select count(*) from tb_paciente where cpf_normalizado=?", Long.class, dados.get("cpf")))
                    .isEqualTo(1);
            assertThat(sql.queryForObject("select status_paciente from tb_paciente where id_publico=?", Integer.class, UUID.fromString(pessoa)))
                    .isNull();
            validarEdicaoConcorrente(primeira, segunda, tokenRecepcao, tokenProfissional, dados, unidadeA, pessoa, localA);
            validarFluxoAssistencial(primeira, tokenRecepcao, tokenProfissional, tokenAdmin, profissional, sql, pessoa);
            validarReservaRf26EntreInstancias(primeira, segunda, sql, profissional, unidadeA);
            assertThat(sql.queryForObject("select status_paciente from tb_paciente where id_publico=?", Integer.class, UUID.fromString(pessoa)))
                    .isNull();
        }
    }

    private void validarEdicaoConcorrente(ConfigurableApplicationContext a, ConfigurableApplicationContext b,
            String tokenA, String tokenB, Map<String, Object> dados, long unidade, String pessoa, String vinculo) throws Exception {
        var outroCadastro = cadastro("87121863090", "907000000000013", "Edição", unidade);
        var resp = http(b, "POST", "/api/pacientes?ignorarSimilaridade=true", tokenB, outroCadastro);
        assertThat(resp.statusCode()).isEqualTo(201);
        var outro = json(b, resp);
        var editado = new java.util.HashMap<>(dados);
        var outroEditado = new java.util.HashMap<>(outroCadastro);
        editado.put("cpf", "41687237042");
        outroEditado.put("cpf", "41687237042");
        var inicio = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var reqA = executor.submit(() -> {
                if (!inicio.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Largada não sinalizada.");
                return http(a, "PUT", rotaEdicao(pessoa, vinculo, 1), tokenA, editado);
            });
            var reqB = executor.submit(() -> {
                if (!inicio.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Largada não sinalizada.");
                return http(b, "PUT", rotaEdicao(outro.path("paciente").asString(), outro.path("vinculo").asString(), 0),
                        tokenB, outroEditado);
            });
            inicio.countDown();
            assertThat(List.of(reqA.get(15, TimeUnit.SECONDS).statusCode(), reqB.get(15, TimeUnit.SECONDS).statusCode()))
                    .containsExactlyInAnyOrder(200, 409);
        }
    }

    private void validarFluxoAssistencial(ConfigurableApplicationContext app, String recepcao, String profissional,
            String administrador, String email, JdbcTemplate sql, String pessoa) throws Exception {
        UUID responsavel = sql.queryForObject("select id_publico from tb_usuario where email=?", UUID.class, email);
        sql.update("""
                insert into tb_disponibilidade_excecao(usuario_id,data,turno,capacidade)
                select id,?,1,10 from tb_usuario where email=?
                """, HOJE, email);
        var agenda = http(app, "POST", "/api/agendamentos", recepcao, Map.of("pacienteId", pessoa,
                "usuarioId", responsavel, "dataAgendamento", HOJE, "turnoAgendamento", "MANHA", "horaAtendimento", "09:00"));
        assertThat(agenda.statusCode()).isEqualTo(201);
        var registro = json(app, agenda);
        String atendimento = "/api/agendamentos/" + registro.path("id").asLong();
        var falta = http(app, "PATCH", atendimento + "/status?novoStatus=FALTOU&version="
                + registro.path("version").asInt(), profissional, null);
        assertThat(falta.statusCode()).isEqualTo(200);
        var correcao = http(app, "PATCH", atendimento + "/frequencia/correcao", profissional,
                Map.of("novaFrequencia", "PRESENTE", "version", json(app, falta).path("version").asInt(),
                        "justificativa", "Retificação sintética após conferência."));
        assertThat(correcao.statusCode()).isEqualTo(200);
        var prontuario = http(app, "GET", "/api/pacientes/" + pessoa + "/prontuario", recepcao, null);
        assertThat(json(app, prontuario).path("presencasComprovadas").asLong()).isEqualTo(1);
        var grupo = http(app, "POST", "/api/grupos", profissional, Map.of("tema", "Oficina sintética",
                "coordenadorId", responsavel, "recorrencia", "UNICA", "dataPrimeiraSessao", HOJE.plusDays(1),
                "horario", "14:00", "participantesIds", List.of(pessoa)));
        assertThat(grupo.statusCode()).isEqualTo(200);
        assertThat(json(app, grupo).path("sessoes").size()).isEqualTo(1);
        assertThat(http(app, "GET", "/api/relatorios/pendencias", administrador, null).statusCode()).isEqualTo(200);
        assertThat(http(app, "GET", "/api/pacientes/busca-ativa?requisicao=" + UUID.randomUUID(), recepcao, null)
                .statusCode()).isEqualTo(200);
    }

    private void validarReservaRf26EntreInstancias(ConfigurableApplicationContext a, ConfigurableApplicationContext b,
            JdbcTemplate sql, String profissional, long unidade) throws Exception {
        var um = a.getBean(com.pet.buscaativa.services.impl.ControleNotificacoesEnsaioService.class);
        var dois = b.getBean(com.pet.buscaativa.services.impl.ControleNotificacoesEnsaioService.class);
        UUID id = sql.queryForObject("select id_publico from tb_usuario where email=?", UUID.class, profissional);
        var chave = new com.pet.buscaativa.services.impl.NotificacoesLocaisEnsaioService.Chave(HOJE, id, "USF:" + unidade);
        var entrou = new CountDownLatch(1);
        var fechar = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var envio = executor.submit(() -> um.executar(chave, entrega -> {
                assertThat(entrega.chave()).isEqualTo(chave);
                assertThat(entrega.email()).isEqualTo(profissional);
                entrou.countDown();
                try {
                    if (!fechar.await(10, TimeUnit.SECONDS)) throw new AssertionError("Simulador não liberado");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(e);
                }
            }));
            try {
                assertThat(entrou.await(10, TimeUnit.SECONDS)).isTrue();
                assertThat(executor.submit(() -> dois.executar(chave, e -> { throw new AssertionError("Duplicata"); }))
                        .get(10, TimeUnit.SECONDS).name()).isEqualTo("DUPLICADA");
            } finally {
                fechar.countDown();
            }
            assertThat(envio.get(10, TimeUnit.SECONDS).name()).isEqualTo("SIMULADA");
        }
    }

    private static String rotaEdicao(String pessoa, String vinculo, int versao) {
        return "/api/pacientes/" + pessoa + "/vinculos/" + vinculo + "/cadastro?versaoCadastro=" + versao;
    }

    @Test
    void marcoComMesmoLimiteERevisaoRf26SobrevivemAReinicioERestauracao() throws Exception {
        var banco = LegacyMigrationFixture.legacy(temp.resolve("transicao"));
        AgendaMigrationTool.migrar(banco);
        AgendaMigrationTool.migrarIdentidade(banco);
        assertThat(AgendaMigrationTool.migrarTransicao(banco).migrationsExecuted).isEqualTo(1);
        assertThat(AgendaMigrationTool.migrarTransicao(banco).migrationsExecuted).isZero();
        var sql = new JdbcTemplate(banco);
        long unidadeA = usf(sql, "Histórico A");
        long unidadeB = usf(sql, "Histórico B");
        String adminA = ator(sql, unidadeA, 1, "transicao-a");
        String adminB = ator(sql, unidadeB, 1, "transicao-b");
        String profissional = ator(sql, unidadeA, 2, "transicao-prof");
        UUID destinatario = sql.queryForObject("select id_publico from tb_usuario where email=?", UUID.class, profissional);
        String sha = "a".repeat(64);
        var manifesto = Map.of("vinculos", List.of(), "evidenciaSha256", sha);
        String marco = "/api/painel/historico/marco";
        String fechamento = "/api/painel/historico/fechamentos/";
        String painel = "/api/painel/resumo?periodoMeses=3";
        LocalDate dia = LocalDate.of(2026, 12, 1);
        String incidente = "/api/auditoria/rf26/ocorrencias/" + dia + "/" + destinatario + "/encerrar";
        String limite = "2026-12-01T03:00:00Z";

        try (var app = iniciar(banco, limite)) {
            var a = login(app, adminA);
            var p = login(app, profissional);
            assertThat(http(app, "POST", marco, p, null).statusCode()).isEqualTo(403);
            var registro = http(app, "POST", marco, a, null);
            assertThat(registro.statusCode()).isEqualTo(200);
            assertThat(json(app, registro).path("iniciadoEm").asString()).isEqualTo(limite);
            assertThat(http(app, "PUT", fechamento + "2026-11", a, manifesto).statusCode()).isEqualTo(409);
            assertThat(http(app, "PUT", fechamento + "2026-12", a, manifesto).statusCode()).isEqualTo(409);
            sql.update("""
                    insert into tb_entrega_rf26(dia,destinatario,unidade,estado,reservada_em)
                    values (?,?,?,'RESERVADA',current_timestamp)
                    """, dia, destinatario, "USF:" + unidadeA);
            var motivo = Map.of("justificativa", "Conferência sintética sem reenvio.");
            assertThat(http(app, "POST", incidente, p, motivo).statusCode()).isEqualTo(403);
            assertThat(http(app, "POST", incidente, login(app, adminB), motivo).statusCode()).isEqualTo(404);
            assertThat(http(app, "POST", incidente, a, motivo).statusCode()).isEqualTo(204);
            assertThat(http(app, "POST", incidente, a, motivo).statusCode()).isEqualTo(409);
        }
        String depois = "2027-01-01T03:00:00Z";
        try (var app = iniciar(banco, depois)) {
            var a = login(app, adminA);
            assertThat(json(app, http(app, "POST", marco, a, null)).path("iniciadoEm").asString()).isEqualTo(limite);
            var divergente = Map.of("vinculos", List.of(9999L), "evidenciaSha256", sha);
            assertThat(http(app, "PUT", fechamento + "2026-12", a, divergente).statusCode()).isEqualTo(409);
            var resumo = http(app, "PUT", fechamento + "2026-12", a, manifesto);
            assertThat(resumo.statusCode()).isEqualTo(200);
            assertThat(json(app, resumo).path("situacao").asString()).isEqualTo("COMPROVADO");
            assertThat(json(app, resumo).path("vermelhos").asLong()).isZero();
            assertThat(http(app, "PUT", fechamento + "2026-12", a, manifesto).body()).isEqualTo(resumo.body());
            assertThat(http(app, "PUT", fechamento + "2026-12", a,
                    Map.of("vinculos", List.of(), "evidenciaSha256", "b".repeat(64))).statusCode()).isEqualTo(409);
        }
        var arquivo = temp.resolve("backup-v18.sql");
        LegacyMigrationFixture.backup(banco, arquivo);
        var restaurado = LegacyMigrationFixture.restore(temp.resolve("restaurado-v18"), arquivo);
        var estadoRestaurado = new JdbcTemplate(restaurado);
        assertThat(estadoRestaurado.queryForObject("select count(*) from tb_revisao_entrega_rf26", Integer.class)).isEqualTo(1);
        try (var app = iniciar(restaurado, depois)) {
            var a = login(app, adminA);
            var evolucao = json(app, http(app, "GET", painel, a, null)).path("evolucao");
            assertThat(evolucao.get(0).path("situacao").asString()).isEqualTo("SEM_COBERTURA");
            assertThat(evolucao.get(1).path("situacao").asString()).isEqualTo("COMPROVADO");
            assertThat(evolucao.get(1).path("verdes").asLong()).isZero();
            assertThat(evolucao.get(2).path("situacao").asString()).isEqualTo("SEM_COBERTURA");
            var outra = json(app, http(app, "GET", painel, login(app, adminB), null));
            assertThat(outra.path("evolucao").get(1).path("situacao").asString()).isEqualTo("SEM_COBERTURA");
            var controle = app.getBean(com.pet.buscaativa.services.impl.ControleNotificacoesEnsaioService.class);
            var chave = new com.pet.buscaativa.services.impl.NotificacoesLocaisEnsaioService.Chave(dia, destinatario, "USF:" + unidadeA);
            assertThat(controle.executar(chave, entrega -> { throw new AssertionError("Reenvio indevido"); }).name())
                    .isEqualTo("DUPLICADA");
        }
    }

    private ConfigurableApplicationContext iniciar(DriverManagerDataSource banco) {
        return iniciar(banco, "2026-11-24T22:00:00Z");
    }

    private ConfigurableApplicationContext iniciar(DriverManagerDataSource banco, String instante) {
        return new SpringApplicationBuilder(BuscaativaApplication.class, Relogio.class).registerShutdownHook(false).logStartupInfo(false)
                .run("--spring.config.location=file:src/main/resources/", "--spring.profiles.active=default",
                        "--server.address=127.0.0.1", "--server.port=0", "--sibraps.usf.sincronizar=false",
                        "--sibraps.test.relogio-operacional=true", "--sibraps.test.instante=" + instante,
                        "--spring.datasource.url=" + banco.getUrl(), "--spring.datasource.username=" + banco.getUsername(),
                        "--spring.datasource.password=" + banco.getPassword(),
                        "--JWT_SECRET_BASE64=" + segredo, "--spring.main.banner-mode=off", "--logging.level.root=ERROR",
                        "--logging.level.org.hibernate.orm.jdbc.error=OFF");
    }

    private String login(ConfigurableApplicationContext app, String email) throws Exception {
        var res = http(app, "POST", "/api/auth/login", null, Map.of("email", email, "senha", senha));
        assertThat(res.statusCode()).isEqualTo(200);
        return json(app, res).path("token").asString();
    }

    private HttpResponse<String> concorrer(ConfigurableApplicationContext app, String token,
            Map<String, Object> dados, CountDownLatch inicio) throws Exception {
        if (!inicio.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Espera pela largada excedida.");
        return http(app, "POST", "/api/pacientes?ignorarSimilaridade=true", token, dados);
    }

    private HttpResponse<String> http(ConfigurableApplicationContext app, String metodo,
            String rota, String token, Object dados) throws Exception {
        var host = (WebServerApplicationContext) app;
        var requisicao = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + host.getWebServer().getPort() + rota))
                .timeout(java.time.Duration.ofSeconds(10));
        if (token != null) requisicao.header("Authorization", "Bearer " + token);
        var corpo = dados == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(
                app.getBean(ObjectMapper.class).writeValueAsString(dados));
        return cliente.send(requisicao.header("Content-Type", "application/json").method(metodo, corpo).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static JsonNode json(ConfigurableApplicationContext app, HttpResponse<String> res) {
        return app.getBean(ObjectMapper.class).readTree(res.body());
    }

    private long usf(JdbcTemplate sql, String nome) {
        var cnes = UUID.randomUUID().toString();
        sql.update("insert into tb_usf_referencia(cnes,nome_usf) values (?,?)", cnes, "USF sintética " + nome);
        return sql.queryForObject("select id from tb_usf_referencia where cnes=?", Long.class, cnes);
    }

    private String ator(JdbcTemplate sql, long unidade, int perfil, String nome) {
        String email = nome + "@test.local";
        sql.update("""
                insert into tb_usuario(id_publico,email,nome,senha,tipo_usuario,unidade_atuacao,usf_atuacao_id)
                values (?,?,?,?,?,1,?)
                """, UUID.randomUUID(), email, nome, new BCryptPasswordEncoder().encode(senha), perfil, unidade);
        return email;
    }

    private static Map<String, Object> cadastro(String cpf, String cns, String nome, long unidade) {
        return Map.ofEntries(Map.entry("cpf", cpf), Map.entry("cns", cns), Map.entry("nome", nome + " Sintética"),
                Map.entry("nomeMae", "Mãe " + nome), Map.entry("dataNascimento", "1984-12-05"),
                Map.entry("sexo", "FEMININO"), Map.entry("racacor", "PARDA"), Map.entry("telefone", "81900000001"),
                Map.entry("situacaoRua", true), Map.entry("tipoAcompanhamento", "AMBOS"),
                Map.entry("usfReferencia", Map.of("id", unidade)), Map.entry("capsReferencia", "CAPS_AD"));
    }
}
