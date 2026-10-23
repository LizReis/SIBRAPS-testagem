package com.pet.buscaativa.services.impl;

import java.util.List;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.pet.buscaativa.entities.UsfReferencia;
import com.pet.buscaativa.repositories.UsfReferenciaRepository;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;


//Classe responsáve por fazer a sincronia das USF presentes no bd

//Padrão Cron: Segundos Minutos Horas Dia-do-Mês Mês Dia-da-Semana
//"0 0 3 * * SUN" = Roda todo Domingo às 03:00 da manhã
//Se preferir mensal: "0 0 3 1 * ?" (Dia 1 de todo mês às 03:00 da manhã)


@Slf4j
@Service
public class UsfSyncService {
    

    private final SusApiAdapter susApiAdapter;
    private final UsfReferenciaRepository usfRepository;

    public UsfSyncService(SusApiAdapter susApiAdapter, UsfReferenciaRepository usfRepository) {
        this.susApiAdapter = susApiAdapter;
        this.usfRepository = usfRepository;
    }

    @PostConstruct
    @Scheduled(cron = "0 0 3 * * SUN")
    public void sincronizarUsfsDoSus() {
        log.info("Iniciando a sincronização semanal das USF...");

        try {
            List<UsfReferencia> unidadesAtualizadas = susApiAdapter.buscarUsfsSantoAntonio();

            if (unidadesAtualizadas != null && !unidadesAtualizadas.isEmpty()) {
                usfRepository.saveAll(unidadesAtualizadas);
                log.info("Sincronização concluída com sucesso! {} unidades salvas/atualizadas.", unidadesAtualizadas.size());
            }

        } catch (Exception e) {
            log.error("Falha ao tentar sincronizar dados com o SUS. O sistema continuará usando os dados salvos no BD.", e);
        }
    }
}
