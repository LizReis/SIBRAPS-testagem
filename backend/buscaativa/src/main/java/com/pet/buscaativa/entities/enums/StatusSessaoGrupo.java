package com.pet.buscaativa.entities.enums;

public enum StatusSessaoGrupo {
    AGENDADA(1),
    REALIZADA(2),
    CANCELADA(3);

    private int codigo;

    StatusSessaoGrupo(int codigo) {
        this.codigo = codigo;
    }

    public int getCodigo() {
        return codigo;
    }

    public static StatusSessaoGrupo valueOf(int codigo) {
        for (StatusSessaoGrupo value : StatusSessaoGrupo.values()) {
            if (value.getCodigo() == codigo) {
                return value;
            }
        }
        throw new IllegalArgumentException("Código de Recorrencia inválido!");
    }
}
