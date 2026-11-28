package br.sibraps.ensaiohttp;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;

import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;

import com.pet.buscaativa.BuscaativaApplication;
import com.pet.buscaativa.entities.UsfReferencia;
import com.pet.buscaativa.entities.Usuario;
import com.pet.buscaativa.entities.enums.TipoUsuario;
import com.pet.buscaativa.entities.enums.UnidadeAtuacao;
import jakarta.persistence.EntityManager;

/** Servidor local, somente no classpath de teste, para execução do Angular de ensaio. */
@TestConfiguration
@Import({EscritoresHttpIsolados.class, br.sibraps.modelovinculo.ResolucoesFrequenciaController.class,
        br.sibraps.modelovinculo.RevisaoOrigemController.class})
public class AplicacaoEnsaioLocal {
    public static void main(String[] args) {
        var senha = System.getenv("SIBRAPS_ENSAIO_SENHA");
        if (senha == null || senha.length() < 16)
            throw new IllegalStateException("Informe uma senha sintética de ao menos 16 caracteres em SIBRAPS_ENSAIO_SENHA.");
        if (args.length != 0) throw new IllegalArgumentException("O servidor isolado não aceita argumentos.");
        var app = new SpringApplication(BuscaativaApplication.class, AplicacaoEnsaioLocal.class);
        app.setAdditionalProfiles("test", "ensaio-estado-local", "escritores-http-local");
        app.addInitializers(ctx -> ctx.getEnvironment().getPropertySources().addFirst(
                new org.springframework.core.env.MapPropertySource("isolamentoEnsaio", Map.of(
                        "server.address", "127.0.0.1",
                        "server.port", "8080",
                        "spring.datasource.url", EscritoresHttpIsolados.BANCO + ";DB_CLOSE_DELAY=-1",
                        "spring.datasource.driver-class-name", "org.h2.Driver",
                        "spring.datasource.username", "sa",
                        "spring.datasource.password", segredo(),
                        "spring.jpa.hibernate.ddl-auto", "create-drop",
                        "spring.flyway.enabled", "false",
                        "app.alertas.email.habilitado", "false",
                        "JWT_SECRET_BASE64", segredo()))));
        app.addInitializers(ctx -> {
            if (ctx.getEnvironment().matchesProfiles("dev"))
                throw new IllegalStateException("O servidor isolado é incompatível com o perfil dev.");
        });
        app.run();
    }

    // Relógio clínico exclusivo deste servidor de ensaio; JWT mantém seu relógio real.
    @Bean
    @org.springframework.context.annotation.Primary
    java.time.Clock relogioJornada() {
        String arquivo = System.getenv("SIBRAPS_ENSAIO_RELOGIO");
        var zona = java.time.ZoneId.of("America/Bahia");
        return arquivo == null ? java.time.Clock.fixed(java.time.Instant.parse("2026-11-24T22:00:00Z"), zona)
                : new RelogioJornada(java.nio.file.Path.of(arquivo), zona);
    }

    private static final class RelogioJornada extends java.time.Clock {
        private final java.nio.file.Path arquivo;
        private final java.time.ZoneId zona;
        private RelogioJornada(java.nio.file.Path arquivo, java.time.ZoneId zona) {
            this.arquivo = arquivo; this.zona = zona;
        }
        @Override public java.time.ZoneId getZone() { return zona; }
        @Override public java.time.Clock withZone(java.time.ZoneId zona) { return new RelogioJornada(arquivo, zona); }
        @Override public java.time.Instant instant() {
            try {
                String instante = java.nio.file.Files.readString(arquivo).trim();
                if (!java.util.Set.of("2026-11-24T22:00:00Z", "2026-11-25T22:00:00Z", "2026-11-25T23:00:00Z", "2026-11-26T00:30:00Z").contains(instante))
                    throw new IllegalStateException("Instante fora das fases previstas do ensaio.");
                return java.time.Instant.parse(instante);
            } catch (java.io.IOException e) {
                throw new IllegalStateException("Relógio do ensaio indisponível.", e);
            }
        }
    }

    @Bean
    ApplicationRunner criarAtoresSinteticos(EntityManager em, TransactionTemplate tx, PasswordEncoder encoder,
            javax.sql.DataSource fonte, org.springframework.context.ApplicationContext app) {
        return args -> {
            String senha = encoder.encode(System.getenv("SIBRAPS_ENSAIO_SENHA"));
            tx.executeWithoutResult(ignorado -> {
                for (String letra : new String[]{"a", "b"}) {
                    var usf = new UsfReferencia();
                    usf.setNomeUsf("USF sintética " + letra.toUpperCase(java.util.Locale.ROOT));
                    usf.setCnes("Ensaio-" + letra);
                    em.persist(usf);
                    for (var perfil : TipoUsuario.values()) {
                        var u = new Usuario();
                        u.setNome(perfil.name() + " sintético " + letra);
                        u.setEmail(perfil.name().toLowerCase(java.util.Locale.ROOT) + "." + letra + "@test.local");
                        u.setSenha(senha);
                        u.setTipoUsuario(perfil);
                        u.setUnidadeAtuacao(UnidadeAtuacao.USF);
                        u.setUsfAtuacao(usf);
                        em.persist(u);
                        if (perfil == TipoUsuario.PROFISSIONAL) {
                            for (int dia = 24; dia <= 25; dia++) {
                                var oferta = new com.pet.buscaativa.entities.DisponibilidadeExcecao();
                                oferta.setUsuario(u);
                                oferta.setData(java.time.LocalDate.of(2026, 11, dia));
                                oferta.setTurno(com.pet.buscaativa.entities.enums.TurnoEnum.MANHA);
                                oferta.setCapacidade(10);
                                em.persist(oferta);
                            }
                        }
                    }
                }
                // CAPS AD não possui USF de atuação; referência territorial não cria vínculo.
                for (var perfil : TipoUsuario.values()) {
                    var u = new Usuario();
                    u.setNome(perfil.name() + " sintético CAPS AD");
                    u.setEmail(perfil.name().toLowerCase(java.util.Locale.ROOT) + ".caps@test.local");
                    u.setSenha(senha);
                    u.setTipoUsuario(perfil);
                    u.setUnidadeAtuacao(UnidadeAtuacao.CAPS_AD);
                    em.persist(u);
                    if (perfil == TipoUsuario.PROFISSIONAL) {
                        var oferta = new com.pet.buscaativa.entities.DisponibilidadeExcecao();
                        oferta.setUsuario(u);
                        oferta.setData(java.time.LocalDate.of(2026, 11, 25));
                        oferta.setTurno(com.pet.buscaativa.entities.enums.TurnoEnum.MANHA);
                        oferta.setCapacidade(10);
                        em.persist(oferta);
                    }
                }
            });
            // Hibernate cria a base JPA; estas quatro estruturas aditivas não são entidades.
            // Somente neste servidor test-only, depois da verificação do H2 em memória.
            try (var conexao = fonte.getConnection()) {
                if (!EscritoresHttpIsolados.BANCO.equals(conexao.getMetaData().getURL()))
                    throw new IllegalStateException("Estruturas de resolução exigem o H2 isolado.");
            }
            var jdbc = new org.springframework.jdbc.core.JdbcTemplate(fonte);
            jdbc.execute("alter table tb_vinculo_assistencial add constraint if not exists uk_vinculo_revisao_identidade unique (id,paciente_id,unidade_chave)");
            var sql = new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator();
            for (String arquivo : new String[]{"V23__resolucao_frequencia_local.sql",
                    "V24__reversao_resolucao_frequencia.sql","V25__aplicacao_pendencia_resolucao.sql",
                    "V26__evidencia_resolucao_historica.sql"})
                sql.addScript(new org.springframework.core.io.ClassPathResource("agenda-migration/h2/" + arquivo));
            sql.execute(fonte);
            if (!com.pet.buscaativa.migration.EvidenciaResolucaoSchemaGuard.instalada(jdbc))
                throw new IllegalStateException("Estrutura de resolução ausente no ensaio.");
            // Reaproveitar migrations originais de origem, apenas no H2 sintético.
            var origem = new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator();
            for (String arquivo : new String[]{"V27__prova_aprovacao_origem_historica.sql",
                    "V28__aplicacao_origem_historica.sql","V29__reversao_origem_historica.sql"})
                origem.addScript(new org.springframework.core.io.ClassPathResource("agenda-migration/h2/" + arquivo));
            origem.execute(fonte);
            try (var conexao = fonte.getConnection()) {
                var contexto = org.mockito.Mockito.mock(org.flywaydb.core.api.migration.Context.class);
                org.mockito.Mockito.when(contexto.getConnection()).thenReturn(conexao);
                new com.pet.buscaativa.migration.V30__reabertura_origem_historica().migrate(contexto);
            }
            new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator(
                    new org.springframework.core.io.ClassPathResource("agenda-migration/h2/V31__evidencia_integridade_origem.sql")).execute(fonte);
            if (!com.pet.buscaativa.migration.EvidenciaOrigemSchemaGuard.instalada(jdbc))
                throw new IllegalStateException("Estrutura de origem ausente no ensaio.");
            com.pet.buscaativa.services.impl.OperacoesResolucaoFrequenciaTeste.prepararDuplicidadeNavegador(app);
            com.pet.buscaativa.services.impl.OperacoesResolucaoFrequenciaTeste.prepararOrigemNavegador(app);
            com.pet.buscaativa.services.impl.OperacoesResolucaoFrequenciaTeste.prepararBuscaAtivaNavegador(app);
        };
    }

    private static String segredo() {
        var bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }
}
