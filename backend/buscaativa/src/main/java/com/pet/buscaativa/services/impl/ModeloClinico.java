package com.pet.buscaativa.services.impl;

import java.util.Optional;

import org.springframework.core.env.Environment;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import com.pet.buscaativa.entities.Paciente;
import com.pet.buscaativa.repositories.PacienteRepository;
import com.pet.buscaativa.services.exceptions.RecursoDuplicadoException;
import org.hibernate.exception.ConstraintViolationException;
import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
class ModeloClinico {
    private final Environment ambiente;
    private final PacienteRepository pacientes;

    boolean operacional() {
        return !ambiente.matchesProfiles("test");
    }

    void exigirLegado() {
        if (operacional()) throw new IllegalStateException("Operação clínica global desativada.");
    }

    Optional<Paciente> porCpf(String cpf) {
        return operacional() ? pacientes.findByCpfNormalizado(cpf) : pacientes.findByCpf(cpf);
    }

    Optional<Paciente> porCns(String cns) {
        return operacional() ? pacientes.findByCnsNormalizado(cns) : pacientes.findByCns(cns);
    }

    void gravarIdentidade(Paciente paciente) {
        try {
            pacientes.saveAndFlush(paciente);
        } catch (DataIntegrityViolationException e) {
            for (Throwable causa = e; causa != null; causa = causa.getCause()) {
                if (causa instanceof ConstraintViolationException violacao && restricaoDocumental(violacao))
                    throw conflitoDocumental();
            }
            throw e;
        }
    }

    private static boolean restricaoDocumental(ConstraintViolationException violacao) {
        if (!"23505".equals(violacao.getSQLState()) || violacao.getConstraintName() == null) return false;
        var nome = violacao.getConstraintName().toUpperCase(java.util.Locale.ROOT);
        return nome.contains("UK_PACIENTE_CPF_NORMALIZADO") || nome.contains("UK_PACIENTE_CNS_NORMALIZADO");
    }

    static RecursoDuplicadoException conflitoDocumental() {
        return new RecursoDuplicadoException("Conflito documental. Confira os dados pelo fluxo de cadastro único.");
    }
}
