-- Reversão imutável de uma resolução; nenhuma alteração de fatos originais.
ALTER TABLE tb_resolucao_frequencia_local ADD CONSTRAINT uk_resolucao_reversao_escopo
    UNIQUE (id,vinculo_id,paciente_id,unidade_chave,responsavel_valido,responsavel_desconsiderado);
CREATE TABLE tb_reversao_resolucao_frequencia (
    id UUID PRIMARY KEY,
    resolucao_id UUID NOT NULL UNIQUE,
    vinculo_id BIGINT NOT NULL,
    paciente_id BIGINT NOT NULL,
    unidade_chave VARCHAR(80) NOT NULL,
    responsavel_valido BIGINT NOT NULL,
    responsavel_desconsiderado BIGINT NOT NULL,
    ator_id BIGINT NOT NULL REFERENCES tb_usuario(id),
    perfil VARCHAR(30) NOT NULL CHECK (perfil IN ('ADMINISTRADOR','PROFISSIONAL')),
    justificativa VARCHAR(2000) NOT NULL CHECK (LENGTH(TRIM(justificativa)) BETWEEN 10 AND 2000),
    registrado_em TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_reversao_resolucao_local FOREIGN KEY
        (resolucao_id,vinculo_id,paciente_id,unidade_chave,responsavel_valido,responsavel_desconsiderado)
        REFERENCES tb_resolucao_frequencia_local
        (id,vinculo_id,paciente_id,unidade_chave,responsavel_valido,responsavel_desconsiderado),
    CONSTRAINT ck_reversao_perfil CHECK (perfil='ADMINISTRADOR' OR
        (ator_id=responsavel_valido AND ator_id=responsavel_desconsiderado))
);
CREATE TRIGGER tr_reversao_resolucao_append_only BEFORE UPDATE, DELETE
    ON tb_reversao_resolucao_frequencia FOR EACH ROW
    CALL 'com.pet.buscaativa.migration.AuditAppendOnlyTrigger';

-- A unicidade vitalícia impediria nova resolução legítima após desfazer.
-- Escritores cooperantes serializam por identidade/vínculo e conferem a visão ativa.
ALTER TABLE tb_resolucao_frequencia_local DROP CONSTRAINT uk_resolucao_fato_desconsiderado;
CREATE INDEX ix_resolucao_fato_desconsiderado ON tb_resolucao_frequencia_local(fonte_desconsiderada,fato_desconsiderado);
CREATE VIEW vw_resolucao_frequencia_ativa AS
    SELECT r.* FROM tb_resolucao_frequencia_local r
    WHERE NOT EXISTS (SELECT 1 FROM tb_reversao_resolucao_frequencia d WHERE d.resolucao_id=r.id);
