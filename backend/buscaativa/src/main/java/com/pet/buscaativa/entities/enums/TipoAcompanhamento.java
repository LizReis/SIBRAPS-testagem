package com.pet.buscaativa.entities.enums;

public enum TipoAcompanhamento {

    GRUPO_TERAPEUTICO(1),
    INDIVIDUAL(2),
    AMBOS(3);

    private int codigo;

    TipoAcompanhamento(int codigo) {
        this.codigo = codigo;
    }

    public int getCodigo() {
        return codigo;
    }

    public static TipoAcompanhamento valueOf(int codigo) {
        for (TipoAcompanhamento value : TipoAcompanhamento.values()) {
            if (value.getCodigo() == codigo) {
                return value;
            }
        }
        throw new IllegalArgumentException("Código de acompanhamento inválido!");
    }
}
