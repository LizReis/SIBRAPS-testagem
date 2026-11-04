package com.pet.buscaativa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

import java.util.Base64;
import java.util.UUID;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.util.ClassUtils;

import com.pet.buscaativa.config.DevConfig;
import com.pet.buscaativa.repositories.UsuarioRepository;
import com.pet.buscaativa.services.impl.SusApiAdapter;

class H2ProfileSecurityTests {
    @TestConfiguration(proxyBeanMethods = false)
    static class ExternalServices {
        @Bean @Primary
        SusApiAdapter isolatedSusApi() { return mock(SusApiAdapter.class); }
    }

    @ParameterizedTest(name = "H2 profile case {index}")
    @ValueSource(strings = {"", "deployment", "dev", "attempted-console-override"})
    void h205To07ConsoleDisabledWithDefaultDeploymentAndExplicitDev(String profile) {
        String password = Base64.getEncoder().encodeToString(SecurityTestSupport.randomBytes());
        var runner = new WebApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withUserConfiguration(BuscaativaApplication.class, ExternalServices.class)
                // Load actual application resources, not src/test/application.properties.
                .withPropertyValues("spring.config.location=file:src/main/resources/",
                        "JWT_SECRET_BASE64=" + Base64.getEncoder().encodeToString(SecurityTestSupport.randomBytes()),
                        "DB_URL=jdbc:h2:mem:profiles-" + UUID.randomUUID(), "DB_USERNAME=sa",
                        "DB_PASSWORD=" + password, "DEV_DB_PASSWORD=" + password,
                        "spring.jpa.hibernate.ddl-auto=create-drop", "logging.level.root=ERROR");
        if (!profile.isEmpty()) runner = runner.withPropertyValues("spring.profiles.active=" + profile);
        if (profile.equals("attempted-console-override")) {
            runner = runner.withPropertyValues("spring.h2.console.enabled=true", "spring.h2.console.path=/internal-db");
        }
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            if (!profile.equals("attempted-console-override")) {
                assertEquals("false", context.getEnvironment().getProperty("spring.h2.console.enabled"));
            }
            assertEquals(profile.equals("dev"), !context.getBeansOfType(DevConfig.class).isEmpty());
            if (!profile.equals("dev")) assertEquals(0, context.getBean(UsuarioRepository.class).count());
            assertFalse(ClassUtils.isPresent("org.springframework.boot.h2console.autoconfigure.H2ConsoleAutoConfiguration",
                    context.getClassLoader()));
            for (var registration : context.getBeansOfType(ServletRegistrationBean.class).values()) {
                assertFalse(registration.getServlet().getClass().getName().startsWith("org.h2."));
            }
        });
    }
}
