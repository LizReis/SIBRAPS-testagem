-- V15: marca exclusivamente pendência detectada por empate local; sem backfill.
ALTER TABLE tb_vinculo_assistencial ADD COLUMN pendencia_empate_local BOOLEAN;
ALTER TABLE tb_vinculo_assistencial ADD CONSTRAINT ck_vinculo_pendencia_empate
    CHECK (pendencia_empate_local IS NULL OR (pendencia_empate_local IS TRUE AND indicadores_pendentes IS TRUE));
ALTER TABLE tb_vinculo_assistencial_aud ADD COLUMN pendencia_empate_local BOOLEAN;
