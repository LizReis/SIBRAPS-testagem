package com.pet.buscaativa.validation;

import static java.lang.annotation.ElementType.ANNOTATION_TYPE;
import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

/**
 * Validação de unicidade para Disponibilidade: usuarioId + diaSemana + turno.
 * Aplica-se ao record DisponibilidadeDTO.
 */
@Documented
@Constraint(validatedBy = UniqueDisponibilidadeValidator.class)
@Target({ TYPE, ANNOTATION_TYPE })
@Retention(RUNTIME)
public @interface UniqueDisponibilidade {

    String message() default "Já existe disponibilidade cadastrada para este profissional, dia da semana e turno.";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}