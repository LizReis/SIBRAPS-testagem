# Etapa 6.5 — parecer de encerramento técnico (6.5E)

Referência validada: 95f9142098d9c0bb425e2507982be2f3abd311b3, branch correcao-cadastro-unico-isolamento. CI [#45883144969](https://github.com/LizReis/SIBRAPS-testagem/actions/runs/45883144969): 1419 testes Java, zero falhas/erros/ignorados, 12 testes adicionais de concorrência aprovados; Angular, contrato experimental e ambos os builds aprovados. Main conferida: 967d5162dd543ec9005608739128423903e75e30.

## Parecer
APROVADA COM RESSALVAS para encerramento técnico no escopo sintético testado, não para implantação nem aceite humano.

- 6.5A: matriz e política no plano do legado histórico.
- 6.5B: H1, fonte local inequívoca, prova V27, aplicação V28, reversão V29, nova rodada V30 e integridade complementar V31. Solicitação, decisão e aplicação separadas, justificativa digitada, auditoria, permissão administrativa local; prontuário, painel, continuidade auditada da frequência, rollback e concorrência HTTP validados. Origem bruta não sobrescrita; não há duplicação de frequência.
- 6.5C: sem evidência suficiente de origem, nenhum acesso ao conteúdo por unidade presumida; sem administrador global, sem atribuição por lotação atual, endereço ou cadastro. Informações contraditórias permanecem inconclusivas; evidência interna confiável, não justificativa isolada. Guarda temporal dos escritores locais, campos de origem dos novos históricos e isolamento dos consumidores testados. Não demonstrada impossibilidade de qualquer inconsistência em todos os escritores legados; sem necessidade concreta identificada de mecanismo adicional de recuperação de outros metadados nos fluxos operacionais analisados. Não afirmar que esse mecanismo foi implementado.
- 6.5D: invariância dos indicadores e consumidores durante reconhecimento/reversão/reaplicação H1; teste integrado de mês efetivamente fechado com V27–V31, reconhecimento e reversão válidos, nova aprovação e reaplicação, preservação do snapshot mensal de todas as unidades, indicadores atuais e backup/restauração. Sem recontar frequência já comprovada.
- 6.5E: revisão dos relatórios e evidências anteriores, sem alteração de código e sem repetir CI documental.

## Ressalvas e limites
Fatos sem origem de fonte comprovada permanecem protegidos, não recebem presença/falta, nem classificação ou visita por inferência; distinguir histórico derivado sem origem própria de fonte factual desconhecida. Nenhuma recuperação por documento externo, nova permissão, backfill indiscriminado ou exclusão de dados. Sem inspeção/alteração de base real. Trilha V31 não é assinatura externa contra administrador SQL privilegiado, nem certificação de eventos anteriores sem evidência. Continuidade de versões de sessão por outro participante permanece conservadora. Marco real de indicadores só após integração definitiva; RF26 externo não ativado. Telas para todas as operações humanas, permissões/erros HTTP, aceitação pelas preceptoras e SGBD/backup/desempenho operacional permanecem nas etapas 7–9 conforme plano. Não confundir suíte sintética verde com homologação completa. Nenhum merge, PR ou execução local.

Próximo portão: 6.6, matriz nominal de regressão e harmonização final. Reaproveitar CI aprovada; não criar execução exclusiva para este parecer.
