package com.pet.buscaativa.validation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

@Documented
@Constraint(validatedBy = CnsValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
public @interface CNS {
    String message() default "O CNS informado é inválido. Verifique a numeração do cartão SUS.";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
