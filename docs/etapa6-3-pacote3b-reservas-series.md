# Etapa 6.3 — Pacote 3B, primeira entrega: ciclo de reservas e criação de séries

Base `3210f72c30ec8d48f87b00ac62fcf33052a5905f`; CI #45491563317: 1.346 Java, 12 concorrência, 28 Angular e build aprovados. Nova CI pendente.

## Implementado em ensaio
- Cancelamento administrativo individual sob locks e autorização local: preserva fatos já registrados e cadeia substituída, rejeita histórico factual incompatível, recálculo local conservador, idempotência de cancelamento sem frequência.
- Recuperação de cancelamento com versão, justificativa, horário estritamente futuro, acompanhamento ativo comprovado, disponibilidade, guardas de história e cadeia, auditoria imutável RECUPERACAO_RESERVA na mesma transação. Não cria frequência.
- Remarcação pelo DTO de cadastro com versão explícita do original: administrador/recepção, mesmo paciente, profissional novo local bloqueado no mesmo plano, guardas de agenda e cadeia, conserva original e RF22. O ensaio anterior de remarcação clínica continua disponível.
- Adaptador de PATCH experimental: PRESENTE/FALTOU selecionam registro ou correção dentro dos locks; CANCELADO usa cancelamento administrativo. Não aceita fabricar cadeia pelo status. Conserva restrição clínica do ensaio anterior (recepção não registra frequência; verificar paridade com a permissão legada de registro inicial antes do corte). Não habilita status de reabertura genérico.
- Criação de grupo e série com DTO existente e validações reutilizadas (recorrência, datas, coordenador, conflitos), todos os participantes locais e estado comprovado; criação atômica de sessões e inscrições sem frequência. Próxima sessão sob locks de grupo e sujeitos; rejeita duplicata e série com origem/estado inconclusivo.
- Três testes integrados: cancelamento/recuperação/remarcação por recepção e profissional; PATCH, auditoria e proteção de fato; série e próxima sessão com isolamento. Nenhuma execução local.

## Limites
Não é ativação operacional. Faltam edição/regeneração e transferência de grupos, inscrições retroativas e futuras em lote, remoção da série, DTOs de saída por vínculo e testes HTTP; requerem descobrir e bloquear todos os recursos relacionados antes da escrita. A equivalência de permissões e a versão opcional do PATCH/POST reais ainda precisam revisão (o ensaio exige versão explícita para alterações). Não amplia limpeza de pendências sem prova. Não modifica V1–V15, main, rotas, frontend ou banco real. Sem notificação. 
