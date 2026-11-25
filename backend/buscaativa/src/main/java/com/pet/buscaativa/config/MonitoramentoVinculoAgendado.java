package com.pet.buscaativa.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.pet.buscaativa.services.impl.MonitoramentoVinculosEnsaioService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@Profile("!test")
@ConditionalOnProperty(name = "sibraps.monitoramento.habilitado", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class MonitoramentoVinculoAgendado {
    private final MonitoramentoVinculosEnsaioService monitoramento;

    @Scheduled(cron = "${sibraps.monitoramento.cron:0 0 1 * * *}", zone = "${app.alertas.zone:America/Bahia}")
    public void executar() {
        var resumo = monitoramento.executar();
        log.info("Monitoramento local concluído. Examinados={}, atualizados={}, inconclusivos={}, falhas={}",
                resumo.examinados(), resumo.atualizados(), resumo.inconclusivos(), resumo.falhas());
    }
}
