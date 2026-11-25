# Histórico automático — primeira base de evidência (V19)

Base: b0b2be93e36074f29ec616047ac15f9e544f3082. A CI anterior #45684287684 passou: 131 testes Angular padrão e 4 experimentais; backend e builds também aprovados.

A V19, instalada somente pela ferramenta offline com `--cobertura-historica` após V18, introduz uma trilha de mudanças de composição dos vínculos (inclusão, exclusão e alteração de identidade/unidade). Um trigger H2 registra DML na mesma transação, inclusive fora do ORM; rollback não deve deixar evidência falsa. O registro guarda IDs internos e escopos, não nomes, documentos ou valores clínicos. UPDATE/DELETE da trilha são bloqueados pelo mecanismo append-only existente. A montagem operacional recusa V19 parcial ou sem os triggers esperados, e mantém V17/V18 válidas.

A instalação não define marco, não cria inventário inicial nem fecha competências. Os fechamentos existentes, V16–V18 e APIs permanecem intactos nesta entrega intermediária. O teste de migração cobre preservação, rollback, restauração, captura e guarda; aprovação depende da CI.

## Limites e próxima implementação

A trilha de composição não comprova sozinha a cobertura de fatos clínicos. O timestamp de DML e ID da sequência não constituem ordenação de commits. A próxima entrega precisa estabelecer um marco autorizado e inventário inicial consistente; evidenciar alterações relevantes dos fatos clínicos/cadastrais; reconciliar com Envers, verificar lacunas, concorrência e fronteiras temporais; e então substituir a certificação manual pela consolidação automática com autoria técnica. Não usar a mesma trilha Envers como segunda fonte. Não inferir cobertura de ausência de eventos; alterações administrativas de DDL, TRUNCATE e acesso privilegiado não são cobertas por triggers de linha. Preservar meses indisponíveis enquanto essas condições não forem atendidas.

Nenhum banco real migrado, nenhum envio externo e nenhum merge. Não executar o procedimento em produção antes da validação final.
