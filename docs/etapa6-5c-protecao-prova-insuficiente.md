# 6.5C — proteção de casos sem prova suficiente e prevenção

Base 200c7462ccfce262f5e05a81b3de4370c1339f3b; CI #45864896395 aprovada (1414 Java, 12 concorrência adicionais, Angular e builds). Encerramento técnico de 6.5B no escopo testado.

## Decisão funcional aprovada
- Sem evidência confiável da unidade: preservar fato inconclusivo, sem acesso clínico por qualquer unidade, sem administrador global, sem atribuição automática e sem contribuição de presença/falta confirmada.
- Usar evidências internas confiáveis do próprio atendimento, frequência, sessão, remarcação e auditoria; justificativa digitada não substitui prova. Sem prova, preservar informação inconclusiva; sem anexos obrigatórios. Não atribuir pela lotação atual ou cadastro compartilhado.
- Unidade comprovada e informação recuperável: análise administrativa local, justificativa e aplicação auditada, sem suposições. Não ampliar H1 nem abrir acesso antes de verificar autorização e consistência. Evidências contraditórias não escolhem lado automaticamente.
- Priorizar prevenção de inconsistências novas; não desenvolver investigação complexa apenas para recuperar dados sintéticos.

## Revisão direcionada (6.5C1)
VerificacaoFatoRevisaoProvenienciaService exige uma fonte, mesma pessoa, unidade local, responsável, horário, frequência e cadeia coerentes; HistoricoPacienteRepository pré-filtra unidade da fonte e não concede autorização. ModeloVinculoOperacionalTests valida candidato individual/coletivo, exclusão por horário divergente, isolamento, aplicação e reversão. AvaliacaoProvenienciaClinicaTests cobre cadeia inválida. OrigemAgendamentoTests, OrigemGrupoSessaoTests e OrigemHistoricoNovoTests cobrem proteção da origem nos escritores testados, inclusive lotação alterada e fonte legada. A inspeção não comprova todos os fluxos de produção nem a impossibilidade de dados incompletos.

## Primeiro ajuste de 6.5C2
Acrescentar dois testes unitários direcionados à recusa administrativa: histórico sem fonte, fonte sem unidade, fonte externa; candidato de fonte local sem responsável histórico e duas fontes, tanto listagem quanto comando direto. Mantém a barreira H1 intacta. Não altera serviços de produção, schema, frontend ou dados. Nenhum novo acesso ou migração. Não executar localmente; validar na CI do commit.

Após a CI, avaliar a recuperabilidade de informação ausente quando a fonte é local e inequívoca (incluindo o responsável); não confundir este bloqueio atual com uma resolução implementada. Antes de qualquer mudança de produção, conferir se a lacuna ocorre nos escritores atuais ou apenas em exemplos legados, e demonstrar ausência de impacto indevido nos indicadores com testes integrados direcionados. Não declarar 6.5C concluída nesta entrega. Não repetir CI somente para documentação; sem PR, merge ou alteração da main.
