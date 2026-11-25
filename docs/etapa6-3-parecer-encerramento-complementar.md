# Etapa 6.3 — parecer de encerramento técnico complementar

Data: 25/11/2026. Branch correcao-cadastro-unico-isolamento. Referência de código validada: 0e964a87fe6c2492d25ca38e64b14565bcd1e69f. Main conferida: 967d5162dd543ec9005608739128423903e75e30. Parecer: APROVADA COM RESSALVAS para encerramento técnico em ambiente sintético, não para implantação nem aceite humano.

## Evidência executada e reaproveitada

A [CI #45701023101](https://github.com/LizReis/SIBRAPS-testagem/actions/runs/45701023101) concluiu os três jobs com sucesso: Maven verify 1.400 testes, 0 falhas, 0 erros, 0 ignorados; matriz ReconciliationConcurrency61Characterization 12/12; Angular padrão 131/131 em 32 arquivos, contrato experimental 4/4 em um arquivo e ambos os builds. ModeloVinculoOperacionalTests: 5/5. A CI #45700168323 havia identificado um conflito de horários criado pela fixture (409 corretamente retornado); o commit de referência separou a sessão às 10:30 da reserva às 09:00, sem modificar produção. A suíte final aprovou também as etapas posteriores que não tinham sido alcançadas na execução anterior.

Revisão documental incremental, sem repetir suites já aprovadas. A matriz de regras e as evidências anteriores permanecem em [revisão integrada anterior](etapa6-3-revisao-final-integrada.md). Suas pendências de testes Angular e procedimento de certificação manual são atualizadas por este parecer; as limitações não resolvidas permanecem.

| Regra / entrega | Evidência |
| --- | --- |
| CPF/CNS únicos, identidade global, vínculo explícito e dados clínicos locais | V17, testes de cadastro e edição concorrentes, autorização por lotação e vínculo; testes operacionais anteriores reaprovados. |
| Três perfis e cinco unidades | Jornada HTTP sintética de ModeloVinculoOperacionalTests: duas USFs, CAPS AD/I/II; recepção, profissional, administração; cadastro e confirmação de vínculo. Não equivale a jornada manual em navegador nem a todas as combinações de papéis/unidades. |
| Presença/falta, correção, isolamento e relatórios | Frequência individual na USF A, coletiva no CAPS I para a mesma identidade, comparação de prontuários, recusa de acesso ao relatório alheio, correção posterior justificada e persistência dos indicadores anteriores. Demais fluxos de remarcação, cancelamento, estado local, busca ativa, RF22 e auditoria RNF17 apoiados pela matriz anterior e suite reaprovada, não todos repetidos na nova jornada. |
| Opção B | Registra pendência e decisão justificada, sem corrigir automaticamente fato ou indicador; evidência anterior de escopo e persistência reaprovada. |
| Histórico automático | V19 inventário, V20 marco técnico, V21 evidências clínicas, reconciliação e V22 observações mensais; painel lê fechamento persistido, conserva zero comprovado e lacunas, recusa certificação manual V18 na instalação V22; preserva fechamentos legados. Teste de cinco unidades, população adicional, filtros, disputa entre fechamentos, correção posterior e restauração. |
| RF26 | Monitoramento local e reserva durável, simulador e matriz anterior reaprovados. Transporte externo permanece desligado. |

## Medições do último Actions

| Pacientes adicionais CAPS AD (acumulados) | Cadastro de lote de 8 (ms) | Monitoramento (ms) | Painel (ms) | Vínculos examinados pelo monitor global |
| ---: | ---: | ---: | ---: | ---: |
| 8 | 61 | 30 | 22 | 10 |
| 16 | 70 | 44 | 23 | 18 |
| 24 | 57 | 42 | 22 | 26 |

Duas instâncias do fechamento: 162/158 ms, uma disputa, quatro leituras concorrentes 31/25/30/27 ms. Depois do fechamento, 1 leitor: 4/4/4 ms (mínimo/mediana/máximo); 4: 7/10/13 ms; 12: 23/33/37 ms. Teste integrado completo passou, incluindo os 24 vínculos adicionais consolidados e ausência de duplicidade. Valores de um runner CI, população moderada e relógio clínico controlado, não SLA, carga institucional ou prova de capacidade para milhares de pacientes.

## Limitações remanescentes e portão de implantação

- A V22 usa observações no último minuto do mês e primeiros cinco minutos do seguinte. Ausência de observação, mudança relevante entre as duas, inconsistência de origem ou divergência Envers pode deixar a competência SEM_COBERTURA; não há recuperação automática de janela perdida, inclusive por indisponibilidade do servidor. Uma divergência inconclusiva pode afetar mais de uma unidade. Novas unidades após o marco não têm cobertura inicial automática. Essas restrições devem ser tratadas/aceitas explicitamente antes de ativar a coleta institucional; não confundir conclusão sintética com garantia de histórico mensal contínuo. Não inventar valores nem exigir certificação manual.
- O relógio controlado e a disputa de dois fechamentos não comprovam a concorrência entre uma correção em transação e o corte físico real. Validar esse cenário, o agendador real, recuperação de indisponibilidade, desempenho com população representativa, tempos de espera, pool, backup e restauração da instalação final V22 em ambiente autorizado antes de implantação. As trilhas não protegem contra todas as operações administrativas privilegiadas. Não foi realizado novo ensaio JAR/proxy V22 institucional.
- O painel atual continua independente da série mensal, que só mostra meses completos comprovados. A data de marco real não foi ativada. A retificação explícita de meses já fechados e a investigação especial do legado sem origem continuam decisões separadas. RF26 externo continua desativado; procedimento de revisão de reservas incertas e eventual transporte exigem etapa própria.
- Aceite visual e de usabilidade com preceptoras e os três perfis ainda pendente. A revisão anterior indica pendências de interface, incluindo controle CSV sem exportação implementada e interface RF26; revisar seu estado e os requisitos antes de modificar. A aprovação das specs não substitui inspeção de navegador.
- Próximo portão: planejar as etapas 7–9 conforme [revisão 6.2G](etapa6-2g-revisao-validacao-final.md), que inclui frontend, CI, orçamento de bundle, respostas HTTP, dependências, banco definitivo, backup e segurança operacional. Reaproveitar a suíte Angular agora verde; não reabrir tarefas concluídas sem evidência. Priorizar revisão de integração/usabilidade e pendências efetivas antes de implementação, sem assumir numeração ou requisitos detalhados não ratificados.

Não foi executado código local nesta revisão; resultados vêm de logs do Actions e relatórios versionados. O novo commit é exclusivamente documental, sem necessidade de repetir CI de código. Nenhum dado real alterado, nenhum e-mail externo, nenhum PR, nenhum merge na main. Encerramento técnico com ressalvas é uma recomendação de revisão, não autorização para implantação.
