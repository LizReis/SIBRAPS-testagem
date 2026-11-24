# Etapa 6.3 — revisão complementar do Pacote 6A

**Classificação — APROVADO PARCIALMENTE — COM PENDÊNCIAS.** Base `8670f477dc335e732a216205c7470ac56179d384` em `correcao-cadastro-unico-isolamento`. A árvore estava limpa após o avanço direto para a base. O pacote está em um único commit, filho direto da base, publicado nessa branch. O SHA do pacote consta no commit que contém este relatório. A `main` permanece em `967d5162dd543ec9005608739128423903e75e30`. [Actions da base #45506657339](https://github.com/LizReis/SIBRAPS-testagem/actions/runs/45506657339) · [Execuções da branch](https://github.com/LizReis/SIBRAPS-testagem/actions/workflows/cadastro-unico-diagnostico.yml?query=branch%3Acorrecao-cadastro-unico-isolamento)

## Histórico de risco por vínculo

O `PainelHistoricoServiceImpl` legado seleciona a última revisão Envers disponível até 23:59:59 do último dia do mês, no fuso da JVM. Usa o status, a modalidade, a situação de rua e o risco global então gravados. Uma revisão posterior não altera aquele fechamento. O ensaio adota esse corte temporal e recalcula o risco pela política atual sobre os fatos que já constavam das revisões naquele corte. Uma correção registrada depois do fechamento não altera o mês anterior. Essa semântica representa as revisões disponíveis no corte, sem afirmar a hora em que alguém tomou conhecimento clínico do fato. O painel operacional conserva seu comportamento.

O ensaio exige inventário independente da população inteira no corte, com cobertura atestada das trilhas factuais e cadastrais e das exclusões. O conjunto esperado de vínculos precisa coincidir com o Envers. A falta de revisão de criação de um vínculo local atualmente existente também impede concluir a completude. Para os vínculos do corte, a prova exige inicialização estruturada e a sequência coerente de transições e estados. Modalidade e situação de rua vêm das revisões quando usadas como filtro. Agenda, grupo e sessão precisam de origem imutável auditada. As faltas e presenças são classificadas pela contagem e pelos comparadores já existentes, com data de referência no fim do mês.

Um inventário independente vazio e coerente pode produzir zero. Revisões com timestamps fora de ordem que incluiriam dados posteriores ao corte bloqueiam o mês. Cobertura desconhecida, lacuna de população, status sem prova, pendência ou frequência inconclusiva produzem `disponivel=false` e valores nulos para todo o mês. A base não possui uma fonte independente de cobertura histórica. O contrato é interno, sem parâmetro de cliente e sem implementação operacional. O ensaio o exercita com inventários sintéticos e dados Envers reais de teste. A possível remoção de registros sem trilha não pode ser inferida só de Envers. A série atual fica indisponível enquanto faltar esse inventário. O ponto atual permanece em campo próprio e o mês corrente fica fora dos fechamentos disponíveis.

## RF26

A V16 offline cria `tb_entrega_rf26`, com chave primária por dia técnico, UUID de destinatário e unidade canônica. Guarda estado e horários da reserva e finalização. Não contém e-mail, nome, dados de pacientes ou conteúdo da mensagem. O ensaio registra `RESERVADA`, `SIMULADA`, `INVALIDADA` ou `INCERTA`. A migration conserva V1–V15 e inicia o controle vazio. Foi testada de V15 a V16 com snapshots do esquema anterior, reexecução sem novas migrations, integridade da chave, restrições de estado e restauração de backup com reservas.

O dia técnico segue o fuso configurado no scheduler RF26. A reserva é confirmada antes de revalidar o usuário, o e-mail e os vínculos em uma nova transação de leitura. Reservas duplicadas, inclusive após recriação do serviço, não chegam à simulação. Uma alteração confirmada antes da revalidação pode invalidar a entrega ou alterar o conteúdo simulado. A simulação roda após o encerramento da transação de leitura. A falha incerta finaliza como `INCERTA`. Uma interrupção deixa a reserva bloqueando nova tentativa. Não há recuperação automática de nenhum desses casos.

Uma mudança confirmada **depois** da revalidação pode ocorrer antes da entrega. Manter a transação clínica durante a rede não resolve a incerteza do SMTP. Para a futura ativação, é necessário definir como tratar essa janela e como conciliar reservas e resultados incertos. A unicidade impede outra tentativa automática pelo mesmo controle e chave. Ela não equivale a uma garantia de entrega SMTP exatamente uma vez. O ensaio não injeta transporte SMTP. Não houve envio real, uso de credenciais de e-mail ou ligação ao scheduler. Os logs de RF26 e do adaptador de e-mail foram reduzidos a mensagens sem endereço, nome, assunto ou detalhes de exceção.

## Relatórios, fila e RNF17

- A exportação de busca ativa passa a sinalizar modalidade desconhecida mesmo com filtro de modalidade. Sessão sem status em escopo e período entra nas pendências da frequência coletiva e impede exportação integral. Permanecem os renderizadores PDF e XLSX, os totais ponderados de frequência, a origem por sessão, o responsável histórico e a deduplicação por sessão. Os testes inspecionam células e texto extraído do PDF, filtram período e grupo e conferem a exclusão de outra unidade.
- A fila administrativa foi verificada com paginação e resultados distintos de aprovação e rejeição. A solicitação permanece original e a decisão tem trilha própria. Os testes confirmam que a deliberação não muda a frequência, e que a recepção e a unidade externa não obtêm acesso administrativo. A Opção B mantém o fluxo existente.
- A consulta de lista RNF17 ganhou uma chave de idempotência explícita. Mesmo sob chamadas concorrentes, o lock de usuário e a unicidade do banco preservam um evento. Uma repetição consulta a lista atual sem armazenar o conteúdo da primeira resposta. A chave fica vinculada ao ator, ao evento e à unidade. Visualização e encaminhamento continuam usando estado e classificação locais e escopo por vínculo. A trilha legada permanece no namespace original, sem atribuição retroativa. O teste de concorrência compara todas as tabelas exceto a própria trilha RNF17, confirmando ausência de escrita em frequência.

## Arquivos

| Arquivos | Finalidade |
| --- | --- |
| `HistoricoRiscoVinculoEnsaioService.java`, `CoberturaHistoricaPorUnidade.java`, `EvidenciaStatusVinculo.java` | Fechamento mensal, exigência de cobertura integral e prova de estado por revisão. |
| `BuscaAtivaPainelLocalEnsaioService.java` | Série mensal separada do ponto atual e snapshot de lista para RNF17. |
| `V16__controle_tecnico_rf26.sql`, `ControleNotificacoesEnsaioService.java`, `NotificacoesLocaisEnsaioService.java` | Armazenamento offline, timestamps, dia técnico e revalidação de indicadores suficientes. |
| `AlertaPacientesVermelhosService.java`, `AlertaDestinatariosProviderPorUnidade.java`, `EmailService.java` | Remoção de dados pessoais e conteúdo dos logs. |
| `RelatoriosLocaisEnsaioService.java`, `AuditoriaBuscaLocalEnsaioService.java` | Sinalização de dados inconclusivos e idempotência da consulta à lista. |
| `HistoricoRiscoVinculoIntegrationTests.java`, `PersistenciaIndicadoresLocaisIntegrationTests.java`, `ControleRf26MigrationTests.java` | Cenários de revisão Envers, isolamento, concorrência, conteúdo exportado e evolução V16. |
| `AgendaLegacyMigrationTests.java`, `CancellationMigrationPersistenceTests.java`, `ClinicalAuditMigrationTests.java`, `DiagnosticoConsolidadoMigracaoClinicaIntegrationTests.java`, `EventoEstadoClinicoVinculoMigrationTests.java`, `GroupResponsibilityMigrationTests.java`, `LevantamentoOrigemLegadaTests.java` | Atualização das expectativas do esquema vigente, com caracterização V15 isolada e regressões anteriores preservadas. |

## Validação e limites de ativação

| Execução realizada | Resultado |
| --- | --- |
| Maven `test` direcionado aos ensaios locais, histórico, RF26 e regressões de migração/projeção | 52 aprovados no focado final conjunto. O verificador adicional de ordem temporal passou em execução isolada. |
| `bash ./mvnw -B -ntp clean verify`, Java 21 | 1.378 aprovados, sem falhas, erros ou ignorados. |
| `bash ./mvnw -B -ntp -Dtest=ReconciliationConcurrency61Characterization test` | 12 aprovados, sem falhas, erros ou ignorados. |
| `npm test -- --watch=false --include=…` para permissões, contrato HTTP cadastral, duas suítes de cadastro e contrato clínico local | 33 aprovados (4, 5, 8, 11 e 5), com Node 22 e npm 11.6.2. |
| `npm run build` e `git diff --check` | Aprovados. O Angular manteve o aviso de orçamento do bundle inicial de 858,72 kB frente ao limite de 500 kB. |

O focado conjunto abrangeu `PersistenciaIndicadoresLocaisIntegrationTests`, `HistoricoRiscoVinculoIntegrationTests` e `ControleRf26MigrationTests`. A regressão inicial também exercitou `EventoEstadoClinicoVinculoMigrationTests`, `LevantamentoOrigemLegadaTests` e `ProjecaoClinicaVinculoIntegrationTests`. O histórico foi isolado em um banco H2 exclusivo porque a fixture controla os timestamps de suas próprias revisões. A primeira expectativa de risco para um vínculo AMBOS ignorava o limiar de 15 dias e foi corrigida no teste. A regra clínica permaneceu a mesma.

O Pacote 6B segue pendente. As rotas operacionais, as telas e o scheduler continuam no fluxo existente. Para disponibilizar fechamentos históricos fora do ensaio, falta uma fonte independente de cobertura. Para ativar RF26, faltam uma decisão sobre a janela entre a revalidação e o envio, tratamento autorizado de reservas interrompidas ou incertas e substituição coordenada do único mecanismo diário existente.
