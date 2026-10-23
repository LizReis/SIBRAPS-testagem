package com.pet.buscaativa.entities.enums;

public enum ClassificacaoRisco {

    VERDE(1),
    AMARELO(2),
    VERMELHO(3);

    private int codigo;

    ClassificacaoRisco(int codigo) {
        this.codigo = codigo;
    }

    public int getCodigo() {
        return codigo;
    }

    public static ClassificacaoRisco valueOf(int codigo) {
        for (ClassificacaoRisco value : ClassificacaoRisco.values()) {
            if (value.getCodigo() == codigo) {
                return value;
            }
        }
        throw new IllegalArgumentException("Código de Classificação de Risco não identificado!");
    }
}
