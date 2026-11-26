-- Associação explícita e imutável: a aprovação, por si, não aplica nada.
ALTER TABLE tb_resolucao_frequencia_local ADD CONSTRAINT uk_resolucao_aplicacao_escopo
    UNIQUE (id,vinculo_id,paciente_id,unidade_chave);
ALTER TABLE tb_pendencia_revisao_proveniencia ADD CONSTRAINT uk_pendencia_aplicacao_escopo
    UNIQUE (id,vinculo_id,paciente_id,unidade_chave);
ALTER TABLE tb_decisao_revisao_proveniencia ADD CONSTRAINT uk_decisao_aplicacao_aprovada
    UNIQUE (id,pendencia_id,paciente_id,unidade_chave,decisao);

CREATE TABLE tb_aplicacao_pendencia_resolucao (
    resolucao_id UUID PRIMARY KEY,
    pendencia_id UUID NOT NULL,
    decisao_id UUID NOT NULL,
    vinculo_id BIGINT NOT NULL,
    paciente_id BIGINT NOT NULL,
    unidade_chave VARCHAR(80) NOT NULL,
    decisao VARCHAR(20) NOT NULL CHECK (decisao='APROVAR'),
    CONSTRAINT fk_aplicacao_resolucao_local FOREIGN KEY (resolucao_id,vinculo_id,paciente_id,unidade_chave)
        REFERENCES tb_resolucao_frequencia_local(id,vinculo_id,paciente_id,unidade_chave),
    CONSTRAINT fk_aplicacao_ticket_local FOREIGN KEY (pendencia_id,vinculo_id,paciente_id,unidade_chave)
        REFERENCES tb_pendencia_revisao_proveniencia(id,vinculo_id,paciente_id,unidade_chave),
    CONSTRAINT fk_aplicacao_decisao_local FOREIGN KEY (decisao_id,pendencia_id,paciente_id,unidade_chave,decisao)
        REFERENCES tb_decisao_revisao_proveniencia(id,pendencia_id,paciente_id,unidade_chave,decisao)
);
CREATE INDEX ix_aplicacao_pendencia ON tb_aplicacao_pendencia_resolucao(pendencia_id);
CREATE TRIGGER tr_aplicacao_pendencia_append_only BEFORE UPDATE, DELETE
    ON tb_aplicacao_pendencia_resolucao FOR EACH ROW CALL 'com.pet.buscaativa.migration.AuditAppendOnlyTrigger';
CREATE VIEW vw_aplicacao_pendencia_ativa AS
    SELECT a.* FROM tb_aplicacao_pendencia_resolucao a
    JOIN vw_resolucao_frequencia_ativa r ON r.id=a.resolucao_id
        AND r.vinculo_id=a.vinculo_id AND r.paciente_id=a.paciente_id AND r.unidade_chave=a.unidade_chave;
