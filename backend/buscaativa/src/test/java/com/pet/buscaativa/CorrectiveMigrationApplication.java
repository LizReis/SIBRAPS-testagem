package com.pet.buscaativa;

import java.util.*;
import javax.sql.DataSource;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;

/** Explicit runner, not a component/test configuration discoverable by unrelated tests. */
final class CorrectiveMigrationApplication {
    static WebApplicationContextRunner application(DataSource source) {
        return new WebApplicationContextRunner().withInitializer(c->{TestEnvironmentIsolation.apply(c.getEnvironment());new ConfigDataApplicationContextInitializer().initialize(c);})
            .withUserConfiguration(BuscaativaApplication.class,H2ProfileSecurityTests.ExternalServices.class).withBean("dataSource",DataSource.class,()->source)
            .withPropertyValues("spring.config.location=file:src/main/resources/","spring.profiles.active=test","spring.jpa.hibernate.ddl-auto=validate",
                "JWT_SECRET_BASE64="+Base64.getEncoder().encodeToString(SecurityTestSupport.randomBytes()),"DEV_DB_USERNAME=sa","DEV_DB_PASSWORD="+UUID.randomUUID(),"app.alertas.email.habilitado=false","logging.level.root=ERROR");
    }
}
