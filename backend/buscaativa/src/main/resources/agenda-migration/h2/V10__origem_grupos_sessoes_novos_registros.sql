-- V10: proveniência imutável dos NOVOS grupos e sessões.
-- Colunas opcionais, sem atribuição retroativa e sem inferir unidade
-- pela lotação atual, coordenador atual ou referências cadastrais.
-- Os dados e revisões existentes permanecem intactos.
ALTER TABLE tb_grupo_terapeutico ADD COLUMN unidade_origem_chave VARCHAR(80);
ALTER TABLE tb_grupo_terapeutico_aud ADD COLUMN unidade_origem_chave VARCHAR(80);
ALTER TABLE tb_sessao_grupo ADD COLUMN unidade_origem_chave VARCHAR(80);
ALTER TABLE tb_sessao_grupo_aud ADD COLUMN unidade_origem_chave VARCHAR(80);
