-- V11: origem de NOVOS eventos de historico assistencial.
-- Sem backfill: registros historicos continuam com NULL ate revisao
-- documental. Nem profissional atual nem referencias cadastrais servem
-- de prova retroativa. Auditorias antigas permanecem intactas.
ALTER TABLE tb_historico_paciente ADD COLUMN unidade_origem_chave VARCHAR(80);
ALTER TABLE tb_historico_paciente_aud ADD COLUMN unidade_origem_chave VARCHAR(80);
