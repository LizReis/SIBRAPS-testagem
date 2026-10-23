package com.pet.buscaativa.entities.enums;

public enum CapsEnum {
    
    CAPS_AD(1),
    CAPS_I(2),
    CAPS_II(3);

    private int codigo;

    CapsEnum(int codigo) {
        this.codigo = codigo;
    }

    public int getCodigo() {
        return codigo;
    }

    public static CapsEnum valueOf(int codigo) {
        for (CapsEnum value : CapsEnum.values()) {
            if (value.getCodigo() == codigo) {
                return value;
            }
        }
        throw new IllegalArgumentException("Código de CAPS Inválido!");
    }
}
