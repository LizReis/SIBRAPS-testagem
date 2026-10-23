package com.pet.buscaativa.config;
import java.time.LocalTime;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import jakarta.annotation.PostConstruct;

@Component
@ConfigurationProperties(prefix = "agenda")
public class AgendaHorarioProperties {
    private final Horarios horarios = new Horarios();
    private int intervaloMinutos = 30;
    public Horarios getHorarios() { return horarios; }
    public int getIntervaloMinutos() { return intervaloMinutos; }
    public void setIntervaloMinutos(int valor) { intervaloMinutos = valor; }
    @PostConstruct void validar() {
        if (intervaloMinutos <= 0 || !horarios.manha.inicio.isBefore(horarios.manha.fim)
                || !horarios.tarde.inicio.isBefore(horarios.tarde.fim))
            throw new IllegalStateException("Configuração de horários da agenda inválida.");
    }
    public static class Horarios {
        private final Periodo manha = new Periodo(LocalTime.of(7, 0), LocalTime.of(11, 30));
        private final Periodo tarde = new Periodo(LocalTime.of(13, 0), LocalTime.of(18, 30));
        public Periodo getManha() { return manha; } public Periodo getTarde() { return tarde; }
    }
    public static class Periodo {
        private LocalTime inicio; private LocalTime fim;
        Periodo(LocalTime inicio, LocalTime fim) { this.inicio = inicio; this.fim = fim; }
        public LocalTime getInicio() { return inicio; } public void setInicio(LocalTime v) { inicio = v; }
        public LocalTime getFim() { return fim; } public void setFim(LocalTime v) { fim = v; }
    }
}