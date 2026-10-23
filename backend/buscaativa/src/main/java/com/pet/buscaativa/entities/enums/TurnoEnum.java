package com.pet.buscaativa.entities.enums;

public enum TurnoEnum {

    MANHA(1),
    TARDE(2);

    private int codigo;

    TurnoEnum(int codigo) {
        this.codigo = codigo;
    }

    public int getCodigo() {
        return codigo;
    }

    public static TurnoEnum valueOf(int codigo) {
        for (TurnoEnum value : TurnoEnum.values()) {
            if (value.getCodigo() == codigo) {
                return value;
            }
        }
        throw new IllegalArgumentException("Código do Turno Inválido!");
    }
}
