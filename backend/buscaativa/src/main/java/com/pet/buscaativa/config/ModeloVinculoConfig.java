package com.pet.buscaativa.config;

import java.util.Map;

import javax.sql.DataSource;

import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.util.ClassUtils;

import br.sibraps.modelovinculo.ConsultasVinculoController;
import br.sibraps.modelovinculo.EscritoresVinculoController;
import com.pet.buscaativa.migration.IdentidadeDocumentalPreflight;
import com.pet.buscaativa.migration.TransicaoLocalSchemaGuard;
import com.pet.buscaativa.migration.InventarioHistoricoSchemaGuard;
import com.pet.buscaativa.migration.MarcoHistoricoSchemaGuard;
import com.pet.buscaativa.migration.EvidenciaClinicaSchemaGuard;
import com.pet.buscaativa.migration.FechamentoAutomaticoSchemaGuard;
import com.pet.buscaativa.migration.ResolucaoFrequenciaSchemaGuard;

@Configuration(proxyBeanMethods = false)
public class ModeloVinculoConfig {
    @Bean
    static BeanFactoryPostProcessor impedirModoDeTesteNoArtefato(ConfigurableEnvironment ambiente) {
        return factory -> {
            if (ambiente.matchesProfiles("test") && !ClassUtils.isPresent(
                    "br.sibraps.ensaiohttp.EscritoresHttpIsolados", ModeloVinculoConfig.class.getClassLoader()))
                throw new IllegalStateException("O perfil test exige o classpath de testes do SIBRAPS.");
        };
    }

    @Configuration(proxyBeanMethods = false)
    @Profile("!test")
    @Import({ConsultasVinculoController.class, EscritoresVinculoController.Cadastro.class,
            EscritoresVinculoController.Individual.class, EscritoresVinculoController.Coletivo.class})
    static class Operacional {
        @Bean
        static BeanFactoryPostProcessor protegerConfiguracao(ConfigurableEnvironment ambiente) {
            return factory -> {
                String url = ambiente.getProperty("spring.datasource.url", "");
                if (!"validate".equals(ambiente.getProperty("spring.jpa.hibernate.ddl-auto"))
                        || ambiente.containsProperty("spring.jpa.properties.hibernate.hbm2ddl.auto")
                        || ambiente.containsProperty("spring.jpa.properties.jakarta.persistence.schema-generation.database.action")
                        || ambiente.containsProperty("spring.datasource.hikari.jdbc-url")
                        || ambiente.containsProperty("spring.datasource.hikari.connection-init-sql")
                        || ambiente.containsProperty("spring.datasource.jndi-name")
                        || java.util.regex.Pattern.compile("(?i);\\s*INIT\\s*=").matcher(url).find()
                        || !"/api".equals(ambiente.getProperty("sibraps.clinica.raiz", "/api"))
                        || ambiente.getProperty("app.alertas.email.habilitado", Boolean.class, false))
                    throw new IllegalStateException("O modelo por vínculo exige schema validado, rotas padrão e envio desativado.");
                // Binding antes de qualquer inicializador de dados. A ferramenta offline é a única via de migração.
                ambiente.getPropertySources().addFirst(new MapPropertySource("modeloVinculoOperacional", Map.of(
                        "spring.sql.init.mode", "never", "spring.flyway.enabled", "false",
                        "spring.liquibase.enabled", "false", "spring.h2.console.enabled", "false",
                        "app.alertas.email.habilitado", "false")));
            };
        }

        @Bean
        static BeanPostProcessor verificarBancoAntesDoUso() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String nome) {
                    if (bean instanceof DataSource fonte) {
                        IdentidadeDocumentalPreflight.verificarOperacional(fonte);
                        TransicaoLocalSchemaGuard.verificar(fonte);
                        InventarioHistoricoSchemaGuard.verificar(fonte);
                        MarcoHistoricoSchemaGuard.verificar(fonte);
                        EvidenciaClinicaSchemaGuard.verificar(fonte);
                        FechamentoAutomaticoSchemaGuard.verificar(fonte);
                        ResolucaoFrequenciaSchemaGuard.verificar(fonte);
                    }
                    return bean;
                }
            };
        }
    }
}
