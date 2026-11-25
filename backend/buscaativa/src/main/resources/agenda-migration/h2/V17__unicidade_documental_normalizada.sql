-- V17: somente execução offline explícita após diagnóstico de V16.
-- Não altera documentos de origem, não mescla pacientes nem reescreve auditoria.
-- H2 UNIQUE permite múltiplos NULL; documento ausente não constitui identidade.
ALTER TABLE tb_paciente ADD COLUMN cpf_normalizado VARCHAR(255)
    GENERATED ALWAYS AS (NULLIF(REGEXP_REPLACE(cpf, '[^0-9]', ''), ''));
ALTER TABLE tb_paciente ADD COLUMN cns_normalizado VARCHAR(255)
    GENERATED ALWAYS AS (NULLIF(REGEXP_REPLACE(cns, '[^0-9]', ''), ''));
ALTER TABLE tb_paciente ADD CONSTRAINT uk_paciente_cpf_normalizado UNIQUE (cpf_normalizado);
ALTER TABLE tb_paciente ADD CONSTRAINT uk_paciente_cns_normalizado UNIQUE (cns_normalizado);
