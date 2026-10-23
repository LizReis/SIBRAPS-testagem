package com.pet.buscaativa.validation;

import com.pet.buscaativa.entities.dto.PacienteDTO;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class EnderecoObrigatorioValidator implements ConstraintValidator<EnderecoObrigatorio, PacienteDTO> {
    @Override
    public boolean isValid(PacienteDTO paciente, ConstraintValidatorContext context) {
        return paciente == null || paciente.situacaoRua() || paciente.endereco() != null;
    }
}