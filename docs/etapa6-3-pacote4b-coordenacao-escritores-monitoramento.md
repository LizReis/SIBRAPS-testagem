# Etapa 6.3 — Pacote 4B: coordenação de escritores, referências e monitoramento

Base `c500dffb66a391a14b27238b06f959435b9bd5ab` — Actions #45497595742: 1.355 Java, 12 concorrência, Angular e build aprovados. CI deste pacote pendente.

## Escritores globais
Scheduler legado enumera IDs escalares, transação independente por paciente, bloqueia e relê identidade, revalida ATIVO, não executa saveAll de entidades antigas. Mantém política global atual até o corte. Inicializador H2 relê sob lock e confirma pendência atual antes de recalcular. GET de busca ativa legado adquire locks de identidade por UUID ordenado, relê e revalida escopo/status antes de reclassificar; mantém auditoria e resultado legados. Não converte GET em leitura local nem desativa escritores globais nesta entrega; exige corte conjunto com consumidores. Não altera API, regra de risco, horário do scheduler ou migrations.

## Usuários e revisão
Proteção de referência local existente sob lock de usuário: recusa alteração de perfil ou lotação que deixe referência incompatível e remoção enquanto referenciado, sem transferir automaticamente vínculos nem reatribuir fatos. Exige ajuste explícito da referência antes da alteração incompatível. Proteção conservadora inclusive para vínculo encerrado; revisar política de desligamento/transferência antes do corte. Adaptador experimental V12/V13 adquire identidade/ator/vínculo e valida versão antes dos comandos existentes; aprovação continua evento de decisão sem aplicação, sem limpeza automática. Chamadas internas diretas anteriores não foram substituídas; falta revisão de todos os pontos de chamada e paridade do contrato.

## Monitoramento
Paginação por chave crescente, lote configurável internamente 1–1000 (padrão 100), limite superior de ID capturado no início, uma transação por vínculo, avanço mesmo após falha isolada, revalidação sob locks. Seleção dinâmica de ativos: não promete snapshot global nem incluir vínculos que se tornem ativos após a passagem do cursor. Exclusão simultânea por instância JVM, não distribuída. Sem @Scheduled ou ativação do ensaio. Testes de falha real por constraint, rollback e continuação, lote de 1, concorrência com correção, tentativa de segunda execução, referência profissional e decisão V12/V13 sem efeitos. Testes não executados localmente.

## Limites e transição
O pacote melhora coordenação mas não autoriza corte operacional. Ainda revisar os chamadores de recálculo global, escritor de cadastro/demografia, referências e grupos/agenda após mudanças de usuário, escopo de histórico e operação V12/V13 direta, multi-instância e testes HTTP. Persistem registros de origem desconhecida e a política transversal de óbito não decidida. Próximo pacote após CI: consultas, busca ativa e painéis por vínculo, seguidos de relatórios/notificações e validação de corte. Sem alterações de main, frontend, schema ou banco real.
