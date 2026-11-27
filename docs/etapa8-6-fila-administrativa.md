# Etapa 8.6 — primeira entrega: fila administrativa de pendências clínicas

Base 5e90aacd4493898ba7efd5e0f6e4a4fc621ad099, CI #45929886734 aprovada: 1448 Java, 12 concorrência, 180 Angular, 5 contrato experimental, ambos builds. Proposta de painel e opção B (consultas detalhadas de auditoria) aprovadas pela usuária.

## Entrega
- Rota /pendencias-clinicas no layout existente, guard ADMINISTRADOR, entrada abaixo de Relatórios somente após confirmação de perfil administrador. Recepção e profissional não recebem acesso à fila geral.
- Move a tabela, filtros por situação, paginação, justificativa e decisão da aba de Relatórios para componente dedicado, reaproveitando CSS e RelatorioService. Sem novos endpoints e sem filtros fictícios. Totais vêm da API.
- Distingue estado da decisão de estado da aplicação/reversão usando campos de PainelPendenciasLocalEnsaioService.Item. Ausência de campo não significa ausência de aplicação. Aprovar não aplica correção. Bloqueia repetição enquanto grava e após recusa até consulta explícita. Mantém rascunho até essa consulta.
- Acesso ao detalhe do paciente continua sob autorização do servidor. Nenhuma mudança de backend, regra clínica, migration ou configuração de contrato.

## Validação e continuidade
Specs adicionadas para fila, decisão, versão/recusa, reenvio e visibilidade do menu. Sem execução local; CI existente será a primeira validação deste commit. Não considerar 8.6/8.7 integralmente concluídas. Falta jornada clínica por registro para solicitação, candidatos, resolução de frequência, aplicação de origem, auditoria, reversão e reabertura. A opção B requer consulta operacional própria com escopo do vínculo comprovado e autorização histórica; não publicar indiscriminadamente AuditoriaCorrecaoController de perfil test. Não presumir que uma justificativa prova autoria. As jornadas devem ser entregues em pacote coerente posterior após revisão dos contratos e evidência do CI. Sem merge/PR.
