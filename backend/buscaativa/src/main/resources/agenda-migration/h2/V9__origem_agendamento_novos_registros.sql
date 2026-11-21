-- V9: proveniencia imutavel somente para NOVOS agendamentos.
-- NULL nos agendamentos antigos significa origem nao comprovada:
-- proibido inferir pela unidade atual do profissional ou por referencias.
-- Migração offline explicita. Sem backfill, sem novos fatos clinicos.
ALTER TABLE tb_agendamento ADD COLUMN unidade_origem_chave VARCHAR(80);
ALTER TABLE tb_agendamento_aud ADD COLUMN unidade_origem_chave VARCHAR(80);
-- As colunas legadas preservam os valores; nenhuma linha e atualizada.
