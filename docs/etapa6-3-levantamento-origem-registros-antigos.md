# Etapa 6.3 — levantamento da origem dos registros antigos

**Estado da Etapa 6.3** — em andamento. Levantamento sobre bases sintéticas em 24/11/2026.

## Base e conjuntos examinados

O HEAD remoto da branch `correcao-cadastro-unico-isolamento` coincidiu com `52df37cfd3994ee8d38f803534a70e9b1fa69d80`. O intervalo e o diff da referência até o HEAD estavam vazios. O workflow de referência aprovou o backend Java 21, a matriz de concorrência, os testes direcionados e o build Angular. O total de referência foi de 1.299 testes Java, 12 de concorrência e 28 Angular. [Commit de referência](https://github.com/LizReis/SIBRAPS-testagem/commit/52df37cfd3994ee8d38f803534a70e9b1fa69d80) · [GitHub Actions #45295096639](https://github.com/LizReis/SIBRAPS-testagem/actions/runs/45295096639)

| Conjunto sintético | Origem e esquema |
| --- | --- |
| Demonstração original e corrigida | Inicializações independentes em H2 de memória com `application-dev.properties` e `DevConfig`. O Hibernate cria o esquema a partir do modelo da branch, que inclui o mapeamento das tabelas V12/V13. Essa inicialização usa o DDL do modelo, sem histórico Flyway. |
| Agenda legada | `legacy-agenda-v0.sql`, exportada antes dos estados canônicos. Restauração em H2 temporário, migração até V8 e outra restauração para migração V8 → V13. |
| Responsabilidade de grupo | `group-responsibility-v4.sql`, gerada pela API e pelo Envers. Mesmo procedimento em arquivos independentes, a partir de V4. |
| Controle de isolamento e conflito | Casos fabricados pelos testes de diagnóstico, com origem registrada, ausente e contraditória, dupla vinculação, transferência de profissional e responsabilidade coletiva pendente. |

As duas fixtures SQL compartilham dados sintéticos. As contagens de cada uma são independentes. A árvore versiona o gerador, as fixtures SQL e cenários construídos em testes. Base externa ou de produção **não inspecionada**. A revisão da árvore rastreada encontrou zero arquivos de banco persistente.

## Inventário

Cada célula traz **total / origem registrada e coerente / sem origem comprovada / inconsistente** segundo o diagnóstico consolidado. Em V8, as colunas de unidade histórica ainda não existem. Em V13, a categoria de origem ausente tem precedência naquelas linhas sobre certas inconsistências internas, documentadas abaixo.

| Conjunto e versão | Agenda | Histórico | Grupo | Sessão | Participação |
| --- | ---: | ---: | ---: | ---: | ---: |
| Agenda, V0 restaurado → V8 | 12 / 0 / 12 / — | 6 / 0 / 6 / — | 0 / 0 / 0 / — | 0 / 0 / 0 / — | 0 / 0 / 0 / — |
| Agenda, cópia V13 | 12 / 0 / 12 / 0 | 6 / 0 / 6 / 0 | 0 / 0 / 0 / 0 | 0 / 0 / 0 / 0 | 0 / 0 / 0 / 0 |
| Grupo, V4 restaurado → V8 | 12 / 0 / 12 / — | 7 / 0 / 7 / — | 1 / 0 / 1 / — | 3 / 0 / 3 / — | 2 / 0 / 2 / — |
| Grupo, cópia V13 | 12 / 0 / 12 / 0 | 7 / 0 / 7 / 0 | 1 / 0 / 1 / 0 | 3 / 0 / 3 / 0 | 2 / 0 / 2 / 0 |
| `dev` original | 23 / 0 / 23 / 0 | 14 / 0 / 14 / 0 | 3 / 0 / 3 / 0 | 13 / 0 / 13 / 0 | 24 / 0 / 24 / 0 |
| `dev` corrigido | 23 / 23 / 0 / 0 | 14 / 14 / 0 / 0 | 3 / 3 / 0 / 0 | 13 / 13 / 0 / 0 | 23 / 23 / 0 / 0 |
| Controle sintético de diagnósticos em esquema atual | 8 / 4 / 2 / 2 | 5 / 1 / 1 / 3 | 2 / 1 / 1 / 0 | 5 / 4 / 0 / 1 | 5 / 2 / 0 / 3 |

| Conjunto | Frequências de agenda excluídas | Frequências de participação excluídas | Total excluído |
| --- | ---: | ---: | ---: |
| Agenda V13 | 10 | 0 | 10 |
| Grupo V13 | 10 | 2 | 12 |
| `dev` original | 16 | 10 | 26 |
| `dev` corrigido | 0 | 0 | 0 |
| Controle sintético de diagnósticos | 3 | 3 | 6 |

Nas duas fixtures, a agenda tem seis frequências canônicas identificadas e quatro registros de migração pendente entre os dez excluídos. Dois agendamentos têm frequência canônica sem registro. A fixture coletiva tem duas faltas em sessões realizadas, ambas sem origem. A pendência inclui um elo de remarcação com paciente divergente em cada ensaio, mais três estados canônicos não resolvidos. Essa contagem de frequências excluídas independe da contagem de históricos, que espelham fatos.

### Evidência disponível

| Conjunto | Mesma pessoa e integridade da fonte | Unidade já registrada na fonte | Pistas e conflitos |
| --- | --- | --- | --- |
| Agenda V8/V13 | 6 históricos apontam para o agendamento da mesma pessoa. | 0 históricos; nenhuma agenda contém unidade histórica. | Os 12 agendamentos têm pista de lotação atual. Há 1 remarcação com paciente divergente entre os 4 registros pendentes. |
| Grupo V8/V13 | 6 históricos de agenda da mesma pessoa e 1 ligado a sessão realizada com a pessoa inscrita e responsável recuperado. | 0 históricos; o grupo e as 3 sessões têm a origem vazia. | Os 12 agendamentos têm pista de lotação atual. Uma sessão tem responsável recuperado diferente do coordenador atual. Há 1 sessão realizada com responsabilidade pendente e 1 histórico coletivo em que o autor é distinto do responsável. A mesma divergência de remarcação foi reproduzida neste ensaio. |
| `dev` original | 9 históricos de agenda para a mesma pessoa e 2 ligados a sessões realizadas com participação da mesma pessoa. | 0 históricos. | Há 3 ações sem fonte direta, duas emitidas pelo recálculo de risco no startup, 2 remarcações cujo original manteve reserva ativa e 1 histórico de falta coletiva com tipo incompatível. |
| `dev` corrigido | 11 históricos de agenda para a mesma pessoa e 2 históricos de sessão com participação e frequência coerentes. Há 1 encerramento local. | 13 históricos herdam a unidade da fonte. As 13 sessões herdam a chave do grupo, e as 23 participações têm vínculo na unidade da sessão. | 10 identidades, 14 vínculos assistenciais explícitos, zero duplicidades e zero conflitos de origem no diagnóstico. |
| Controle de transferência | 2 históricos locais com fonte coerente. Um terceiro aponta corretamente para a agenda de uma das unidades, com origem vazia no próprio histórico. | Esse terceiro histórico é 1 candidato a conferência da fonte. | 1 falta sem origem tem apenas a lotação atual do responsável como pista. Ambos os casos com campo vazio ficam fora da leitura e da revisão local. |

Em cada fixture SQL, três revisões de usuário registram a inclusão de três usuários. Para os 12 agendamentos de cada fixture, zero revisões de inclusão do profissional responsável são anteriores ou do mesmo dia do fato. O autor do lançamento possui uma trilha de autoria, enquanto o agendamento identifica o profissional responsável. A V6 recupera um responsável de sessão pelo cruzamento da transição Envers de realização com a coordenação naquela revisão. A outra sessão realizada começa assim na série Envers, deixando a responsabilidade pendente. As duas auditorias de correção da primeira sessão guardam a unidade do ator e identificações de responsável diferentes, ambas posteriores à data da sessão. O próprio histórico de frequência registra o administrador como autor. Esses registros sustentam a análise de autoria, responsabilidade e sequência de correções. A unidade que realizou a sessão continua sem confirmação.

Em um caso real, uma revisão de lotação só seria candidata a avaliação se identificasse o responsável daquele fato, cobrisse a data e determinasse a unidade com série íntegra e sem versões incompatíveis. Os conjuntos inspecionados contêm zero documentos históricos reais. As revisões e trilhas das fixtures são evidências técnicas sintéticas.

Os preflights V8 contam referências, vínculos e pistas. O diagnóstico V13 verifica também os campos de origem, as relações clínicas, a cadeia de remarcação e as frequências excluídas. As cópias V13 foram abertas com `ACCESS_MODE_DATA=r`. Os snapshots do mesmo esquema foram iguais antes e depois das consultas nos ensaios V8, V13 e na demonstração corrigida, incluindo relações, versões, revisões Envers e as tabelas V12/V13 onde presentes. A comparação V8 → V13 conservou todos os valores das colunas clínicas preexistentes. O histórico Flyway ganhou as cinco migrações previstas. Nas duas fixtures, as tabelas V12 e V13 e suas auditorias ficaram vazias. O ensaio existente de projeção também cobre restauração com solicitação e decisão preexistentes.

## Causa e correção da demonstração

| Arquivo | Alteração |
| --- | --- |
| `DevConfig.java` | Cria os vínculos planejados para cada cenário e usa os métodos das entidades ao gravar a origem de agenda, grupos, sessões e históricos. Mantém as referências cadastrais. Usa o relógio da aplicação, preserva a confirmação de responsabilidade das sessões e dá ao encerramento a origem da profissional autorizada. |
| `DevDemonstracaoIntegrationTests.java` | Exercita o gerador em H2 de memória com relógio fixo e SUS simulado. Confere origem, vínculo, fontes, datas, frequência, remarcações, risco, RF22 e autorização nos dois sentidos. |
| `LevantamentoOrigemLegadaTests.java` e `LegacyMigrationFixture.java` | Restauram e medem cada fixture de forma independente em V8 e V13. Comparam esquema e snapshots e conferem os limites das pistas de lotação, autoria e responsabilidade. |
| `DiagnosticoConsolidadoMigracaoClinicaIntegrationTests.java` | Compara históricos e frequências permitidos em dois vínculos da mesma pessoa após a transferência do profissional, com os casos vazios excluídos da revisão local e preservados no banco. |

O gerador montava os objetos diretamente, deixando de chamar os métodos que fixam a origem. O recálculo também produzia duas linhas de mudança de risco sem ator ou fonte no startup. As duas linhas foram substituídas por dois eventos de falta da cadeia RF22. A explicação do risco vermelho passou para os históricos de falta individual e coletiva que documentam a terceira falta. Assim, cada sessão tem no máximo um histórico de frequência por pessoa nos exemplos gravados, como exige o fluxo de correção coletiva.

As reservas originais de P2 e P3 passam a `SUBSTITUIDA`, preservando a frequência de falta em ambas. Os seus sucessores futuros ficam sem frequência registrada. O segundo atendimento de P7 tornou-se o sucessor do primeiro, com falta própria e sem presença interveniente ou posterior. O recálculo oficial gera o alerta por esses fatos. P7 fica amarelo e entra na busca ativa com a mensagem de busca em ponto de referência e o aviso de referência ausente. P3 entra em vermelho com gatilho desligado. As atribuições da equipe e do aviso automático seguem as regras vigentes.

As duas inscrições de P7 em grupos foram revistas porque o cenário é de acompanhamento individual. A inscrição futura passou para P5, e a segunda foi retirada da sessão agendada do dia, que já tinha P5. Conservou-se a cobertura das duas sessões sem frequência concluída. P1 passou a `AMBOS` para refletir agenda e grupo. P9 passou a acompanhamento de grupo, coerente com seus fatos. As fixtures SQL e os casos negativos de origem ausente, conflito e responsabilidade pendente integram os ensaios.

As rotas clínicas legadas de cadastro e indicadores aplicam um filtro de transição que exige referência compatível e recusa múltiplos vínculos. Isso pode reduzir a visibilidade de exemplos nas telas globais. As leituras locais de fatos comprovados continuam disponíveis ao vínculo autorizado. O estado de encerramento de P8 está no cadastro global e no histórico local, enquanto o vínculo recém-criado tem o estado inicial da fábrica. A evolução sugerida para o corte futuro é migrar as telas e o estado para as leituras por vínculo depois de aprovar a evidência de estado local. Os resultados desta rodada medem a proveniência dos fatos, com os indicadores globais e a projeção diagnóstica seguindo seus contratos existentes.

## Decisões do legado

A **Opção B** mantém três atos distintos. A solicitação exige justificativa escrita. Nos fatos comprovados e já acessíveis à própria unidade, o administrador local analisa a evidência e registra uma decisão auditada V13. Uma eventual aplicação clínica exige comando e autorização próprios. Fatos de origem ainda inconclusiva ficam preservados e fora dos cálculos conclusivos e da fila local. As rotas, as permissões, as migrações V8–V13 e o recálculo oficial seguem o contrato de referência.

| Tema a decidir | Proposta para deliberação da equipe | Exemplo sintético |
| --- | --- | --- |
| Prova suficiente | Definir os documentos contemporâneos aceitos, a identificação mínima do fato, como tratar divergências e o que reter para permitir auditoria. Avaliar Envers apenas com responsável, data, unidade e série coerentes. | H1 aponta para uma agenda com origem gravada e a mesma pessoa. A fonte torna H1 candidato a conferência, com acesso e decisão ainda sujeitos ao procedimento a aprovar. H2 aponta apenas para um profissional hoje lotado em outra unidade e permanece inconclusivo. |
| Quem investiga origem vazia | Aprovar papel, atribuição por caso e acesso mínimo à evidência pertinente. Definir quem pode encaminhar um caso, quem pode pedir comprovação e a quem compete a decisão. O administrador local continua com o escopo já comprovado. | S1 tem responsável recuperado, mas a unidade do atendimento segue desconhecida. Um encaminhamento agregado pode existir no procedimento futuro, com acesso ao conteúdo condicionado à atribuição aprovada. |
| Encerrar e reabrir sem prova | Definir se cabe encerramento inconclusivo com motivo, quais metadados ficam guardados e se uma nova prova admissível permite reabertura auditada. Definir como tratar versões contraditórias e revisão duplicada. | A1 tem duas pistas incompatíveis. O encerramento inconclusivo preserva a falta fora do indicador local. Só uma nova evidência admissível justificaria reabrir a análise. |

O próximo passo é aprovar a matriz de prova, papéis e estados com a equipe e ensaiá-la com H1, H2, S1 e A1 em cópia sintética. O ensaio precisa verificar isolamento nos dois sentidos, revisão concorrente, registro de decisão e eventual aplicação clínica como atos distintos.

## Reprodução e validação

No diretório `backend/buscaativa`, com Java 21:

```bash
bash ./mvnw -B -ntp -Dtest=DevDemonstracaoIntegrationTests,LevantamentoOrigemLegadaTests,CadastroUnicoPreflightTests,OrigemClinicaPreflightTests,DiagnosticoConsolidadoMigracaoClinicaIntegrationTests,ProjecaoClinicaVinculoIntegrationTests,ComparadorSelecaoBuscaAtivaVinculoTests test
bash ./mvnw -B -ntp clean verify
bash ./mvnw -B -ntp -Dtest=ReconciliationConcurrency61Characterization test
git diff --check
```

`LevantamentoOrigemLegadaTests` usa diretórios temporários do JUnit e H2 `SCRIPT`/restore, e executa `CadastroUnicoPreflight` e `OrigemClinicaPreflight` na cópia V8. Após a migração explícita à V13 pela ferramenta offline, `CorrectiveMigrationApplication` inicia o diagnóstico somente leitura sobre a nova cópia. O perfil `dev` do teste usa uma instância em memória com URL exclusiva.

A execução de código e testes desta entrega será identificada nesta seção após a validação do workflow. A referência do workflow consta no início do relatório.

[Checkpoint de conciliação](etapa6-3-checkpoint-conciliacao-historicos-legados.md) · [Contrato do legado inconclusivo](etapa6-3-contrato-revisao-legado-inconclusivo.md) · [Diagnóstico consolidado](etapa6-3-consolidacao-diagnosticos-prontidao.md) · [Projeção clínica](etapa6-3-projecao-clinica-diagnostica-por-vinculo.md)
