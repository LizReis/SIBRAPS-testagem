# SIBRAPS — Etapa 6.3 · Pacote 6B.3C

**Parecer — aprovado para revisão em ambiente sintético.** A revisão da etapa e a autorização da transição real permanecem pendentes.

A entrega parte de `13e033b40753f1832e72582078c8d189d956a041` na branch `correcao-cadastro-unico-isolamento`. A CI de referência concluiu com sucesso. O SHA desta entrega é o do commit que contém este relatório. [CI de referência](https://github.com/LizReis/SIBRAPS-testagem/actions/runs/45527790592)

## Marco e fechamentos por unidade

A V18 adiciona o histórico prospectivo e o registro de revisão das reservas RF26. A versão é necessária para preservar a evidência, os fechamentos já certificados e a trilha de recuperação após restauração. A V16 e a V17 seguem intactas. Um banco V17 continua apto a iniciar, executar o monitor local e manter a proteção documental. O fechamento persistente e a revisão administrativa de RF26 exigem V18. O startup permanece com validação de schema e migrações offline explícitas.

- `POST /api/painel/historico/marco` registra o instante do relógio institucional, a zona, o administrador e o inventário corrente dos vínculos de sua unidade, em leitura serializável. A operação repetida devolve o mesmo marco. A API não aceita uma data retroativa. Esta entrega deixa todas as unidades sem marco até a ativação autorizada.
- `PUT /api/painel/historico/fechamentos/{AAAA-MM}` recebe o manifesto `vinculos` com o inventário independente do corte e `evidenciaSha256` com o hash do arquivo que fundamentou a conferência. Apenas meses completos a partir do marco podem fechar. Um marco exatamente no primeiro instante do mês permite aquele mês quando ele terminar. O corte usa o último milissegundo na zona institucional.
- A certificação confere a população inteira com as revisões do corte e reaproveita a prova temporal de estado, proveniência, modalidade, situação de rua, frequência e risco. Diferenças de inventário recusam a certificação. O resultado de cada filtro é persistido uma vez por unidade e mês. Mesma evidência e mesmo inventário retornam o fechamento existente. Alterar o manifesto de período certificado resulta em conflito.
- O painel distingue `SEM_COBERTURA` para período sem certificação, `INDISPONIVEL` para indicador inconclusivo em período processado e `COMPROVADO` para valores conhecidos, inclusive zero. O ponto atual fica separado, e lacunas interrompem as séries do gráfico.

O arquivo de evidência independente é responsabilidade da operação autorizada. Deve registrar população, exclusões, fontes factuais e cadastrais e lacunas de cobertura para aquele corte. O sistema registra o hash declarado e verifica a consistência temporal com a evidência disponível no banco. A presença do hash não autentica um arquivo externo nem prova por si só que não houve alterações fora dos escritores coordenados. Sem fonte independente suficiente, a unidade deve manter o mês sem fechamento. Nenhum fechamento anterior foi fabricado, e a V18 não faz backfill.

## Monitoramento e RF26

`MonitoramentoVinculoAgendado` aciona o recálculo existente no contexto operacional. O horário padrão é 01:00 na zona institucional configurada. A configuração aceita `sibraps.monitoramento.cron` e pode ser suspensa por `sibraps.monitoramento.habilitado=false`. As travas de identidade e vínculo serializam o trabalho com os escritores, e nova aplicação do mesmo cálculo não cria nova transição. Instâncias distintas podem percorrer os mesmos alvos. A idempotência e a consistência vêm das travas e revalidações do banco, sem eleição de um executor distribuído. O contexto operacional continua sem o monitor global, serviço de e-mail e agendador de notificações legado. RF22, elegibilidade de vermelho para visita e proveniência comprovada continuam na política local existente.

O controle RF26 reserva a chave da V16 formada por dia, destinatário e unidade em uma transação durável. A chave primária arbitra novas reservas entre instâncias. Uma tentativa bloqueia a reserva, toma as travas de pacientes em ordem canônica, trava o destinatário e reconstrói o conteúdo da unidade da reserva com os dados, a lotação e a elegibilidade atuais do destinatário. O adaptador simulado roda com a transação clínica suspensa e os bloqueios são mantidos até o retorno. Uma alteração clínica que confirma primeiro entra na revalidação. Uma correção que chegou depois do ponto de coordenação aguarda o término da tentativa já iniciada. A janela de teste deve permanecer limitada por um adaptador de duração controlada.

- Falha ambígua do simulador termina em `INCERTA`. Uma interrupção que impede a finalização mantém `RESERVADA`. Nenhum dos estados abre nova tentativa automática.
- `GET /api/auditoria/rf26/ocorrencias?dia=AAAA-MM-DD` lista ocorrências da própria unidade para um administrador. `POST /api/auditoria/rf26/ocorrencias/{dia}/{destinatario}/encerrar` exige justificativa e registra administrador, instante e estado anterior. A revisão compete pelo mesmo bloqueio da tentativa e encerra a ocorrência sem reenvio. A chave da V16 permanece ocupada.
- O contexto operacional contém apenas a infraestrutura de simulação e revisão. O envio real permanece desativado e não há agendador RF26 para transporte. Se um transporte externo vier a ser aprovado, será necessário validar seu timeout, a conciliação de respostas ambíguas e o custo de manter bloqueios. A reserva garante exclusão de duplicata no sistema e não oferece garantia de entrega externa exatamente uma vez.

## Preparação offline e validação

1. Isole uma cópia H2 persistida, pare os processos que a usam e valide uma restauração independente de `SCRIPT TO` com `RUNSCRIPT FROM`.
2. Configure `DB_URL`, `DB_USERNAME` e `DB_PASSWORD` para essa cópia. A ferramenta do pacote anterior prepara até a V16 sem argumentos. Execute `--diagnostico-identidade` e trate os casos bloqueantes pelo fluxo autorizado antes de `--identidade`.
3. Aplique `--transicao` exclusivamente após V17 validada. Reexecute em cópia para conferir que as tabelas e seus dados permanecem. Inicialize o JAR com JWT de teste, mantendo e-mail desativado. Para o ensaio, use loopback e desative a sincronização externa de USFs.
4. Confirme a leitura autenticada em `/api`, o disparo local em cron de ensaio, a recusa dos caminhos legados e a preservação de marco, fechamento e revisão após reinício e restauração. Use o Angular padrão apontado para o mesmo backend.

```sh
JAR=target/buscaativa-0.0.1-SNAPSHOT.jar
java -Dloader.main=com.pet.buscaativa.migration.AgendaMigrationTool -cp "$JAR" \
  org.springframework.boot.loader.launch.PropertiesLauncher --transicao
java -jar "$JAR" --server.address=127.0.0.1 --sibraps.usf.sincronizar=false
```

| Verificação | Resultado |
| --- | --- |
| Java dirigido | 57 aprovações no primeiro conjunto. O segundo passou com 54, incluindo os novos cenários de duas instâncias, bloqueio de correção durante a tentativa simulada, recuperação auditada, marco na fronteira mensal, zero comprovado, reinício e backup restaurado. |
| Maven `clean verify` e matriz adicional | 1.392 testes no verify e 12 adicionais na matriz de concorrência. Todos aprovados, sem falhas, erros ou testes ignorados. |
| Angular direcionado e builds | 59 testes no build padrão e 4 no ensaio aprovados. Ambos os builds passaram. O padrão mantém aviso de orçamento inicial de 500 kB. |
| JAR operacional sobre H2 sintético | Diagnóstico e migração offline V16, V17 e V18 a partir do JAR. Startup padrão com JWT real, registro de marco sintético, disparo do monitor via cron, rejeição da rota de ensaio e recusa do perfil test. |

A revisão futura deve conferir a evidência externa e o dimensionamento do monitor na cópia de implantação, aprovar os responsáveis pela certificação e decidir sobre qualquer transporte real. Nenhum banco real foi usado, migrado ou modificado. Nenhuma implantação ou mensagem externa foi realizada. A publicação se restringe à branch de trabalho, com a `main` intacta.
