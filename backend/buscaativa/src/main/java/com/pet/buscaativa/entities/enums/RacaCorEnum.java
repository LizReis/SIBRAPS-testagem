package com.pet.buscaativa.entities.enums;

public enum RacaCorEnum {

    BRANCA(1),
    PRETA(2),
    PARDA(3),
    AMARELA(4),
    INDIGENA(5),
    NAO_INFORMADO(6);

    private int codigo;

    RacaCorEnum(int codigo) {
        this.codigo = codigo;
    }

    public int getCodigo() {
        return codigo;
    }

    public static RacaCorEnum valueOf(int codigo) {
        for (RacaCorEnum value : RacaCorEnum.values()) {
            if (value.getCodigo() == codigo) {
                return value;
            }
        }
        throw new IllegalArgumentException("Código de Raça/Cor não identificado!");
    }
}
