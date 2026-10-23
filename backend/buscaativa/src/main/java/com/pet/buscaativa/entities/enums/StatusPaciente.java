package com.pet.buscaativa.entities.enums;

public enum StatusPaciente {

    ATIVO(1),
    ALTA_TERAPEUTICA(2),
    TRANSFERIDO(3),
    ABANDONO_TRATAMENTO(4),
    OBITO(5),
    OUTRO(6),
    INATIVO(7);

    private int codigo;

    StatusPaciente(int codigo) {
        this.codigo = codigo;
    }

    public int getCodigo() {
        return codigo;
    }

    public static StatusPaciente valueOf(int codigo) {
        for (StatusPaciente value : StatusPaciente.values()) {
            if (value.getCodigo() == codigo) {
                return value;
            }
        }
        throw new IllegalArgumentException("Código de Status inválido!");
    }
}
