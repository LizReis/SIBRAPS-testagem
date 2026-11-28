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
@Import(EscritoresHttpIsolados.class)
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
                if (!java.util.Set.of("2026-11-24T22:00:00Z", "2026-11-25T22:00:00Z").contains(instante))
                    throw new IllegalStateException("Instante fora das fases previstas do ensaio.");
                return java.time.Instant.parse(instante);
            } catch (java.io.IOException e) {
                throw new IllegalStateException("Relógio do ensaio indisponível.", e);
            }
        }
    }

    @Bean
    ApplicationRunner criarAtoresSinteticos(EntityManager em, TransactionTemplate tx, PasswordEncoder encoder) {
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
            });
        };
    }

    private static String segredo() {
        var bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }
}
