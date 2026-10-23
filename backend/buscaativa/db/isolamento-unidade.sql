-- Aplicação manual única em bancos persistentes com o schema anterior.
-- Não atribui uma USF automaticamente aos usuários legados.
ALTER TABLE tb_usuario ADD COLUMN usf_atuacao_id BIGINT;
ALTER TABLE tb_usuario ADD CONSTRAINT fk_usuario_usf_atuacao
    FOREIGN KEY (usf_atuacao_id) REFERENCES tb_usf_referencia (id);
ALTER TABLE tb_usuario_aud ADD COLUMN usf_atuacao_id BIGINT;
