-- Additive H2 schema; historical revisions deliberately remain NULL, never rewritten.
ALTER TABLE tb_sessao_grupo ADD COLUMN responsavel_atendimento_id BIGINT;
ALTER TABLE tb_sessao_grupo ADD COLUMN responsabilidade_pendente BOOLEAN DEFAULT FALSE NOT NULL;
ALTER TABLE tb_sessao_grupo ADD COLUMN motivo_responsabilidade_pendente VARCHAR(200);
ALTER TABLE tb_sessao_grupo ADD COLUMN origem_responsabilidade VARCHAR(80);
ALTER TABLE tb_sessao_grupo ADD CONSTRAINT fk_sessao_responsavel_historico FOREIGN KEY (responsavel_atendimento_id) REFERENCES tb_usuario(id);
ALTER TABLE tb_sessao_grupo_aud ADD COLUMN responsavel_atendimento_id BIGINT;
ALTER TABLE tb_sessao_grupo_aud ADD COLUMN responsabilidade_pendente BOOLEAN;
ALTER TABLE tb_sessao_grupo_aud ADD COLUMN motivo_responsabilidade_pendente VARCHAR(200);
ALTER TABLE tb_sessao_grupo_aud ADD COLUMN origem_responsabilidade VARCHAR(80);
