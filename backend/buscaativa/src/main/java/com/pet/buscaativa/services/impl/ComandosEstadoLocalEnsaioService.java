package com.pet.buscaativa.services.impl;

import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.pet.buscaativa.entities.dto.EncerramentoPacienteDTO;
import com.pet.buscaativa.entities.dto.ReativacaoPacienteDTO;
import com.pet.buscaativa.entities.enums.MotivoEncerramento;
import com.pet.buscaativa.entities.enums.StatusPaciente;
import com.pet.buscaativa.entities.enums.TipoAcompanhamento;
import com.pet.buscaativa.services.exceptions.ValidationException;
import lombok.RequiredArgsConstructor;

/** Adaptador interno de estado clínico, não ativa rotas reais. */
@Service
@Profile("!test | (test & ensaio-estado-local)")
@RequiredArgsConstructor
public class ComandosEstadoLocalEnsaioService {
    private final EstadoClinicoLocalEnsaioService estado;

    @Transactional
    public EstadoClinicoLocalEnsaioService.Resultado configurar(UUID paciente, UUID vinculo, Integer versao,
            TipoAcompanhamento tipo, UUID referencia) {
        return estado.configurar(paciente,vinculo,versao,tipo,referencia);
    }
    @Transactional
    public EstadoClinicoLocalEnsaioService.Resultado encerrar(UUID paciente, UUID vinculo, Integer versao,
            EncerramentoPacienteDTO dto) {
        if (dto == null) throw new ValidationException("Informe o encerramento.");
        if (dto.statusPaciente()==StatusPaciente.OBITO || dto.motivo()==MotivoEncerramento.OBITO)
            throw new ValidationException("Óbito fora do escopo deste ensaio de integração.");
        return estado.encerrar(paciente,vinculo,versao,dto);
    }
    @Transactional
    public EstadoClinicoLocalEnsaioService.Resultado inativar(UUID paciente, UUID vinculo, Integer versao) {
        return estado.inativar(paciente,vinculo,versao);
    }
    @Transactional
    public EstadoClinicoLocalEnsaioService.Resultado reativar(UUID paciente, UUID vinculo, Integer versao,
            ReativacaoPacienteDTO dto) {
        if (dto==null) throw new ValidationException("Informe a reativação.");
        return estado.reativar(paciente,vinculo,versao,dto.motivo());
    }
}
