package com.pet.buscaativa.alertas;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class AlertaPacientesVermelhosScheduler {

    private final AlertaPacientesVermelhosService service;

    @Scheduled(cron = "${app.alertas.cron:0 0 6 * * *}", zone = "${app.alertas.zone:America/Bahia}")
    public void executar() {
        log.info("RF26: iniciando envio diário de alertas de pacientes em vermelho.");
        try {
            service.enviarAlertasDiarios();
        } catch (Exception e) {
            log.error("RF26: falha inesperada no job de alertas.", e);
        }
    }
}