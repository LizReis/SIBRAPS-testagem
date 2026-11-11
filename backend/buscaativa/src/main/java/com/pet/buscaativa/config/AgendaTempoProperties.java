package com.pet.buscaativa.config;

import java.time.ZoneId;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Institutional interpretation of local appointment dates/times, independent of the host timezone. */
@ConfigurationProperties(prefix = "agenda.tempo")
public class AgendaTempoProperties {
    private ZoneId fuso = ZoneId.of("America/Bahia");
    public ZoneId getFuso() { return fuso; }
    public void setFuso(ZoneId fuso) { this.fuso = java.util.Objects.requireNonNull(fuso, "Fuso institucional obrigatório."); }
}
