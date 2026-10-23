package com.pet.buscaativa.config;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.pet.buscaativa.entities.Paciente;
import com.pet.buscaativa.entities.enums.StatusPaciente;
import com.pet.buscaativa.repositories.PacienteRepository;
import com.pet.buscaativa.services.PacienteService;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class MonitoramentoBuscaAtiva {
    private static final Logger log = LoggerFactory.getLogger(MonitoramentoBuscaAtiva.class);

    private final PacienteRepository pacienteRepository;
    private final PacienteService pacienteService;

    //Verificara todo dia à 1h da manhã (segundos, minutos, horas, dia, mes, ano)
    @Scheduled(cron = "0 0 1 * * ?") 
    public void atualizarRiscosDiariamente() {
        // Busca todos os ativos
        List<Paciente> pacientesAtivos = pacienteRepository.findByStatusPaciente(StatusPaciente.ATIVO);

        int falhas = 0;
        
        for (Paciente p : pacientesAtivos) {
            try {
                pacienteService.calcularEAtualizarRisco(p);
                pacienteRepository.save(p);
            } catch (RuntimeException ex) {
                falhas++;
                // O identificador técnico permite suporte sem registrar nome, CPF ou CNS.
                log.error("Falha ao classificar paciente id={}", p.getId(), ex);
            }
        }
        if (falhas > 0) {
            throw new IllegalStateException("Classificação de risco concluída com " + falhas + " falha(s).");
        }
        
        pacienteRepository.saveAll(pacientesAtivos);
    }
}
