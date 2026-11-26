-- A designação de agenda fica separada da autoria confirmada. Sem backfill.
ALTER TABLE tb_sessao_grupo ADD COLUMN profissional_designado_id BIGINT;
ALTER TABLE tb_sessao_grupo ADD CONSTRAINT fk_sessao_profissional_designado
    FOREIGN KEY (profissional_designado_id) REFERENCES tb_usuario(id);
ALTER TABLE tb_sessao_grupo_aud ADD COLUMN profissional_designado_id BIGINT;
