-- Instala somente o armazenamento do marco prospectivo. Nenhuma data ou população é criada.
CREATE TABLE tb_marco_tecnico_historico (
    id INTEGER PRIMARY KEY CHECK (id = 1),
    iniciado_em TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    zona VARCHAR(80) NOT NULL,
    ultimo_movimento BIGINT NOT NULL CHECK (ultimo_movimento >= 0),
    total_unidades INTEGER NOT NULL CHECK (total_unidades >= 0),
    total_vinculos BIGINT NOT NULL CHECK (total_vinculos >= 0),
    inventario CLOB NOT NULL
);
CREATE TRIGGER tr_marco_tecnico_historico_append_only
    BEFORE UPDATE, DELETE ON tb_marco_tecnico_historico
    FOR EACH ROW CALL 'com.pet.buscaativa.migration.AuditAppendOnlyTrigger';
