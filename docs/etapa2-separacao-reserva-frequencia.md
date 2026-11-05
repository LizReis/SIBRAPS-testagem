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
