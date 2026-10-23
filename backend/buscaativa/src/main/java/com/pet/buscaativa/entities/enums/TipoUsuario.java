package com.pet.buscaativa.entities.enums;

public enum TipoUsuario {

    ADMINISTRADOR(1),
    PROFISSIONAL(2),
    RECEPCAO(3);

    private int codigo;

    TipoUsuario(int codigo) {
        this.codigo = codigo;
    }

    public int getCodigo() {
        return codigo;
    }

    public static TipoUsuario valueOf(int codigo) {
        for (TipoUsuario value : TipoUsuario.values()) {
            if (value.getCodigo() == codigo) {
                return value;
            }
        }
        throw new IllegalArgumentException("Código de Usuário Inválido!");
    }

}
