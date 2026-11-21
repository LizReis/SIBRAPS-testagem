package com.pet.buscaativa.entities;

import java.util.Objects;

import com.pet.buscaativa.entities.enums.UnidadeAtuacao;

/**
 * Chave CANONICA de um vinculo local.
 *
 * A unidade atual do usuario fornece somente contexto da operacao PRESENTE;
 * nunca identifica sozinha a unidade historica de um atendimento antigo.
 *
 * Diferente de rotulos legados de painel/auditoria:
 * painel usa "USF:<CNES>" ou "CAPS:CAPS_AD";
 * vinculo novo usa ID imutavel da USF e nome do enum CAPS.
 * Nao converter trilhas historicas apenas alterando o prefixo.
 */
public final class ChaveUnidadeAssistencial {
    private ChaveUnidadeAssistencial() {}

    public static String daUsf(UsfReferencia usf) {
        if (usf == null || usf.getId() == null || usf.getId() <= 0)
            throw new IllegalArgumentException("USF de atuacao persistida obrigatoria.");
        return "USF:" + usf.getId();
    }

    public static String doCaps(UnidadeAtuacao caps) {
        if (caps == null || caps == UnidadeAtuacao.USF)
            throw new IllegalArgumentException("Unidade CAPS valida obrigatoria.");
        return caps.name();
    }

    public static String doVinculo(UnidadeAtuacao unidade, UsfReferencia usf) {
        Objects.requireNonNull(unidade, "Unidade de atuacao obrigatoria.");
        if (unidade == UnidadeAtuacao.USF) return daUsf(usf);
        if (usf != null) throw new IllegalArgumentException("Vinculo CAPS nao usa USF de atuacao.");
        return doCaps(unidade);
    }

    /**
     * Apenas para CRIAR NOVO vinculo na unidade autenticada.
     * Proibido usar este metodo para reatribuir fatos clinicos anteriores.
     */
    public static String doUsuarioAtual(Usuario usuario) {
        Objects.requireNonNull(usuario, "Usuario autenticado obrigatorio.");
        return doVinculo(usuario.getUnidadeAtuacao(), usuario.getUsfAtuacao());
    }
}
