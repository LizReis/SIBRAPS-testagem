# SIBRAPS — Etapa 0: preservação e planejamento dos fluxos assistenciais

## 1. Identificação, escopo e natureza das evidências

Projeto: SIBRAPS, para acompanhamento assistencial em USF e CAPS. Repositório: <https://github.com/LizReis/SIBRAPS-testagem>. Inspeção e baseline executados em **05/11/2026, UTC**, em `/workspace/SIBRAPS-testagem`.

Esta entrega cria somente este documento. Não altera produção, testes, entidades, Angular, dependências, migrações ou regras de negócio; não corrige as nove regressões, não abre PR e não integra branches à main. As nove etapas abaixo são **trabalho futuro**, não executado nem implicitamente autorizado por esta entrega.

As afirmações sobre comportamento atual vêm da leitura do código no commit-base. Os resultados quantitativos vêm da execução local de `mvnw verify` e dos XMLs Surefire. Regras aprovadas pela usuária são identificadas como requisitos futuros quando ainda não implementadas. Testes de caracterização não constituem aprovação da regra caracterizada; testes com falha não comprovam os efeitos que suas asserções posteriores não chegaram a executar.

Tecnologias observadas: Java 21, Spring Boot/Security/Data JPA, Hibernate/Envers, H2, Maven Wrapper, Angular e GitHub Actions. Não houve acesso a banco de produção nem uso de dados reais de pacientes.

## 2. Branch de origem e preservação

Origem: `correcao-estabilidade-agenda-p03-p04-p05`, confirmada localmente e em `origin` no mesmo SHA esperado. Foi criada `correcao-fluxos-assistenciais-sibraps` diretamente de `origin/correcao-estabilidade-agenda-p03-p04-p05`, após fetch e confirmação do SHA e do checkout limpo.

As duas grafias de fluxos, com e sem `]`, estavam ausentes tanto nos refs locais quanto nos heads remotos consultados. Aplicou-se o **cenário C** da solicitação: criar a branch correta. Não houve renomeação nem exclusão. A inspeção não alcança branches apenas locais de outro Codespace; não se afirma que o nome incorreto inexista naquele ambiente.

## 3. SHA inicial e ancestralidade

- Base exata: `05ee61a5123dfe4e914d7c2f5f486f7ecf310164`.
- Assunto: `test(review): expose historical attendance compatibility regressions`.
- HEAD da nova branch imediatamente após sua criação: o mesmo SHA.
- `git merge-base --is-ancestor 05ee61a5123dfe4e914d7c2f5f486f7ecf310164 HEAD`: sucesso.
- O commit documental desta etapa deve ter esse SHA como pai direto e adicionar somente este arquivo. Seu SHA final será informado no relatório de entrega, evitando autorreferência no próprio commit.

A base contém as correções P01/P02, P03/P04/P05, os testes complementares de frequência/concorrência e `.github/workflows/ci.yml`. A preservação será conferida também por comparação dos hashes dos arquivos rastreados preexistentes, além do diff Git; não se reconstruiu a base por cherry-pick.

## 4. Main de referência

`origin/main` foi confirmado em `967d5162dd543ec9005608739128423903e75e30`. Essa é a referência da main, não a origem da nova branch. A branch P03/P04/P05 continua inadequada para integração enquanto persistirem as nove regressões. Esta etapa não cria merge, push ou PR para main.

## 5. Estado do Git e procedimento de publicação

| Item | Estado observado antes da documentação |
|---|---|
| Checkout inicial | `correcao-estabilidade-agenda-p03-p04-p05`, em `05ee61a` |
| Modificações e arquivos não rastreados | Nenhum no checkout inicial |
| Branches locais iniciais | `correcao-estabilidade-agenda-p03-p04-p05` (`05ee61a`), `correcao-seguranca-p01-p02` (`e772f2b`), `work` (`e92606f`) |
| Heads remotos após fetch | P03/P04/P05 (`05ee61a`), P01/P02 (`e772f2b`), main (`967d516`) |
| Remote | `origin`, HTTPS para `github.com/LizReis/SIBRAPS-testagem.git`; credenciais omitidas |
| Grafia terminada em `]` | Não encontrada localmente nem no remoto consultado |
| Branch de trabalho criada | `correcao-fluxos-assistenciais-sibraps`, sem commits adicionais na criação |

Antes do commit: conferir `git status`, `git diff --check`, estatísticas, log e índice. Adicionar nominalmente apenas `docs/plano-etapa0-fluxos-assistenciais.md`; conferir também o diff em cache. Mensagem prevista: `docs(plan): registra baseline e regras dos fluxos assistenciais`.

Depois: conferir pai direto, diff de um único arquivo e hashes dos arquivos anteriores; publicar por push normal exclusivamente a branch correta, com upstream nela mesma. Reconsultar os heads remotos para conferir o novo SHA e a preservação de P03/P04/P05 e main. Se houver divergência, autenticação recusada ou conflito remoto, registrar e não forçar. O resultado efetivo da publicação e o estado final do checkout constarão do relatório de entrega.

Não se usou reset, clean, stash, force push, rebase, exclusão de branches ou merge. Artefatos Maven em `target/` permanecem ignorados. Logs e manifestos temporários de conferência ficam fora do repositório, não entram no commit.

## 6. Regras atuais comprovadas no código

Os nomes de arquivos nesta seção são detalhados com seus caminhos na seção 12. O inventário descreve o comportamento existente, inclusive limitações; não o redefine.

| Nº | Fluxo | Funcionamento atual e limites |
|---|---|---|
| 1 | Cadastro de agendamento | `AgendamentoController` autoriza ADMINISTRADOR/RECEPCAO. `save()` transacional valida IDs, paciente ativo e escopo de paciente/profissional, perfil assistencial do profissional, data não passada e grade. Bloqueia paciente e profissional; valida colisões, bloqueio e capacidade; cria AGENDADO e histórico. |
| 2 | Horários disponíveis | `/horarios-disponiveis` consulta profissional/data/turno, usa a mesma grade do cadastro e informa vagas e motivos AGENDA_BLOQUEADA, TURNO_NAO_CONFIGURADO ou CAPACIDADE_ESGOTADA. Horários ocupados ficam indisponíveis. Não recebe paciente; a colisão desse paciente é validada ao salvar. Consulta não reserva vaga. |
| 3 | Disponibilidade semanal e exceções | A exceção por profissional/data/turno prevalece sobre o padrão semanal. Sem capacidade aplicável não se cria reserva. Alterações semanais verificam a maior ocupação por data de hoje em diante; exclusão semanal verifica AGENDADO/REMARCADO. Exceções verificam ocupação da data específica e a capacidade resultante da exclusão. Não são versões históricas da oferta. |
| 4 | Capacidade por turno | Vagas = capacidade aplicável menos AGENDADO, REMARCADO e PRESENTE, limitada a zero. FALTOU, CANCELADO e REMARCADO_ORIGEM não ocupam vaga. Capacidade nula, não positiva ou esgotada rejeita ingresso ativo com 409. |
| 5 | Bloqueio de agenda | Bloqueio por profissional e intervalo inclusivo de datas; consulta/salvamento recusam novas reservas no período. Criar bloqueio com atendimentos AGENDADO/REMARCADO/PRESENTE no intervalo é recusado. Faltas não impedem um bloqueio posterior, o que pode afetar sua correção atual. |
| 6 | Remarcação | Novo agendamento referencia o original bloqueado, da mesma unidade e do MESMO paciente persistido (P05). Pode mudar profissional dentro do escopo. Original deve estar AGENDADO/REMARCADO/FALTOU; fica REMARCADO_ORIGEM, novo fica REMARCADO. Guarda `remarcacaoAposFalta`. Grupo terapêutico não usa essa remarcação individual. Operação e histórico são atômicos. |
| 7 | Cancelamento | Não há fluxo dedicado de regularização factual: a API genérica de status aceita CANCELADO, que deixa de ocupar vaga. A guarda de data futura de `atualizarStatus()` incide sobre qualquer mudança, inclusive cancelamento/reativação; não se deve descrevê-la como restrita à frequência. Não há justificativa obrigatória. |
| 8 | Atualização de status | PATCH `/{id}/status?novoStatus=...&version=...`, com versão opcional. Ordem: leitura escalar da identidade, lock de paciente, profissional e atendimento, escopo, data, versão, idempotência, permissões específicas e transição. Mudança não ativa → ativa chama `validarVaga()`. Versão divergente retorna 409. Não existe uma matriz completa de transições clínicas/operacionais separadas. |
| 9 | Presença | AGENDADO/REMARCADO → PRESENTE em hoje/passado atualiza frequência, recálculo e histórico. Como origem e destino são ativos, não revalida oferta. Não há verificação da hora prevista no mesmo dia. CANCELADO/REMARCADO_ORIGEM → PRESENTE é proibido com 422. |
| 10 | Falta | AGENDADO/REMARCADO → FALTOU em hoje/passado registra ausência e recalcula. CANCELADO/REMARCADO_ORIGEM → FALTOU é proibido com 422. Recepção tem acesso operacional ao registro inicial; não confundir com a correção clínica de frequência existente. |
| 11 | Correção de frequência | PRESENTE ↔ FALTOU exige ADMINISTRADOR/PROFISSIONAL, mas não confere se o profissional logado é o responsável específico. Não exige justificativa. FALTOU → PRESENTE revalida oferta/grade/colisão; PRESENTE → FALTOU não. A primeira direção tem as nove regressões demonstradas abaixo. |
| 12 | Histórico assistencial | `HistoricoPacienteServiceImpl` registra agendamento/remarcação/presença/falta. Ao corrigir PRESENTE ↔ FALTOU reutiliza o evento de presença/falta existente, muda tipo/descrição e mantém seu ID; não acrescenta uma entrada completa por correção. O profissional do evento é o atribuído ao agendamento. Envers e campos de auditoria do histórico existem, mas a API não oferece a trilha completa da RN-09. Cancelamento não tem tipo específico no switch de registro de alteração. |
| 13 | Faltas consecutivas | `PacienteServiceImpl` recalcula a partir dos agendamentos PRESENTE/FALTOU e de participantes com presença registrada em sessões de grupo REALIZADA. Ordena por data/hora, origem e ID; presença zera sequência e atualiza última presença, falta incrementa. Não usa apenas incremento do contador para correções. |
| 14 | Classificação de risco | Amarelo com 2 faltas consecutivas ou dias sem presença acima do limite; vermelho com 3 ou acima do limite vermelho. Individual: >60/>120 dias; grupo/AMBOS: >15/>30 dias. INATIVO fica verde. Entrada em vermelho gera evento próprio de situação atualizada. Não mudar limites nesta evolução. |
| 15 | Visita domiciliar | Falta em remarcação originalmente após falta aciona o gatilho; sair dessa falta o desativa; presença também o limpa no serviço de paciente. Recalcular percorre presenças e pode limpar o gatilho; não equivale a uma trilha de visitas realizadas. Correção bloqueada por oferta preserva o gatilho antigo por rollback. |
| 16 | Autorização por perfil | Segurança stateless JWT e anotações de método. Cadastro/remarcação: ADMINISTRADOR/RECEPCAO. Consulta/status: os três perfis. Correção PRESENTE ↔ FALTOU: ADMINISTRADOR/PROFISSIONAL. Consulta da agenda/detalhe restringe profissional à própria agenda; o PATCH não replica a verificação de responsabilidade. |
| 17 | Isolamento entre unidades | `EscopoUnidadeService` exige unidade configurada; USF compara ID da USF, CAPS compara referência correspondente. Agendamento exige paciente E profissional no escopo, mesmo para administrador. Recurso externo retorna 404, unidade não configurada gera 422. Acesso ao paciente não prova responsabilidade pelo atendimento. |
| 18 | Concorrência | Locks pessimistas de paciente/profissional/original ou atendimento, consultas de colisão do paciente e do profissional no mesmo instante, transação e `@Version`. P04 cobre cadastro/remarcação/reativação concorrentes. A proteção é dos fluxos de serviço testados em H2; não prova proteção contra SQL externo nem comportamento em outro SGBD. |

### Diferença histórica e efeito em cascata

A comparação com main já está registrada em [revisão complementar](revisao-complementar-frequencia-concorrencia.md); nesta etapa o baseline foi reexecutado na base `05ee61a`, sem novo checkout da main nem alteração de código.

| Transição | Antes de P03/P04/P05, conforme revisão anterior | Comportamento na base atual |
|---|---|---|
| FALTOU → PRESENTE | Correção não dependia da oferta vigente | `validarVaga()` exige grade vigente, ausência de colisão, agenda desbloqueada e capacidade. Oferta removida/zerada/esgotada ou bloqueio posterior: 409. |
| PRESENTE → FALTOU | Permitida por perfil autorizado e escopo | Continua permitida, pois deixa o conjunto ativo; os dez casos parametrizados atuais passam. |
| CANCELADO → AGENDADO | Não revalidava vaga | Revalida integridade: passa com oferta válida; falha com 409 quando indisponível/conflitante. Restrição intencional para reativar reserva; não serve como solução para corrigir um fato assistencial. |

Guarda de data e perfil continuam aplicáveis. A reativação de data futura também esbarra na guarda genérica preexistente; os testes históricos não demonstram autorização de reativação futura.

O caminho causal principal é: **enum único mistura reserva/frequência → PRESENTE ocupa vaga → correção de uma falta entra em `validarVaga()` → oferta alterada bloqueia o fato histórico → transação não muda frequência, indicadores, histórico nem gatilho de visita**. A atomicidade evita inconsistência parcial, mas mantém indicadores antigos enquanto a correção legítima não pode ser concluída.

Outras relações: ausência de responsabilidade no PATCH + acesso por unidade amplia quem pode corrigir; ausência de justificativa + sobrescrita do evento limita a explicação administrativa; 409 global + Angular tratando todo 409 como edição concorrente pode mostrar causa incorreta ao usuário. Separar conceitos (Etapa 2) afeta consultas, relatórios, histórico, remarcação, gatilho e telas, não só o enum.

## 7. RN-01 a RN-12: regras aprovadas para evolução

Classificação refere-se à base atual. A coluna desejada registra a política aprovada para trabalho futuro, não uma mudança feita nesta etapa.

| Regra | Classificação atual | Evidência atual | Regra aprovada / etapa |
|---|---|---|---|
| RN-01 — Responsável pelo atendimento | IMPLEMENTADA PARCIALMENTE | Consulta própria é restrita; PATCH de correção só verifica perfil e unidade, não igualdade com o profissional atribuído. | Profissional corrige apenas o atendimento atribuído a ele. Etapa 3. |
| RN-02 — Administração por unidade | JÁ IMPLEMENTADA | Perfil administrador pode corrigir; escopo de paciente e profissional também é exigido para ele. | Preservar administrador da própria unidade, sem acesso externo. Etapas 3–6 mantêm a barreira. |
| RN-03 — Justificativa obrigatória | NÃO IMPLEMENTADA | PATCH tem apenas status/versão; descrição automática não é justificativa informada pelo autor. | Exigir e persistir justificativa não vazia para correções de presença, falta e cancelamento incorreto, acessível à auditoria/administração. Etapa 3. |
| RN-04 — Correção retroativa | EXIGE REVISÃO DE COMPORTAMENTO | Passado é aceito, mas FALTOU → PRESENTE depende da oferta atual; nove falhas no baseline. | Correção factual autorizada independente da oferta atual; tratar colisão real com segurança. Etapas 1 e 4. |
| RN-05 — Cancelamento por engano | IMPLEMENTADA PARCIALMENTE | Existe reativação genérica, sem fluxo dedicado, justificativa ou verificação de responsável. | Regularização pelo responsável ou administrador da unidade com justificativa. Etapa 5, sobre Etapas 2/3/4. |
| RN-06 — Cancelamento de fato já ocorrido | NÃO IMPLEMENTADA | CANCELADO → PRESENTE/FALTOU é explicitamente recusado. | Registrar resultado factual no atendimento existente sem criar reserva retroativa fictícia; preservar evidência do cancelamento. Etapa 5. |
| RN-07 — Integridade do paciente/horário | JÁ IMPLEMENTADA | P04: locks e consulta de mesmo paciente/data/hora, inclusive com profissionais diferentes; testes atuais passam. | Não permitir dois agendamentos ativos do mesmo paciente no mesmo instante. Preservar em todas as etapas. |
| RN-08 — Regularização de conflitos | NÃO IMPLEMENTADA | Existem rejeição/rollback e controle de versão, não procedimento de reconciliação com justificativa e evidências. | Resolução autorizada pelo responsável ou administrador; não modificar automaticamente atendimento de outro profissional. Etapa 6. |
| RN-09 — Rastreabilidade | IMPLEMENTADA PARCIALMENTE | Envers; histórico com `createdBy/updatedBy` e datas via `AbstractEntities`/`AuditoriaConfig`; descrição de última correção. Falta evento dedicado completo com justificativa e consulta de antes/depois/ator de cada correção. `Agendamento` não herda `AbstractEntities`. | Atendimento, antes, depois, justificativa, usuário executor e instante em cada correção relevante. Etapa 3 e apresentação na 8. |
| RN-10 — Recepção | IMPLEMENTADA PARCIALMENTE | Correção PRESENTE ↔ FALTOU é vedada; registro inicial operacional e status genérico são acessíveis. Regularização de cancelamento ainda não tem política própria. | Preservar operações existentes e vedar correções clínicas reservadas. Delimitar operações explicitamente nas Etapas 3/5/8, sem retirar silenciosamente o registro operacional inicial. |
| RN-11 — Correção no mesmo dia | DEPENDE DE DECISÃO TÉCNICA | Código e tela comparam data, não hora prevista; relógio do navegador não é autoridade. | Proposta aprovada para detalhamento: correção factual após horário previsto. Definir relógio oficial, fuso e registro tardio; implementar na Etapa 4. |
| RN-12 — Reserva separada de frequência | DEPENDE DE DECISÃO TÉCNICA | Um único `SituacaoAtendimento` individual. Grupos possuem modelo distinto, que não implementa essa separação para individuais. | Planejar reserva AGENDADA/REMARCADA/CANCELADA/SUBSTITUÍDA e frequência NÃO_REGISTRADA/PRESENTE/FALTOU; compatibilidade, mapeamento e migração na Etapa 2. |

RN-02/RN-07 implementadas não significam cobertura completa das futuras rotas. RN-05 parcial reconhece apenas a reativação genérica existente, não um fluxo clínico já pronto. Não há necessidade de rediscutir a aprovação das RN; as pendências são desenho e comprovação técnica.

## 8. Matriz de compatibilidade e contratos

Abreviações de arquivos: **AGS** = `AgendamentoServiceImpl`; **AGR** = `AgendamentoRepository`; **AGC** = `AgendamentoController`; **HIS** = `HistoricoPacienteServiceImpl`; **PAC** = `PacienteServiceImpl`; **ESC** = `EscopoUnidadeService`; **UI** = páginas/serviços Angular listados na seção 12. Riscos são os de uma implementação futura incorreta.

| Regra / cenário | Comportamento atual | Comportamento desejado | Arquivos envolvidos | Risco | Etapa prevista |
|---|---|---|---|---|---|
| Criar dentro da grade (P03) | Cria se data, paciente, escopo, capacidade e colisões válidos. | Preservar; modelo separado não pode ampliar vagas. | AGS, `AgendaHorarioProperties`, AGC, UI novo-agendamento | Alto: regressão P03/P04 | 1–9 |
| Criar fora da grade (P03) | Rejeita com 422, inclusive hora incompatível com turno/intervalo. | Preservar. Flexibilidade histórica não deve alcançar POST. | AGS, `AgendaHorarioProperties`, `AgendaGridTests` | Alto | 1/2/9 |
| Mesmo paciente simultâneo, profissionais diferentes (P04) | Lock de paciente serializa; uma reserva vence e outra conflita. | Preservar rejeição e rollback integral. | AGS, AGR, `PacienteRepository`, `AgendaConcurrencyTests` | Alto: duplicidade | 1/2/6/9 |
| Remarcar mesmo paciente para outro profissional | Permitido dentro do escopo, oferta válida e estados compatíveis. | Preservar identidade e cadeia original/substituto. | AGS, AGR, HIS, UI novo-agendamento | Alto: perda do vínculo | 2/9 |
| Remarcar original de outro paciente (P05) | 422 sem alteração parcial. | Continuar proibido. | AGS, `AgendaRescheduleTests` | Alto: integridade clínica | Todas |
| Registrar presença inicial | Permitido em AGENDADO/REMARCADO hoje/passado, sem exigir justificativa de correção. | Preservar operação inicial; distinguir da correção e do horário oficial futuro. | AGC, AGS, PAC, HIS, UI agenda | Alto: confundir registro e correção | 2/3/4/8 |
| Registrar falta inicial | Permitido em AGENDADO/REMARCADO hoje/passado, com recálculo/gatilho. | Preservar efeitos e permissões operacionais existentes. | AGS, PAC, HIS, UI agenda | Alto | 2/3/4/8 |
| Corrigir FALTOU → PRESENTE | 409 se oferta atual removida/zerada/esgotada/bloqueada. | Permitir fato autorizado sem oferta atual, com controle de colisão, versão, motivo e auditoria. | AGS, AGR, PAC, HIS, novo contrato de correção, UI | Alto: nove falhas | 1/2/3/4/8 |
| Corrigir PRESENTE → FALTOU | Permitido por administrador/profissional; recálculo e evento existente atualizado. | Preservar efeito factual, exigir responsável/motivo e trilha completa. | AGS, PAC, HIS, DTO de correção, UI | Alto: risco/gatilho | 2/3/4/8 |
| Corrigir após capacidade alterada | FALTOU → PRESENTE depende da capacidade vigente; direção inversa não. | Não condicionar o fato passado à capacidade atual; reserva real continua sujeita à capacidade. | AGS, serviços de disponibilidade/exceção, AGR | Alto: misturar conceitos | 1/2/4 |
| Corrigir após bloqueio posterior | FALTOU → PRESENTE bloqueado; inverso permitido. | Correção factual independente do bloqueio posterior; novas reservas continuam bloqueadas. | AGS, `BloqueioAgendaServiceImpl`, AGR | Alto | 1/2/4 |
| Corrigir outro profissional, mesma unidade (RN-01) | Guarda de correção no PATCH não confere responsável, apesar do filtro de leitura. | Profissional alheio recusado no backend; administrador da unidade autorizado. | AGS, ESC, usuário autenticado, UI | Alto: autorização clínica | 3/8 |
| Corrigir outra unidade (RN-02) | 404 no escopo, inclusive administrador. | Preservar em todas as novas rotas/consultas/auditorias. | ESC, AGS, HIS, controllers futuros | Alto: isolamento | 3/5/6/9 |
| Corrigir cancelamento por engano | Reativação genérica revalida oferta; sem motivo e sem fluxo próprio. | Separar recuperação de reserva de correção do fato; responsável/admin, motivo e versão. Recuperar reserva exige oferta. | AGC, AGS, novos DTOs, entidade, HIS, UI | Alto: duplicidade/contrato | 2/3/5/8 |
| Regularizar cancelado já realizado | CANCELADO → PRESENTE/FALTOU proibido com 422. | Atualizar fato real do atendimento existente sem POST retroativo; manter cancelamento como evidência. | AGS, modelo separado, DTOs, HIS, migração, UI | Alto: falsificar reserva/histórico | 2/3/5/8 |
| Regularizar históricos conflitantes | Rejeição por colisão; não há fluxo auditável de decisão. | Análise autorizada explícita; evidências; nunca editar automaticamente o atendimento alheio. | AGS, AGR, entidade/DTO de reconciliação a definir, HIS, UI | Alto: apagar fato ou violar P04 | 6/8 |
| Recalcular indicadores/risco | Fonte cronológica de individuais/grupos; limites fixos. Não recalcula se correção recusada. | Aplicar ao fato canônico; preservar limites, desempates e consistência de relatórios. | PAC, `PoliticaClassificacaoPaciente`, HIS, relatórios/painel | Alto: cascata clínica | 1/2/4/5/6/9 |
| Acionar/desativar visita domiciliar | Falta na remarcação após falta aciona; correção aceita pode limpar. Oferta fechada impede essa correção. | Preservar gatilho em fluxo normal e corrigir bloqueio factual; testar repetição e histórico misto. | AGS, PAC, flag da entidade, UI acompanhamento | Alto | 1/2/4/5/9 |
| Consultar alterações | Linha do fato pode ser sobrescrita; API não lista correções completas. | Mostrar evento factual e trilha de cada alteração com autor real, instante e motivo, no escopo autorizado. | HIS, `HistoricoPaciente`, DTOs/repositório, UI histórico | Alto: perda de evidência | 2/3/8 |

### Inventário dos impactos de contrato, sem implementá-los

- **HTTP/DTO**: manter cadastro, disponibilidade e remarcação compatíveis; desenhar operação explícita de correção/regularização com justificativa, versão esperada, resultado e identidade do atendimento. Não definir URLs finais neste planejamento. O PATCH atual recebe parâmetros e corpo nulo: exigir motivo apenas no Angular seria insuficiente. Uma fase de compatibilidade deve impedir que o PATCH legado contorne a nova política, sem quebrar registro operacional inicial. Avaliar códigos estáveis de erro para distinguir vaga, versão, autorização e infraestrutura.
- **Entidades/dados**: separar reserva/frequência exige alteração aditiva em `Agendamento`, conversores, queries por status, mapeadores, histórico e eventual tabela/evento de correções. Preservar FK do original, flags, versão, unidades e revisões. Estruturas de auditoria/reconciliação ainda são propostas, não arquivos existentes.
- **Migrações**: preservar os códigos legados PRESENTE=1, AGENDADO=2, FALTOU=3, REMARCADO=4, CANCELADO=5, REMARCADO_ORIGEM=6 enquanto houver leitores legados. Estudar mapeamento de casos ambíguos antes de alterar dados; plano na seção 13.
- **Angular**: `agendamento-service.ts` contém interfaces e PATCH; `agenda.ts/html` usa o enum para legendas, contagens, ações e trata todo 409 como alteração por outra pessoa. Novos campos e erros exigirão ajustes também em histórico, acompanhamento, cadastro, agendamentos do paciente e relatórios. Datas do navegador não substituem validação no servidor.

## 9. Baseline executado e cobertura

### 9.1 Execução real

Executado na nova branch ainda em `05ee61a`, antes da criação do documento, a partir de `backend/buscaativa`. Comando de `verify`, sem supressão de testes, com opções somente de infraestrutura do ambiente:

```bash
bash ./mvnw -s /workspace/.cache/sibraps/maven-settings.xml -B \
  -Dmaven.repo.local=/workspace/.cache/sibraps/m2/repository verify
```

Java 21 preparado no ambiente, truststore do sistema e cache Maven foram usados. No subprocesso foram removidas variáveis de perfil/JWT/banco que poderiam vir do ambiente externo; a suíte usa perfil de teste, chaves sintéticas e H2 isolado. Nenhum valor real de produção foi fornecido, nem configuração do projeto modificada. Os caminhos do comando são específicos desta nuvem; em outro ambiente Java 21 configurado, a referência continua `bash ./mvnw verify`.

| Resultado | Valor |
|---|---:|
| Total | 289 |
| Aprovados | 280 |
| Falhas de asserção | 9 |
| Erros | 0 |
| Ignorados | 0 |
| Build | **BUILD FAILURE** |
| Código de saída | 1 |
| Duração Maven | 46,033 s |
| Término | 2026-11-05 19:08:45 UTC |

Fontes: resumo Maven e `target/surefire-reports/TEST-*.xml`. Log local temporário: `/tmp/sibraps-etapa0/baseline-verify.log`; não é um artefato versionado ou permanente. O Surefire falhou na fase `test`; não se afirma que as fases seguintes de empacotamento/verificação concluíram. Os 231 testes anteriores à revisão complementar passaram; os 58 complementares são 33 de frequência (24 aprovados/9 falhas) e 25 de handler (todos aprovados).

### 9.2 Nove falhas preservadas para a Etapa 1

Todas em `HistoricalAttendanceReviewTests`, com resposta esperada 200 e recebida 409. Índices correspondem às invocações registradas nos XMLs; parâmetros conferidos pela fonte `correctionCases()` e mensagens de execução.

| Invocação / método | Perfil / cenário | Causa provável confirmada pelo caminho do código |
|---|---|---|
| `historicalAbsenceCorrectionMustSurviveLaterScheduleChanges[2]` | ADMINISTRADOR / REMOVED | Oferta semanal removida; `validarVaga()` não encontra capacidade. |
| Mesmo método `[3]` | ADMINISTRADOR / ZERO_CAPACITY | Capacidade vigente zero. |
| Mesmo método `[4]` | ADMINISTRADOR / REDUCED_CAPACITY | Redução posterior abaixo da ocupação do turno bloqueia ingresso em PRESENTE. |
| Mesmo método `[5]` | ADMINISTRADOR / BLOCKED | Bloqueio posterior da data. |
| Mesmo método `[7]` | PROFISSIONAL / REMOVED | Mesma dependência de oferta removida. |
| Mesmo método `[8]` | PROFISSIONAL / ZERO_CAPACITY | Mesma capacidade zero. |
| Mesmo método `[9]` | PROFISSIONAL / REDUCED_CAPACITY | Mesma capacidade esgotada após redução. |
| Mesmo método `[10]` | PROFISSIONAL / BLOCKED | Mesmo bloqueio posterior. |
| `correctingReplacementAttendanceMustClearVisitEvenAfterCapacityWasClosed` | ADMINISTRADOR / remarcação após falta, exceção zerada | FALTOU → PRESENTE recusado pela oferta; transação não limpa gatilho nem contador. |

Os dois cenários AVAILABLE de FALTOU → PRESENTE passam; os dez de PRESENTE → FALTOU passam. Os cinco testes de reativação caracterizam ingresso ativo com oferta; quatro testes adicionais comprovam rollback integral da correção atualmente bloqueada. Nenhuma falha foi ocultada, teste removido, desabilitado ou modificado nesta etapa.

### 9.3 Suítes existentes e resultado desta execução

| Tema | Classes | Executados / falhas |
|---|---|---:|
| P01: chave e autenticação | `JwtKeyConfigurationTests`, `JwtKeyPolicyTests`, `SecurityP01P02Tests` (também P02) | 14 + 2 + 15 / 0 |
| P02: perfis H2 | `H2ProfileSecurityTests` | 4 / 0 |
| P03: grade e configuração | `AgendaGridTests`, `AgendaConfiguredGridTests` | 33 + 3 / 0 |
| P04: concorrência | `AgendaConcurrencyTests` | 18 / 0 |
| P05: remarcação | `AgendaRescheduleTests` | 10 / 0 |
| Integração P03/P04/P05 | `AgendaCombinedTests` | 4 / 0 |
| Frequência e efeitos clínicos | `HistoricalAttendanceReviewTests` | 33 / 9 |
| Mapeamento HTTP global | `GlobalConcurrencyHandlerReviewTests` | 25 / 0 |
| Escopo/unidade/profissionais/histórico | `IsolamentoUnidadeTests`, `ProfissionaisEscopoTests`, `UsuarioUnidadeTests`, `MigracaoUnidadeTests`, `PainelHistoricoEscopoTests` | 65 + 47 + 13 + 1 + 1 / 0 |
| Inicialização | `BuscaativaApplicationTests` | 1 / 0 |
| **Total** | | **289 / 9** |

Os 18 casos P04 incluem criação simultânea com profissionais distintos, mesmo profissional, múltiplas requisições e disputa com remarcação, cancelamento e reativação. Não equivalem a concorrência entre duas novas operações clínicas auditáveis ainda inexistentes.

### 9.4 Lacunas: testes a criar ou adequar futuramente

| Necessidade | Cobertura atual / limite | Próximos testes, sem implementação nesta etapa |
|---|---|---|
| Correção de outro profissional | Há filtros de leitura, mas falta bloqueio equivalente no PATCH. | Token real de colega na mesma unidade via HTTP; negar e conferir estado/histórico intactos. Permitir ao responsável. |
| Administrador da própria unidade | Positivos de correção e negativos de unidade externa já existem. | Repetir em cada nova rota de correção/cancelamento/reconciliação e combinações USF/CAPS. |
| Justificativa ausente/vazia | Contrato não contém justificativa. | Ausente, nula, espaços, tamanho limite e válida; rejeição sem writes; persistência/consulta no escopo. |
| Retroatividade | Dez cenários por direção, nove falhas conhecidas; não cobrem todo contrato futuro. | Corrigir oferta alterada, grade alterada, exceção, fuso, dia anterior e hora do mesmo dia; colisão real tratada distintamente. |
| Reativação/cancelamento indevido | Cinco cenários históricos de reativação; cancelado → frequência vedado. | Reserva futura com/sem oferta, já ocorrido sem nova reserva, versão/motivo/autor e preservação da evidência do cancelamento. |
| Colisão entre atendimentos | P04 forte em cadastro e ingresso ativo. | Correção factual versus criação/reativação; históricos conflitantes, dupla correção e profissional distinto; nenhum cancelamento automático. |
| Auditoria | Testes conferem evento/ID/totais atuais; não trilha completa de correções. | Antes/depois/motivo/ator executor/instante, administrador diferente do atribuído, múltiplas correções, rollback e acesso por unidade. |
| Indicadores | Contagem, última presença, risco, histórico e gatilho cobertos em fluxos aceitos. | Modelo separado, correção de cancelado, grupos + individuais, cadeia de remarcações, empates e repetição sem duplicar evento clínico. |
| Versão | `@Version`, versão esperada e conflitos já exercitados em fluxos atuais. | Versão obrigatória ou política equivalente no contrato definitivo, duas telas obsoletas, auditoria exatamente uma vez. |
| Concorrência entre correções | Locks existentes não provam todos os fluxos futuros. | Duas correções opostas simultâneas; disputa com regularização/remarcação; confirmar isolamento, rollback e ausência de deadlock persistente. |
| Migração/compatibilidade | Não existe separação individual implementada. | Dry-run com todos os códigos legados, nulos/anomalias, remarcações, contagem e rollback; clientes antigos/novos. |
| Erros e Angular | 25 casos HTTP de injeção no handler; componentes usam mocks e há falhas Angular conhecidas. | Exceções aninhadas/infraestrutura, motivos de 409 distintos, formulário/motivo/visibilidade de auditoria e testes integrados com API. |

Na Etapa 1, os quatro testes de **caracterização do bloqueio atual por oferta** precisarão ser adequados explicitamente à RN-04, mantendo testes separados de atomicidade para conflitos reais. Não transformar a nova regra em correção cega: não enfraquecer P04 ou asserções de indicadores para fazer a suíte passar. Aqui todos os testes permanecem idênticos.

### 9.5 CI

O workflow `SIBRAPS CI` está presente: PR para main, push na main ou acionamento manual. Backend executa Java 21 / Wrapper `verify` e publica Surefire mesmo em falha. Build Angular é obrigatório; suíte Angular é diagnóstica opcional, selecionada manualmente, e suas falhas ficam visíveis (sem `continue-on-error`). Ações fixadas por SHA e permissão `contents: read` permanecem intactas.

A consulta de runs pelo `gh run list` nesta etapa retornou **Forbidden**. Logo, o estado das execuções remotas e de eventuais checks/branch protection é **não verificado**. Não se afirma CI verde nem que uma execução remota ocorreu. Push só na branch de planejamento não corresponde aos gatilhos automáticos de push/main; não se abriu PR nem se disparou workflow.

Frontend não foi reexecutado nesta etapa documental. O relatório anterior registra sete falhas e cinco erros não tratados Q01–Q04; são evidências anteriores, não resultado novo nem correção desta etapa. Seu tratamento exige escopo próprio; a Etapa 9 deve distingui-los de novas regressões, sem silenciá-los.

## 10. Riscos e classificação dos erros

| Prioridade de planejamento | Risco comprovado ou a prevenir | Relação / mitigação futura |
|---|---|---|
| Alta | Correção histórica depende de oferta | Nove falhas; afeta frequência, risco, última presença, histórico e visita. Separar validação do fato da aquisição de vaga, mantendo colisão e locks. |
| Alta | Profissional da unidade pode alcançar PATCH de atendimento alheio | RN-01; validar responsável no servidor em todas as rotas, além do escopo. |
| Alta | Migração do enum único perde significado/evidência | RN-12 depende de compatibilidade; não inferir reserva passada só a partir de presença/falta. |
| Alta | Correção cega permite duplicidade ou apaga atendimento de colega | Preservar P04; reconciliar explicitamente com versão, autorização e auditoria. |
| Alta | Auditoria incompleta e justificativa ausente | RN-03/RN-09; manter fato e trilha de mudanças separados, sem sobrescrever evidência histórica. |
| Média/alta | Erro interno rotulado como conflito recuperável | Handler global e UI com mensagem única de 409; Etapa 7 deve classificar causa e preservar diagnóstico no servidor. |
| Média/alta | Relógios e conceitos divergentes entre UI/API | RN-11; definir relógio/fuso oficiais e testar limites. |
| Média | Testes H2 não representam todo SGBD | Validar transações/migração no banco de implantação antes de liberar. |

### Alcance atual do handler, além de agendamentos

`ResourceExceptionHandler` é `@ControllerAdvice` global. Os novos grupos capturados são `PessimisticLockingFailureException` e `OptimisticLockingFailureException` do Spring, `jakarta.persistence.PessimisticLockException` e `LockTimeoutException`. Afetam **qualquer controller MVC** do aplicativo que propague essas exceções, não só agenda. `@Version` também está presente em paciente/grupo/sessão. Rotinas agendadas e filtro JWT não passam automaticamente por esse handler MVC.

Os testes HTTP com serviços simulados confirmaram 409 para falhas Spring de versão/lock em **pacientes, grupos, profissionais, disponibilidade, histórico e relatórios**; falha de conexão permaneceu 500 nesses seis módulos. São testes de mapeamento por injeção, não prova de corrida real de banco em cada módulo. Exceção JPA otimista já retornava 409 antes; a ampliação inclui novas famílias que anteriormente chegavam a 500, conforme revisão anterior. Timeouts de consulta e erros de programação dos casos atuais continuam 500.

| Natureza | Exemplo | Tratamento atual / orientação para Etapa 7 |
|---|---|---|
| Negócio | Vaga ocupada, reativação incompatível | `ConflictException`/duplicidade: 409; validação: 422; `DatabaseException` tem 400 atual. Explicar motivo de negócio, não afirmar outra edição sem evidência. |
| Transação | Versão obsoleta; disputa real de lock | 409 atual. Confirmar causa e semântica recuperável; retorno deve orientar recarregar/reavaliar, sem repetir automaticamente uma alteração clínica. |
| Infraestrutura | Conexão indisponível, falha sistêmica ou timeout de origem interna | Casos diretos testados retornam 500; não convertê-los indiscriminadamente em 409. Examinar exceções aninhadas, tradução do driver e sinais de indisponibilidade; definir eventual 503 apenas com contrato técnico adequado. |
| Programação | `IllegalStateException` no teste | 500; preservar diagnóstico interno e resposta externa sem detalhes sensíveis. |

O handler de concorrência atual ignora o argumento `exception` e não faz logging próprio. A classe genérica de falha de lock pode encobrir causas operacionais em determinados drivers/encapsulamentos; o teste direto de conexão não cobre isso. Planejar diagnóstico estruturado e identificador de correlação no servidor, sem expor dados clínicos ou stack trace ao cliente; não usar catch genérico para chamar qualquer falha de conflito. Nenhuma mudança no handler foi feita agora.

## 11. Dependências e nove etapas futuras

Ordem mantida conforme solicitação: **1 → 2 → 3 → 4 → 5 → 6 → 7 → 8 → 9**. Além dessa sequência, Etapas 4–6 dependem do contrato e da auditoria das 2/3; Etapa 8 depende dos contratos de erros da 7 e das operações das 3–6; Etapa 9 depende da validação de todas. A fase de compatibilidade da 2 antecede qualquer alteração de dados.

Etapas 1 e 2 podem ser desenvolvidas/verificadas isoladamente, mas a exposição dos novos fluxos clínicos em uso real depende de concluir responsabilidade, justificativa e auditoria da Etapa 3. Isso é um critério de liberação, não inversão da ordem nem autorização de implantação agora. A análise dos erros já informa o desenho dos contratos, mas a alteração do handler fica na 7.

### Etapa 1 — Correção da regressão da frequência

- **Objetivo:** desvincular correção factual histórica da oferta vigente. Distinguir validação de aquisição/reativação de vaga de verificação de colisão do fato histórico; manter recálculo atômico.
- **Dependências/arquivos:** esta base e RN-04/RN-07; AGS, AGR, PAC, HIS e `HistoricalAttendanceReviewTests`, suítes P03/P04/P05.
- **Preservar:** locks, escopo, identidade de paciente/original, grade para cadastro, capacidade para reserva, versão, histórico e gatilho. Não simplesmente retirar `validarVaga()` de todo ingresso ativo.
- **Testes:** tornar verdes os nove casos legítimos; adequar caracterização de bloqueio por oferta à regra aprovada; acrescentar colisões reais paciente/profissional, correção versus criação e rollback. Manter direção inversa e reativação sujeita à oferta.
- **Risco:** aceitar uma segunda reserva ativa sob pretexto de corrigir histórico. Sem resolução segura, conflito real permanece rejeitado para futura Etapa 6, com evidências intactas.
- **Aprovação:** baseline sem as nove regressões e sem novas falhas; P01–P05 íntegros; relatório explícito das mudanças e limites. Não liberar novos fluxos sem a Etapa 3.

### Etapa 2 — Separação entre reserva e frequência assistencial

- **Objetivo:** modelo individual com dois conceitos canônicos, compatível com legado. Começar por especificação de transições/projeções e fase de compatibilidade; só depois schema/backfill controlados.
- **Dependências/arquivos:** Etapa 1; `Agendamento`, enum/conversores, DTO/mapper, AGR/AGS, PAC/HIS, relatórios/painel, consultas de disponibilidade e futuras migrações. Interfaces Angular são consumidoras a compatibilizar, não alteração antecipada sem contrato.
- **Preservar:** IDs/FKs, códigos legados, cadeia de remarcação/flag, revisões, unidades, versão, semântica de ocupação P04 e totais clínicos. Grupo já tem sessões/presença separadas; não fundir modelos sem análise.
- **Testes:** todos os estados legados, ambiguidades e anomalias, contratos antigo/novo, projeção de leitura, rollback/dry-run, queries/totais antes/depois, ocupação e concorrência. Plano de dados na seção 13.
- **Risco:** inferência irreversível de reserva a partir de PRESENTE/FALTOU e dual write divergente. Definir uma fonte canônica com projeção legada e transações consistentes; nenhuma reinterpretação silenciosa de cancelados/substituídos.
- **Aprovação:** mapeamento documentado por caso, compatibilidade demonstrada antes da alteração de dados, migração reversível ensaiada e invariantes preservadas. Anomalias sem resolução ficam sinalizadas, não inventadas.

### Etapa 3 — Permissões, justificativas e auditoria

- **Objetivo:** responsável específico ou administrador da própria unidade; motivo obrigatório e trilha de correção RN-01/02/03/09/10.
- **Dependências/arquivos:** modelo/contratos da 2; AGC/AGS, ESC, autenticação de usuário, DTOs de correção a definir, HIS, entidades/repositórios de auditoria a definir, `AuditoriaConfig`. Reusar segurança P01/P02 sem enfraquecer controles.
- **Preservar:** escopo inclusive administrador, recepção operacional, registro inicial distinto de correção, histórico clínico e Envers existentes, rollback transacional.
- **Testes:** responsável versus colega, admin da unidade versus externo, recepção, falta/vazio de motivo, versões, autor real diferente do atribuído e múltiplas correções com trilha consultável; chamadas diretas à API.
- **Risco:** proteger só a tela ou deixar o PATCH legado contornar a nova regra; confundir profissional assistencial com executor administrativo da correção.
- **Aprovação:** barreiras no backend em todas as entradas e evento completo por alteração, sem writes em rejeições; consulta da auditoria respeita unidade/perfil. Justificativa não substitui autorização.

### Etapa 4 — Fluxo definitivo de correção da frequência

- **Objetivo:** correção retroativa e no mesmo dia após horário previsto, com relógio oficial/fuso definidos, validação temporal e recálculo factual.
- **Dependências/arquivos:** Etapas 1–3; AGS/AGC, contrato de correção, relógio/configuração temporal a definir, PAC/HIS, relatório/painel.
- **Preservar:** permissões/motivo/auditoria, proibição de registrar fato futuro, sem dependência da oferta para passado; idempotência de indicadores e histórico clínico.
- **Testes:** antes/no/depois do horário, mudança de dia/fuso, dias passados, registro tardio, duas versões, repetição de requisição e efeitos em grupos/individuais/gatilho.
- **Risco:** aceitar antes da hora por relógio do cliente ou duplicar contadores/eventos em retry. Distinguir instante do atendimento do instante da correção.
- **Aprovação:** política temporal explícita e determinística no servidor, correção auditável, totais e risco coerentes após commit e nenhum efeito após rollback.

### Etapa 5 — Regularização de cancelamentos incorretos

- **Objetivo:** separar recuperar reserva futura de registrar PRESENTE/FALTOU de atendimento ocorrido cancelado por engano; sem reserva retroativa fictícia.
- **Dependências/arquivos:** Etapas 2/3/4; AGC/AGS, modelo separado, DTOs/mapper/AGR, HIS/PAC e auditoria. Ajuste temporal de cancelamento/recuperação precisa ser explícito, pois a guarda atual rejeita status futuro genericamente.
- **Preservar:** reserva recuperada só com oferta/grade/ausência de colisão; correção do fato não depende da oferta atual; responsável/admin e motivo, P04/P05, cancelamento original como evidência.
- **Testes:** cancelamento equivocado antes e depois do atendimento, resultado presença/falta, oferta zerada/bloqueada, conflito real, outra unidade/profissional, recepção, versão e totais/auditoria; confirmar que não há novo agendamento artificial.
- **Risco:** usar CANCELADO → AGENDADO → PRESENTE como atalho e destruir a evidência do erro. Definir representação final da reserva regularizada no modelo da 2 antes de persistir; não presumir uma escolha aqui.
- **Aprovação:** duas operações com semânticas claras, efeitos mínimos no registro original, fato preservado e integralmente auditável, sem violar disponibilidade de reservas reais.

### Etapa 6 — Reconciliação de atendimentos conflitantes

- **Objetivo:** análise e resolução explícitas de inconsistências históricas, com evidências e justificativa, sem alterar automaticamente atendimento de outro profissional.
- **Dependências/arquivos:** Etapas 2–5; AGS/AGR/ESC, entidades/DTOs de reconciliação a definir, HIS/auditoria/PAC. Definir escopo de decisão do responsável e encaminhamento ao administrador quando envolver outro atendimento.
- **Preservar:** P04, isolamento, direitos do profissional, registros originais e versões de todos os envolvidos. Definir ordem estável de locks para múltiplos registros.
- **Testes:** dois registros mesmo instante, profissionais diferentes, resolução por responsável limitado/admin da unidade, acesso externo, disputa com criação/correção, rollback de toda a decisão, evidências preservadas e indicadores recalculados.
- **Risco:** cancelar silenciosamente o registro concorrente ou deadlock; manter pendência explícita quando a decisão não puder ser tomada com segurança.
- **Aprovação:** decisão autorizada, rastreável e atômica; nenhum atendimento alheio modificado sem autoridade explícita; nenhuma duplicidade ativa aceita para encerrar o conflito.

### Etapa 7 — Tratamento de erros do backend

- **Objetivo:** distinguir regra de negócio, concorrência recuperável, infraestrutura e defeito interno, considerando o alcance MVC global.
- **Dependências/arquivos:** contratos das 2–6 e caracterização atual; `ResourceExceptionHandler`, `StandardError`, exceções de serviço, observabilidade e `GlobalConcurrencyHandlerReviewTests`.
- **Preservar:** 404 de escopo, 403 de autorização, validação de campos e diagnósticos internos; não vazar dados sensíveis nem mascarar indisponibilidade em 409.
- **Testes:** seis módulos atuais, locks/versões reais e causas aninhadas, conexão/timeout/driver, erro de programação, diferenciação de motivo HTTP e correlação de diagnóstico. Não presumir que todo lock timeout é escolha clínica do usuário.
- **Risco:** mudança global de contrato/regressão em relatórios, disponibilidade, pacientes, grupos, usuários e histórico; nova tentativa automática indevida de correção clínica.
- **Aprovação:** matriz causa/resposta documentada, testes de não mascaramento de falhas internas e mensagem segura/acionável para cliente; registros internos permitem diagnóstico.

### Etapa 8 — Adaptação do frontend Angular

- **Objetivo:** representar reserva/frequência separadas, correção com justificativa e auditoria, regularização/reconciliação e mensagens de erro específicas.
- **Dependências/arquivos:** contratos estáveis das 2–7; UI/serviços/rotas/permissões da seção 12 e testes correspondentes.
- **Preservar:** grade recebida do backend, escolha de paciente/profissional, remarcação, escopo, ações operacionais da recepção, tratamento de versão. Ocultar botão não substitui autorização no servidor.
- **Testes:** formulário/motivo, estados de carregamento/erro, 409 de negócio versus versão, 403/404/422/5xx, telas obsoletas, horário oficial, listas/totais/histórico e fluxo ponta a ponta com API. Separar falhas anteriores Q01–Q04 de novas regressões.
- **Risco:** enum/legendas/contagens desatualizados, interface afirmando outro editor em todo 409, retries duplicando fatos ou exposição de auditoria fora do escopo.
- **Aprovação:** build passa; fluxos acessíveis e coerentes com API, sem permissões ampliadas pelo cliente; resultados da suíte reportados integralmente, sem ocultar falhas.

### Etapa 9 — Validação integrada e preparação para implantação

- **Objetivo:** validar conjunto, CI, compatibilidade de dados e procedimento operacional antes de qualquer decisão de implantação.
- **Dependências/arquivos:** todas as etapas; suítes backend/frontend, `.github/workflows/ci.yml`, migrações futuras e documentação operacional/rollback.
- **Preservar:** P01–P05, unidade/segurança, histórico/dados, critérios clínicos e versões. Não integrar automaticamente à main nem confundir baseline conhecido com aprovação para produção.
- **Testes:** `mvnw verify` integral; build/suíte Angular e testes integrados; disputa real no SGBD alvo; migração ensaiada com restauração; contratos legados, autorização e auditoria em toda a jornada.
- **Risco:** baseline verde só no H2, CI não executada, backup não restaurável ou falhas antigas silenciadas. Documentar pendências e eventual escopo separado para Q01–Q04; não desabilitar checks para aparentar aprovação.
- **Aprovação:** evidências locais/remotas verificáveis, regressões resolvidas, migração/rollback ensaiados e requisitos operacionais documentados. Merge/implantação dependem de decisão posterior, fora desta Etapa 0.

## 12. Inventário dos arquivos relevantes e dependências

Prefixos: **B** = `backend/buscaativa/src/main/java/com/pet/buscaativa/`; **R** = `backend/buscaativa/src/main/resources/`; **T** = `backend/buscaativa/src/test/java/com/pet/buscaativa/`; **F** = `frontend/sentinela-pet/src/app/`. Caminhos abaixo são existentes, exceto os explicitamente marcados como futuros. São candidatos a análise, não autorização de alterar todos eles em cada etapa.

| Área | Arquivos | Dependências / etapas |
|---|---|---|
| Serviço de agenda | B`services/AgendamentoService.java`, B`services/impl/AgendamentoServiceImpl.java` | Reserva, status, remarcação, frequência, locks, recálculo; 1–6 |
| API e DTO agenda | B`controllers/AgendamentoController.java`, B`entities/dto/AgendamentoDTO.java`, B`entities/dto/HorariosDisponiveisDTO.java`, B`mapping/AgendamentoMapper.java` | Compatibilidade HTTP, versão/motivo/novos campos; 2–5/8 |
| Modelo e conversores | B`entities/Agendamento.java`, B`entities/enums/SituacaoAtendimento.java`, B`entities/SituacaoAtendimentoConverter.java`, B`entities/enums/TurnoEnum.java` | Enum inteiro persistido e cadeia de remarcação; 2/5/6 |
| Concorrência e queries | B`repositories/AgendamentoRepository.java`, B`repositories/PacienteRepository.java`, B`repositories/UsuarioRepository.java` | Conjunto ativo, locks, projeções de identidade, versão e filtros; 1/2/6 |
| Grade | B`config/AgendaHorarioProperties.java`, R`application.properties` | Manhã 07:00–11:30, tarde 13:00–18:30, intervalo padrão 30 min, limites alinhados inclusivos; preservar P03 |
| Oferta semanal/exceções | B`services/impl/DisponibilidadeServiceImpl.java`, B`services/impl/DisponibilidadeExcecaoServiceImpl.java`, B`entities/Disponibilidade.java`, B`entities/DisponibilidadeExcecao.java`, respectivos repositories/DTOs/controllers | Queries usam estados ativos; oferta não é linha do tempo histórica; 1/2/5 |
| Bloqueios | B`services/impl/BloqueioAgendaServiceImpl.java`, B`entities/BloqueioAgenda.java`, B`repositories/BloqueioAgendaRepository.java`, B`controllers/BloqueioAgendaController.java`, B`entities/dto/BloqueioAgendaDTO.java` | Reserva real mantém bloqueio, correção factual não depende de bloqueio posterior; 1/2/5 |
| Paciente/indicadores | B`services/impl/PacienteServiceImpl.java`, B`services/PoliticaClassificacaoPaciente.java`, B`entities/Paciente.java`, B`controllers/PacienteController.java`, DTOs/mapeador de paciente | Frequência canônica → faltas, última presença, risco, visita; 1/2/4/5/6 |
| Grupos | B`services/impl/GrupoTerapeuticoServiceImpl.java`, B`entities/SessaoGrupo.java`, B`entities/SessaoGrupoParticipante.java`, B`repositories/SessaoGrupoParticipanteRepository.java` | Alimentam recálculo combinado; modelo distinto de sessão/presença; preservar integração |
| Histórico/auditoria | B`services/impl/HistoricoPacienteServiceImpl.java`, B`services/HistoricoPacienteService.java`, B`entities/HistoricoPaciente.java`, B`repositories/HistoricoPacienteRepository.java`, B`entities/dto/HistoricoPacienteDTO.java`, B`entities/dto/HistoricoPacienteEventoDTO.java`, B`entities/dto/RegistroHistoricoPacienteDTO.java` | Fato clínico, evento corrigido e consulta; 2/3/4/5/6/8 |
| Auditoria técnica | B`entities/AbstractEntities.java`, B`config/AuditoriaConfig.java`, anotações Envers | Quem criou/alterou e revisões não substituem motivo por correção. Futuro evento/tabela de correção a definir; 2/3 |
| Escopo e segurança | B`services/impl/EscopoUnidadeService.java`, B`services/impl/UsuarioContextService.java`, B`config/SecurityConfig.java`, B`config/SecurityFilter.java` | Unidade, responsável e perfis; preservar JWT/H2 sem ampliar escopo; 3–9 |
| Erros | B`services/exceptions/ResourceExceptionHandler.java`, B`services/exceptions/StandardError.java`, exceções de validação/conflito | Alcance global e contrato Angular; 7/8 |
| Painel/relatórios | B`services/impl/PainelServiceImpl.java`, B`services/impl/PainelHistoricoServiceImpl.java`, B`services/impl/RelatorioServiceImpl.java`, B`services/ExcelRelatorioService.java`, R`templates/reports/` | Leitores de estado/indicadores e revisões; 2/4/5/6/9 |
| Modelos/API Angular | F`services/agendamento-service.ts`, F`services/paciente/paciente-service.ts`, F`services/relatorio-service.ts` | Interfaces vivem nos serviços; PATCH atual sem motivo; 8 |
| Agenda/cadastro Angular | F`pages/agenda/agenda.ts`, `.html`, `.css`, `.spec.ts`; F`pages/novo-agendamento/novo-agendamento.ts`, `.html`, `.css`, `.spec.ts` | Estado único, ações por data/perfil, versão, erros, grade e remarcação; 8 |
| Oferta Angular | F`pages/configuracao-agenda/`, F`services/disponibilidade-service.ts`, F`services/disponibilidade-excecao-service.ts`, F`services/bloqueio-agenda-service.ts` | Manter configuração operacional coerente com backend; 8/9 |
| Acompanhamento Angular | F`pages/agendamentos-paciente/`, F`pages/historico-paciente/`, F`pages/detalhe-paciente/`, F`pages/pacientes/`, F`pages/cadastro-paciente/` | Histórico, indicadores e rótulos/status; 8/9 |
| Permissões/HTTP Angular | F`auth/permissoes.ts`, F`auth/role-guard.ts`, F`services/http-interceptor.service.ts` | Navegação/perfil e erros; validação final sempre no backend; 8 |
| Testes | T`HistoricalAttendanceReviewTests.java`, T`GlobalConcurrencyHandlerReviewTests.java`, T`AgendaGridTests.java`, T`AgendaConfiguredGridTests.java`, T`AgendaConcurrencyTests.java`, T`AgendaRescheduleTests.java`, T`AgendaCombinedTests.java` e demais suites da seção 9 | Baseline e invariantes; ampliar só em etapas futuras |
| Suporte de testes | T`SecurityTestSupport.java`, T`TestEnvironmentIsolation.java`, T`AgendaIntegritySupport.java`, R e recursos de teste | Isolamento sintético; não introduzir valores reais |
| Banco/migração | `backend/buscaativa/db/isolamento-unidade.sql`, R`application-dev.properties`, `backend/buscaativa/pom.xml` | Há SQL de isolamento e Hibernate; não foi localizado versionamento Flyway/Liquibase para separar os estados. Ferramenta/scripts e diretório da migração futura a decidir; não criar nesta etapa. `ddl-auto=create` de dev não é estratégia de preservação. |
| CI/documentação | `.github/workflows/ci.yml`, `docs/seguranca-p01-p02.md`, `docs/correcao-agendamentos-p03-p04-p05.md`, `docs/revisao-complementar-frequencia-concorrencia.md` | Preservados; referências de segurança, limitações e comparação anterior |

## 13. Estratégia futura de preservação dos dados

Nenhum dado ou schema foi alterado na Etapa 0. Antes de separar estados:

1. **Inventariar** volumes por código legado, FKs paciente/profissional/original, unidades, versões, `remarcacaoAposFalta`, duplicidades/nulos, revisões Envers e eventos. Fazer consultas e resultados agregados em ambiente autorizado, sem dados pessoais em logs/documentação.
2. **Definir compatibilidade antes de alterar dados:** tabela de mapeamento por transição e consumidor; fonte canônica dos dois estados e projeção para o contrato legado. PRESENTE/FALTOU não revelam sozinhos toda a história da reserva; CANCELADO não autoriza presumir presença nem ausência. Preservar valor bruto e sinalizar casos ambíguos para decisão explícita. Não usar default que invente resultado clínico.
3. **Planejar migração aditiva:** manter colunas/códigos legados durante compatibilidade; adicionar campos/eventos necessários e mapear reversibilidade. Atualizar leituras/queries, conversores e relatórios de forma consistente; controlar escritas numa transação para evitar dois estados divergentes. Decidir estratégia de ferramenta de migração e suporte ao SGBD alvo antes da execução.
4. **Ensaiar backup/restauração e dry-run**, fora de produção, com contagens por estado, IDs/FKs/revisões, somas de indicadores, ocupação e cadeia de remarcação antes/depois. Garantir que audit trail e dados externos não sejam perdidos no rollback. Não tratar `ddl-auto=create` como migração.
5. **Preservar evidências:** fato clínico distinto de evento de correção, autor executor distinto do profissional atribuído, instante da alteração distinto do atendimento. Cancelamento equivocado permanece evidenciado mesmo quando regularizado. Reconciliação não apaga, cancela nem transfere automaticamente o registro de outro profissional.
6. **Preservar P04 no novo modelo:** especificar quais combinações ocupam vaga e as transições que adquirem/liberam reserva. Não criar índice único incondicional que impeça registros históricos cancelados/substituídos legítimos; analisar restrições condicionais e locks no banco alvo, além de testes de disputa.
7. **Liberar gradualmente só após critérios técnicos**, compatibilidade de clientes, verificação de integridade e plano de rollback. A decisão de remoção do contrato/coluna legada exige evidência de que não há consumidores; não pertence à Etapa 0. Não executar migração ou implantação automaticamente.

## 14. Critérios de aprovação e preservação nas próximas etapas

- Cada entrega deve apresentar diff limitado, regra alterada, contratos afetados, execução real de testes, efeito em indicadores/histórico e riscos remanescentes. Não corrigir defeitos P06–P16 ou Q01–Q04 incidentalmente sem escopo explícito.
- Preservar P01/P02 (chave forte externa, autenticação e console H2 bloqueado) e P03/P04/P05 (grade, concorrência paciente/horário e identidade da remarcação). Não trocar segurança por compatibilidade de clientes antigos.
- Correção factual precisa de responsável/admin da unidade, motivo, versão/controle transacional, recálculo e trilha de auditoria. Oferta atual continua obrigatória para reserva real; histórico exige política própria de colisão, não ingresso cego.
- Toda rejeição deve preservar atendimento, paciente, eventos e trilha transacional sem gravação parcial. Repetição/idempotência não pode duplicar o fato clínico; correções relevantes efetivas mantêm seus eventos de auditoria.
- Migração só depois da compatibilidade definida e ensaiada, com contagens e restauração verificadas. UI só deve afirmar causas reconhecidas pelo contrato de erros.
- `BUILD FAILURE` do baseline documentado não é aprovação para merge. Etapas corretivas devem comprovar resolução, sem remover/desabilitar testes ou ignorar erros. CI remota deve ser verificada com acesso disponível antes de afirmar aprovação.

## 15. Pendências e encerramento da Etapa 0

| Pendência | Encaminhamento |
|---|---|
| Nove falhas de correção histórica | Etapa 1, preservando integridade e adequando explicitamente caracterizações da regra anterior. |
| Matriz canônica de estados e projeção legada, inclusive cancelados regularizados | Etapa 2; compatibilidade antes de qualquer alteração de dados. |
| Responsável por atendimento, motivo e consulta da auditoria | Etapa 3; não confundir campos técnicos existentes com RN-09 integral. |
| Fonte de horário/fuso oficial e tratamento do registro tardio | Decisão técnica na Etapa 4 dentro da proposta RN-11. |
| Reserva futura versus cancelamento de fato ocorrido | Etapa 5; não usar criação retroativa como atalho. |
| Colisões históricas envolvendo responsáveis distintos | Etapa 6; autoridade/evidências explícitas, sem edição automática alheia. |
| Erros aninhados e diagnóstico global | Etapa 7; negócio/concorrência/infraestrutura diferenciados. |
| Contratos/telas e falhas Angular anteriores | Etapa 8 e escopo específico para Q01–Q04; Etapa 9 registra a validação integrada completa. |
| Execuções remotas da CI | Não verificadas: consulta recusada com Forbidden; validar quando houver acesso, sem contornar permissões. |
| Banco de implantação e mecanismo de migração | Decisão técnica antes de backfill; H2 local não prova compatibilidade com outro SGBD. |
| Eventual branch com colchete apenas em outro Codespace | Não observável a partir deste checkout/remoto; não excluir nem sobrescrever trabalho daquele ambiente. |

A Etapa 0 tem como critério de conclusão preservar a base e entregar este diagnóstico com o baseline real, mesmo reprovado pelas nove falhas conhecidas. O commit/publicação documental e as verificações finais de preservação são registrados no relatório de entrega. Não houve decisão funcional nova nem implementação de regra futura. A Etapa 1 pode partir desta branch preservada, com revisão própria e sem integrar a main nesta entrega.
