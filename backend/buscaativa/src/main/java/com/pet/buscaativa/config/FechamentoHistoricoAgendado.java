package com.pet.buscaativa.config;

import org.springframework.context.annotation.Profile;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import com.pet.buscaativa.services.impl.FechamentoHistoricoAutomaticoService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Component
@Profile("!test")
@ConditionalOnProperty(name="sibraps.historico.automatico.habilitado",havingValue="true",matchIfMissing=true)
@RequiredArgsConstructor
@Slf4j
public class FechamentoHistoricoAgendado {
    private final FechamentoHistoricoAutomaticoService service;

    @Scheduled(cron="0 * * * * *",zone="${app.alertas.zone:America/Bahia}")
    public void executar() {
        try {
            var resumo=service.executar();
            if(resumo.observados()+resumo.fechados()+resumo.semProva()>0)
                log.info("Histórico mensal: observados={}, fechados={}, sem prova={}",resumo.observados(),resumo.fechados(),resumo.semProva());
        } catch(RuntimeException e) {
            log.warn("Fechamento técnico não concluído: {}",e.getClass().getSimpleName());
        }
    }
}
