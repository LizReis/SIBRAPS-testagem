package com.pet.buscaativa;

import java.util.HashMap;
import java.util.Set;

import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

/** Isolates only the context under test; never changes System properties or process environment. */
final class TestEnvironmentIsolation {
    private static final Set<String> KEYS = Set.of("JWT_SECRET_BASE64", "DEV_DB_PASSWORD", "SPRING_PROFILES_ACTIVE",
            "spring.profiles.active", "spring.profiles.default", "SPRING_PROFILES_DEFAULT");

    private TestEnvironmentIsolation() {}

    static void apply(ConfigurableEnvironment environment) {
        var variables = new HashMap<String, Object>(System.getenv());
        KEYS.forEach(variables::remove);
        var properties = new HashMap<String, Object>();
        System.getProperties().forEach((k, v) -> properties.put(k.toString(), v));
        KEYS.forEach(properties::remove);
        environment.getPropertySources().replace(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, variables));
        environment.getPropertySources().replace(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME,
                new MapPropertySource(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME, properties));
    }
}
