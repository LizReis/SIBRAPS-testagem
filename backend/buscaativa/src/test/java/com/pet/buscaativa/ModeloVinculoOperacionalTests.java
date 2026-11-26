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
        AgendaMigrationTool.migrarResolucaoFrequencia(banco);
        AgendaMigrationTool.migrarReversaoResolucao(banco);
        AgendaMigrationTool.migrarAplicacaoPendencia(banco);
        AgendaMigrationTool.migrarEvidenciaResolucao(banco);
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
            UUID paciente=UUID.fromString(pessoa);
            UUID vinculo=UUID.fromString(json(app,novo).path("vinculo").asString());
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
            com.pet.buscaativa.services.impl.OperacoesResolucaoFrequenciaTeste.Reserva primeiraFalta=null;
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
                if(dia==0) primeiraFalta=new com.pet.buscaativa.services.impl.OperacoesResolucaoFrequenciaTeste.Reserva(registro.path("id").asLong(),versao);
            }
            long pessoaId=sql.queryForObject("select id from tb_paciente where id_publico=?",Long.class,paciente);
            long profissionalId=sql.queryForObject("select id from tb_usuario where email=?",Long.class,profA);
            var duplicada=com.pet.buscaativa.services.impl.OperacoesResolucaoFrequenciaTeste.falta(app,profA,pessoaId,profissionalId,primeiroDia,9);
            assertThat(faltasResolucao(sql,vinculo)).isEqualTo(3);
            var resolucao=com.pet.buscaativa.services.impl.OperacoesResolucaoFrequenciaTeste.resolver(app,profA,paciente,vinculo,
                    versaoResolucao(sql,vinculo),primeiraFalta,duplicada,false);
            assertThat(faltasResolucao(sql,vinculo)).isEqualTo(2);
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
            com.pet.buscaativa.services.impl.OperacoesResolucaoFrequenciaTeste.desfazer(app,profA,paciente,vinculo,
                    versaoResolucao(sql,vinculo),resolucao);
            assertThat(faltasResolucao(sql,vinculo)).isEqualTo(3);
            assertThat(evolucao(app,painel,a,mes)).isEqualTo(historicoA);
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

    @Test
    void resolucaoEReversaoAtualizamIndicadoresNaMesmaTransacao() throws Exception {
        var banco=LegacyMigrationFixture.legacy(temp.resolve("resolucao-v24"));
        AgendaMigrationTool.migrar(banco);
        AgendaMigrationTool.migrarIdentidade(banco);
        AgendaMigrationTool.migrarTransicao(banco);
        AgendaMigrationTool.migrarCoberturaHistorica(banco);
        AgendaMigrationTool.migrarMarcoHistorico(banco);
        AgendaMigrationTool.migrarEvidenciaClinica(banco);
        AgendaMigrationTool.migrarFechamentoAutomatico(banco);
        AgendaMigrationTool.migrarResolucaoFrequencia(banco);
        AgendaMigrationTool.migrarReversaoResolucao(banco);
        assertThat(AgendaMigrationTool.migrarAplicacaoPendencia(banco).migrationsExecuted).isEqualTo(1);
        assertThat(AgendaMigrationTool.migrarAplicacaoPendencia(banco).migrationsExecuted).isZero();
        assertThat(AgendaMigrationTool.migrarEvidenciaResolucao(banco).migrationsExecuted).isEqualTo(1);
        assertThat(AgendaMigrationTool.migrarEvidenciaResolucao(banco).migrationsExecuted).isZero();
        assertThat(AgendaMigrationTool.migrarProvaOrigem(banco).migrationsExecuted).isEqualTo(1);
        assertThat(AgendaMigrationTool.migrarProvaOrigem(banco).migrationsExecuted).isZero();
        assertThat(AgendaMigrationTool.migrarAplicacaoOrigem(banco).migrationsExecuted).isEqualTo(1);
        assertThat(AgendaMigrationTool.migrarAplicacaoOrigem(banco).migrationsExecuted).isZero();
        assertThat(AgendaMigrationTool.migrarReversaoOrigem(banco).migrationsExecuted).isEqualTo(1);
        assertThat(AgendaMigrationTool.migrarReversaoOrigem(banco).migrationsExecuted).isZero();
        assertThat(AgendaMigrationTool.migrarReaberturaOrigem(banco).migrationsExecuted).isEqualTo(1);
        assertThat(AgendaMigrationTool.migrarReaberturaOrigem(banco).migrationsExecuted).isZero();
        assertThat(AgendaMigrationTool.migrarEvidenciaOrigem(banco).migrationsExecuted).isEqualTo(1);
        assertThat(AgendaMigrationTool.migrarEvidenciaOrigem(banco).migrationsExecuted).isZero();
        var sql=new JdbcTemplate(banco);
        long unidade=usf(sql,"Resolução A");
        String prof=ator(sql,unidade,2,"resolucao-prof");
        String admin=ator(sql,unidade,1,"resolucao-admin");
        String colega=ator(sql,unidade,2,"resolucao-colega");
        String recepcao=ator(sql,unidade,3,"resolucao-recepcao");
        String externo=ator(sql,usf(sql,"Resolução B"),1,"resolucao-externo");
        try(var app=iniciar(banco)) {
            var token=login(app,prof);
            var resposta=http(app,"POST","/api/pacientes?ignorarSimilaridade=true",token,
                    cadastro("11144477735","800000000000001","Resolução",unidade));
            assertThat(resposta.statusCode()).as(resposta.body()).isEqualTo(201);
            var novo=json(app,resposta);
            var paciente=UUID.fromString(novo.path("paciente").asString());
            var vinculo=UUID.fromString(novo.path("vinculo").asString());
            long pessoa=sql.queryForObject("select id from tb_paciente where id_publico=?",Long.class,paciente);
            long responsavel=sql.queryForObject("select id from tb_usuario where email=?",Long.class,prof);
            var a=com.pet.buscaativa.services.impl.OperacoesResolucaoFrequenciaTeste.falta(app,prof,pessoa,responsavel,HOJE.minusDays(2),9);
            var b=com.pet.buscaativa.services.impl.OperacoesResolucaoFrequenciaTeste.falta(app,prof,pessoa,responsavel,HOJE.minusDays(2),9);
            var c=com.pet.buscaativa.services.impl.OperacoesResolucaoFrequenciaTeste.falta(app,prof,pessoa,responsavel,HOJE.minusDays(1),9);
            assertThat(faltasResolucao(sql,vinculo)).isEqualTo(3);
            int versao=versaoResolucao(sql,vinculo);
            com.pet.buscaativa.services.impl.OperacoesResolucaoFrequenciaTeste.resolver(app,prof,paciente,vinculo,versao,a,b,true);
            assertThat(sql.queryForObject("select count(*) from tb_resolucao_frequencia_local",Integer.class)).isZero();
            assertThat(sql.queryForObject("select count(*) from tb_evidencia_resolucao_historica",Integer.class)).isZero();
            assertThat(faltasResolucao(sql,vinculo)).isEqualTo(3);
            assertThat(versaoResolucao(sql,vinculo)).isEqualTo(versao);
            String rota="/api/pacientes/"+paciente+"/vinculos/"+vinculo+"/resolucoes-frequencia";
            String tokenColega=login(app,colega),tokenRecepcao=login(app,recepcao),tokenExterno=login(app,externo);
            assertThat(http(app,"GET",rota+"/candidatos",null,null).statusCode()).isEqualTo(401);
            assertThat(http(app,"GET",rota+"/candidatos",tokenRecepcao,null).statusCode()).isEqualTo(403);
            assertThat(http(app,"GET",rota+"/candidatos",tokenExterno,null).statusCode()).isEqualTo(404);
            var candidatos=http(app,"GET",rota+"/candidatos",token,null);
            assertThat(candidatos.statusCode()).as(candidatos.body()).isEqualTo(200);
            assertThat(json(app,candidatos).path("candidatos").size()).isEqualTo(1);
            assertThat(json(app,candidatos).path("candidatos").get(0).path("fatos").size()).isEqualTo(2);
            assertThat(json(app,candidatos).path("versaoVinculo").asInt()).isEqualTo(versao);
            var dadosResolucao=Map.of("valido",Map.of("fonte","ATENDIMENTO_INDIVIDUAL","id",a.id(),"versao",a.versao()),
                    "desconsiderado",Map.of("fonte","ATENDIMENTO_INDIVIDUAL","id",b.id(),"versao",b.versao()),
                    "justificativa","Duplicidade sintética conferida pelo profissional.");
            assertThat(http(app,"POST",rota+"?version="+versao,tokenColega,dadosResolucao).statusCode()).isEqualTo(403);
            assertThat(http(app,"POST",rota+"?version="+versao,tokenRecepcao,dadosResolucao).statusCode()).isEqualTo(403);
            assertThat(http(app,"POST",rota+"?version="+versao,tokenExterno,dadosResolucao).statusCode()).isEqualTo(404);
            var resolvida=http(app,"POST",rota+"?version="+versao,token,dadosResolucao);
            assertThat(resolvida.statusCode()).as(resolvida.body()).isEqualTo(201);
            var id=UUID.fromString(json(app,resolvida).path("id").asString());
            var auditoria=http(app,"GET",rota,token,null);
            assertThat(auditoria.statusCode()).as(auditoria.body()).isEqualTo(200);
            assertThat(json(app,auditoria).path("registros").size()).isEqualTo(1);
            assertThat(json(app,auditoria).path("registros").get(0).path("id").asString()).isEqualTo(id.toString());
            assertThat(json(app,http(app,"GET",rota,tokenColega,null)).path("total").asLong()).isZero();
            assertThat(http(app,"GET",rota,tokenExterno,null).statusCode()).isEqualTo(404);
            // O validador compartilhado de versão local usa ValidationException (422).
            // A padronização global de versões obsoletas pertence à etapa 7.
            var obsoleta=http(app,"POST",rota+"?version="+versao,token,dadosResolucao);
            assertThat(obsoleta.statusCode()).as(obsoleta.body()).isEqualTo(422);
            assertThat(obsoleta.body()).contains("Versão local divergente");
            int versaoAtual=versaoResolucao(sql,vinculo);
            var repetida=http(app,"POST",rota+"?version="+versaoAtual,token,dadosResolucao);
            assertThat(repetida.statusCode()).as(repetida.body()).isEqualTo(409);
            assertThat(repetida.body()).contains("resolução incompatível");
            assertThat(versaoResolucao(sql,vinculo)).isEqualTo(versaoAtual);
            assertThat(sql.queryForObject("select count(*) from tb_resolucao_frequencia_local",Integer.class)).isEqualTo(1);
            assertThat(sql.queryForObject("select count(*) from tb_evidencia_resolucao_historica",Integer.class)).isEqualTo(1);
            assertThat(faltasResolucao(sql,vinculo)).isEqualTo(2);
            var prontuario=http(app,"GET","/api/pacientes/"+paciente+"/prontuario",token,null);
            assertThat(prontuario.statusCode()).as(prontuario.body()).isEqualTo(200);
            var leitura=json(app,prontuario);
            assertThat(leitura.path("atendimentos").size()).isEqualTo(3);
            assertThat(leitura.path("faltasComprovadas").asLong()).isEqualTo(2);
            assertThat(leitura.path("atendimentosDesconsiderados").size()).isEqualTo(1);
            assertThat(leitura.path("atendimentosDesconsiderados").get(0).asLong()).isEqualTo(b.id());
            var eventosDesconsiderados=java.util.stream.StreamSupport.stream(leitura.path("historico").spliterator(),false)
                    .filter(h->h.path("desconsiderado").asBoolean()).toList();
            assertThat(eventosDesconsiderados).hasSize(1);
            assertThat(eventosDesconsiderados.getFirst().path("agendamentoId").asLong()).isEqualTo(b.id());
            assertThat(eventosDesconsiderados.getFirst().path("numeroFaltaConsecutiva").isNull()).isTrue();
            var resumo=http(app,"GET","/api/inicio/resumo",token,null);
            assertThat(resumo.statusCode()).as(resumo.body()).isEqualTo(200);
            assertThat(json(app,resumo).path("totalFaltasUltimosSeteDias").asLong()).isEqualTo(2);
            assertThat(sql.queryForObject("select count(*) from vw_resolucao_frequencia_ativa",Integer.class)).isEqualTo(1);
            assertThat(versaoResolucao(sql,vinculo)).isGreaterThan(versao);
            assertThatThrownBy(()->com.pet.buscaativa.services.impl.OperacoesResolucaoFrequenciaTeste.corrigir(app,prof,b))
                    .isInstanceOf(com.pet.buscaativa.services.exceptions.ConflictException.class)
                    .hasMessageContaining("resolução ativa");
            int apos=versaoResolucao(sql,vinculo);
            assertThatThrownBy(()->com.pet.buscaativa.services.impl.OperacoesResolucaoFrequenciaTeste.desfazer(app,externo,paciente,vinculo,apos,id))
                    .isInstanceOf(com.pet.buscaativa.services.exceptions.ResourceNotFoundException.class);
            var desfeita=http(app,"POST",rota+"/"+id+"/desfazer?version="+apos,token,
                    Map.of("justificativa","Conferência sintética: eram atendimentos distintos."));
            assertThat(desfeita.statusCode()).as(desfeita.body()).isEqualTo(201);
            assertThat(faltasResolucao(sql,vinculo)).isEqualTo(3);
            var auditReversao=http(app,"GET",rota,token,null);
            assertThat(auditReversao.statusCode()).as(auditReversao.body()).isEqualTo(200);
            assertThat(json(app,auditReversao).path("registros").get(0).path("reversao").asString())
                    .isEqualTo(json(app,desfeita).path("id").asString());
            var revertido=http(app,"GET","/api/pacientes/"+paciente+"/prontuario",token,null);
            assertThat(revertido.statusCode()).as(revertido.body()).isEqualTo(200);
            assertThat(json(app,revertido).path("faltasComprovadas").asLong()).isEqualTo(3);
            assertThat(json(app,revertido).path("atendimentosDesconsiderados").size()).isZero();
            assertThat(sql.queryForObject("select count(*) from vw_resolucao_frequencia_ativa",Integer.class)).isZero();
            assertThat(sql.queryForObject("select count(*) from tb_resolucao_frequencia_local",Integer.class)).isEqualTo(1);
            assertThat(sql.queryForObject("select count(*) from tb_reversao_resolucao_frequencia",Integer.class)).isEqualTo(1);
            assertThat(sql.queryForObject("select count(*) from tb_agendamento where id in (?,?,?) and frequencia_assistencial='FALTOU'",Integer.class,a.id(),b.id(),c.id())).isEqualTo(3);
            // Reaproveita a fixture após reversão da resolução direta.
            var ticket=com.pet.buscaativa.services.impl.OperacoesResolucaoFrequenciaTeste.solicitar(app,prof,paciente,vinculo,versaoResolucao(sql,vinculo),b);
            assertThatThrownBy(()->com.pet.buscaativa.services.impl.OperacoesResolucaoFrequenciaTeste.resolver(app,prof,paciente,vinculo,versaoResolucao(sql,vinculo),a,b,false,ticket))
                    .isInstanceOf(com.pet.buscaativa.services.exceptions.ConflictException.class);
            var decisao=com.pet.buscaativa.services.impl.OperacoesResolucaoFrequenciaTeste.aprovar(app,admin,paciente,vinculo,versaoResolucao(sql,vinculo),ticket);
            assertThat(faltasResolucao(sql,vinculo)).isEqualTo(3);
            assertThat(sql.queryForObject("select count(*) from tb_aplicacao_pendencia_resolucao",Integer.class)).isZero();
            assertThatThrownBy(()->com.pet.buscaativa.services.impl.OperacoesResolucaoFrequenciaTeste.resolver(app,prof,paciente,vinculo,versaoResolucao(sql,vinculo),b,a,false,ticket))
                    .isInstanceOf(com.pet.buscaativa.services.exceptions.ConflictException.class);
            var vinculada=com.pet.buscaativa.services.impl.OperacoesResolucaoFrequenciaTeste.resolver(app,prof,paciente,vinculo,versaoResolucao(sql,vinculo),a,b,false,ticket);
            assertThat(faltasResolucao(sql,vinculo)).isEqualTo(2);
            assertThat(sql.queryForObject("select decisao_id from vw_aplicacao_pendencia_ativa where resolucao_id=?",UUID.class,vinculada)).isEqualTo(decisao);
            var tokenAdmin=login(app,admin);
            var painel=http(app,"GET","/api/relatorios/pendencias",tokenAdmin,null);
            assertThat(painel.statusCode()).as(painel.body()).isEqualTo(200);
            var item=json(app,painel).path("itens").get(0);
            assertThat(item.path("estado").asString()).isEqualTo("APROVADA");
            assertThat(item.path("aplicacao").asString()).isEqualTo("RESOLUCAO_REGISTRADA");
            com.pet.buscaativa.services.impl.OperacoesResolucaoFrequenciaTeste.desfazer(app,prof,paciente,vinculo,versaoResolucao(sql,vinculo),vinculada);
            assertThat(faltasResolucao(sql,vinculo)).isEqualTo(3);
            assertThat(sql.queryForObject("select count(*) from vw_aplicacao_pendencia_ativa",Integer.class)).isZero();
            assertThat(sql.queryForObject("select count(*) from tb_aplicacao_pendencia_resolucao",Integer.class)).isEqualTo(1);
            var painelRevertido=http(app,"GET","/api/relatorios/pendencias",tokenAdmin,null);
            assertThat(painelRevertido.statusCode()).as(painelRevertido.body()).isEqualTo(200);
            assertThat(json(app,painelRevertido).path("itens").get(0).path("aplicacao").asString()).isEqualTo("REVERTIDA");
            assertThatThrownBy(()->sql.update("delete from tb_aplicacao_pendencia_resolucao where resolucao_id=?",vinculada))
                    .isInstanceOf(org.springframework.dao.DataAccessException.class);
            assertThat(sql.queryForObject("select count(*) from tb_evidencia_resolucao_historica",Integer.class)).isEqualTo(4);
            var limite=Instant.parse("2026-11-25T00:00:00Z");
            var unidadeChave="USF:"+unidade;
            assertThat(com.pet.buscaativa.migration.EvidenciaResolucaoHistorica.conferir(sql,unidadeChave,limite,"base").consistente()).isTrue();
            assertThatThrownBy(()->sql.update("delete from tb_evidencia_resolucao_historica where evento_id=?",vinculada))
                    .isInstanceOf(org.springframework.dao.DataAccessException.class);
            // Inserção SQL sintética sem passagem pelo escritor: não fabricar comprovante.
            sql.update("""
                    insert into tb_resolucao_frequencia_local
                    (id,vinculo_id,paciente_id,unidade_chave,fonte_valida,fato_valido,versao_valida,frequencia_valida,responsavel_valido,
                     fonte_desconsiderada,fato_desconsiderado,versao_desconsiderada,frequencia_desconsiderada,responsavel_desconsiderado,
                     horario,ator_id,perfil,justificativa,registrado_em)
                    select ?,vinculo_id,paciente_id,unidade_chave,fonte_valida,fato_valido,versao_valida,frequencia_valida,responsavel_valido,
                     fonte_desconsiderada,fato_desconsiderado,versao_desconsiderada,frequencia_desconsiderada,responsavel_desconsiderado,
                     horario,ator_id,perfil,justificativa,registrado_em from tb_resolucao_frequencia_local where id=?
                    """,UUID.randomUUID(),vinculada);
            assertThat(com.pet.buscaativa.migration.EvidenciaResolucaoHistorica.conferir(sql,unidadeChave,limite,"base").consistente()).isFalse();
            assertThat(com.pet.buscaativa.migration.EvidenciaResolucaoHistorica.conferir(sql,"CAPS_I",limite,"base").consistente()).isTrue();
            var inconclusivo=http(app,"GET","/api/pacientes/"+paciente+"/prontuario",token,null);
            assertThat(inconclusivo.statusCode()).as(inconclusivo.body()).isEqualTo(200);
            var semProva=json(app,inconclusivo);
            assertThat(semProva.path("atendimentos").size()).isEqualTo(3);
            assertThat(semProva.path("atendimentosDesconsiderados").size()).isZero();
            assertThat(semProva.path("faltasComprovadas").isNull()).isTrue();
            assertThat(semProva.path("presencasComprovadas").isNull()).isTrue();
            assertThat(semProva.path("historicoLocalRequerConferencia").asBoolean()).isTrue();
            validarResolucaoColetivaConcorrente(app,sql,unidade,prof,colega,token,tokenColega,tokenAdmin,tokenExterno);
            validarRevisaoOrigemHistorico(app,sql,unidade,token,tokenAdmin,tokenExterno,tokenRecepcao,prof,admin,externo);
        }
    }

    /** Reutiliza o banco V26, mas com outra identidade e vínculo independentes. */
    private void validarResolucaoColetivaConcorrente(ConfigurableApplicationContext app,JdbcTemplate sql,long unidade,
            String prof,String colega,String tokenProf,String tokenColega,String tokenAdmin,String tokenExterno) throws Exception {
        var novo=http(app,"POST","/api/pacientes?ignorarSimilaridade=true",tokenProf,
                cadastro(cpfSintetico(91),cnsSintetico(91),"Reconciliação coletiva",unidade));
        assertThat(novo.statusCode()).as(novo.body()).isEqualTo(201);
        UUID paciente=UUID.fromString(json(app,novo).path("paciente").asString());
        UUID vinculo=UUID.fromString(json(app,novo).path("vinculo").asString());
        long pessoa=sql.queryForObject("select id from tb_paciente where id_publico=?",Long.class,paciente);
        long local=sql.queryForObject("select id from tb_vinculo_assistencial where id_publico=?",Long.class,vinculo);
        UUID coordenador=sql.queryForObject("select id_publico from tb_usuario where email=?",UUID.class,prof);
        long outro=sql.queryForObject("select id from tb_usuario where email=?",Long.class,colega);
        var clock=(RelogioMutavel)app.getBean(Clock.class);
        var data=LocalDate.now(clock).plusDays(1);
        var grupo=http(app,"POST","/api/grupos",tokenAdmin,Map.of("tema","Reconciliação coletiva sintética",
                "coordenadorId",coordenador,"recorrencia","UNICA","dataPrimeiraSessao",data,
                "horario","09:00","participantesIds",List.of(paciente)));
        assertThat(grupo.statusCode()).as(grupo.body()).isEqualTo(200);
        long grupoId=json(app,grupo).path("grupo").asLong();
        long sessao=json(app,grupo).path("sessoes").get(0).asLong();
        clock.avancar(data.atTime(12,0).atZone(clock.getZone()).toInstant());
        int versaoSessao=sql.queryForObject("select version from tb_sessao_grupo where id=?",Integer.class,sessao);
        var confirmada=http(app,"POST","/api/grupos/sessoes/"+sessao+"/confirmacao-ocorrencia",tokenProf,
                Map.of("ocorreu",true,"version",versaoSessao,"frequencias",
                        List.of(Map.of("pacienteId",paciente,"statusPresenca","PRESENTE"))));
        assertThat(confirmada.statusCode()).as(confirmada.body()).isEqualTo(200);
        long participante=sql.queryForObject("select id from tb_sessao_grupo_participante where sessao_grupo_id=? and paciente_id=?",
                Long.class,sessao,pessoa);
        int versaoGrupoFato=sql.queryForObject("select version from tb_sessao_grupo where id=?",Integer.class,sessao);
        // Fixture legada deliberada, sem relaxar o bloqueio de novas reservas.
        var falta=com.pet.buscaativa.services.impl.OperacoesResolucaoFrequenciaTeste.falta(app,colega,pessoa,outro,data,9);
        assertThat(sql.queryForObject("select pendencia_empate_local from tb_vinculo_assistencial where id=?",Boolean.class,local)).isTrue();
        String rota="/api/pacientes/"+paciente+"/vinculos/"+vinculo+"/resolucoes-frequencia";
        String revisoes="/api/pacientes/"+paciente+"/vinculos/"+vinculo+"/revisoes";
        String relatorio="/api/relatorios/frequencia-grupos";
        var antes=http(app,"GET",relatorio+"?grupoId="+grupoId,tokenAdmin,null);
        assertThat(antes.statusCode()).as(antes.body()).isEqualTo(200);
        assertThat(json(app,antes).path("dados").get(0).path("quantidadePresentes").asInt()).isEqualTo(1);
        var candidatos=http(app,"GET",rota+"/candidatos",tokenAdmin,null);
        assertThat(candidatos.statusCode()).as(candidatos.body()).isEqualTo(200);
        assertThat(json(app,candidatos).path("candidatos").get(0).path("tipo").asString()).isEqualTo("FREQUENCIAS_CONTRADITORIAS");
        assertThat(json(app,http(app,"GET",rota+"/candidatos",tokenProf,null)).path("total").asLong()).isZero();
        var ticketResposta=http(app,"POST",revisoes+"?version="+versaoResolucao(sql,vinculo),tokenProf,
                Map.of("tipo","SESSAO_GRUPO","registro",sessao,"justificativa","Conferir frequência coletiva em conflito."));
        assertThat(ticketResposta.statusCode()).as(ticketResposta.body()).isEqualTo(200);
        UUID ticket=UUID.fromString(json(app,ticketResposta).asString());
        var aprovada=http(app,"POST",revisoes+"/"+ticket+"/decisao?version="+versaoResolucao(sql,vinculo),tokenAdmin,
                Map.of("decisao","APROVAR","justificativa","Revisão administrativa da frequência coletiva."));
        assertThat(aprovada.statusCode()).as(aprovada.body()).isEqualTo(200);
        var dados=Map.of("valido",Map.of("fonte","ATENDIMENTO_INDIVIDUAL","id",falta.id(),"versao",falta.versao()),
                "desconsiderado",Map.of("fonte","SESSAO_GRUPO","id",participante,"versao",versaoGrupoFato),
                "ticket",ticket,"justificativa","Conferência sintética confirma duplicidade da frequência coletiva.");
        int versao=versaoResolucao(sql,vinculo);
        assertThat(http(app,"POST",rota+"?version="+versao,tokenProf,dados).statusCode()).isEqualTo(403);
        assertThat(http(app,"POST",rota+"?version="+versao,tokenColega,dados).statusCode()).isEqualTo(403);
        var respostas=disputarResolucao(app,rota+"?version="+versao,tokenAdmin,dados);
        UUID id=UUID.fromString(json(app,respostas.stream().filter(x->x.statusCode()==201).findFirst().orElseThrow()).path("id").asString());
        assertThat(sql.queryForObject("select count(*) from vw_resolucao_frequencia_ativa where vinculo_id=?",Integer.class,local)).isEqualTo(1);
        assertThat(sql.queryForObject("select indicadores_pendentes from tb_vinculo_assistencial where id=?",Boolean.class,local)).isFalse();
        assertThat(faltasResolucao(sql,vinculo)).isEqualTo(1);
        var leitura=http(app,"GET",relatorio+"?grupoId="+grupoId,tokenAdmin,null);
        assertThat(leitura.statusCode()).as(leitura.body()).isEqualTo(200);
        var dadosRelatorio=json(app,leitura);
        assertThat(dadosRelatorio.path("sessoesPendentes").asLong()).isZero();
        assertThat(dadosRelatorio.path("participacoesDesconsideradas").asLong()).isEqualTo(1);
        assertThat(dadosRelatorio.path("dados").get(0).path("quantidadePresentes").asInt()).isZero();
        assertThat(dadosRelatorio.path("dados").get(0).path("nomesPresentes").size()).isZero();
        assertThat(sql.queryForObject("select status_presenca from tb_sessao_grupo_participante where id=?",String.class,participante)).isEqualTo("PRESENTE");
        assertThat(http(app,"GET",relatorio+"?grupoId="+grupoId,tokenExterno,null).statusCode()).isEqualTo(404);
        var xlsx=exportacaoResolucao(app,relatorio+"/excel?grupoId="+grupoId,tokenAdmin);
        assertThat(xlsx.statusCode()).isEqualTo(200);
        try(var livro=new org.apache.poi.xssf.usermodel.XSSFWorkbook(new java.io.ByteArrayInputStream(xlsx.body()))) {
            var folha=livro.getSheetAt(0);
            assertThat(folha.getRow(2).getCell(1).getStringCellValue()).contains("participações desconsideradas: 1");
            assertThat(folha.getRow(8).getCell(3).getNumericCellValue()).isZero();
            assertThat(folha.getRow(8).getCell(4).getNumericCellValue()).isZero();
        }
        var pdf=exportacaoResolucao(app,relatorio+"/pdf?grupoId="+grupoId,tokenAdmin);
        assertThat(pdf.statusCode()).isEqualTo(200);
        assertThat(new String(pdf.body(),0,5,java.nio.charset.StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
        assertThat(http(app,"PATCH","/api/grupos/sessoes/"+sessao+"/frequencias",tokenProf,
                Map.of("version",versaoGrupoFato,"frequencias",List.of(Map.of("pacienteId",paciente,"statusPresenca","FALTOU")),
                        "justificativa","Correção sintética de registro coletivo ativo.")).statusCode()).isEqualTo(409);
        int apos=versaoResolucao(sql,vinculo);
        var desfazer=Map.of("justificativa","Nova conferência: os fatos devem voltar à revisão.");
        assertThat(http(app,"POST",rota+"/"+id+"/desfazer?version="+apos,tokenProf,desfazer).statusCode()).isEqualTo(403);
        disputarResolucao(app,rota+"/"+id+"/desfazer?version="+apos,tokenAdmin,desfazer);
        assertThat(sql.queryForObject("select count(*) from tb_reversao_resolucao_frequencia where resolucao_id=?",Integer.class,id)).isEqualTo(1);
        assertThat(sql.queryForObject("select indicadores_pendentes from tb_vinculo_assistencial where id=?",Boolean.class,local)).isTrue();
        var restaurada=http(app,"GET",relatorio+"?grupoId="+grupoId,tokenAdmin,null);
        assertThat(restaurada.statusCode()).as(restaurada.body()).isEqualTo(200);
        assertThat(json(app,restaurada).path("dados").get(0).path("quantidadePresentes").asInt()).isEqualTo(1);
        // Uma segunda solicitação sem aplicação não deve desaparecer quando a primeira é reaplicada.
        var adicional=http(app,"POST",revisoes+"?version="+versaoResolucao(sql,vinculo),tokenColega,
                Map.of("tipo","AGENDAMENTO","registro",falta.id(),"justificativa","Outra conferência ainda precisa ser realizada."));
        assertThat(adicional.statusCode()).as(adicional.body()).isEqualTo(200);
        var reaplicada=http(app,"POST",rota+"?version="+versaoResolucao(sql,vinculo),tokenAdmin,dados);
        assertThat(reaplicada.statusCode()).as(reaplicada.body()).isEqualTo(201);
        assertThat(sql.queryForObject("select indicadores_pendentes from tb_vinculo_assistencial where id=?",Boolean.class,local)).isTrue();
        assertThat(sql.queryForObject("select count(*) from vw_aplicacao_pendencia_ativa where vinculo_id=?",Integer.class,local)).isEqualTo(1);
        assertThat(json(app,http(app,"GET",rota,tokenAdmin,null)).path("total").asLong()).isEqualTo(2);
    }

    private List<HttpResponse<String>> disputarResolucao(ConfigurableApplicationContext app,String rota,String token,Object dados) throws Exception {
        var prontos=new CountDownLatch(2);
        var largada=new CountDownLatch(1);
        try(var executor=Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<HttpResponse<String>> chamada=()->{
                aguardarLargada(prontos,largada);
                return http(app,"POST",rota,token,dados);
            };
            var um=executor.submit(chamada);
            var dois=executor.submit(chamada);
            try {assertThat(prontos.await(10,TimeUnit.SECONDS)).isTrue();} finally {largada.countDown();}
            var respostas=List.of(um.get(30,TimeUnit.SECONDS),dois.get(30,TimeUnit.SECONDS));
            assertThat(respostas.stream().filter(x->x.statusCode()==201).count()).as(respostas.toString()).isEqualTo(1);
            assertThat(respostas.stream().filter(x->x.statusCode()!=201).findFirst().orElseThrow().statusCode()).isIn(409,422);
            return respostas;
        }
    }

    private HttpResponse<byte[]> exportacaoResolucao(ConfigurableApplicationContext app,String rota,String token) throws Exception {
        var host=(WebServerApplicationContext)app;
        var requisicao=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+host.getWebServer().getPort()+rota))
                .timeout(java.time.Duration.ofSeconds(20)).header("Authorization","Bearer "+token).GET().build();
        return cliente.send(requisicao,HttpResponse.BodyHandlers.ofByteArray());
    }

    private UUID resultadoOrigemConcorrente(ConfigurableApplicationContext app,String rota,String token,Object dados) throws Exception {
        var respostas=disputarResolucao(app,rota,token,dados);
        return UUID.fromString(json(app,respostas.stream().filter(r->r.statusCode()==201).findFirst().orElseThrow()).path("id").asString());
    }

    private void validarRevisaoOrigemHistorico(ConfigurableApplicationContext app,JdbcTemplate sql,long unidade,
            String tokenProf,String tokenAdmin,String tokenExterno,String tokenRecepcao,String prof,String admin,String externoEmail) throws Exception {
        var dados=cadastro(cpfSintetico(92),cnsSintetico(92),"Origem histórica",unidade);
        var novo=http(app,"POST","/api/pacientes?ignorarSimilaridade=true",tokenProf,dados);
        assertThat(novo.statusCode()).as(novo.body()).isEqualTo(201);
        UUID paciente=UUID.fromString(json(app,novo).path("paciente").asString());
        UUID vinculo=UUID.fromString(json(app,novo).path("vinculo").asString());
        long pessoa=sql.queryForObject("select id from tb_paciente where id_publico=?",Long.class,paciente);
        long responsavel=sql.queryForObject("select id from tb_usuario where email=?",Long.class,prof);
        var dia=LocalDate.now(app.getBean(Clock.class)).minusDays(2);
        var falta=com.pet.buscaativa.services.impl.OperacoesResolucaoFrequenciaTeste.falta(app,prof,pessoa,responsavel,dia,9);
        var outra=com.pet.buscaativa.services.impl.OperacoesResolucaoFrequenciaTeste.falta(app,prof,pessoa,responsavel,dia,10);
        long h=sql.queryForObject("select id from tb_historico_paciente where agendamento_id=? and tipo='FALTA'",Long.class,falta.id());
        long divergente=sql.queryForObject("select id from tb_historico_paciente where agendamento_id=? and tipo='FALTA'",Long.class,outra.id());
        var confirmacao=Map.of("cpf",dados.get("cpf"),"cns",dados.get("cns"),"nome",dados.get("nome"),
                "nomeMae",dados.get("nomeMae"),"dataNascimento",dados.get("dataNascimento"),"tipoAcompanhamento","AMBOS");
        // Estabelecer os dois vínculos enquanto a proveniência externa é comprovada.
        // A proteção de início de acompanhamento recusa históricos sem origem.
        var vinculada=http(app,"POST","/api/pacientes/cadastro-unico/confirmar-vinculo",tokenExterno,confirmacao);
        assertThat(vinculada.statusCode()).as(vinculada.body()).isEqualTo(204);
        var externo=http(app,"GET","/api/pacientes/"+paciente+"/detalhe",tokenExterno,null);
        assertThat(externo.statusCode()).as(externo.body()).isEqualTo(200);
        UUID outroVinculo=UUID.fromString(json(app,externo).path("paciente").path("vinculo").asString());
        // Reutilizar a criação/realização coletiva já validada em 6.4D.
        var clock=(RelogioMutavel)app.getBean(Clock.class);
        var dataGrupo=LocalDate.now(clock).plusDays(1);
        UUID coordenador=sql.queryForObject("select id_publico from tb_usuario where email=?",UUID.class,prof);
        var grupo=http(app,"POST","/api/grupos",tokenAdmin,Map.of("tema","Origem coletiva sintética",
                "coordenadorId",coordenador,"recorrencia","UNICA","dataPrimeiraSessao",dataGrupo,
                "horario","09:00","participantesIds",List.of(paciente)));
        assertThat(grupo.statusCode()).as(grupo.body()).isEqualTo(200);
        long sessao=json(app,grupo).path("sessoes").get(0).asLong();
        clock.avancar(dataGrupo.atTime(12,0).atZone(clock.getZone()).toInstant());
        int versaoSessao=sql.queryForObject("select version from tb_sessao_grupo where id=?",Integer.class,sessao);
        var confirmada=http(app,"POST","/api/grupos/sessoes/"+sessao+"/confirmacao-ocorrencia",tokenProf,
                Map.of("ocorreu",true,"version",versaoSessao,"frequencias",
                        List.of(Map.of("pacienteId",paciente,"statusPresenca","FALTOU"))));
        assertThat(confirmada.statusCode()).as(confirmada.body()).isEqualTo(200);
        long hGrupo=sql.queryForObject("select id from tb_historico_paciente where sessao_grupo_id=? and paciente_id=? and tipo='FALTA'",Long.class,sessao,pessoa);
        long participante=sql.queryForObject("select id from tb_sessao_grupo_participante where sessao_grupo_id=? and paciente_id=?",
                Long.class,sessao,pessoa);
        // 6.5C: verificar os dados gravados pelos escritores reais antes de simular o legado.
        String chaveLocal=sql.queryForObject("select unidade_origem_chave from tb_agendamento where id=?",String.class,falta.id());
        assertThat(chaveLocal).isNotBlank();
        assertThat(sql.queryForObject("select unidade_origem_chave from tb_sessao_grupo where id=?",String.class,sessao))
                .isEqualTo(chaveLocal);
        var horarioIndividual=dia.atTime(9,0);
        var horarioColetivo=dataGrupo.atTime(9,0);
        String consultaCoerencia="""
                select count(*) from tb_historico_paciente
                where id=? and paciente_id=? and profissional_id=? and unidade_origem_chave=?
                    and ocorrido_em=? and tipo='FALTA'
                """;
        assertThat(sql.queryForObject(consultaCoerencia,Integer.class,h,pessoa,responsavel,chaveLocal,horarioIndividual))
                .isEqualTo(1);
        assertThat(sql.queryForObject(consultaCoerencia,Integer.class,hGrupo,pessoa,responsavel,chaveLocal,horarioColetivo))
                .isEqualTo(1);
        // Divergência exclusivamente sintética, mantendo unidade comprovada, para
        // testar a barreira temporal no comando HTTP sem confundi-la com origem nula.
        int versaoIndividualAntes=sql.queryForObject("select version from tb_agendamento where id=?",Integer.class,falta.id());
        int versaoGrupoAntes=sql.queryForObject("select version from tb_sessao_grupo where id=?",Integer.class,sessao);
        int versaoVinculoAntes=versaoResolucao(sql,vinculo);
        var faltasAntes=faltasResolucao(sql,vinculo);
        var faltasExternasAntes=faltasResolucao(sql,outroVinculo);
        var frequenciaGrupoAntes=sql.queryForObject("select status_presenca from tb_sessao_grupo_participante where id=?",String.class,participante);
        var auditoriasAntes=sql.queryForObject("select count(*) from tb_auditoria_correcao_frequencia where paciente_id=?",Integer.class,pessoa);
        sql.update("update tb_historico_paciente set ocorrido_em=? where id=?",horarioIndividual.plusMinutes(15),h);
        sql.update("update tb_historico_paciente set ocorrido_em=? where id=?",horarioColetivo.plusMinutes(15),hGrupo);
        var recusaIndividual=http(app,"PATCH","/api/agendamentos/"+falta.id()+"/frequencia/correcao",tokenProf,
                Map.of("novaFrequencia","PRESENTE","version",versaoIndividualAntes,
                        "justificativa","Conferência sintética de horário individual divergente."));
        assertThat(recusaIndividual.statusCode()).as(recusaIndividual.body()).isEqualTo(409);
        var recusaColetiva=http(app,"PATCH","/api/grupos/sessoes/"+sessao+"/frequencias",tokenProf,
                Map.of("version",versaoGrupoAntes,"frequencias",List.of(Map.of("pacienteId",paciente,"statusPresenca","PRESENTE")),
                        "justificativa","Conferência sintética de horário coletivo divergente."));
        assertThat(recusaColetiva.statusCode()).as(recusaColetiva.body()).isEqualTo(409);
        assertThat(sql.queryForObject("select version from tb_agendamento where id=?",Integer.class,falta.id()))
                .isEqualTo(versaoIndividualAntes);
        assertThat(sql.queryForObject("select version from tb_sessao_grupo where id=?",Integer.class,sessao))
                .isEqualTo(versaoGrupoAntes);
        assertThat(sql.queryForObject("select status_presenca from tb_sessao_grupo_participante where id=?",String.class,participante))
                .isEqualTo(frequenciaGrupoAntes);
        assertThat(sql.queryForObject(consultaCoerencia,Integer.class,h,pessoa,responsavel,chaveLocal,horarioIndividual.plusMinutes(15)))
                .isEqualTo(1);
        assertThat(sql.queryForObject(consultaCoerencia,Integer.class,hGrupo,pessoa,responsavel,chaveLocal,horarioColetivo.plusMinutes(15)))
                .isEqualTo(1);
        assertThat(versaoResolucao(sql,vinculo)).isEqualTo(versaoVinculoAntes);
        assertThat(faltasResolucao(sql,vinculo)).isEqualTo(faltasAntes);
        assertThat(faltasResolucao(sql,outroVinculo)).isEqualTo(faltasExternasAntes);
        assertThat(sql.queryForObject("select count(*) from tb_auditoria_correcao_frequencia where paciente_id=?",Integer.class,pessoa))
                .isEqualTo(auditoriasAntes);
        // Restaurar somente a fixture para preservar integralmente o cenário 6.5B a seguir.
        sql.update("update tb_historico_paciente set ocorrido_em=? where id=?",horarioIndividual,h);
        sql.update("update tb_historico_paciente set ocorrido_em=? where id=?",horarioColetivo,hGrupo);
        // Apenas fixture sintética: reproduzir campo legado vazio e fonte temporal divergente.
        sql.update("update tb_historico_paciente set unidade_origem_chave=null where id=?",hGrupo);
        sql.update("update tb_historico_paciente set unidade_origem_chave=null where id=?",h);
        sql.update("update tb_historico_paciente set unidade_origem_chave=null,ocorrido_em=? where id=?",
                dia.atTime(11,0),divergente);
        String rota="/api/pacientes/"+paciente+"/vinculos/"+vinculo+"/revisoes";
        String outraRota="/api/pacientes/"+paciente+"/vinculos/"+outroVinculo+"/revisoes";
        var candidatos=http(app,"GET",rota+"/candidatos-origem",tokenAdmin,null);
        assertThat(candidatos.statusCode()).as(candidatos.body()).isEqualTo(200);
        var lista=json(app,candidatos).path("candidatos");
        assertThat(lista.size()).isEqualTo(2);
        var itens=java.util.stream.StreamSupport.stream(lista.spliterator(),false).toList();
        assertThat(itens.stream().map(e->e.path("historico").asLong()).toList()).containsExactlyInAnyOrder(h,hGrupo);
        assertThat(itens.stream().filter(e->e.path("historico").asLong()==h).findFirst().orElseThrow()
                .path("registroFonte").asLong()).isEqualTo(falta.id());
        assertThat(itens.stream().filter(e->e.path("historico").asLong()==hGrupo).findFirst().orElseThrow()
                .path("registroFonte").asLong()).isEqualTo(sessao);
        assertThat(http(app,"GET",rota+"/candidatos-origem",tokenProf,null).statusCode()).isEqualTo(403);
        assertThat(http(app,"GET",rota+"/candidatos-origem",tokenRecepcao,null).statusCode()).isEqualTo(403);
        assertThat(http(app,"GET",rota+"/candidatos-origem",tokenExterno,null).statusCode()).isEqualTo(404);
        var listaExterna=http(app,"GET",outraRota+"/candidatos-origem",tokenExterno,null);
        assertThat(listaExterna.statusCode()).as(listaExterna.body()).isEqualTo(200);
        assertThat(json(app,listaExterna).path("candidatos").size()).isZero();
        var justificativa="Conferência sintética da origem registrada no atendimento.";
        var solicitacao=Map.of("tipo","HISTORICO","registro",h,"justificativa",justificativa);
        int versao=versaoResolucao(sql,vinculo);
        assertThat(http(app,"POST",rota+"?version="+versao,tokenProf,solicitacao).statusCode()).isEqualTo(404);
        assertThat(http(app,"POST",outraRota+"?version="+versaoResolucao(sql,outroVinculo),tokenExterno,solicitacao).statusCode()).isEqualTo(404);
        assertThat(http(app,"POST",rota+"?version="+versao,tokenAdmin,
                Map.of("tipo","HISTORICO","registro",divergente,"justificativa",justificativa)).statusCode()).isEqualTo(404);
        var criada=http(app,"POST",rota+"?version="+versao,tokenAdmin,solicitacao);
        assertThat(criada.statusCode()).as(criada.body()).isEqualTo(200);
        UUID ticket=UUID.fromString(json(app,criada).asString());
        assertThat(sql.queryForObject("select situacao_evidencia from tb_pendencia_revisao_proveniencia where id=?",String.class,ticket))
                .isEqualTo("REVISAR_ORIGEM_DESCONHECIDA");
        var aprovada=http(app,"POST",rota+"/"+ticket+"/decisao?version="+versaoResolucao(sql,vinculo),tokenAdmin,
                Map.of("decisao","APROVAR","justificativa","Origem comprovada pela fonte individual da unidade."));
        assertThat(aprovada.statusCode()).as(aprovada.body()).isEqualTo(200);
        UUID decisao=UUID.fromString(json(app,aprovada).asString());
        assertThat(sql.queryForObject("select fonte_id from tb_prova_aprovacao_origem where decisao_id=?",Long.class,decisao)).isEqualTo(falta.id());
        assertThat(sql.queryForObject("select versao_fonte from tb_prova_aprovacao_origem where decisao_id=?",Integer.class,decisao)).isEqualTo(falta.versao());
        assertThat(sql.queryForObject("select participante_id from tb_prova_aprovacao_origem where decisao_id=?",Long.class,decisao)).isNull();
        assertThatThrownBy(()->sql.update("delete from tb_prova_aprovacao_origem where decisao_id=?",decisao))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        var solicitacaoGrupo=http(app,"POST",rota+"?version="+versaoResolucao(sql,vinculo),tokenAdmin,
                Map.of("tipo","HISTORICO","registro",hGrupo,"justificativa","Conferência sintética da origem da sessão coletiva."));
        assertThat(solicitacaoGrupo.statusCode()).as(solicitacaoGrupo.body()).isEqualTo(200);
        UUID ticketGrupo=UUID.fromString(json(app,solicitacaoGrupo).asString());
        var aprovadaGrupo=http(app,"POST",rota+"/"+ticketGrupo+"/decisao?version="+versaoResolucao(sql,vinculo),tokenAdmin,
                Map.of("decisao","APROVAR","justificativa","Sessão e participação comprovadas na unidade local."));
        assertThat(aprovadaGrupo.statusCode()).as(aprovadaGrupo.body()).isEqualTo(200);
        UUID decisaoGrupo=UUID.fromString(json(app,aprovadaGrupo).asString());
        assertThat(sql.queryForObject("select fonte_id from tb_prova_aprovacao_origem where decisao_id=?",Long.class,decisaoGrupo)).isEqualTo(sessao);
        assertThat(sql.queryForObject("select participante_id from tb_prova_aprovacao_origem where decisao_id=?",Long.class,decisaoGrupo)).isEqualTo(participante);
        assertThat(sql.queryForObject("select responsavel_id from tb_prova_aprovacao_origem where decisao_id=?",Long.class,decisaoGrupo)).isEqualTo(responsavel);
        assertThat(sql.queryForObject("select count(*) from tb_prova_aprovacao_origem",Integer.class)).isEqualTo(2);
        assertThat(sql.queryForObject("select count(*) from tb_historico_paciente where id in (?,?) and unidade_origem_chave is null",Integer.class,h,hGrupo)).isEqualTo(2);
        assertThat(faltasResolucao(sql,vinculo)).isEqualTo(3);
        assertThat(faltasResolucao(sql,outroVinculo)).isZero();
        var prontuario=http(app,"GET","/api/pacientes/"+paciente+"/prontuario",tokenAdmin,null);
        assertThat(prontuario.statusCode()).as(prontuario.body()).isEqualTo(200);
        assertThat(java.util.stream.StreamSupport.stream(json(app,prontuario).path("historico").spliterator(),false)
                .noneMatch(e->e.path("id").asLong()==h || e.path("id").asLong()==hGrupo)).isTrue();
        // Reconhecer a origem do histórico derivado não cria outro fato clínico.
        var consumidoresAntes=consumidoresOrigem(app,tokenAdmin,paciente);
        var consumidoresExternosAntes=consumidoresOrigem(app,tokenExterno,paciente);
        int versaoAtual=versaoResolucao(sql,vinculo);
        assertThatThrownBy(()->com.pet.buscaativa.services.impl.OperacoesResolucaoFrequenciaTeste.aplicarOrigem(
                app,prof,paciente,vinculo,versaoAtual,ticket,false))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        assertThatThrownBy(()->com.pet.buscaativa.services.impl.OperacoesResolucaoFrequenciaTeste.aplicarOrigem(
                app,externoEmail,paciente,outroVinculo,versaoResolucao(sql,outroVinculo),ticket,false))
                .isInstanceOf(com.pet.buscaativa.services.exceptions.ResourceNotFoundException.class);
        com.pet.buscaativa.services.impl.OperacoesResolucaoFrequenciaTeste.aplicarOrigem(
                app,admin,paciente,vinculo,versaoAtual,ticket,true);
        assertThat(sql.queryForObject("select count(*) from tb_aplicacao_origem_historica",Integer.class)).isZero();
        String aplicacaoRota=rota+"/"+ticket+"/origem/aplicar?version="+versaoAtual;
        String auditoriaRota=rota+"/"+ticket+"/origem/auditoria";
        var comandoOrigem=Map.of("justificativa","Aplicação sintética após conferência da fonte assistencial.");
        assertThat(http(app,"POST",aplicacaoRota,null,comandoOrigem).statusCode()).isEqualTo(401);
        assertThat(http(app,"POST",aplicacaoRota,tokenProf,comandoOrigem).statusCode()).isEqualTo(403);
        assertThat(http(app,"POST",aplicacaoRota,tokenRecepcao,comandoOrigem).statusCode()).isEqualTo(403);
        assertThat(http(app,"POST",outraRota+"/"+ticket+"/origem/aplicar?version="+versaoResolucao(sql,outroVinculo),
                tokenExterno,comandoOrigem).statusCode()).isEqualTo(404);
        assertThat(http(app,"POST",rota+"/"+ticket+"/origem/aplicar?version="+(versaoAtual+1),
                tokenAdmin,comandoOrigem).statusCode()).isEqualTo(422);
        assertThat(http(app,"POST",aplicacaoRota,tokenAdmin,Map.of("justificativa","")).statusCode()).isEqualTo(422);
        var antesAplicacao=http(app,"GET",auditoriaRota,tokenAdmin,null);
        assertThat(antesAplicacao.statusCode()).as(antesAplicacao.body()).isEqualTo(200);
        assertThat(json(app,antesAplicacao).path("estadoAplicacao").asString()).isEqualTo("SEM_APLICACAO");
        assertThat(http(app,"GET",auditoriaRota,tokenProf,null).statusCode()).isEqualTo(403);
        assertThat(http(app,"GET",auditoriaRota,tokenRecepcao,null).statusCode()).isEqualTo(403);
        assertThat(http(app,"GET",outraRota+"/"+ticket+"/origem/auditoria",tokenExterno,null).statusCode()).isEqualTo(404);
        var aplicada=resultadoOrigemConcorrente(app,aplicacaoRota,tokenAdmin,comandoOrigem);
        assertThat(consumidoresOrigem(app,tokenAdmin,paciente)).isEqualTo(consumidoresAntes);
        assertThat(consumidoresOrigem(app,tokenExterno,paciente)).isEqualTo(consumidoresExternosAntes);
        var auditada=http(app,"GET",auditoriaRota,tokenAdmin,null);
        assertThat(auditada.statusCode()).as(auditada.body()).isEqualTo(200);
        var trilha=json(app,auditada);
        assertThat(trilha.path("solicitacao").path("id").asString()).isEqualTo(ticket.toString());
        assertThat(trilha.path("decisao").path("evento").path("id").asString()).isEqualTo(decisao.toString());
        assertThat(trilha.path("aplicacao").path("id").asString()).isEqualTo(aplicada.toString());
        assertThat(trilha.path("aplicacao").path("justificativa").asString()).isEqualTo(comandoOrigem.get("justificativa"));
        assertThat(trilha.path("estadoAplicacao").asString()).isEqualTo("ORIGEM_RECONHECIDA");
        assertThat(trilha.path("integridadeEventos").asBoolean()).isTrue();
        assertThat(sql.queryForObject("select decisao_id from tb_aplicacao_origem_historica where id=?",UUID.class,aplicada)).isEqualTo(decisao);
        assertThat(sql.queryForObject("select historico_id from tb_aplicacao_origem_historica where id=?",Long.class,aplicada)).isEqualTo(h);
        assertThatThrownBy(()->com.pet.buscaativa.services.impl.OperacoesResolucaoFrequenciaTeste.aplicarOrigem(
                app,admin,paciente,vinculo,versaoAtual,ticket,false))
                .isInstanceOf(com.pet.buscaativa.services.exceptions.ConflictException.class);
        assertThatThrownBy(()->sql.update("delete from tb_aplicacao_origem_historica where id=?",aplicada))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        // Fixture: versão da sessão mudou após aprovação, mas os valores continuam coerentes.
        sql.update("update tb_sessao_grupo set version=version+1 where id=?",sessao);
        assertThatThrownBy(()->com.pet.buscaativa.services.impl.OperacoesResolucaoFrequenciaTeste.aplicarOrigem(
                app,admin,paciente,vinculo,versaoAtual,ticketGrupo,false))
                .isInstanceOf(com.pet.buscaativa.services.exceptions.ConflictException.class)
                .hasMessageContaining("desatualizada");
        assertThat(sql.queryForObject("select count(*) from tb_aplicacao_origem_historica",Integer.class)).isEqualTo(1);
        assertThat(sql.queryForObject("select count(*) from tb_historico_paciente where id in (?,?) and unidade_origem_chave is null",Integer.class,h,hGrupo)).isEqualTo(2);
        assertThat(faltasResolucao(sql,vinculo)).isEqualTo(3);
        assertThat(faltasResolucao(sql,outroVinculo)).isZero();
        // A leitura reconhece a aplicação individual válida, não a coletiva obsoleta.
        var reconhecido=http(app,"GET","/api/pacientes/"+paciente+"/prontuario",tokenProf,null);
        assertThat(reconhecido.statusCode()).as(reconhecido.body()).isEqualTo(200);
        var leitura=json(app,reconhecido);
        var eventos=java.util.stream.StreamSupport.stream(leitura.path("historico").spliterator(),false).toList();
        var evento=eventos.stream().filter(e->e.path("id").asLong()==h).findFirst().orElseThrow();
        assertThat(evento.path("origemCorrigida").asBoolean()).isTrue();
        assertThat(evento.path("unidadeOrigem").asString()).isEqualTo("USF:"+unidade);
        assertThat(eventos.stream().noneMatch(e->e.path("id").asLong()==hGrupo || e.path("id").asLong()==divergente)).isTrue();
        assertThat(leitura.path("faltasComprovadas").asLong()).isEqualTo(3);
        var prontuarioExterno=http(app,"GET","/api/pacientes/"+paciente+"/prontuario",tokenExterno,null);
        assertThat(prontuarioExterno.statusCode()).as(prontuarioExterno.body()).isEqualTo(200);
        assertThat(java.util.stream.StreamSupport.stream(json(app,prontuarioExterno).path("historico").spliterator(),false)
                .noneMatch(e->e.path("id").asLong()==h || e.path("id").asLong()==hGrupo)).isTrue();
        var painel=http(app,"GET","/api/relatorios/pendencias?tamanho=100",tokenAdmin,null);
        assertThat(painel.statusCode()).as(painel.body()).isEqualTo(200);
        var item=java.util.stream.StreamSupport.stream(json(app,painel).path("itens").spliterator(),false)
                .filter(e->e.path("ticket").asString().equals(ticket.toString())).findFirst().orElseThrow();
        assertThat(item.path("revisaoOrigem").asBoolean()).isTrue();
        assertThat(item.path("aplicacaoOrigem").asString()).isEqualTo("ORIGEM_RECONHECIDA");
        assertThat(item.path("registroAplicacaoOrigem").asString()).isEqualTo(aplicada.toString());
        assertThat(item.path("aplicacao").asString()).isEqualTo("SEM_APLICACAO");
        assertThatThrownBy(()->com.pet.buscaativa.services.impl.OperacoesResolucaoFrequenciaTeste.desfazerOrigem(
                app,prof,paciente,vinculo,versaoAtual,aplicada,false))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        assertThatThrownBy(()->com.pet.buscaativa.services.impl.OperacoesResolucaoFrequenciaTeste.desfazerOrigem(
                app,externoEmail,paciente,outroVinculo,versaoResolucao(sql,outroVinculo),aplicada,false))
                .isInstanceOf(com.pet.buscaativa.services.exceptions.ResourceNotFoundException.class);
        com.pet.buscaativa.services.impl.OperacoesResolucaoFrequenciaTeste.desfazerOrigem(
                app,admin,paciente,vinculo,versaoAtual,aplicada,true);
        assertThat(sql.queryForObject("select count(*) from tb_reversao_origem_historica",Integer.class)).isZero();
        var leituraAposRollback=http(app,"GET","/api/pacientes/"+paciente+"/prontuario",tokenProf,null);
        assertThat(leituraAposRollback.statusCode()).as(leituraAposRollback.body()).isEqualTo(200);
        assertThat(java.util.stream.StreamSupport.stream(json(app,leituraAposRollback).path("historico").spliterator(),false)
                .anyMatch(e->e.path("id").asLong()==h && e.path("origemCorrigida").asBoolean())).isTrue();
        // Simular divergência posterior de versão da fonte, sem alterar a aprovação.
        sql.update("update tb_agendamento set version=version+1 where id=?",falta.id());
        var inconclusivo=http(app,"GET","/api/pacientes/"+paciente+"/prontuario",tokenAdmin,null);
        assertThat(inconclusivo.statusCode()).as(inconclusivo.body()).isEqualTo(200);
        var semOrigem=json(app,inconclusivo);
        assertThat(semOrigem.path("historicoLocalRequerConferencia").asBoolean()).isTrue();
        assertThat(java.util.stream.StreamSupport.stream(semOrigem.path("historico").spliterator(),false)
                .noneMatch(e->e.path("id").asLong()==h)).isTrue();
        assertThat(semOrigem.path("faltasComprovadas").asLong()).isEqualTo(3);
        var painelInconclusivo=http(app,"GET","/api/relatorios/pendencias?tamanho=100",tokenAdmin,null);
        assertThat(painelInconclusivo.statusCode()).as(painelInconclusivo.body()).isEqualTo(200);
        var itemInconclusivo=java.util.stream.StreamSupport.stream(json(app,painelInconclusivo).path("itens").spliterator(),false)
                .filter(e->e.path("ticket").asString().equals(ticket.toString())).findFirst().orElseThrow();
        assertThat(itemInconclusivo.path("aplicacaoOrigem").asString()).isEqualTo("INCONCLUSIVA");
        assertThat(sql.queryForObject("select count(*) from tb_aplicacao_origem_historica where id=?",Integer.class,aplicada)).isEqualTo(1);
        // Reversão deve funcionar inclusive depois que a fonte se tornou incompatível.
        String reversaoRota=rota+"/origem/aplicacoes/"+aplicada+"/desfazer?version="+versaoAtual;
        var comandoReversao=Map.of("justificativa","Nova conferência sintética: retirar o reconhecimento de origem.");
        assertThat(http(app,"POST",reversaoRota,tokenProf,comandoReversao).statusCode()).isEqualTo(403);
        assertThat(http(app,"POST",outraRota+"/origem/aplicacoes/"+aplicada+"/desfazer?version="+versaoResolucao(sql,outroVinculo),
                tokenExterno,comandoReversao).statusCode()).isEqualTo(404);
        var revertida=resultadoOrigemConcorrente(app,reversaoRota,tokenAdmin,comandoReversao);
        assertThat(consumidoresOrigem(app,tokenAdmin,paciente)).isEqualTo(consumidoresAntes);
        assertThat(consumidoresOrigem(app,tokenExterno,paciente)).isEqualTo(consumidoresExternosAntes);
        var auditadaReversao=http(app,"GET",auditoriaRota,tokenAdmin,null);
        assertThat(auditadaReversao.statusCode()).as(auditadaReversao.body()).isEqualTo(200);
        assertThat(json(app,auditadaReversao).path("reversao").path("id").asString()).isEqualTo(revertida.toString());
        assertThat(json(app,auditadaReversao).path("estadoAplicacao").asString()).isEqualTo("REVERTIDA");
        assertThat(sql.queryForObject("select aplicacao_id from tb_reversao_origem_historica where id=?",UUID.class,revertida)).isEqualTo(aplicada);
        assertThatThrownBy(()->com.pet.buscaativa.services.impl.OperacoesResolucaoFrequenciaTeste.desfazerOrigem(
                app,admin,paciente,vinculo,versaoAtual,aplicada,false))
                .isInstanceOf(com.pet.buscaativa.services.exceptions.ConflictException.class);
        assertThatThrownBy(()->com.pet.buscaativa.services.impl.OperacoesResolucaoFrequenciaTeste.aplicarOrigem(
                app,admin,paciente,vinculo,versaoAtual,ticket,false))
                .isInstanceOf(com.pet.buscaativa.services.exceptions.ConflictException.class);
        assertThatThrownBy(()->sql.update("delete from tb_reversao_origem_historica where id=?",revertida))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        var painelRevertido=http(app,"GET","/api/relatorios/pendencias?tamanho=100",tokenAdmin,null);
        assertThat(painelRevertido.statusCode()).as(painelRevertido.body()).isEqualTo(200);
        var itemRevertido=java.util.stream.StreamSupport.stream(json(app,painelRevertido).path("itens").spliterator(),false)
                .filter(e->e.path("ticket").asString().equals(ticket.toString())).findFirst().orElseThrow();
        assertThat(itemRevertido.path("estado").asString()).isEqualTo("APROVADA");
        assertThat(itemRevertido.path("aplicacaoOrigem").asString()).isEqualTo("REVERTIDA");
        assertThat(itemRevertido.path("registroAplicacaoOrigem").asString()).isEqualTo(aplicada.toString());
        assertThat(itemRevertido.path("registroReversaoOrigem").asString()).isEqualTo(revertida.toString());
        var prontuarioRevertido=http(app,"GET","/api/pacientes/"+paciente+"/prontuario",tokenProf,null);
        assertThat(prontuarioRevertido.statusCode()).as(prontuarioRevertido.body()).isEqualTo(200);
        assertThat(java.util.stream.StreamSupport.stream(json(app,prontuarioRevertido).path("historico").spliterator(),false)
                .noneMatch(e->e.path("id").asLong()==h)).isTrue();
        assertThat(json(app,prontuarioRevertido).path("faltasComprovadas").asLong()).isEqualTo(3);
        assertThat(sql.queryForObject("select unidade_origem_chave from tb_historico_paciente where id=?",String.class,h)).isNull();
        assertThat(sql.queryForObject("select count(*) from tb_aplicacao_origem_historica where id=?",Integer.class,aplicada)).isEqualTo(1);
        assertThat(sql.queryForObject("select count(*) from tb_prova_aprovacao_origem where decisao_id=?",Integer.class,decisao)).isEqualTo(1);
        assertThat(faltasResolucao(sql,outroVinculo)).isZero();
        // Uma aplicação revertida e uma aprovação coletiva obsoleta permitem novas rodadas distintas.
        assertThatThrownBy(()->com.pet.buscaativa.services.impl.OperacoesResolucaoFrequenciaTeste.reabrirOrigem(
                app,prof,paciente,vinculo,versaoAtual,ticket,false))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        assertThatThrownBy(()->com.pet.buscaativa.services.impl.OperacoesResolucaoFrequenciaTeste.reabrirOrigem(
                app,externoEmail,paciente,outroVinculo,versaoResolucao(sql,outroVinculo),ticket,false))
                .isInstanceOf(com.pet.buscaativa.services.exceptions.ResourceNotFoundException.class);
        com.pet.buscaativa.services.impl.OperacoesResolucaoFrequenciaTeste.reabrirOrigem(
                app,admin,paciente,vinculo,versaoAtual,ticket,true);
        assertThat(sql.queryForObject("select count(*) from tb_reabertura_origem_historica",Integer.class)).isZero();
        String reaberturaRota=rota+"/"+ticket+"/origem/reabrir?version="+versaoAtual;
        var comandoReabertura=Map.of("justificativa","Nova análise sintética da origem do registro histórico.");
        assertThat(http(app,"POST",reaberturaRota,tokenProf,comandoReabertura).statusCode()).isEqualTo(403);
        assertThat(http(app,"POST",outraRota+"/"+ticket+"/origem/reabrir?version="+versaoResolucao(sql,outroVinculo),
                tokenExterno,comandoReabertura).statusCode()).isEqualTo(404);
        var nova=resultadoOrigemConcorrente(app,reaberturaRota,tokenAdmin,comandoReabertura);
        var auditadaReabertura=http(app,"GET",rota+"/"+nova+"/origem/auditoria",tokenAdmin,null);
        assertThat(auditadaReabertura.statusCode()).as(auditadaReabertura.body()).isEqualTo(200);
        assertThat(json(app,auditadaReabertura).path("reaberturaAnterior").path("anterior").asString()).isEqualTo(ticket.toString());
        assertThat(json(app,auditadaReabertura).path("reaberturaAnterior").path("motivo").asString()).isEqualTo("APLICACAO_REVERTIDA");
        assertThat(json(app,auditadaReabertura).path("estadoAplicacao").asString()).isEqualTo("SEM_APLICACAO");
        assertThatThrownBy(()->com.pet.buscaativa.services.impl.OperacoesResolucaoFrequenciaTeste.reabrirOrigem(
                app,admin,paciente,vinculo,versaoAtual,ticket,false))
                .isInstanceOf(com.pet.buscaativa.services.exceptions.ConflictException.class);
        assertThatThrownBy(()->com.pet.buscaativa.services.impl.OperacoesResolucaoFrequenciaTeste.aplicarOrigem(
                app,admin,paciente,vinculo,versaoAtual,nova,false))
                .isInstanceOf(com.pet.buscaativa.services.exceptions.ConflictException.class);
        var novaDecisao=com.pet.buscaativa.services.impl.OperacoesResolucaoFrequenciaTeste.aprovar(
                app,admin,paciente,vinculo,versaoAtual,nova);
        assertThat(novaDecisao).isNotEqualTo(decisao);
        var novaAplicacao=com.pet.buscaativa.services.impl.OperacoesResolucaoFrequenciaTeste.aplicarOrigem(
                app,admin,paciente,vinculo,versaoAtual,nova,false);
        assertThat(novaAplicacao).isNotEqualTo(aplicada);
        assertThatThrownBy(()->com.pet.buscaativa.services.impl.OperacoesResolucaoFrequenciaTeste.reabrirOrigem(
                app,admin,paciente,vinculo,versaoAtual,nova,false))
                .isInstanceOf(com.pet.buscaativa.services.exceptions.ConflictException.class);
        var novaGrupo=com.pet.buscaativa.services.impl.OperacoesResolucaoFrequenciaTeste.reabrirOrigem(
                app,admin,paciente,vinculo,versaoAtual,ticketGrupo,false);
        assertThat(sql.queryForObject("select motivo from tb_reabertura_origem_historica where novo_id=?",String.class,novaGrupo))
                .isEqualTo("APROVACAO_DESATUALIZADA");
        assertThatThrownBy(()->com.pet.buscaativa.services.impl.OperacoesResolucaoFrequenciaTeste.aplicarOrigem(
                app,admin,paciente,vinculo,versaoAtual,ticketGrupo,false))
                .isInstanceOf(com.pet.buscaativa.services.exceptions.ConflictException.class);
        var novaDecisaoGrupo=com.pet.buscaativa.services.impl.OperacoesResolucaoFrequenciaTeste.aprovar(
                app,admin,paciente,vinculo,versaoAtual,novaGrupo);
        assertThat(novaDecisaoGrupo).isNotEqualTo(decisaoGrupo);
        var aplicadaGrupoHttp=http(app,"POST",rota+"/"+novaGrupo+"/origem/aplicar?version="+versaoAtual,
                tokenAdmin,comandoOrigem);
        assertThat(aplicadaGrupoHttp.statusCode()).as(aplicadaGrupoHttp.body()).isEqualTo(201);
        var novaAplicacaoGrupo=UUID.fromString(json(app,aplicadaGrupoHttp).path("id").asString());
        assertThat(sql.queryForObject("select historico_id from tb_aplicacao_origem_historica where id=?",Long.class,novaAplicacaoGrupo)).isEqualTo(hGrupo);
        assertThat(consumidoresOrigem(app,tokenAdmin,paciente)).isEqualTo(consumidoresAntes);
        assertThat(consumidoresOrigem(app,tokenExterno,paciente)).isEqualTo(consumidoresExternosAntes);
        var reaberto=http(app,"GET","/api/pacientes/"+paciente+"/prontuario",tokenProf,null);
        assertThat(reaberto.statusCode()).as(reaberto.body()).isEqualTo(200);
        var novosEventos=java.util.stream.StreamSupport.stream(json(app,reaberto).path("historico").spliterator(),false)
                .filter(e->e.path("origemCorrigida").asBoolean()).map(e->e.path("id").asLong()).toList();
        assertThat(novosEventos).containsExactlyInAnyOrder(h,hGrupo);
        assertThat(json(app,reaberto).path("faltasComprovadas").asLong()).isEqualTo(3);
        var painelNovo=http(app,"GET","/api/relatorios/pendencias?tamanho=100",tokenAdmin,null);
        assertThat(painelNovo.statusCode()).as(painelNovo.body()).isEqualTo(200);
        var itensNovos=java.util.stream.StreamSupport.stream(json(app,painelNovo).path("itens").spliterator(),false).toList();
        var antigo=itensNovos.stream().filter(e->e.path("ticket").asString().equals(ticket.toString())).findFirst().orElseThrow();
        var novoItem=itensNovos.stream().filter(e->e.path("ticket").asString().equals(nova.toString())).findFirst().orElseThrow();
        assertThat(antigo.path("aplicacaoOrigem").asString()).isEqualTo("REVERTIDA");
        assertThat(antigo.path("novaSolicitacao").asString()).isEqualTo(nova.toString());
        assertThat(novoItem.path("solicitacaoAnterior").asString()).isEqualTo(ticket.toString());
        assertThat(novoItem.path("aplicacaoOrigem").asString()).isEqualTo("ORIGEM_RECONHECIDA");
        assertThat(sql.queryForObject("select count(*) from tb_aplicacao_origem_historica where historico_id=?",Integer.class,h)).isEqualTo(2);
        assertThatThrownBy(()->sql.update("delete from tb_reabertura_origem_historica where novo_id=?",nova))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        // A correção autorizada de frequência não exige uma nova aprovação de origem.
        // A mesma trilha já usada pelos escritores comprova a evolução do fato.
        assertThat(sql.queryForObject("select numero_falta_consecutiva from tb_historico_paciente where id=?",Integer.class,h)).isEqualTo(1);
        assertThat(sql.queryForObject("select numero_falta_consecutiva from tb_historico_paciente where id=?",Integer.class,hGrupo)).isEqualTo(3);
        int versaoIndividualAtual=sql.queryForObject("select version from tb_agendamento where id=?",Integer.class,falta.id());
        com.pet.buscaativa.services.impl.OperacoesResolucaoFrequenciaTeste.corrigir(app,prof,
                new com.pet.buscaativa.services.impl.OperacoesResolucaoFrequenciaTeste.Reserva(falta.id(),versaoIndividualAtual));
        var corrigido=http(app,"GET","/api/pacientes/"+paciente+"/prontuario",tokenProf,null);
        assertThat(corrigido.statusCode()).as(corrigido.body()).isEqualTo(200);
        var leituraCorrigida=json(app,corrigido);
        // Contrato real do escritor: unidade da auditoria não é a chave canônica.
        String chaveAuditoria="USF:"+sql.queryForObject("select cnes from tb_usf_referencia where id=?",String.class,unidade);
        assertThat(sql.queryForObject("select unidade from tb_auditoria_correcao_frequencia where agendamento_id=?",String.class,falta.id()))
                .isEqualTo(chaveAuditoria).isNotEqualTo("USF:"+unidade);
        assertThat(sql.queryForObject("select versao_anterior from tb_auditoria_correcao_frequencia where agendamento_id=?",Integer.class,falta.id()))
                .isEqualTo(versaoIndividualAtual);
        assertThat(sql.queryForObject("select versao_resultante from tb_auditoria_correcao_frequencia where agendamento_id=?",Integer.class,falta.id()))
                .isEqualTo(sql.queryForObject("select version from tb_agendamento where id=?",Integer.class,falta.id()));
        assertThat(java.util.stream.StreamSupport.stream(leituraCorrigida.path("historico").spliterator(),false)
                .anyMatch(e->e.path("id").asLong()==h && e.path("origemCorrigida").asBoolean()
                        && e.path("situacao").asString().equals("PRESENTE"))).as("Prontuário após correção individual: %s",corrigido.body()).isTrue();
        assertThat(leituraCorrigida.path("faltasComprovadas").asLong()).isEqualTo(2);
        assertThat(sql.queryForObject("select numero_falta_consecutiva from tb_historico_paciente where id=?",Integer.class,h)).isNull();
        assertThat(sql.queryForObject("select numero_falta_consecutiva from tb_historico_paciente where id=?",Integer.class,hGrupo)).isEqualTo(2);
        int versaoColetivaAtual=sql.queryForObject("select version from tb_sessao_grupo where id=?",Integer.class,sessao);
        var corrigidaGrupo=http(app,"PATCH","/api/grupos/sessoes/"+sessao+"/frequencias",tokenProf,
                Map.of("version",versaoColetivaAtual,"frequencias",List.of(Map.of("pacienteId",paciente,"statusPresenca","PRESENTE")),
                        "justificativa","Conferência sintética da frequência coletiva reconhecida."));
        assertThat(corrigidaGrupo.statusCode()).as(corrigidaGrupo.body()).isEqualTo(200);
        var leituraFinal=http(app,"GET","/api/pacientes/"+paciente+"/prontuario",tokenProf,null);
        assertThat(leituraFinal.statusCode()).as(leituraFinal.body()).isEqualTo(200);
        var finalJson=json(app,leituraFinal);
        assertThat(sql.queryForObject("select unidade from tb_auditoria_correcao_frequencia where participante_id=?",String.class,participante))
                .isEqualTo(chaveAuditoria);
        assertThat(java.util.stream.StreamSupport.stream(finalJson.path("historico").spliterator(),false)
                .filter(e->e.path("origemCorrigida").asBoolean() && e.path("situacao").asString().equals("PRESENTE"))
                .map(e->e.path("id").asLong()).toList()).as("Prontuário após correção coletiva: %s",leituraFinal.body()).containsExactlyInAnyOrder(h,hGrupo);
        assertThat(finalJson.path("faltasComprovadas").asLong()).isEqualTo(1);
        assertThat(finalJson.path("presencasComprovadas").asLong()).isEqualTo(2);
        assertThat(sql.queryForObject("select count(*) from tb_historico_paciente where id in (?,?) and unidade_origem_chave is null",Integer.class,h,hGrupo)).isEqualTo(2);
        assertThat(sql.queryForObject("select count(*) from tb_prova_aprovacao_origem where decisao_id in (?,?) and frequencia='FALTOU'",Integer.class,novaDecisao,novaDecisaoGrupo)).isEqualTo(2);
        assertThat(sql.queryForObject("select count(*) from tb_auditoria_correcao_frequencia where paciente_id=?",Integer.class,pessoa)).isEqualTo(2);
        assertThat(sql.queryForObject("select count(*) from tb_aplicacao_origem_historica where historico_id in (?,?)",Integer.class,h,hGrupo)).isEqualTo(3);
        assertThat(faltasResolucao(sql,outroVinculo)).isZero();
        // Nova versão sem correção auditada continua inconclusiva; não ampliar a prova.
        sql.update("update tb_agendamento set version=version+1 where id=?",falta.id());
        var semProvaPosterior=http(app,"GET","/api/pacientes/"+paciente+"/prontuario",tokenProf,null);
        assertThat(semProvaPosterior.statusCode()).as(semProvaPosterior.body()).isEqualTo(200);
        assertThat(java.util.stream.StreamSupport.stream(json(app,semProvaPosterior).path("historico").spliterator(),false)
                .noneMatch(e->e.path("id").asLong()==h)).isTrue();
        // Três aplicações, uma reversão e duas reaberturas, sem eventos de rollback.
        assertThat(sql.queryForObject("select count(*) from tb_evidencia_origem_historica",Integer.class)).isEqualTo(6);
        long localId=sql.queryForObject("select id from tb_vinculo_assistencial where id_publico=?",Long.class,vinculo);
        String unidadeChave="USF:"+unidade;
        assertThat(com.pet.buscaativa.migration.EvidenciaOrigemHistorica.inconsistentes(sql,localId,pessoa,unidadeChave)).isEmpty();
        assertThatThrownBy(()->sql.update("delete from tb_evidencia_origem_historica where evento_id=?",novaAplicacaoGrupo))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        // Fixture privilegiada: reversão inserida sem o comprovante do escritor.
        sql.update("""
                insert into tb_reversao_origem_historica
                (id,aplicacao_id,vinculo_id,paciente_id,unidade_chave,historico_id,ator_id,perfil,justificativa,registrado_em)
                select ?,id,vinculo_id,paciente_id,unidade_chave,historico_id,ator_id,perfil,
                    'Reversão sintética sem evidência do escritor.',registrado_em
                from tb_aplicacao_origem_historica where id=?
                """,UUID.randomUUID(),novaAplicacaoGrupo);
        assertThat(com.pet.buscaativa.migration.EvidenciaOrigemHistorica.inconsistentes(sql,localId,pessoa,unidadeChave))
                .containsExactly(hGrupo);
        var painelSemEvidencia=http(app,"GET","/api/relatorios/pendencias?tamanho=100",tokenAdmin,null);
        assertThat(painelSemEvidencia.statusCode()).as(painelSemEvidencia.body()).isEqualTo(200);
        var grupoSemEvidencia=java.util.stream.StreamSupport.stream(json(app,painelSemEvidencia).path("itens").spliterator(),false)
                .filter(e->e.path("ticket").asString().equals(novaGrupo.toString())).findFirst().orElseThrow();
        assertThat(grupoSemEvidencia.path("aplicacaoOrigem").asString()).isEqualTo("INCONCLUSIVA");
        var auditoriaInconclusiva=http(app,"GET",rota+"/"+novaGrupo+"/origem/auditoria",tokenAdmin,null);
        assertThat(auditoriaInconclusiva.statusCode()).as(auditoriaInconclusiva.body()).isEqualTo(200);
        assertThat(json(app,auditoriaInconclusiva).path("integridadeEventos").asBoolean()).isFalse();
        assertThat(json(app,auditoriaInconclusiva).path("estadoAplicacao").asString()).isEqualTo("INCONCLUSIVA");
        var leituraSemEvidencia=http(app,"GET","/api/pacientes/"+paciente+"/prontuario",tokenProf,null);
        assertThat(leituraSemEvidencia.statusCode()).as(leituraSemEvidencia.body()).isEqualTo(200);
        assertThat(java.util.stream.StreamSupport.stream(json(app,leituraSemEvidencia).path("historico").spliterator(),false)
                .noneMatch(e->e.path("id").asLong()==hGrupo)).isTrue();
        assertThat(json(app,leituraSemEvidencia).path("faltasComprovadas").asLong()).isEqualTo(1);
    }

    /** Somente os resultados clínicos; a trilha e a visibilidade do histórico mudam por desenho. */
    private List<JsonNode> consumidoresOrigem(ConfigurableApplicationContext app,String token,UUID paciente) throws Exception {
        var p=http(app,"GET","/api/pacientes/"+paciente+"/prontuario",token,null);
        var painel=http(app,"GET","/api/painel/resumo?periodoMeses=3",token,null);
        var grupos=http(app,"GET","/api/relatorios/frequencia-grupos",token,null);
        assertThat(p.statusCode()).as(p.body()).isEqualTo(200);
        assertThat(painel.statusCode()).as(painel.body()).isEqualTo(200);
        assertThat(grupos.statusCode()).as(grupos.body()).isEqualTo(200);
        var prontuario=json(app,p);
        var resumo=json(app,painel);
        var relatorio=json(app,grupos);
        var indicadores=prontuario.path("paciente").path("indicadores");
        assertThat(indicadores.isObject()).isTrue();
        assertThat(resumo.path("atividade").isObject()).isTrue();
        assertThat(resumo.path("distribuicaoClassificacao").isObject()).isTrue();
        assertThat(relatorio.path("dados").isArray()).isTrue();
        return List.of(indicadores,prontuario.path("presencasComprovadas"),prontuario.path("faltasComprovadas"),
                resumo.path("atividade"),resumo.path("distribuicaoClassificacao"),resumo.path("evolucao"),relatorio);
    }

    private static int faltasResolucao(JdbcTemplate sql,UUID vinculo) {
        return sql.queryForObject("select count_faltas from tb_vinculo_assistencial where id_publico=?",Integer.class,vinculo);
    }
    private static int versaoResolucao(JdbcTemplate sql,UUID vinculo) {
        return sql.queryForObject("select version from tb_vinculo_assistencial where id_publico=?",Integer.class,vinculo);
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
