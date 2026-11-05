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

## 2E — Integração, regressões e revisão final

### Objetivo, estado inicial e diagnóstico consolidado

A entrega corrige o gatilho após mudanças factuais e separa reserva de frequência, preservando dados e contratos. O checkout inicial estava limpo, no repositório LizReis/SIBRAPS-testagem, na branch exigida e exatamente em `aef07bc58aee401e6b95e3426650b5d35baa94cc`. Etapas 0/1 e relatórios P03–P05 foram lidos antes das alterações. Baseline executado: 334/334. Não foram usados dados reais, banco real, merge, PR, force push ou remoção de testes.

Antes, o recálculo limpava o alerta ao encontrar presença e o serviço de agenda o sobrescrevia a partir da transição corrente/flag. Isso podia apagar um alerta sustentado por faltas posteriores ou reativá-lo ignorando presença posterior. A Frente A reproduziu sete falhas e centralizou a decisão. A Frente B substituiu a reconstrução provisória do original pela frequência canônica comprovada.

**Ativação:** existe original com falta comprovada e sucessor faltoso, remarcado após essa falta, do mesmo paciente, em cronologia coerente, ambos posteriores à última presença relevante. **Desativação:** após o recálculo completo não resta esse fundamento. Corrigir um par não apaga outro par válido posterior. Presença individual ou em sessão de grupo REALIZADA interrompe a sequência; falta de grupo participa do contador, mas não cria a regra de remarcação individual. A flag de proveniência não é reescrita para esconder correções. Nenhum limite numérico de visita foi criado e os limites de risco anteriores foram mantidos.

Em histórico incompleto, o resultado não é declarado confirmado: contadores usam fatos conhecidos, o marcador técnico permanece e risco/visita anteriores ficam sem reclassificação clínica. Textos de acompanhamento indicam revisão. Isso preserva a ambiguidade em vez de concluir que o paciente compareceu, faltou ou não teve frequência registrada.

### Matriz de comportamento: preservações e mudanças intencionais

| Situação | Antes | Depois | Regra preservada ou alterada | Teste | Resultado |
|---|---|---|---|---|---|
| FALTOU → PRESENTE em atendimento passado, oferta alterada/zerada/bloqueada | Etapa 1 permitia correção sem oferta atual | Continua permitida sem criar reserva ou trocar horário | Preservada; colisão real continua vedada | HistoricalAttendanceReviewTests, AttendanceCorrectionIntegrityTests | Aprovado |
| PRESENTE → FALTOU | Correção factual, indicadores cronológicos | Mantém reserva, registra falta e recalcula todos os derivados | Preservada; estado agora explícito | Mesmas suítes e CanonicalAppointmentTests | Aprovado |
| Falta original remarcada | Status 6 ocultava a falta e retirava-a de contagens baseadas só no status | SUBSTITUIDA + FALTOU, com vínculo e proveniência preservados | Alteração intencional: fato assistencial não desaparece | HistoricalAttendanceReviewTests, CanonicalAppointmentTests | Aprovado |
| Corrigir frequência conhecida do original substituído | API recusava por status operacional 6 | Permite correção factual com perfis atuais de correção, preservando SUBSTITUIDA e liberação da vaga | Alteração intencional; não é regularização de cancelado nem nova rota | CanonicalAppointmentTests, HomeVisitRecalculationTests | Aprovado |
| Corrigir substituído para presença com colisão real | Não havia correção clínica desse original pela API | Recusa colisão paciente/profissional e não altera nada | Integridade preservada | Dois casos de colisão em CanonicalAppointmentTests | Aprovado |
| Visita após correção antiga | A transição isolada podia sobrescrever o resultado da cronologia | Derivada da sequência final; mantém outro par válido, desativa quando fundamento desaparece | Alteração aprovada pela responsável | HomeVisitRecalculationTests | Aprovado |
| Frequência já registrada → AGENDADO/REMARCADO | Comando genérico podia apagar o fato clínico | 409 sem efeitos; não aceita reset operacional como atalho de correção | Alteração intencional de integridade | CanonicalAppointmentTests | Aprovado |
| CANCELADO → PRESENTE/FALTOU | Recusado | Continua recusado | Preservada; regularização pertence à Etapa 5 | HistoricalAttendanceReviewTests, CanonicalAppointmentTests | Aprovado |
| CANCELADO sem frequência → AGENDADO | Reativação genérica submetida à oferta/grade/colisão e guarda temporal | Continua submetida às mesmas validações; pendente legado fica congelado | Preservada onde estado é identificável; pendência exige revisão | HistoricalAttendanceReviewTests, AttendanceCorrectionIntegrityTests | Aprovado |
| Cancelar com clínica comprovada | Projeção 5 ocultava fato prévio | Reserva CANCELADA libera vaga e conserva frequência comprovada | Alteração intencional de preservação | CanonicalAppointmentTests, AgendaLegacyMigrationTests | Aprovado |
| Nova reserva/remarcação | Validava grade, turno, disponibilidade, bloqueio, capacidade, paciente e original | Mantém todas essas validações e P05 | Preservada | AgendaGridTests, AgendaConfiguredGridTests, AgendaCombinedTests, AgendaRescheduleTests | Aprovado |
| Ocupação | Status 1/2/4 ocupavam; 3/5/6 liberavam | Política central mantém semântica para identificados e congela observação legada dos ambíguos | Preservada; projeção é derivada | AgendaConcurrencyTests, AgendaLegacyMigrationTests | Aprovado |
| Grupo terapêutico e presença posterior | Cronologia compartilhada, sessão realizada | Mantida sem impor regra individual a todo grupo | Preservada | HomeVisitRecalculationTests, IsolamentoUnidadeTests | Aprovado |
| Isolamento/perfis existentes | Unidade e perfis validados no backend | Mesmas barreiras, inclusive original/sucessor; campos novos não dão permissão | Preservada | SecurityP01P02Tests, IsolamentoUnidadeTests, MigratedAgendaHttpTests | Aprovado |
| Histórico do paciente e Envers | Eventos clínicos/remarcações e revisões | Eventos preservados; correção atualiza evento clínico; revisões antigas legíveis com campos novos nulos | Preservada e ampliada | PainelHistoricoEscopoTests, CanonicalAppointmentTests, MigratedAgendaHttpTests | Aprovado |
| Repetição, versão obsoleta e falha controlada | Idempotência, conflito e rollback | Nenhuma reserva/evento extra ou gravação parcial; versão continua exigível | Preservada | HomeVisitRecalculationTests, AttendanceCorrectionIntegrityTests, CanonicalAppointmentTests, MigratedAgendaHttpTests | Aprovado |
| Contrato legado Angular/API | situacaoAtendimento, PATCH, filtros e versão | Mantidos, com campos canônicos adicionais somente de resposta | Preservada | CanonicalAppointmentTests, MigratedAgendaHttpTests, build Angular | Aprovado |
| Históricos ambíguos | Frequência não representada separadamente | Frequência desconhecida nula, motivo técnico, ocupação congelada, escrita 409 e indicadores sinalizados | Alteração intencional para não fabricar clínica | AgendaLegacyMigrationTests, CanonicalAppointmentTests, MigratedAgendaHttpTests | Aprovado |

Exemplo novo: `situacaoReserva=REMARCADA` + `frequenciaAssistencial=PRESENTE` informa que o atendimento remarcado foi realizado; o legado continua exibindo PRESENTE. `SUBSTITUIDA` + FALTOU conserva a falta do original sem ocupar vaga. Não há setter público independente para os campos canônicos nem entrada alternativa por JSON; o comando legado passa pelas validações existentes.

### Integração dos testes e problemas corrigidos na 2E

A primeira execução completa dentro do sandbox teve falha de autoanexação Mockito/Byte Buddy à JVM, contaminando contextos de teste. Foi repetida fora dessa restrição, sem desligar testes. A repetição identificou duas regressões reais de integração: duas novas configurações de fixture eram descobertas pelo runner de perfis H2, e um campo primitivo somente de resposta impedia desserialização legada do DTO de paciente. As fixtures agora só se ativam com a propriedade específica do seu teste; `indicadoresPendentes` no DTO é Boolean, aceita ausência na entrada e continua somente de resposta, ignorado pelo mapper. Não foi habilitado override global de beans nem relaxado o teste de segurança. O focado dessas correções passou com 76/76.

Os 334 cenários anteriores foram conservados. Três execuções repetidas E1-12 tiveram nome atualizado para refletir reserva/frequência independentes, mantendo a disputa e ampliando asserções. A expectativa de contagem da remarcação foi fundamentadamente corrigida, conforme seção 2C. Não houve remoção ou desativação de testes. O utilitário LegacyAgendaFixtureGenerator foi executado uma vez antes do modelo e não é contado como nova regressão automática.

Resultados integrais finais, sem skipTests: `bash ./mvnw test` com ambiente herdado (**48,743 s**, término **19:10:48**) e `bash ./mvnw verify` com variáveis dev sintéticas (**52,074 s**, término **19:12:42**), em **05/11/2026, America/Sao_Paulo**. Ambos BUILD SUCCESS. As únicas opções operacionais adicionais foram settings/cache Maven da nuvem e Java 21; o primeiro comando completo não precisou de segredo/perfil manual. A segunda execução verifica também o isolamento diante de variáveis de desenvolvimento. XMLs confirmam 23 classes, 334 cenários anteriores + 48 novos, zero ignorados. A comparação nominal dos testes considera o rename documentado das três repetições E1-12 e confirma zero cenário anterior ausente.

| Execução | Executados | Aprovados | Reprovados | Ignorados | Resultado |
|---|---:|---:|---:|---:|---|
| Baseline anterior, verify | 334 | 334 | 0 | 0 | BUILD SUCCESS |
| Frente A, verify independente | 348 | 348 | 0 | 0 | BUILD SUCCESS |
| Camada 2C, verify independente | 360 | 360 | 0 | 0 | BUILD SUCCESS |
| Migração 2D, focado independente | 34 | 34 | 0 | 0 | BUILD SUCCESS |
| Integração 2E corrigida, focado | 76 | 76 | 0 | 0 | BUILD SUCCESS |
| Final, mvnw test | 382 | 382 | 0 | 0 | BUILD SUCCESS |
| Final, mvnw verify | 382 | 382 | 0 | 0 | BUILD SUCCESS |

Antes desses resultados finais, a tentativa no sandbox registrou 4 falhas/349 erros; a falha de autoanexação inviabilizou grande parte da execução. A primeira repetição fora dele registrou 4 falhas/1 erro de integração, corrigidos e reexecutados conforme diagnóstico acima. Não são ocultados como sucesso nem atribuídos a falha clínica sem análise.

Por classe no verify final:

| Suíte | Executados | Aprovados | Reprovados | Ignorados | Resultado |
|---|---:|---:|---:|---:|---|
| AgendaCombinedTests | 4 | 4 | 0 | 0 | Aprovado |
| AgendaConcurrencyTests | 18 | 18 | 0 | 0 | Aprovado |
| AgendaConfiguredGridTests | 3 | 3 | 0 | 0 | Aprovado |
| AgendaGridTests | 33 | 33 | 0 | 0 | Aprovado |
| AgendaLegacyMigrationTests | 9 | 9 | 0 | 0 | Aprovado |
| AgendaRescheduleTests | 10 | 10 | 0 | 0 | Aprovado |
| AttendanceCorrectionIntegrityTests | 45 | 45 | 0 | 0 | Aprovado |
| BuscaativaApplicationTests | 1 | 1 | 0 | 0 | Aprovado |
| CanonicalAppointmentTests | 18 | 18 | 0 | 0 | Aprovado |
| GlobalConcurrencyHandlerReviewTests | 25 | 25 | 0 | 0 | Aprovado |
| H2ProfileSecurityTests | 4 | 4 | 0 | 0 | Aprovado |
| HistoricalAttendanceReviewTests | 33 | 33 | 0 | 0 | Aprovado |
| HomeVisitRecalculationTests | 14 | 14 | 0 | 0 | Aprovado |
| IsolamentoUnidadeTests | 65 | 65 | 0 | 0 | Aprovado |
| JwtKeyConfigurationTests | 14 | 14 | 0 | 0 | Aprovado |
| MigracaoUnidadeTests | 1 | 1 | 0 | 0 | Aprovado |
| MigratedAgendaHttpTests | 5 | 5 | 0 | 0 | Aprovado |
| MigratedIndicatorsInitializationTests | 2 | 2 | 0 | 0 | Aprovado |
| PainelHistoricoEscopoTests | 1 | 1 | 0 | 0 | Aprovado |
| ProfissionaisEscopoTests | 47 | 47 | 0 | 0 | Aprovado |
| SecurityP01P02Tests | 15 | 15 | 0 | 0 | Aprovado |
| UsuarioUnidadeTests | 13 | 13 | 0 | 0 | Aprovado |
| JwtKeyPolicyTests | 2 | 2 | 0 | 0 | Aprovado |

Os novos casos são 14 de visita, 18 canônicos/compatibilidade/indicadores/rollback, 9 de migração, 5 HTTP sobre arquivo migrado e 2 de recálculo técnico na inicialização: **48**. As verificações de rollback estão nessas suítes e na integridade anterior, não são testes removidos do total.

O comando `PropertiesLauncher` documentado acima foi realmente executado a partir do **jar produzido pelo verify**, sobre outro arquivo antigo sintético: preparação, primeira migração, repetição e consulta de contagens tiveram código 0. Primeira execução: duas migrações; segunda: zero; total 12, pendentes 4, ocupação 3. Isso valida também recursos SQL e classes Flyway presentes no artefato, além da execução pelas classes de teste.


### Concorrência, rollback e ordem de locks

Continua a ordem **paciente → profissional → agendamento**, com bloqueios do banco e controle otimista de versão; não foi criado lock em memória. O recálculo técnico de startup usa apenas um paciente por transação, com o mesmo domínio, e não modifica reservas/profissionais. A migração offline exige escritores suspensos, não concorre com aplicação servindo requisições.

| Execução / grupo | Disputas | Operações | Confirmadas | Rejeitadas |
|---|---:|---:|---:|---:|
| test: Integrados | 3 | 8 | 2 | 6 |
| test: E2A visita | 2 | 4 | 4 | 0 |
| test: P04 | 17 | 52 | 19 | 33 |
| test: Etapa 1 | 19 | 38 | 22 | 16 |
| test: P05 | 1 | 2 | 1 | 1 |
| **Total test** | 42 | 104 | 48 | 56 |
| verify: Integrados | 3 | 8 | 2 | 6 |
| verify: E2A visita | 2 | 4 | 4 | 0 |
| verify: P04 | 17 | 52 | 19 | 33 |
| verify: Etapa 1 | 19 | 38 | 21 | 17 |
| verify: P05 | 1 | 2 | 1 | 1 |
| **Total verify** | 42 | 104 | 47 | 57 |


Cada disputa usa conexões/sessões/transações independentes, sincronização de início e inspeção das linhas/eventos persistidos após o commit. Colisões, capacidade e versões têm rejeição sem gravações parciais. A disputa correção versus remarcação pode confirmar duas operações se a remarcação vier antes: a correção altera o fato do original já SUBSTITUIDA e não cria outro sucessor nem ocupa vaga. Se a correção vencer, a remarcação pode ser recusada. Ambos são resultados serializáveis documentados; não são relaxamento da unicidade. Exceções técnicas inesperadas falham o teste; não são contadas como rejeição de negócio válida.

Rollback controlado cobre frequência, alerta, contador, risco, histórico e número de reservas. A restauração física anterior e posterior à migração cobre preservação dos dados; são evidências diferentes e não se substituem.

### Frontend e limites de validação

No diretório `frontend/sentinela-pet`, `npm ci` e `npm run build` concluíram com código 0, usando Node **24.19.0** e npm **11.6.2**, compatíveis com Angular 21. O npm foi selecionado por npx para usar a versão declarada sem modificar lockfile ou dependências. Build terminou às **18:25:00 de 05/11/2026, America/Sao_Paulo** (7,832 s). Houve aviso de orçamento do bundle: 847,77 kB para limite de 500 kB, excedente de 347,77 kB. Nenhum arquivo frontend mudou. O aviso permanece visível.

A suíte unitária Angular não foi executada nesta etapa. Sete falhas e cinco erros Q01–Q04 constam nos relatórios anteriores, como evidência anterior; não são resultados novos nem foram corrigidos/silenciados aqui. O build verde comprova compilação e contrato estático preservado, não cobertura ponta a ponta de toda a interface.

### Implantação, decisões e pendências das Etapas 3–9

A implementação está preparada para revisão da Etapa 2 em H2; **implantação permanece bloqueada**. O banco final não foi definido/validado e o Flyway avisa sobre H2 acima de sua versão oficialmente verificada. Não há certificação de produção, teste de concorrência no SGBD futuro, execução de CI remota nem migração real nesta entrega. Arquivos temporários sintéticos/logs/XMLs estão em `/tmp/sibraps-etapa2-fluxos/`; não contêm evidência de execução em banco real.

As ambiguidades preservadas não autorizam preenchimento automático: precisam de revisão clínica autorizada e futura reconciliação. A rotina de restauração atual preserva escritas posteriores com binário compatível; downgrade semântico para o binário antigo não foi garantido. Binários antigos não podem escrever em paralelo no modelo novo.

| Etapa | Pendência preservada fora desta entrega |
|---|---|
| 3 | Responsável por atendimento, justificativa obrigatória e trilha detalhada de correções. Os perfis/unidades atuais foram mantidos; não se afirma que a política futura já está pronta. |
| 4 | Fluxo temporal definitivo, relógio/fuso oficial e tratamento de correções no mesmo dia após horário previsto. Guarda temporal anterior preservada. |
| 5 | Regularização explícita de cancelamentos incorretos, sem nova reserva fictícia. |
| 6 | Reconciliação autorizada de conflitos e dados ambíguos, com evidências/justificativa e locks de múltiplos registros. |
| 7 | Classificação global de negócio, conflito transacional e infraestrutura. ResourceExceptionHandler não foi modificado; suas limitações conhecidas continuam pendentes. |
| 8 | UI com reserva/frequência separadas, motivo/auditoria e mensagens específicas. Nenhum redesenho nesta entrega. |
| 9 | Validação integrada/remota, tratamento das falhas Angular anteriores e ensaio operacional no SGBD escolhido com backup/restauração e concorrência. |

Não há nova decisão sobre limiar numérico de visita: foi usada a regra aprovada. As decisões pendentes são a interpretação autorizada das evidências ambíguas, a escolha/validação do SGBD, a política definitiva de cancelamentos/correções e os fluxos das etapas seguintes. Nenhuma foi resolvida inventando fatos clínicos.

### Arquivos modificados e preservação de escopo

Inventário completo: **37 arquivos** (código, testes, fixture e documentação).

- [backend/buscaativa/pom.xml](../backend/buscaativa/pom.xml)
- [backend/buscaativa/src/main/java/com/pet/buscaativa/entities/Agendamento.java](../backend/buscaativa/src/main/java/com/pet/buscaativa/entities/Agendamento.java)
- [backend/buscaativa/src/main/java/com/pet/buscaativa/entities/Paciente.java](../backend/buscaativa/src/main/java/com/pet/buscaativa/entities/Paciente.java)
- [backend/buscaativa/src/main/java/com/pet/buscaativa/entities/PoliticaOcupacaoAgendamento.java](../backend/buscaativa/src/main/java/com/pet/buscaativa/entities/PoliticaOcupacaoAgendamento.java)
- [backend/buscaativa/src/main/java/com/pet/buscaativa/entities/dto/AgendamentoDTO.java](../backend/buscaativa/src/main/java/com/pet/buscaativa/entities/dto/AgendamentoDTO.java)
- [backend/buscaativa/src/main/java/com/pet/buscaativa/entities/dto/PacienteDTO.java](../backend/buscaativa/src/main/java/com/pet/buscaativa/entities/dto/PacienteDTO.java)
- [backend/buscaativa/src/main/java/com/pet/buscaativa/entities/enums/FrequenciaAssistencial.java](../backend/buscaativa/src/main/java/com/pet/buscaativa/entities/enums/FrequenciaAssistencial.java)
- [backend/buscaativa/src/main/java/com/pet/buscaativa/entities/enums/SituacaoReserva.java](../backend/buscaativa/src/main/java/com/pet/buscaativa/entities/enums/SituacaoReserva.java)
- [backend/buscaativa/src/main/java/com/pet/buscaativa/mapping/PacienteMapper.java](../backend/buscaativa/src/main/java/com/pet/buscaativa/mapping/PacienteMapper.java)
- [backend/buscaativa/src/main/java/com/pet/buscaativa/migration/AgendaMigratedIndicatorsInitializer.java](../backend/buscaativa/src/main/java/com/pet/buscaativa/migration/AgendaMigratedIndicatorsInitializer.java)
- [backend/buscaativa/src/main/java/com/pet/buscaativa/migration/AgendaMigrationTool.java](../backend/buscaativa/src/main/java/com/pet/buscaativa/migration/AgendaMigrationTool.java)
- [backend/buscaativa/src/main/java/com/pet/buscaativa/migration/CanonicalAgendaGuard.java](../backend/buscaativa/src/main/java/com/pet/buscaativa/migration/CanonicalAgendaGuard.java)
- [backend/buscaativa/src/main/java/com/pet/buscaativa/migration/V2__identificar_frequencias_legadas.java](../backend/buscaativa/src/main/java/com/pet/buscaativa/migration/V2__identificar_frequencias_legadas.java)
- [backend/buscaativa/src/main/java/com/pet/buscaativa/repositories/AgendamentoRepository.java](../backend/buscaativa/src/main/java/com/pet/buscaativa/repositories/AgendamentoRepository.java)
- [backend/buscaativa/src/main/java/com/pet/buscaativa/repositories/PacienteRepository.java](../backend/buscaativa/src/main/java/com/pet/buscaativa/repositories/PacienteRepository.java)
- [backend/buscaativa/src/main/java/com/pet/buscaativa/services/HistoricoPacienteService.java](../backend/buscaativa/src/main/java/com/pet/buscaativa/services/HistoricoPacienteService.java)
- [backend/buscaativa/src/main/java/com/pet/buscaativa/services/PacienteService.java](../backend/buscaativa/src/main/java/com/pet/buscaativa/services/PacienteService.java)
- [backend/buscaativa/src/main/java/com/pet/buscaativa/services/PoliticaVisitaDomiciliar.java](../backend/buscaativa/src/main/java/com/pet/buscaativa/services/PoliticaVisitaDomiciliar.java)
- [backend/buscaativa/src/main/java/com/pet/buscaativa/services/impl/AgendamentoServiceImpl.java](../backend/buscaativa/src/main/java/com/pet/buscaativa/services/impl/AgendamentoServiceImpl.java)
- [backend/buscaativa/src/main/java/com/pet/buscaativa/services/impl/HistoricoPacienteServiceImpl.java](../backend/buscaativa/src/main/java/com/pet/buscaativa/services/impl/HistoricoPacienteServiceImpl.java)
- [backend/buscaativa/src/main/java/com/pet/buscaativa/services/impl/InicioServiceImpl.java](../backend/buscaativa/src/main/java/com/pet/buscaativa/services/impl/InicioServiceImpl.java)
- [backend/buscaativa/src/main/java/com/pet/buscaativa/services/impl/PacienteServiceImpl.java](../backend/buscaativa/src/main/java/com/pet/buscaativa/services/impl/PacienteServiceImpl.java)
- [backend/buscaativa/src/main/java/com/pet/buscaativa/services/impl/PainelServiceImpl.java](../backend/buscaativa/src/main/java/com/pet/buscaativa/services/impl/PainelServiceImpl.java)
- [backend/buscaativa/src/main/resources/agenda-migration/h2/V1__adicionar_estados_canonicos.sql](../backend/buscaativa/src/main/resources/agenda-migration/h2/V1__adicionar_estados_canonicos.sql)
- [backend/buscaativa/src/main/resources/application.properties](../backend/buscaativa/src/main/resources/application.properties)
- [backend/buscaativa/src/test/java/com/pet/buscaativa/AgendaLegacyMigrationTests.java](../backend/buscaativa/src/test/java/com/pet/buscaativa/AgendaLegacyMigrationTests.java)
- [backend/buscaativa/src/test/java/com/pet/buscaativa/AttendanceCorrectionIntegrityTests.java](../backend/buscaativa/src/test/java/com/pet/buscaativa/AttendanceCorrectionIntegrityTests.java)
- [backend/buscaativa/src/test/java/com/pet/buscaativa/CanonicalAppointmentTests.java](../backend/buscaativa/src/test/java/com/pet/buscaativa/CanonicalAppointmentTests.java)
- [backend/buscaativa/src/test/java/com/pet/buscaativa/HistoricalAttendanceReviewTests.java](../backend/buscaativa/src/test/java/com/pet/buscaativa/HistoricalAttendanceReviewTests.java)
- [backend/buscaativa/src/test/java/com/pet/buscaativa/HomeVisitRecalculationTests.java](../backend/buscaativa/src/test/java/com/pet/buscaativa/HomeVisitRecalculationTests.java)
- [backend/buscaativa/src/test/java/com/pet/buscaativa/LegacyAgendaFixtureGenerator.java](../backend/buscaativa/src/test/java/com/pet/buscaativa/LegacyAgendaFixtureGenerator.java)
- [backend/buscaativa/src/test/java/com/pet/buscaativa/LegacyMigrationFixture.java](../backend/buscaativa/src/test/java/com/pet/buscaativa/LegacyMigrationFixture.java)
- [backend/buscaativa/src/test/java/com/pet/buscaativa/MigratedAgendaHttpTests.java](../backend/buscaativa/src/test/java/com/pet/buscaativa/MigratedAgendaHttpTests.java)
- [backend/buscaativa/src/test/java/com/pet/buscaativa/MigratedIndicatorsInitializationTests.java](../backend/buscaativa/src/test/java/com/pet/buscaativa/MigratedIndicatorsInitializationTests.java)
- [backend/buscaativa/src/test/resources/fixtures/legacy-agenda-v0.sql](../backend/buscaativa/src/test/resources/fixtures/legacy-agenda-v0.sql)
- [docs/etapa2-separacao-reserva-frequencia.md](../docs/etapa2-separacao-reserva-frequencia.md)
- [docs/etapa2a-gatilho-visita.md](../docs/etapa2a-gatilho-visita.md)


Conferência SHA-256 contra o estado inicial: **144 arquivos protegidos sem alterações**, incluindo frontend, CI, segurança/JWT e handler global. Nenhum arquivo rastreado foi removido. Enum/conversor legados e serviços de grupo não foram reescritos. O diff completo foi revisado antes dos commits e passou em `git diff --check`.

### Commits e publicação

- `99e61e0` — fix(paciente): deriva gatilho de visita da frequencia recalculada
- `4f933af` — docs(agenda): define compatibilidade e migracao dos estados assistenciais
- `b38c029` — refactor(agenda): separa reserva e frequencia com projecao legada
- `bd8cdc8` — feat(agenda): migra legado de forma aditiva e preserva ambiguidades
- `37aa725` — fix(test): isola fixtures migradas e preserva entrada legada de paciente

O commit de fechamento da 2E acrescenta os resultados e o inventário a este relatório; nenhum código muda depois da validação integral.

Publicação somente por push normal de `correcao-fluxos-assistenciais-sibraps`. Referências protegidas verificadas antes do push: main `967d5162dd543ec9005608739128423903e75e30`; branch `correcao-estabilidade-agenda-p03-p04-p05` em `05ee61a5123dfe4e914d7c2f5f486f7ecf310164`. Não houve merge, PR automático ou force push. O hash do commit de fechamento deste relatório e a confirmação remota são apresentados na resposta final, pois o arquivo não pode conter o próprio hash de commit.

### Respostas explícitas para revisão

| Pergunta | Resposta |
|---|---|
| 1. Frente A concluída? | Sim; diagnóstico reproduzido, regra centralizada, commit próprio e verify independente. |
| 2. Gatilho depende do recálculo? | Sim; cronologia e frequência comprovada definem a decisão; dados insuficientes ficam sem confirmação clínica. |
| 3. Faltas consecutivas corretas? | Sim nos fatos identificados, incluindo original substituído e grupos; quando incompletas, contagem explicitamente provisória. |
| 4. Última presença correta? | Sim, pela data assistencial e por todos os eventos relevantes; não pela data administrativa da correção. |
| 5. Risco correto? | Sim em histórico completo com limites anteriores; com incerteza relevante, valor anterior não é reclassificado como confirmado. |
| 6. Estados separados? | Sim; reserva e frequência canônicas, com projeções derivadas. |
| 7. Seis status legados preservados? | Sim; códigos, conversor, coluna, DTO e endpoints compatíveis. |
| 8. Migração testada com dados anteriores? | Sim; arquivo H2 com schema antigo, validate, HTTP, reabertura e reaplicação. |
| 9. Perda de informação? | Nenhuma observada nos ensaios: snapshots de todas as colunas antigas, FKs, flags, versões, eventos e revisões conferidos; lacunas anteriores permanecem sinalizadas. |
| 10. P01–P05 preservados? | Sim nas regressões executadas; fontes de segurança/handler/CI preservadas. |
| 11. 334 testes anteriores passaram? | Sim na execução integral final; cenários mantidos, expectativas intencionais documentadas. |
| 12. Novos testes passaram? | Sim, todos os 48; total 382. |
| 13. Frontend compatível? | Contratos mantidos e npm ci/build aprovados; sem afirmar execução da suíte Angular ou validação ponta a ponta completa. |
| 14. Pronta para revisão? | Sim, Etapa 2 validada localmente e publicada para revisão; sem merge ou liberação de produção. |
| 15. O que depende de validação antes da implantação? | SGBD/versões, concorrência e migração/backup nesse banco, política operacional de recuperação, revisão das ambiguidades e requisitos das Etapas 3–9. |
