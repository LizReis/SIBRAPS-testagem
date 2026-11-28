package br.sibraps.ensaiohttp;

import java.util.Set;

import javax.sql.DataSource;

import br.sibraps.modelovinculo.ConsultasVinculoController;
import br.sibraps.modelovinculo.AuditoriaCorrecaoLocalController;
import br.sibraps.modelovinculo.EscritoresVinculoController.Cadastro;
import br.sibraps.modelovinculo.EscritoresVinculoController.Individual;
import br.sibraps.modelovinculo.EscritoresVinculoController.Coletivo;

import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.config.TaskManagementConfigUtils;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.method.HandlerMethod;

import com.pet.buscaativa.controllers.AuthController;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/** Montagem explícita, fora da busca de componentes e do artefato da aplicação. */
@TestConfiguration
@Profile("test & ensaio-estado-local & escritores-http-local & !dev")
@Import({Cadastro.class, Individual.class, Coletivo.class, ConsultasVinculoController.class,
        AuditoriaCorrecaoLocalController.class})
public class EscritoresHttpIsolados {
    public static final String RAIZ = "/api/ensaio-escritores";
    public static final String BANCO = "jdbc:h2:mem:escritores-http-6b1";

    @Bean
    static BeanDefinitionRegistryPostProcessor isolarEscritoresDeFundo(org.springframework.core.env.ConfigurableEnvironment ambiente) {
        return registry -> {
            String url = ambiente.getProperty("spring.datasource.url");
            if (!BANCO.equals(url) && !(BANCO + ";DB_CLOSE_DELAY=-1").equals(url))
                throw new IllegalStateException("O ensaio HTTP exige seu banco H2 em memória.");
            if (ambiente.getProperty("app.alertas.email.habilitado", Boolean.class, true))
                throw new IllegalStateException("O ensaio HTTP exige alertas por e-mail desativados.");
            ambiente.getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource(
                    "rotasIsoladas", java.util.Map.of("sibraps.clinica.raiz", RAIZ)));
            for (String bean : Set.of("agendaMigratedIndicatorsInitializer", "monitoramentoBuscaAtiva",
                    "alertaPacientesVermelhosScheduler", "usfSyncService", TaskManagementConfigUtils.SCHEDULED_ANNOTATION_PROCESSOR_BEAN_NAME)) {
                if (registry.containsBeanDefinition(bean)) registry.removeBeanDefinition(bean);
            }
        };
    }

    @Bean
    SmartInitializingSingleton verificarBancoDoEnsaio(DataSource fonte) {
        return () -> {
            try (var conexao = fonte.getConnection()) {
                if (!BANCO.equals(conexao.getMetaData().getURL()))
                    throw new IllegalStateException("O ensaio HTTP exige seu banco H2 em memória.");
            } catch (java.sql.SQLException e) {
                throw new IllegalStateException("Falha ao conferir o banco isolado.", e);
            }
        };
    }

    @Bean
    WebMvcConfigurer limitarSuperficieHttp() {
        return new WebMvcConfigurer() {
            @Override public void addInterceptors(InterceptorRegistry registry) {
                registry.addInterceptor(new HandlerInterceptor() {
                    @Override public boolean preHandle(HttpServletRequest req, HttpServletResponse resp, Object handler) {
                        if (handler instanceof HandlerMethod metodo) {
                            var tipo = metodo.getBeanType();
                            if (Cadastro.class.isAssignableFrom(tipo) || Individual.class.isAssignableFrom(tipo)
                                    || Coletivo.class.isAssignableFrom(tipo) || ConsultasVinculoController.class.isAssignableFrom(tipo)
                                    || (AuditoriaCorrecaoLocalController.class.isAssignableFrom(tipo)
                                        && "GET".equals(req.getMethod())
                                        && Set.of("individual", "coletiva").contains(metodo.getMethod().getName()))
                                    || (br.sibraps.modelovinculo.ResolucoesFrequenciaController.class.isAssignableFrom(tipo)
                                        && (("GET".equals(req.getMethod()) && Set.of("candidatos","auditoria").contains(metodo.getMethod().getName()))
                                        || ("POST".equals(req.getMethod()) && Set.of("resolver","desfazer").contains(metodo.getMethod().getName()))))
                                    || (br.sibraps.modelovinculo.RevisaoOrigemController.class.isAssignableFrom(tipo)
                                        && (("GET".equals(req.getMethod()) && "auditoria".equals(metodo.getMethod().getName()))
                                        || ("POST".equals(req.getMethod()) && Set.of("aplicar","desfazer","reabrir").contains(metodo.getMethod().getName()))))
                                    || AuthController.class.isAssignableFrom(tipo) && metodo.getMethod().getName().equals("login")
                                    || com.pet.buscaativa.controllers.UsuarioController.class.isAssignableFrom(tipo)
                                    && Set.of("me", "listarProfissionaisParaSelecao", "listarUsuariosElegiveisParaReferencia")
                                            .contains(metodo.getMethod().getName())
                                    || com.pet.buscaativa.controllers.DisponibilidadeController.class.isAssignableFrom(tipo)
                                    || com.pet.buscaativa.controllers.DisponibilidadeExcecaoController.class.isAssignableFrom(tipo)
                                    || com.pet.buscaativa.controllers.BloqueioAgendaController.class.isAssignableFrom(tipo)
                                    || com.pet.buscaativa.controllers.UsfReferenciaController.class.isAssignableFrom(tipo)
                                    && metodo.getMethod().getName().equals("listar"))
                                return true;
                        }
                        resp.setStatus(HttpStatus.NOT_FOUND.value());
                        return false;
                    }
                });
            }
        };
    }

}
