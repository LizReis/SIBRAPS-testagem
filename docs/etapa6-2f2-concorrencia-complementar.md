# Etapa 6.2F2 — Disputas concorrentes complementares

**Base:** `cfe560d1c0e9839d7f1941ebf9d6e778c4ff4e30`. Etapa F1 aprovada: 12/12 cenários originais CO-01..11 e CO-13, 139/139 regressões direcionadas, Actions `44429328341`, commit `cfe560d1c0e9839d7f1941ebf9d6e778c4ff4e30`.

## Escopo nesta entrega

Apenas uma classe nova de testes e a atualização do workflow direcionado: **nenhuma alteração em lógica de produção, POM, testes existentes, migração, frontend ou `main`**. Dados inteiramente sintéticos em H2, transações separadas, barreira de início e duas conexões reais por disputa conforme `AgendaIntegritySupport.race()`, com timeout e rejeição de erros técnicos mascarados como conflito.

Cenários F2 adicionais:
1. Criação de grupo × consulta para o mesmo paciente; 2 repetições
2. Regeneração de grupo × consulta no novo horário; 2 repetições
3. Inscrições futuras em dois grupos no mesmo horário
4. Criação de duas próximas sessões para um coordenador no mesmo horário
5. Transferência × inscrição: nenhuma operação confirmada pode ter seu efeito perdido; revalidação pode devolver conflito 409 justificável e exigir novo pedido
6. Criação simultânea de dois grupos independentes deve permitir ambas
7. Regularização factual × confirmação de presença coletiva deve permitir somente uma presença efetiva

**Total esperado:** nove invocações (7 métodos, 2 repetidos). O suporte de corrida exige duas conexões e valida códigos HTTP, e os testes verificam persistência após commit.

## Regressões selecionadas
Se a matriz F2 passar, executar F1 (12 cenários), testes de responsabilidade histórica, auditoria, temporalidade de grupo e frequência, validação de transferências/regenerações e remarcações.

## Riscos restantes — não considerar F concluída
F3: rollback com injeção tardia de falha após escrita, snapshots completos, matriz de permissão/escopo/privacidade (401/403/404/409/422), inversão de interleavings e condições de alteração de perfil, revalidação de participantes e cenários vazios. Não alterar política clínica, RF22, gatilho histórico RF-07, desempate, frontend nem migrar dados. Qualquer falha de F2 exige diagnóstico antes de mudar regra/teste. Resultados pendentes.

## Primeira execução — falha técnica de compilação
GitHub Actions [44430367948](https://github.com/LizReis/SIBRAPS-testagem/actions/runs/44430367948), commit 4055064143d76fecb3f968584c464a4c9a4ab7e7: o compilador dos testes produziu oito erros `String cannot be converted to AgendamentoDTO` em `AdditionalConcurrency62Tests`. A classe herdou `post(AgendamentoDTO)` de `AgendaIntegritySupport`; as chamadas estáticas não qualificadas `post(String)` foram resolvidas para esse método herdado. Nenhum cenário F2 foi executado e as regressões foram ignoradas. Ajuste exclusivamente na classe nova: referenciar `org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(...)` de forma explícita nas oito chamadas. Não foi alterado contrato, comportamento de produção nem expectativa de teste. Nova validação pendente.
