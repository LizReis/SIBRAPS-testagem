package com.pet.buscaativa.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class CnsValidator implements ConstraintValidator<CNS, String>{
    
    @Override
    public void initialize(CNS constraintAnnotation) {
        // Nenhuma inicialização extra necessária
    }

    @Override
    public boolean isValid(String cns, ConstraintValidatorContext context) {
        // Se for nulo ou vazio, deixamos passar na validação de CNS.
        // O controle de obrigatoriedade deve ser feito com @NotBlank no DTO, se necessário.
        if (cns == null || cns.trim().isEmpty()) {
            return true; 
        }

        // O CNS deve ter obrigatoriamente 15 números exatos. Nada de letras ou traços.
        if (!cns.matches("[0-9]{15}")) {
            return false;
        }

        char digitoInicial = cns.charAt(0);

        // O SUS define rotinas diferentes dependendo do número inicial
        if (digitoInicial == '1' || digitoInicial == '2') {
            return validarCnsIniciadoCom1Ou2(cns);
        } else if (digitoInicial == '7' || digitoInicial == '8' || digitoInicial == '9') {
            return validarCnsIniciadoCom789(cns);
        }

        // Se começar com outro número (ex: 3, 4, 5, 6), não é um CNS válido no SUS
        return false;
    }

    /**
     * Regra matemática para CNS que inicia com 1 ou 2:
     * O cálculo é feito com os 11 primeiros dígitos. Caso o Dígito Verificador (DV) dê 10,
     * o sistema do SUS força a inserção de '001' antes do DV e recalcula.
     */
    private boolean validarCnsIniciadoCom1Ou2(String cns) {
        String pis = cns.substring(0, 11);
        int soma = 0;
        
        // Multiplica os 11 primeiros números pelos pesos de 15 até 5
        for (int i = 0; i < 11; i++) {
            soma += Character.getNumericValue(pis.charAt(i)) * (15 - i);
        }
        
        int resto = soma % 11;
        int dv = 11 - resto;
        
        if (dv == 11) {
            dv = 0;
        }
        
        String cnsCalculado;
        if (dv == 10) {
            // Regra de exceção do SUS: adiciona peso do bloco "001" (que equivale a somar 2)
            soma += 2;
            resto = soma % 11;
            dv = 11 - resto;
            cnsCalculado = pis + "001" + dv;
        } else {
            // Regra normal: adiciona "000" e o DV
            cnsCalculado = pis + "000" + dv;
        }
        
        // Compara se o CNS digitado é matematicamente igual ao calculado
        return cns.equals(cnsCalculado);
    }

    /**
     * Regra matemática para CNS que inicia com 7, 8 ou 9:
     * A soma direta dos 15 dígitos com pesos de 15 a 1 deve ter resto 0 na divisão por 11.
     */
    private boolean validarCnsIniciadoCom789(String cns) {
        int soma = 0;
        
        // Multiplica os 15 números pelos pesos de 15 até 1
        for (int i = 0; i < 15; i++) {
            soma += Character.getNumericValue(cns.charAt(i)) * (15 - i);
        }
        
        // Se a divisão por 11 não deixar resto, o cartão é real.
        return (soma % 11) == 0;
    }
}
