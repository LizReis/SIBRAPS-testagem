# Etapa 2 — reserva, frequência e visita domiciliar

## 2B — Decisões registradas antes da alteração de schema

Base inicial: `aef07bc58aee401e6b95e3426650b5d35baa94cc`. Branch: `correcao-fluxos-assistenciais-sibraps`. Frente A concluída no commit `99e61e0`, com 348 testes no verify, incluindo os 334 anteriores e 14 novos. Evidência específica em [Etapa 2A](etapa2a-gatilho-visita.md). Este registro de desenho precede novos campos, enums e migração; resultados das subetapas seguintes serão acrescentados após execução, sem antecipar aprovação.

### Modelo e fonte de verdade

Proposta a implementar: `SituacaoReserva` (AGENDADA, REMARCADA, CANCELADA, SUBSTITUIDA) e `FrequenciaAssistencial` (NAO_REGISTRADA, PRESENTE, FALTOU). Reserva e frequência serão canônicas. `situacaoAtendimento`/conversor/códigos 1–6 permanecerão como projeção compatível, atualizada transacionalmente. Não haverá dois setters independentes para as fontes: o comando legado será traduzido para o modelo canônico. Mudanças operacionais não apagam frequência já registrada; correção de frequência não muda reserva.

Campos técnicos de migração pendente/motivo preservarão casos que não possam ser identificados. Frequência nula significa desconhecimento técnico em registro legado, não uma quarta frequência clínica, nem NAO_REGISTRADA comprovada. Não disponibilizar resolução automática/administrativa desses casos nesta etapa. Escrita de registros pendentes será bloqueada; leitura manterá seu status legado e sinalização técnica.

### Matriz de migração dos seis códigos

| Status legado | Reserva resultante | Frequência resultante | Evidência usada | Ambiguidade | Tratamento |
|---|---|---|---|---|---|
| AGENDADO (2) | AGENDADA | NAO_REGISTRADA | Estado operacional sem registro clínico, checando contradições | Histórico conflitante | Preservar bruto e marcar pendente se contraditório |
| REMARCADO (4) | REMARCADA | NAO_REGISTRADA | Vínculo original do mesmo paciente e cronologia coerente | Vínculo ausente/inválido/ciclo | Pendente, sem fabricar vínculo |
| PRESENTE (1) | AGENDADA ou REMARCADA | PRESENTE | Valor clínico explícito; origem operacional pelo vínculo válido | Origem contraditória/histórico incoerente | Manter evidência; não adivinhar reserva |
| FALTOU (3) | AGENDADA ou REMARCADA | FALTOU | Valor clínico explícito; origem operacional pelo vínculo válido | Mesmo cuidado acima | Preservar a falta |
| CANCELADO (5) | CANCELADA | Frequência comprovada ou nula | Evento clínico confiável, se existente | Ausência de evidência não prova que não houve frequência | Pendente quando desconhecida |
| REMARCADO_ORIGEM (6) | SUBSTITUIDA | Frequência comprovada ou nula | Histórico clínico; vínculo sucessor após falta coerente pode corroborar, não superar correção posterior | Vínculos múltiplos/cíclicos, pacientes divergentes, histórico conflitante ou insuficiente | Pendente sem inventar fato; preservar flag e revisões |

Histórico corrigido terá precedência sobre flag antiga. Inferência exige verificar tipo/situação, ordem temporal, identidade e coerência da cadeia; contradição não será resolvida escolhendo arbitrariamente uma evidência. IDs, datas, horas, turnos, FKs, versões e flag de proveniência serão preservados. Revisões antigas não receberão preenchimento retroativo de fatos.

### Política central de ocupação

Reserva AGENDADA/REMARCADA com frequência NAO_REGISTRADA ou PRESENTE ocupa vaga; com FALTOU não ocupa. CANCELADA/SUBSTITUIDA não ocupa, mesmo quando conserva frequência clínica. A política produzirá uma projeção persistida de ocupação para consultas; serviços/repositórios não poderão redefinir a regra por conta própria. Registros ambíguos conservarão a ocupação legada observada (1/2/4 ocupantes; 3/5/6 não ocupantes) até decisão autorizada, sem alterar silenciosamente a oferta. Isso é estabilização operacional da migração, não confirmação de frequência.

Colisão paciente/profissional continua validada sob locks paciente → profissional → agendamento. Correção factual não adquire reserva e não depende de oferta. Para reserva substituída, corrigir frequência comprovada preservará SUBSTITUIDA e não reabrirá vaga; cancelamento incorreto continua reservado à Etapa 5. Não se ampliam perfis/permissões, nem se acrescentam justificativa ou reconciliação da Etapa 3/6.

### Consumidores e compatibilidade

| Consumidor | Significado atual / adaptação necessária |
|---|---|
| `Agendamento`, enum/conversor, DTO/mapper | Separar estado operacional/resultado clínico; manter projeção e construtores legados dos testes; novos campos de resposta sem entrada alternativa de escrita. |
| `AgendamentoServiceImpl.save/atualizarStatus` | Cadastro/remarcação completos, frequência factual distinta; manter versão, data, unidade, P03/P04/P05. Não apagar falta do original ao substituí-lo. |
| `AgendamentoRepository` | Contagens por turno/período, colisões de paciente/profissional e horários ocupados usam política central; filtros legados operacionais continuam compatíveis; contagens clínicas passam a frequência. |
| Disponibilidade semanal/exceções/bloqueios | Ocupação central na validação e capacidade; preservar diferenças preexistentes dos filtros de exclusão/alteração. |
| `PacienteServiceImpl` | Assiduidade considera frequência comprovada mesmo em original substituído; não cria frequência para migração pendente. Próximo agendamento/detalhe preservam contrato legado. |
| `HistoricoPacienteServiceImpl` | Preservar evidência de faltas e remarcações; corrigir evento clínico sem confundir SUBSTITUIDA com resultado. Não antecipar auditoria detalhada. |
| `PoliticaVisitaDomiciliar` | Consumir frequência canônica e cadeia coerente; flag permanece proveniência, não decisão isolada. |
| `InicioServiceImpl` | Indicadores de faltas usam frequência; total operacional de agendados mantém semântica documentada. |
| Painel/relatórios/Excel | Leem indicadores de paciente/histórico; relatórios de grupos usam sessão/participante próprios. Validar totais sem aplicar regra individual a grupos. |
| `GrupoTerapeuticoServiceImpl` | Preservar modelo e permissões de grupo; integração de indicadores continua cronológica. Próximo atendimento mantém projeção compatível. |
| Angular agenda/cadastro/paciente/histórico e `agendamento-service.ts` | Preservar `situacaoAtendimento`, endpoints, filtros, versão e erros aplicáveis. Não redesenhar; executar npm ci/build. |
| Envers/painel histórico | Revisões e dados originais preservados; colunas novas em auditoria são aditivas, revisões anteriores ficam sem fatos inferidos. |

### Estratégia de migração e implantação

Será avaliado Flyway Core na versão gerenciada pelo BOM Spring Boot 4.0.6, com H2 do mesmo BOM, para execução **explícita e isolada**, antes de iniciar a aplicação em banco persistente. Razão: versionamento/checksum/controle de reaplicação sem introduzir inicialização destrutiva ou executar antes de existir o schema legado. Não habilitar migração automática indiscriminada nem usar Hibernate create como prova de migração.

A migração será aditiva: novas colunas no agendamento e na tabela de revisões, coluna legada preservada, backfill apenas identificável e marcador dos demais. Produzir fixtures de schema legado anteriores aos campos novos, persistir em banco de arquivo H2, migrar efetivamente os registros, conferir dados antes/depois e reiniciar o migrador. Testar backup/restauração incluindo alterações posteriores. Não chamar reversibilidade completa para binário antigo sem teste: estado clínico de reserva substituída pode não ser compreendido pela versão antiga. Sem apagar colunas para simular rollback.

O SGBD final não está definido neste repositório. A infraestrutura deve recusar execução não validada em outro SGBD; implantação permanece dependente de escolha, ensaio e aprovação operacional. Não executar migração em banco real. Leituras antigas via HTTP permanecerão suportadas; não operar simultaneamente binários antigos escrevendo diretamente no schema novo, pois não conhecem os campos canônicos.

### Validação prevista

Conservar os 334 casos, além dos 14 da Frente A; adicionar modelo, equivalência/projeção, rejeição de estados incoerentes, migração dos seis códigos, ambiguidades, Envers, reinicialização, restauração e contrato HTTP. Reexecutar concorrência e rollback reais, indicadores em cadeia, `mvnw test`, `mvnw verify`, `npm ci`, `npm run build`. Registrar quaisquer expectativas alteradas pela preservação aprovada de faltas antes ocultas, sem remover cenários. Resultado desta subetapa: desenho registrado, implementação e migração ainda não executadas.

## 2C — Camada canônica implementada e validada

Os enums propostos e os campos `situacaoReserva`/`frequenciaAssistencial` foram implementados. As colunas técnicas são `migracao_pendente`, `motivo_migracao_pendente` e `ocupa_vaga`. Reserva/frequência têm mutação controlada; o setter legado traduz comandos e sincroniza a projeção. Callbacks de persistência mantêm a coluna legada e ocupação coerentes. A revisão antiga permanece legível sem preencher retroativamente os novos campos. As tabelas persistentes ainda dependem da migração explícita da 2D.

O DTO ganhou os campos canônicos/técnicos somente de resposta e conserva seu construtor legado e os endpoints. POST não aceita esses campos como entrada alternativa para registrar clínica; o teste tenta enviar presença/cancelamento pelos campos novos e confirma criação AGENDADA/NAO_REGISTRADA. PATCH conserva perfil, unidade, versão, data e oferta onde aplicável.

A política central `PoliticaOcupacaoAgendamento` produz `ocupaVaga`; queries de capacidade, horários e colisões usam essa projeção, mantendo os subconjuntos legados operacionais. Contagens clínicas e assiduidade usam frequência, incluindo fatos preservados em reservas substituídas/canceladas. Histórico clínico é corrigido sem mudar a reserva. A política de visita agora usa a frequência canônica do original e do sucessor, não reconstrói clínica a partir da flag. Marcadores pendentes impedem escrita automática; fatos não identificados não se tornam NAO_REGISTRADA.

### Alterações intencionais das expectativas anteriores

1. `HistoricalAttendanceReviewTests.rescheduleVisitTriggerAndRepeatedCorrectionsRemainIdempotentWhenScheduleExists`: a correção inversa na remarcação resulta em **duas**, não uma falta, pois a falta do original SUBSTITUIDA deixou de desaparecer. Acrescentada asserção da frequência FALTOU preservada no original. Os 33 casos foram mantidos.
2. `HomeVisitRecalculationTests.correctedEvidenceOverridesStaleRescheduleFlag`: deixou de modificar apenas o evento de histórico pela fixture; usa a API para corrigir a frequência canônica comprovada do original, conferindo reserva ainda SUBSTITUIDA, flag de proveniência preservada e alerta desativado.
3. As três repetições E1-12 agora admitem 1 confirmação/1 rejeição quando a correção precede a tentativa de remarcação, ou 2 confirmações quando a remarcação precede a correção clínica do original. Não é duplicidade: o original conserva SUBSTITUIDA, frequência PRESENTE e não ocupa vaga; só há um sucessor. Asserts incluem frequência, contagens de linhas/eventos, vínculo, ocupação e relação entre confirmações e reservas criadas. Nenhum cenário foi removido.

Um reset operacional AGENDADO/REMARCADO não pode apagar uma frequência já registrada: é recusado com 409, preservando o fato e impedindo atalho legado para contornar a correção clínica. Cancelado → frequência continua recusado; não foi implementada regularização da Etapa 5. Original SUBSTITUIDA só aceita correção de frequência já comprovada, com as permissões atuais de correção e sem adquirir reserva. Idempotência e rollback são testados.

`CanonicalAppointmentTests` acrescenta **12 casos**, incluindo seis comandos legados, DTO, preservação da falta/remarcação, correção de substituído, cancelamento sem apagamento de clínica, reset indevido, comandos inválidos e rollback. `verify` completo: **360 executados, 360 aprovados, 0 falhas/erros/ignorados**, BUILD SUCCESS, 41,872 s, término **18:13:16 de 05/11/2026, America/Sao_Paulo**. Esse conjunto conserva os 334 anteriores, os 14 da 2A e os 12 novos. Migração de banco persistente e build Angular ainda não executados nesta subetapa.

## 2D — Migração aditiva executada sobre dados preexistentes

### Infraestrutura escolhida e execução

Flyway Core **11.14.1**, gerenciado pelo BOM Boot **4.0.6**, sem starter de autoexecução. H2 **2.4.240**, também do BOM. `AgendaMigrationTool` é um programa offline explícito, sem inicializar a aplicação web, sem JWT/SUS/email, usando `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` obrigatórios e sem fallback. Recusa outro SGBD, memória, banco vazio/incorreto e ciclo de vida de arquivo diferente do validado (`DB_CLOSE_DELAY=0`). Não foi executado em banco real.

- `agenda-migration/h2/V1__adicionar_estados_canonicos.sql`: cinco colunas no agendamento, as mesmas cinco no audit e `indicadores_pendentes` em paciente/audit. Nenhuma coluna ou tabela é removida; códigos legados permanecem.
- `V2__identificar_frequencias_legadas`: mapeia apenas evidências identificáveis, conserva ocupação legada nos pendentes e marca todos os pacientes com agendamentos para recálculo técnico dos indicadores antes da prontidão da aplicação. Não altera versões, identidades, FKs, flags, datas/horas, eventos ou revisões anteriores. O marcador novo de paciente é técnico, não clínico.
- Histórico Flyway separado: `sibraps_agenda_schema_history`, baseline explícito 0 no schema legado. V1 tem checksum SQL; V2 tem checksum Java explícito. Migrações publicadas são imutáveis: evolução requer nova versão, não editar V1/V2 nem usar repair automático para ocultar divergência. Clean está desabilitado. Dados já canônicos sem comprovação de V2 concluída são recusados antes de criar histórico ou refazer backfill; colunas aditivas ainda nulas permitem completar a migração identificável.
- `CanonicalAgendaGuard`: confere estado completo/coerente antes de servir requisições, sem migrar ou reparar automaticamente. Um backfill incompleto, projeções divergentes ou ocupação pendente alterada bloqueiam inicialização.
- Perfil padrão passa a `ddl-auto=validate`, evitando que um banco persistente seja recriado implicitamente. `dev`/`test` mantêm suas configurações explícitas preexistentes; essas configurações de bases descartáveis não devem ser usadas na cópia persistente. Validate não substitui migração: ela continua sendo uma operação versionada separada.

O export do schema antigo carregava `DB_CLOSE_DELAY=-1` do banco de teste em memória. Sua reutilização em arquivo revelou uma falha H2 de constraint com sessão encerrada. A fixture de arquivo omite somente essa configuração de ciclo de vida e `CREATE USER`/hash de autenticação; **todas as constraints, dados e relações foram mantidos**. O migrador recusa a configuração não validada em vez de afrouxar constraints. Os testes passaram com fechamento/reabertura real do arquivo.

Flyway emite aviso de que H2 2.4.240 é mais novo que seu último H2 oficialmente verificado (2.3.232). A combinação foi exercitada aqui, não certificada para produção. O aviso permanece visível. A validação do SGBD final, versões, ciclo de conexão e requisitos operacionais é pré-condição de implantação; não se extrapola a garantia local.

### Evidências e ambiguidades

O schema legado completo foi exportado pelo código anterior aos campos novos, ainda na preparação da 2B. `legacy-agenda-v0.sql` é uma fixture fixa: o teste não cria o schema atual por Hibernate e o chama de migração. `LegacyAgendaFixtureGenerator` é utilitário de preparação, executado uma vez antes do modelo canônico (1 sucesso), não suíte clínica; recusa gerar sobre o modelo novo. Os testes de regressão usam o SQL antigo imutável em arquivo H2 temporário.

O backfill compara status explícito com eventos clínicos vinculados. Seleciona evidência clínica atualizada; empate contraditório/tipo incompatível fica pendente. Uma revisão Envers **clínica real** pode recuperar resultado quando não sobreviveu evento vinculado. Uma revisão apenas operacional ou a flag sozinha não viram frequência. Revisões antigas recebem somente colunas novas nulas, sem backfill de fatos; um teste com AuditReader comprova leitura antiga após a mudança.

Vínculos ausentes, pacientes diferentes, ciclos, cronologia incoerente, contexto de remarcação não identificado e evidências contraditórias são sinalizados. Frequência comprovada pode ser preservada mesmo se outra parte da relação continuar pendente; caso contrário permanece nula. Uma flag antiga não é corrigida retroativamente para forçar coerência. Nenhum P/F/NAO_REGISTRADA é atribuído apenas para preencher campo obrigatório.

| Resultado na fixture principal | Antes | Depois |
|---|---:|---:|
| Agendamentos | 12 | 12 |
| Identificados | Modelo legado | 8 |
| Pendentes | Sem classificação técnica | 4 |
| Ocupações observadas | 3 | 3 |
| Eventos de histórico | 6 | 6 |
| Revisões Envers | 2 | 2 |
| Versão do atendimento com versionamento especial | 7 | 7 |
| Reaplicações após fechar/reabrir arquivo | Não aplicável | 0 migrações |

Os quatro pendentes principais são um substituído sem frequência identificável, um cancelado sem evidência clínica, uma remarcação com pacientes divergentes e um status legado nulo. São preservados e congelados para alterações automáticas. Os testes comparam **todas as colunas antigas de todas as tabelas exportadas**, antes/depois: não apenas totais. Casos adicionais incluem contradição clínica, cadeia circular, evidência Envers, ocupação pendente preservada e schema parcial.

### Indicadores incompletos e alerta não confirmado

`indicadoresPendentes` é um campo técnico, somente de resposta no DTO do paciente; o mapper ignora entrada desse campo. A migração sinaliza todos os pacientes com agendamentos. `AgendaMigratedIndicatorsInitializer`, antes da prontidão, identifica o histórico Flyway concluído e recalcula esses pacientes com a mesma política de domínio, sob uma transação/lock de paciente por vez. Pacientes com histórico completo recebem contadores, última presença, risco e visita coerentes e saem da pendência; os demais permanecem explicitamente provisórios. O recálculo técnico não cria eventos clínicos de mudança de risco, presenças, faltas ou reservas; alterações derivadas ficam legitimamente auditadas por Envers/SISTEMA. Os snapshots de preservação de colunas antigas descritos no ensaio de migração referem-se ao backfill offline, antes desse recálculo derivado na aplicação. Versões de agendamentos não são alteradas pelo recálculo técnico. Ao recalcular, se uma pendência posterior à última presença comprovada, ou sem cronologia, puder alterar o resultado, contagem e última presença representam **apenas fatos identificados**, são provisórias, e o marcador permanece ativo. Risco e gatilho anteriores não são reclassificados como decisões confirmadas; não se gera novo evento de risco a partir da informação incompleta. Detalhe, busca ativa e painel indicam revisão do histórico em vez de afirmar necessidade de visita.

Uma presença comprovada posterior às pendências permite novamente determinar a sequência atual, limpando a incerteza desses indicadores sem preencher a frequência desconhecida anterior. Quatro testes cobrem pendência anterior/posterior e alerta anterior verdadeiro/falso. Isso é tratamento de desconhecimento, não um novo limite clínico. Casos completos continuam derivando alerta, risco e contadores normalmente. Correções de falta em original SUBSTITUIDA também verificam colisões reais de paciente/profissional antes da alteração, mesmo sem adquirir uma reserva.

### Validações independentes da 2D

`AgendaLegacyMigrationTests`: **9 casos** de dados preexistentes, reaplicação após SHUTDOWN, evidências/ambiguidades, projeção incoerente, schema parcial, banco/ciclo incorretos e restauração. `MigratedAgendaHttpTests`: **5 casos**, iniciando a aplicação com **Hibernate validate** sobre o arquivo efetivamente migrado: DTO legado/canônico, bloqueio de pendências, versão, indicadores provisórios, perfil/unidade e leitura de revisão Envers antiga. `CanonicalAppointmentTests` passou de 12 para **18 casos**, acrescentando quatro de incerteza e dois de colisão de frequência substituída.

`MigratedIndicatorsInitializationTests`: **2 casos** verificam recálculo no primeiro boot de paciente com cadeia completa (duas faltas, risco amarelo e visita ativa), sem inventar eventos, e repetição sem novas gravações.

Focado final da 2D: **34 executados, 34 aprovados, zero falhas/erros/ignorados**, BUILD SUCCESS, 28,079 s, término **19:01:02 de 05/11/2026, America/Sao_Paulo**. O schema e os dados preexistentes foram realmente migrados; não se certificou outro SGBD. A suíte completa final será registrada na 2E.

### Restauração e limite do rollback

O ensaio criou backup anterior à migração e backup mais recente depois de uma correção sintética e de uma nova reserva: **12 → 13 registros**. Restaurou ambos em destinos vazios separados. O primeiro reproduziu todas as colunas antigas e o schema anterior; o segundo reproduziu **todos** os dados atuais, inclusive a correção, nova reserva, campos canônicos e histórico Flyway. O migrador na restauração atual executou zero transformações novamente. O teste de escrita clínica via HTTP é separado desse ensaio físico.

Não há downgrade semântico automático para o binário antigo: ele não compreende, por exemplo, SUBSTITUIDA com frequência corrigida. Restaurar só o backup anterior descartaria escritas posteriores. Após começar a escrever no modelo novo, a estratégia segura exige congelar escritas, preservar backup/export atualizado com todas as alterações e recuperar com binário compatível, ou projetar/testar replay auditado antes de retornar à versão antiga. **Não foi alegado rollback completo para binário antigo** nem apagadas colunas para simulá-lo.

H2 pode confirmar DDL separadamente: falha após V1 pode deixar colunas aditivas. O guard bloqueia a aplicação nesse estado. Não executar clean, reset, repair ou remoção de dados como recuperação automática; usar a cópia/backup verificados e diagnosticar a falha. Os testes demonstram que um schema parcialmente adicionado não é servido, e que o backfill explícito completo o torna validável.

### Procedimento de ensaio em cópia isolada

1. Suspender escritores da cópia, confirmar origem/SHA/schema e inventariar contagens por código, IDs/FKs, versões, ocupação, histórico, revisões e indicadores. Guardar snapshot/backup e testar restauração em outro destino.
2. Configurar `DB_URL` H2 em arquivo isolado, `DB_USERNAME` e `DB_PASSWORD`, sem imprimir seus valores. Nenhum fallback de senha é fornecido. O SGBD de implantação ainda precisa ser escolhido e validado; não usar este procedimento em banco real nesta entrega.
3. Executar o programa explícito do artefato empacotado, sem iniciar a aplicação web:

```bash
java -Dloader.main=com.pet.buscaativa.migration.AgendaMigrationTool \
  -cp backend/buscaativa/target/buscaativa-0.0.1-SNAPSHOT.jar \
  org.springframework.boot.loader.launch.PropertiesLauncher
```

4. Conferir o histórico versionado e comparar colunas antigas, vínculos/versões, eventos/revisões, ocupação e marcadores. Listar pendências apenas em contexto autorizado, sem dados pessoais em logs/documentos. Não preencher lacunas clínicas para concluir o lote.
5. Repetir inicialização/migrador e confirmar zero reaplicação. Iniciar somente binário compatível, schema validate e guard aprovados. Não permitir escritores de versões antigas em paralelo.
6. Ensaiar API/perfis/concorrência, indicadores completos e provisórios, backup mais recente e restauração com escritas posteriores. Só então avaliar os requisitos da futura implantação. A validação empacotada e os resultados integrais serão acrescentados na 2E.
