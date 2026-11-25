package com.pet.buscaativa.migration;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Types;
import java.util.HashMap;
import java.util.Locale;
import java.util.Objects;
import org.h2.api.Trigger;

/** Assinaturas independentes da trilha ORM. Escrita e evidência confirmam ou revertem juntas. */
public final class EvidenciaClinicaTrigger implements Trigger {
    private String tabela;
    private int[] posicoes;

    @Override public void init(Connection c, String schema, String nome, String tabela, boolean antes, int tipo)
            throws SQLException {
        this.tabela = tabela.toUpperCase(Locale.ROOT);
        var colunas = AssinaturaEvidenciaClinica.FONTES.get(this.tabela);
        if (colunas == null) throw new SQLException("Fonte clínica não reconhecida.");
        var indices = new HashMap<String, Integer>();
        try (var rs = c.getMetaData().getColumns(null, schema, tabela, null)) {
            while (rs.next()) indices.put(rs.getString("COLUMN_NAME").toUpperCase(Locale.ROOT),
                    rs.getInt("ORDINAL_POSITION") - 1);
        }
        posicoes = new int[colunas.size()];
        for (int i = 0; i < posicoes.length; i++) {
            var indice = indices.get(colunas.get(i));
            if (indice == null) throw new SQLException("Coluna de evidência clínica ausente.");
            posicoes[i] = indice;
        }
    }

    @Override public void fire(Connection c, Object[] anterior, Object[] atual) throws SQLException {
        var antes = assinatura(anterior);
        var depois = assinatura(atual);
        if (Objects.equals(antes, depois)) return;
        try (var sql = c.prepareStatement("""
                insert into tb_movimento_evidencia_clinica
                (fonte,operacao,registro_anterior,registro_atual,assinatura_anterior,assinatura_atual)
                values (?,?,?,?,?,?)
                """)) {
            sql.setString(1, tabela);
            sql.setString(2, anterior == null ? "INCLUSAO" : atual == null ? "EXCLUSAO" : "ALTERACAO");
            preencherId(sql, 3, anterior);
            preencherId(sql, 4, atual);
            sql.setString(5, antes);
            sql.setString(6, depois);
            sql.executeUpdate();
        }
    }

    private String assinatura(Object[] linha) throws SQLException {
        if (linha == null) return null;
        var valores = new Object[posicoes.length];
        for (int i = 0; i < valores.length; i++) valores[i] = linha[posicoes[i]];
        return AssinaturaEvidenciaClinica.calcular(tabela, valores);
    }

    private void preencherId(java.sql.PreparedStatement sql, int indice, Object[] linha) throws SQLException {
        if (linha == null) sql.setNull(indice, Types.BIGINT);
        else sql.setLong(indice, ((Number) linha[posicoes[0]]).longValue());
    }
}
