package com.pet.buscaativa.utils;

public class DocumentoUtil {
    
    /**
     * Remove formatação de CPF
     * Exemplo: 111.444.777-35 → 11144477735
     */
    public static String normalizarCPF(String cpf) {
        if (cpf == null) return null;
        return cpf.replaceAll("[^0-9]", "");
    }
    
    /**
     * Remove formatação de CNS
     * Exemplo: 1 2000 0000 0001 91 → 120000000000191
     */
    public static String normalizarCNS(String cns) {
        if (cns == null) return null;
        return cns.replaceAll("[^0-9]", "");
    }
}