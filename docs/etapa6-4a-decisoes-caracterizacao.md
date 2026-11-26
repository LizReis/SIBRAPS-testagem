# Etapa 6.4A — decisões e caracterização de candidatos à reconciliação

Base 3d41028ade74a423ac8be9501e7d44656ef49e52; código anteriormente validado em 0e964a8, Actions #45701023101. Parecer 6.3 preservado. Etapa 6.4 não é nova reestruturação.

## Decisões funcionais aprovadas

- Duplicidade comprovada do mesmo atendimento: preservar ambos os registros, identificar o válido e retirar apenas a contribuição indevida do duplicado dos cálculos. Não excluir fisicamente fatos reais. Coincidência de horário ou de frequência, por si só, não comprova duplicidade.
- Contradições: profissional autorizado ou administrador pode registrar a pendência e justificar a correção. Justificativa escrita é suficiente; não exigir documentos, anexos ou prova documental adicional para esta etapa. Reutilizar justificativas, permissões e auditoria atuais. A correção ordinária de frequência própria continua autorizada, sem criar aprovação administrativa adicional. Para uma resolução que afete atendimentos de responsáveis distintos, manter escopo e competência do administrador da própria unidade. Não tornar uma decisão administrativa genérica uma alteração automática.
- Entre unidades, não consultar nem alterar o fato externo. Presença em uma unidade e falta na outra não provam duplicidade ou contradição clínica; não invalidar automaticamente.
- Preservar assiduidade unificada local, horários distintos, RF22/RNF17 e meses já consolidados. Correção altera o estado atual; nenhuma reescrita silenciosa de fechamento mensal.
- Dados de demonstração descartáveis não são autorização para apagar fatos reais ou fixtures necessárias à regressão.

## Achado técnico

DiagnosticoFaltasConsecutivasVinculoService distingue empate com frequências diferentes, mas duas faltas com mesmo instante e origem local comprovada podem ser contadas duas vezes. O teste resolveSomenteEmpateLocalComprovadoPelaCorrecao (PersistenciaIndicadoresLocaisIntegrationTests) espera duas faltas depois de corrigir a presença de um compromisso para falta. Não alterar essa expectativa sem distinguir dois compromissos reais de uma duplicidade comprovada. A nova função pura candidatosReconciliacao agrupa fatos locais por instante, preserva fonte e ID, distingue coincidência de contradição e não aplica decisões, não altera cálculos, não abre tickets, não expõe endpoint. Não detecta duplicidades com horários divergentes por si só. Quatro testes dirigidos: horários distintos, faltas coincidentes, frequências contraditórias com IDs numéricos iguais em fontes distintas, repetição da mesma representação. Sem mudança na regra operacional ou nos dados.

## Próximos pacotes

6.4B: definir comando explícito de resolução, identidade e versão dos fatos envolvidos, vínculo com solicitação e decisão quando aplicável, ator e justificativa, registro auditável e atomicidade. Reutilizar correção existente quando suficiente; não criar anexos, não inferir duplicidade, não desativar barreiras de escopo. Antes de permitir supressão, definir como registrar e revisar a resolução equivocada e competência para aplicação em registros do mesmo profissional versus responsáveis distintos. Casos sem proveniência comprovada ficam para 6.5.

6.4C: integrar a resolução aos cálculos de frequência, renumeração, risco, visita, prontuário, relatórios e reconstrução histórica prospectiva, preservando fatos originais e fechamentos antigos; considerar cadeia de remarcação e autoria histórica sem atribuir por lotação atual.

6.4D: testes integrados de permissões, rollback, concorrência e regressão. Não repetir suites isoladas sem necessidade. Nesta entrega não houve migração, mudança de endpoints, envio externo, alteração de banco real, uso de Codex ou merge. Testes novos não executados localmente; dependem de CI.

## CI #45734368269 — correção direcionada de teste preexistente

A execução registrou 1.404 testes Java, uma falha, zero erros/ignorados, e jobs Angular aprovados. A falha única foi ClinicalAuditMigrationTests.realCorrectionSurvivesApplicationRestartAndLatestBackupRestore, linha 101: esperava 200 na gravação inicial de falta de atendimento sintético, recebeu 422. Os quatro casos novos da 6.4A não falharam. O teste anterior não registrava o corpo HTTP; causa precisa do 422 não comprovada. Foi identificada fragilidade temporal concreta: criação de datas pela LocalDate.now() do host e validação clínica por Clock/fuso institucional. Corrigida a fixture para usar a data institucional obtida da mesma configuração e relógio da aplicação, sempre em dias anteriores; acrescentada mensagem de asserção com corpo da resposta sintética e data de referência. Mantidos HTTP 200 esperado, três faltas, auditoria, restart e restauração. Sem alteração de produção ou relaxamento de regra. Nova CI necessária; nenhum teste local executado. Se o 422 persistir, investigar o corpo registrado em vez de assumir a causa.
