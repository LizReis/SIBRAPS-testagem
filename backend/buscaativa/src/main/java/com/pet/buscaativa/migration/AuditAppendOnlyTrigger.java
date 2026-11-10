package com.pet.buscaativa.migration;

import java.sql.Connection;
import java.sql.SQLException;
import org.h2.api.Trigger;

/** H2 adapter. Protects UPDATE/DELETE even through direct DML, not against a database administrator changing DDL. */
public final class AuditAppendOnlyTrigger implements Trigger {
    @Override public void fire(Connection connection,Object[] oldRow,Object[] newRow) throws SQLException {
        throw new SQLException("Registros de auditoria não permitem alteração ou exclusão.","45000");
    }
}
