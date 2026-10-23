package com.pet.buscaativa.validation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = EnderecoObrigatorioValidator.class)
public @interface EnderecoObrigatorio {
    String message() default "Informe o endereço do paciente que não está em situação de rua.";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};
}