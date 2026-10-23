package com.pet.buscaativa.alertas;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class AlertaPacientesVermelhosService {

    private final AlertaDestinatariosProvider destinatariosProvider;
    private final RelatorioEmailBuilder emailBuilder;
    private final EmailService emailService;

    @Value("${app.alertas.zone:America/Bahia}")
    private String zona;

    public void enviarAlertasDiarios() {
        LocalDate hoje = LocalDate.now(ZoneId.of(zona));
        List<DestinatarioAlerta> destinatarios = destinatariosProvider.montarDestinatarios();

        int enviados = 0;
        int falhas = 0;
        int ignorados = 0;

        for (DestinatarioAlerta destinatario : destinatarios) {
            if (destinatario.email() == null || destinatario.email().isBlank()) {
                ignorados++;
                log.warn("RF26: destinatário '{}' sem e-mail cadastrado. Ignorado.", destinatario.nome());
                continue;
            }

            // Uma falha com um destinatário não pode impedir o envio para os demais.
            try {
                List<PacienteVermelhoDTO> pacientes =
                        destinatario.pacientes() == null ? List.of() : destinatario.pacientes();

                String assunto = emailBuilder.montarAssunto(hoje, pacientes.size());
                String corpo = emailBuilder.montarCorpoHtml(destinatario.nome(), hoje, pacientes);

                emailService.enviarHtml(destinatario.email(), assunto, corpo);
                enviados++;
            } catch (Exception e) {
                falhas++;
                log.error("RF26: falha ao enviar alerta para {}: {}", destinatario.email(), e.getMessage());
            }
        }

        log.info("RF26: alertas de {} concluídos. Destinatários: {} | enviados: {} | falhas: {} | ignorados: {}",
                hoje, destinatarios.size(), enviados, falhas, ignorados);
    }
}