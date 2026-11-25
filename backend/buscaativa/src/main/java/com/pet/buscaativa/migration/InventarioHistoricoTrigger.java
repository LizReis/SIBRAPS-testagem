package com.pet.buscaativa.migration;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Types;
import java.util.Objects;
import org.h2.api.Trigger;

/** Registra mudanças de composição na mesma transação do DML, independentemente do ORM. */
public final class InventarioHistoricoTrigger implements Trigger {
    private int id = -1;
    private int paciente = -1;
    private int unidade = -1;

    @Override public void init(Connection connection, String schemaName, String triggerName,
            String tableName, boolean before, int type) throws SQLException {
        try (var colunas = connection.getMetaData().getColumns(null, schemaName, tableName, null)) {
            while (colunas.next()) {
                int posicao = colunas.getInt("ORDINAL_POSITION") - 1;
                switch (colunas.getString("COLUMN_NAME").toUpperCase(java.util.Locale.ROOT)) {
                    case "ID" -> id = posicao;
                    case "PACIENTE_ID" -> paciente = posicao;
                    case "UNIDADE_CHAVE" -> unidade = posicao;
                    default -> { }
                }
            }
        }
        if (id < 0 || paciente < 0 || unidade < 0)
            throw new SQLException("Estrutura de inventário assistencial incompatível.");
    }

    @Override public void fire(Connection connection, Object[] oldRow, Object[] newRow) throws SQLException {
        var anterior = identidade(oldRow);
        var atual = identidade(newRow);
        if (Objects.equals(anterior, atual)) return;
        try (var sql = connection.prepareStatement("""
                insert into tb_movimento_inventario_historico
                (operacao,vinculo_anterior,paciente_anterior,unidade_anterior,vinculo_atual,paciente_atual,unidade_atual)
                values (?,?,?,?,?,?,?)
                """)) {
            sql.setString(1, anterior == null ? "INCLUSAO" : atual == null ? "EXCLUSAO" : "ALTERACAO");
            preencher(sql, 2, anterior);
            preencher(sql, 5, atual);
            sql.executeUpdate();
        }
    }

    private record Identidade(long vinculo, long paciente, String unidade) {}

    private Identidade identidade(Object[] linha) {
        return linha == null ? null : new Identidade(((Number) linha[id]).longValue(),
                ((Number) linha[paciente]).longValue(), (String) linha[unidade]);
    }

    private static void preencher(java.sql.PreparedStatement sql, int posicao, Identidade valor) throws SQLException {
        if (valor == null) {
            sql.setNull(posicao, Types.BIGINT);
            sql.setNull(posicao + 1, Types.BIGINT);
            sql.setNull(posicao + 2, Types.VARCHAR);
        } else {
            sql.setLong(posicao, valor.vinculo());
            sql.setLong(posicao + 1, valor.paciente());
            sql.setString(posicao + 2, valor.unidade());
        }
    }
}
