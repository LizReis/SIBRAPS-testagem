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
            return new RelogioMutavel(Instant.parse(instante), ZoneId.of("America/Bahia"));
        }
    }

    private static final class RelogioMutavel extends Clock {
        private final java.util.concurrent.atomic.AtomicReference<Instant> instante;
        private final ZoneId zona;
        RelogioMutavel(Instant instante, ZoneId zona) {
            this.instante = new java.util.concurrent.atomic.AtomicReference<>(instante);
            this.zona = zona;
        }
        @Override public ZoneId getZone() { return zona; }
        @Override public Clock withZone(ZoneId zona) { return Clock.fixed(instant(), zona); }
        @Override public Instant instant() { return instante.get(); }
        void avancar(Instant novo) {
            if (novo.isBefore(instant())) throw new IllegalArgumentException("Relógio deve avançar.");
            instante.set(novo);
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
    void inicializacaoRecusaV18RegistradaComEstruturaIncompleta() throws Exception {
        var banco = LegacyMigrationFixture.legacy(temp.resolve("v18-incompleta"));
        AgendaMigrationTool.migrar(banco);
        AgendaMigrationTool.migrarIdentidade(banco);
        AgendaMigrationTool.migrarTransicao(banco);
        var sql = new JdbcTemplate(banco);
        sql.execute("alter table tb_fechamento_historico_vinculo drop column indicadores");
        var antes = LegacyMigrationFixture.columns(banco);
        assertThatThrownBy(() -> {
            try (var app = iniciar(banco)) {
                assertThat(app.isActive()).isFalse();
            }
        }).hasRootCauseMessage("Banco operacional com estrutura V18 incompleta ou incompatível.");
        assertThat(LegacyMigrationFixture.columns(banco)).isEqualTo(antes);
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
        String profissionalB = ator(sql, unidadeB, 2, "profissional-b");

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
            var marcoSemV18 = http(segunda, "POST", "/api/painel/historico/marco", tokenAdmin, null);
            assertThat(marcoSemV18.statusCode()).isEqualTo(409);
            assertThat(json(segunda, marcoSemV18).path("message").asString()).contains("V18");
            validarCadastroConcorrenteEntreUnidades(primeira, segunda, tokenProfissional,
                    login(segunda, profissionalB), sql, unidadeA, unidadeB);
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

    private void validarCadastroConcorrenteEntreUnidades(ConfigurableApplicationContext a,
            ConfigurableApplicationContext b, String tokenA, String tokenB, JdbcTemplate sql,
            long unidadeA, long unidadeB) throws Exception {
        String cpf = "39053344705";
        String cns = "800000000000028";
        var inicio = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var reqA = executor.submit(() -> concorrer(a, tokenA, cadastro(cpf, cns, "Identidade", unidadeA), inicio));
            var reqB = executor.submit(() -> concorrer(b, tokenB, cadastro(cpf, cns, "Identidade", unidadeB), inicio));
            inicio.countDown();
            var respostaA = reqA.get(15, TimeUnit.SECONDS);
            var respostaB = reqB.get(15, TimeUnit.SECONDS);
            assertThat(List.of(respostaA.statusCode(), respostaB.statusCode())).containsExactlyInAnyOrder(201, 409);
            assertThat(sql.queryForObject("select count(*) from tb_paciente where cpf_normalizado=? and cns_normalizado=?",
                    Integer.class, cpf, cns)).isEqualTo(1);
            assertThat(sql.queryForList("""
                    select v.unidade_chave from tb_vinculo_assistencial v join tb_paciente p on p.id=v.paciente_id
                    where p.cpf_normalizado=?
                    """, String.class, cpf)).containsExactly("USF:" + (respostaA.statusCode() == 201 ? unidadeA : unidadeB));
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
        String segundoAdminA = ator(sql, unidadeA, 1, "transicao-a2");
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
            var largada = new CountDownLatch(1);
            var segundoToken = login(app, segundoAdminA);
            HttpResponse<String> resumo;
            HttpResponse<String> concorrente;
            try (var executor = Executors.newFixedThreadPool(2)) {
                var uma = executor.submit(() -> {
                    if (!largada.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Largada excedida.");
                    return http(app, "PUT", fechamento + "2026-12", a, manifesto);
                });
                var duas = executor.submit(() -> {
                    if (!largada.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Largada excedida.");
                    return http(app, "PUT", fechamento + "2026-12", segundoToken, manifesto);
                });
                largada.countDown();
                var primeira = uma.get(15, TimeUnit.SECONDS);
                var segunda = duas.get(15, TimeUnit.SECONDS);
                resumo = primeira.statusCode() == 200 ? primeira : segunda;
                concorrente = primeira.statusCode() == 200 ? segunda : primeira;
            }
            assertThat(resumo.statusCode()).isEqualTo(200);
            assertThat(concorrente.statusCode()).isIn(200, 409);
            if (concorrente.statusCode() == 200) assertThat(concorrente.body()).isEqualTo(resumo.body());
            assertThat(sql.queryForObject("select count(*) from tb_fechamento_historico_vinculo", Integer.class)).isEqualTo(1);
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

    @Test
    void fechamentoAutomaticoPorUnidadePreservaMesDepoisDeCorrecao() throws Exception {
        var banco = LegacyMigrationFixture.legacy(temp.resolve("historico-automatico-integrado"));
        AgendaMigrationTool.migrar(banco);
        AgendaMigrationTool.migrarIdentidade(banco);
        AgendaMigrationTool.migrarTransicao(banco);
        AgendaMigrationTool.migrarCoberturaHistorica(banco);
        AgendaMigrationTool.migrarMarcoHistorico(banco);
        AgendaMigrationTool.migrarEvidenciaClinica(banco);
        AgendaMigrationTool.migrarFechamentoAutomatico(banco);
        var sql = new JdbcTemplate(banco);
        long usfA = usf(sql,"Automático A");
        long usfB = usf(sql,"Automático B");
        String adminA = ator(sql,usfA,1,"historico-auto-a");
        String profA = ator(sql,usfA,2,"historico-auto-prof");
        String recepA = ator(sql,usfA,3,"historico-auto-recep");
        String adminB = ator(sql,usfB,1,"historico-auto-b");
        String capsAD = atorCaps(sql,2,"historico-auto-ad");
        String capsI = atorCaps(sql,3,"historico-auto-i");
        String capsII = atorCaps(sql,4,"historico-auto-ii");
        var zona = ZoneId.of("America/Bahia");
        var marco = com.pet.buscaativa.migration.MarcoHistoricoOffline.registrar(banco,zona);
        var mes = java.time.YearMonth.from(marco.iniciadoEm().atZoneSameInstant(zona)).plusMonths(1);
        var corte = mes.plusMonths(1).atDay(1).atStartOfDay(zona).toInstant();
        LocalDate primeiroDia = mes.atEndOfMonth().minusDays(2);
        String painel = "/api/painel/resumo?periodoMeses=3";
        String unidadeA = "USF:"+usfA;
        String unidadeB = "USF:"+usfB;
        String indicadores;
        try (var app = iniciar(banco,primeiroDia.atTime(12,0).atZone(zona).toInstant().toString())) {
            var clock = (RelogioMutavel) app.getBean(Clock.class);
            var tecnico = app.getBean(com.pet.buscaativa.services.impl.FechamentoHistoricoAutomaticoService.class);
            String a = login(app,adminA), p = login(app,profA), r = login(app,recepA), b = login(app,adminB);
            String i = login(app,capsI), ii = login(app,capsII), ad = login(app,capsAD);
            assertThat(http(app,"POST","/api/painel/historico/marco",a,null).statusCode()).isEqualTo(409);
            assertThat(http(app,"PUT","/api/painel/historico/fechamentos/"+mes,a,
                    Map.of("vinculos",List.of(),"evidenciaSha256","a".repeat(64))).statusCode()).isEqualTo(409);
            var dados = cadastro("11144477735","800000000000001","Histórico integrado",usfA);
            var novo = http(app,"POST","/api/pacientes?ignorarSimilaridade=true",r,dados);
            assertThat(novo.statusCode()).as(novo.body()).isEqualTo(201);
            String pessoa = json(app,novo).path("paciente").asString();
            var confirmacao = Map.of("cpf",dados.get("cpf"),"cns",dados.get("cns"),"nome",dados.get("nome"),
                    "nomeMae",dados.get("nomeMae"),"dataNascimento",dados.get("dataNascimento"),
                    "tipoAcompanhamento","GRUPO_TERAPEUTICO");
            assertThat(http(app,"POST","/api/pacientes/cadastro-unico/confirmar-vinculo",i,confirmacao).statusCode()).isEqualTo(204);
            assertThat(http(app,"GET","/api/pacientes/"+pessoa,b,null).statusCode()).isEqualTo(404);
            // Grupo na unidade que compartilha a identidade, sem transportar faltas da USF.
            UUID coordenador = sql.queryForObject("select id_publico from tb_usuario where email=?",UUID.class,capsI);
            var grupo = http(app,"POST","/api/grupos",i,Map.of("tema","Grupo sintético integrado",
                    "coordenadorId",coordenador,"recorrencia","UNICA","dataPrimeiraSessao",primeiroDia.plusDays(1),
                    "horario","10:30","participantesIds",List.of(pessoa)));
            assertThat(grupo.statusCode()).as(grupo.body()).isEqualTo(200);
            long grupoId = json(app,grupo).path("grupo").asLong();
            long sessao = json(app,grupo).path("sessoes").get(0).asLong();
            assertThat(http(app,"GET","/api/grupos/"+grupoId,a,null).statusCode()).isEqualTo(404);
            // População adicional em unidade distinta: cadastro real por HTTP, documentos sintéticos.
            validarVolumeHistorico(app,ad,usfA,painel);
            UUID responsavel = sql.queryForObject("select id_publico from tb_usuario where email=?",UUID.class,profA);
            String atendimento = null;
            int versao = -1;
            for (int dia=0; dia<2; dia++) {
                var data = primeiroDia.plusDays(dia);
                clock.avancar(data.atTime(12,0).atZone(zona).toInstant());
                sql.update("""
                        insert into tb_disponibilidade_excecao(usuario_id,data,turno,capacidade)
                        select id,?,1,10 from tb_usuario where email=?
                        """,data,profA);
                var agenda = http(app,"POST","/api/agendamentos",r,Map.of("pacienteId",pessoa,
                        "usuarioId",responsavel,"dataAgendamento",data,"turnoAgendamento","MANHA","horaAtendimento","09:00"));
                assertThat(agenda.statusCode()).as(agenda.body()).isEqualTo(201);
                var registro = json(app,agenda);
                atendimento = "/api/agendamentos/"+registro.path("id").asLong();
                var falta = http(app,"PATCH",atendimento+"/status?novoStatus=FALTOU&version="+registro.path("version").asInt(),p,null);
                assertThat(falta.statusCode()).as(falta.body()).isEqualTo(200);
                versao = json(app,falta).path("version").asInt();
            }
            var detalheGrupo = http(app,"GET","/api/grupos/sessoes?dataInicio="+primeiroDia.plusDays(1)
                    +"&dataFim="+primeiroDia.plusDays(1),i,null);
            assertThat(detalheGrupo.statusCode()).as(detalheGrupo.body()).isEqualTo(200);
            var sessaoDto = json(app,detalheGrupo).get(0);
            assertThat(sessaoDto.path("id").asLong()).isEqualTo(sessao);
            var confirmar = Map.of("ocorreu",true,"version",sessaoDto.path("version").asInt(),
                    "frequencias",List.of(Map.of("pacienteId",pessoa,"statusPresenca","PRESENTE")));
            var ocorrencia = http(app,"POST","/api/grupos/sessoes/"+sessao+"/confirmacao-ocorrencia",i,confirmar);
            assertThat(ocorrencia.statusCode()).as(ocorrencia.body()).isEqualTo(200);
            assertThat(json(app,ocorrencia).path("status").asString()).isEqualTo("REALIZADA");
            var localCaps = json(app,http(app,"GET","/api/pacientes/"+pessoa+"/prontuario",i,null));
            var localUsf = json(app,http(app,"GET","/api/pacientes/"+pessoa+"/prontuario",a,null));
            assertThat(localCaps.path("presencasComprovadas").asLong()).isEqualTo(1);
            assertThat(localCaps.path("faltasComprovadas").asLong()).isZero();
            assertThat(localUsf.path("presencasComprovadas").asLong()).isZero();
            assertThat(localUsf.path("faltasComprovadas").asLong()).isEqualTo(2);
            var relatorio = http(app,"GET","/api/relatorios/frequencia-grupos?grupoId="+grupoId,i,null);
            assertThat(relatorio.statusCode()).as(relatorio.body()).isEqualTo(200);
            assertThat(json(app,relatorio).path("dados").size()).isPositive();
            assertThat(http(app,"GET","/api/relatorios/frequencia-grupos?grupoId="+grupoId,a,null).statusCode()).isEqualTo(404);
            assertThat(http(app,"GET","/api/relatorios/pendencias",a,null).statusCode()).isEqualTo(200);
            assertThat(tecnico.executar().fechados()).isZero();
            clock.avancar(corte.minusSeconds(30));
            tecnico.executar();
            assertThat(sql.queryForList("""
                    select unidade from tb_observacao_historico_mensal where mes=? and fase='PRE'
                    """,String.class,mes.atDay(1))).contains(unidadeA,unidadeB,"CAPS_I","CAPS_II","CAPS_AD");
            assertThat(tecnico.executar().observados()).isZero();
            // Alteração exclusivamente no CAPS II na janela de corte. Não impede outras unidades.
            assertThat(http(app,"POST","/api/pacientes/cadastro-unico/confirmar-vinculo",ii,confirmacao).statusCode()).isEqualTo(204);
            clock.avancar(corte.plusSeconds(10));
            // Duas instâncias sobre o mesmo H2 e leituras HTTP durante a disputa do fechamento.
            try (var segunda = iniciar(banco,corte.plusSeconds(10).toString());
                 var executor = Executors.newFixedThreadPool(6)) {
                var outro = segunda.getBean(com.pet.buscaativa.services.impl.FechamentoHistoricoAutomaticoService.class);
                var prontos = new CountDownLatch(6);
                var largada = new CountDownLatch(1);
                var primeira = executor.submit(() -> medirFechamento(tecnico,prontos,largada));
                var concorrente = executor.submit(() -> medirFechamento(outro,prontos,largada));
                var leituras = new java.util.ArrayList<java.util.concurrent.Future<Long>>();
                for (int n=0;n<4;n++) leituras.add(executor.submit(() -> {
                    aguardarLargada(prontos,largada);
                    long inicio=System.nanoTime();
                    var item=evolucao(app,painel,a,mes);
                    assertThat(item.path("situacao").asString()).isIn("SEM_COBERTURA","COMPROVADO");
                    if ("COMPROVADO".equals(item.path("situacao").asString()))
                        assertThat(item.path("amarelos").asLong()).isEqualTo(1);
                    else assertThat(item.path("amarelos").isNull()).isTrue();
                    return TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-inicio);
                }));
                try {
                    assertThat(prontos.await(10,TimeUnit.SECONDS)).isTrue();
                } finally { largada.countDown(); }
                var um=primeira.get(60,TimeUnit.SECONDS);
                var dois=concorrente.get(60,TimeUnit.SECONDS);
                assertThat(um.fechados()+dois.fechados()).isPositive();
                var tempos=new java.util.ArrayList<Long>();
                for(var leitura:leituras) tempos.add(leitura.get(30,TimeUnit.SECONDS));
                System.out.printf("HISTORICO_CI fechamento concorrente: ms=%d/%d, disputas=%d, leituras_ms=%s%n",
                        um.ms(),dois.ms(),(um.disputa()?1:0)+(dois.disputa()?1:0),tempos);
            }
            assertThat(sql.queryForList("""
                    select unidade from tb_observacao_historico_mensal where mes=? and fase='FECHADO'
                    """,String.class,mes.atDay(1))).contains(unidadeA,unidadeB,"CAPS_I","CAPS_AD").doesNotContain("CAPS_II");
            assertThat(tecnico.executar().fechados()).isZero();
            var historicoA = evolucao(app,painel,a,mes);
            assertThat(historicoA.path("situacao").asString()).isEqualTo("COMPROVADO");
            assertThat(historicoA.path("amarelos").asLong()).isEqualTo(1);
            assertThat(evolucao(app,painel,i,mes).path("verdes").asLong()).isEqualTo(1);
            assertThat(evolucao(app,painel,b,mes).path("situacao").asString()).isEqualTo("COMPROVADO");
            assertThat(evolucao(app,painel,b,mes).path("verdes").asLong()).isZero();
            assertThat(evolucao(app,painel,ad,mes).path("situacao").asString()).isEqualTo("COMPROVADO");
            assertThat(evolucao(app,painel,ad,mes).path("verdes").asLong()).isEqualTo(24);
            var indisponivel = evolucao(app,painel,ii,mes);
            assertThat(indisponivel.path("situacao").asString()).isEqualTo("SEM_COBERTURA");
            assertThat(indisponivel.path("verdes").isNull()).isTrue();
            assertThat(evolucao(app,painel,a,mes.minusMonths(1)).path("situacao").asString()).isEqualTo("SEM_COBERTURA");
            assertThat(evolucao(app,painel,a,mes.plusMonths(1)).path("situacao").asString()).isEqualTo("SEM_COBERTURA");
            assertThat(evolucao(app,painel+"&situacaoRua=true&tipoAcompanhamento=AMBOS",a,mes).path("amarelos").asLong()).isEqualTo(1);
            indicadores = sql.queryForObject("""
                    select indicadores from tb_observacao_historico_mensal where unidade=? and mes=? and fase='FECHADO'
                    """,String.class,unidadeA,mes.atDay(1));
            clock.avancar(corte.plusSeconds(3600));
            var correcao = http(app,"PATCH",atendimento+"/frequencia/correcao",p,
                    Map.of("novaFrequencia","PRESENTE","version",versao,"justificativa","Correção sintética após fechamento."));
            assertThat(correcao.statusCode()).as(correcao.body()).isEqualTo(200);
            assertThat(json(app,http(app,"GET",painel,a,null)).path("pontoAtual").path("distribuicao").path("verdes").asLong()).isEqualTo(1);
            assertThat(evolucao(app,painel,a,mes)).isEqualTo(historicoA);
            assertThat(evolucao(app,painel,i,mes).path("verdes").asLong()).isEqualTo(1);
            assertThat(sql.queryForObject("""
                    select indicadores from tb_observacao_historico_mensal where unidade=? and mes=? and fase='FECHADO'
                    """,String.class,unidadeA,mes.atDay(1))).isEqualTo(indicadores);
            assertThat(tecnico.executar().fechados()).isZero();
            // Carga progressiva de leitores, sem recriar o cenário ou simular volume de pacientes.
            for (int leitores : List.of(1,4,12)) {
                var prontos=new CountDownLatch(leitores);
                var largada=new CountDownLatch(1);
                try(var executor=Executors.newFixedThreadPool(leitores)) {
                    var futuros=new java.util.ArrayList<java.util.concurrent.Future<Long>>();
                    for(int n=0;n<leitores;n++) futuros.add(executor.submit(()->{
                        aguardarLargada(prontos,largada);
                        long inicio=System.nanoTime();
                        assertThat(evolucao(app,painel,a,mes)).isEqualTo(historicoA);
                        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-inicio);
                    }));
                    try { assertThat(prontos.await(10,TimeUnit.SECONDS)).isTrue(); }
                    finally { largada.countDown(); }
                    var tempos=new java.util.ArrayList<Long>();
                    for(var futuro:futuros) tempos.add(futuro.get(30,TimeUnit.SECONDS));
                    tempos.sort(Long::compareTo);
                    System.out.printf("HISTORICO_CI leitores=%d min_ms=%d mediana_ms=%d max_ms=%d%n",
                            leitores,tempos.getFirst(),tempos.get(tempos.size()/2),tempos.getLast());
                }
            }
        }
        var arquivo=temp.resolve("automatico-v22.sql");
        LegacyMigrationFixture.backup(banco,arquivo);
        var copia=LegacyMigrationFixture.restore(temp.resolve("automatico-restaurado"),arquivo);
        try(var app=iniciar(copia,corte.plusSeconds(7200).toString())) {
            assertThat(evolucao(app,painel,login(app,adminA),mes).path("amarelos").asLong()).isEqualTo(1);
            assertThat(new JdbcTemplate(copia).queryForObject("""
                    select indicadores from tb_observacao_historico_mensal where unidade=? and mes=? and fase='FECHADO'
                    """,String.class,unidadeA,mes.atDay(1))).isEqualTo(indicadores);
        }
    }

    private void validarVolumeHistorico(ConfigurableApplicationContext app,String token,long usfReferencia,
            String painel) throws Exception {
        int cadastrados=0;
        for(int alvo:List.of(8,16,24)) {
            long inicio=System.nanoTime();
            while(cadastrados<alvo) {
                int n=cadastrados+1;
                var dados=cadastro(cpfSintetico(n),cnsSintetico(n),"Volume sintético "+n,usfReferencia);
                var resposta=http(app,"POST","/api/pacientes?ignorarSimilaridade=true",token,dados);
                assertThat(resposta.statusCode()).as("cadastro sintético %d: %s",n,resposta.body()).isEqualTo(201);
                cadastrados++;
            }
            long cadastroMs=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-inicio);
            inicio=System.nanoTime();
            var monitor=app.getBean(MonitoramentoVinculosEnsaioService.class).executar();
            long monitorMs=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-inicio);
            assertThat(monitor.falhas()).isZero();
            inicio=System.nanoTime();
            var resposta=http(app,"GET",painel,token,null);
            long painelMs=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-inicio);
            assertThat(resposta.statusCode()).isEqualTo(200);
            var dados=json(app,resposta);
            assertThat(dados.path("totalPacientesAtivos").asLong()).isEqualTo(alvo);
            assertThat(dados.path("distribuicaoClassificacao").path("verdes").asLong()).isEqualTo(alvo);
            System.out.printf("HISTORICO_VOLUME pacientes=%d cadastro_lote_ms=%d monitor_ms=%d painel_ms=%d examinados=%d%n",
                    alvo,cadastroMs,monitorMs,painelMs,monitor.examinados());
        }
    }

    private static String cpfSintetico(int n) {
        String base=String.format(java.util.Locale.ROOT,"%09d",930000000+n);
        for(int tamanho=9;tamanho<=10;tamanho++) {
            int soma=0;
            for(int k=0;k<tamanho;k++) soma+=(base.charAt(k)-'0')*(tamanho+1-k);
            int dv=11-soma%11;
            base+=dv>=10?0:dv;
        }
        return base;
    }

    private static String cnsSintetico(int n) {
        for(int extra=0;extra<10;extra++) {
            String base=String.format(java.util.Locale.ROOT,"%014d",89000000000000L+n*10L+extra);
            int soma=0;
            for(int k=0;k<14;k++) soma+=(base.charAt(k)-'0')*(15-k);
            int dv=(11-soma%11)%11;
            if(dv<10) return base+dv;
        }
        throw new AssertionError("Não foi possível gerar CNS sintético.");
    }

    private record MedicaoFechamento(long ms,int fechados,boolean disputa) {}

    private static void aguardarLargada(CountDownLatch prontos,CountDownLatch largada) throws InterruptedException {
        prontos.countDown();
        if(!largada.await(10,TimeUnit.SECONDS)) throw new AssertionError("Largada concorrente excedida.");
    }

    private static MedicaoFechamento medirFechamento(
            com.pet.buscaativa.services.impl.FechamentoHistoricoAutomaticoService tecnico,
            CountDownLatch prontos,CountDownLatch largada) throws InterruptedException {
        aguardarLargada(prontos,largada);
        long inicio=System.nanoTime();
        try {
            var resultado=tecnico.executar();
            return new MedicaoFechamento(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-inicio),resultado.fechados(),false);
        } catch(org.springframework.dao.DuplicateKeyException | org.springframework.dao.ConcurrencyFailureException disputa) {
            // A outra transação deve concluir; qualquer erro não relacionado à disputa falha o teste.
            return new MedicaoFechamento(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-inicio),0,true);
        }
    }

    private JsonNode evolucao(ConfigurableApplicationContext app,String painel,String token,java.time.YearMonth mes) throws Exception {
        var resposta = http(app,"GET",painel,token,null);
        assertThat(resposta.statusCode()).as(resposta.body()).isEqualTo(200);
        for (var item:json(app,resposta).path("evolucao"))
            if (mes.toString().equals(item.path("mes").asString())) return item;
        throw new AssertionError("Mês não retornado: "+mes);
    }

    private String atorCaps(JdbcTemplate sql,int unidade,String nome) {
        String email=nome+"@test.local";
        sql.update("""
                insert into tb_usuario(id_publico,email,nome,senha,tipo_usuario,unidade_atuacao)
                values (?,?,?,?,1,?)
                """,UUID.randomUUID(),email,nome,new BCryptPasswordEncoder().encode(senha),unidade);
        return email;
    }

    private ConfigurableApplicationContext iniciar(DriverManagerDataSource banco) {
        return iniciar(banco, "2026-11-24T22:00:00Z");
    }

    private ConfigurableApplicationContext iniciar(DriverManagerDataSource banco, String instante) {
        return new SpringApplicationBuilder(BuscaativaApplication.class, Relogio.class).registerShutdownHook(false).logStartupInfo(false)
                .run("--spring.config.location=file:src/main/resources/", "--spring.profiles.active=default",
                        "--server.address=127.0.0.1", "--server.port=0", "--sibraps.usf.sincronizar=false",
                        "--sibraps.test.relogio-operacional=true", "--sibraps.test.instante=" + instante,
                        "--sibraps.historico.automatico.habilitado=false",
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
