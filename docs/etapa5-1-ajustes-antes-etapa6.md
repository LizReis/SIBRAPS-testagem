# Etapa 5.1 — Elegibilidade, integridade da oferta e relógio institucional

## 1. Escopo, rastreabilidade e parecer

- Repositório: `LizReis/SIBRAPS-testagem`.
- Branch: `correcao-fluxos-assistenciais-sibraps`.
- Base: `37d8b26f8a49661a7b990da7930eb405deb08190` (Etapa 5).
- Validação local em 17/11/2026 (UTC), no GitHub Codespaces. Revisão estática do diff exportado dessa cópia de trabalho.
- Parecer: **aprovada para continuidade do desenvolvimento, condicionada à publicação do conjunto revisado na branch autorizada**. Não é certificação de produção, de CI remota ou de outro SGBD.
- O commit e o push da Etapa 5.1 ainda não haviam sido feitos quando este documento foi redigido. Não atribuir SHA de entrega fictício. No remoto consultado, main = `967d5162dd543ec9005608739128423903e75e30`, P03/P04/P05 = `05ee61a5123dfe4e914d7c2f5f486f7ecf310164` e P01/P02 = `e772f2b4035ca75574d05fe083009992ff48aa29`.

A entrega é corretiva, sem implementação da Etapa 6 e sem alteração de migrações, DTOs HTTP, frontend ou infraestrutura de auditoria. Os resultados de execução foram fornecidos pela pessoa desenvolvedora; o revisor não executou independentemente o Maven no Codespaces.

## 2. Diagnóstico e caracterização

A revisão da Etapa 5 levantou três problemas: recuperação de reserva sem verificar elegibilidade atual, validação de oferta apenas no destino de uma edição e divergência entre relógio institucional e data padrão da JVM.

No baseline, 872 testes passaram. As duas novas classes de caracterização totalizaram 25 casos: 7 aprovados e 18 reprovados antes das mudanças (9 falhas de elegibilidade e 9 de oferta). Após as mudanças, todos os 25 passaram. Os logs resumidos não identificam individualmente cada falha inicial; por isso, não atribuímos 18 defeitos independentes nem inventamos quais métodos específicos falharam.

## 3. Elegibilidade e responsabilidade

`AgendamentoServiceImpl` reutiliza as verificações de elegibilidade de paciente e profissional na criação (inclusive criação de sucessor em remarcação) e na recuperação específica de reserva. A recuperação verifica estado, versão, justificativa e tempo; após os locks, atualiza paciente e profissional no contexto de persistência, revalida autorização e elegibilidade, e mantém as verificações anteriores de cadeia, histórico e vaga.

| Operação | Paciente em estado diferente de ATIVO |
|---|---|
| Criar consulta ou sucessor de remarcação | Negada |
| Recuperar reserva futura | Negada (422 nos testes) |
| Regularizar frequência histórica comprovada | Permitida conforme demais requisitos |
| Corrigir frequência histórica | Permitida conforme demais requisitos |

Todos os valores não ativos do enum são exercitados por testes parametrizados. Há teste de retorno ao estado ATIVO mediante fixture, seguido de recuperação; isso não equivale a validar novamente todo o endpoint de reativação. Nenhuma regularização altera o status atual do paciente para ATIVO. O histórico do atendimento cancelado permanece vinculado ao registro original.

A elegibilidade operacional do profissional utiliza os perfis PROFISSIONAL e ADMINISTRADOR, sem criar campo de ativação inexistente. Uma mudança para RECEPCAO impede recuperação. Os testes HTTP mostram que o administrador da unidade ainda registra e corrige fato histórico do antigo responsável; o ex-profissional, agora recepcionista, não recebe permissão clínica. Transferência entre unidades continua sujeita ao escopo existente; não se presume titularidade histórica para resolver casos ambíguos.

`PacienteServiceImpl` passa a bloquear o paciente na edição cadastral e na inativação; a inativação torna-se transacional. Encerramento e reativação já usavam lock do paciente. `UsuarioServiceImpl.save` é transacional e bloqueia o usuário na edição. Essas operações compartilham proteção com a recuperação. Não foi criada uma política automática de cancelar reservas já existentes quando o paciente encerra o acompanhamento ou quando o profissional deixa de receber novos agendamentos.

## 4. Integridade da oferta

Novo `IntegridadeOfertaService` concentra leitura escalar da identidade original, aquisição de locks, recarga e verificação de assinatura da configuração, validação de campos e capacidade efetiva.

- Disponibilidade semanal: alterações de proprietário, dia ou turno validam a retirada da origem e a capacidade no destino. Exclusão equivale à retirada da oferta semanal. A consulta identifica datas com ocupação a partir da data institucional, sem horizonte arbitrário. Exceções específicas prevalecem, inclusive de capacidade zero.
- Exceção: alteração de proprietário, data ou turno verifica a capacidade semanal que permanecerá na origem e a capacidade solicitada no destino; exclusão verifica o fallback semanal. A validação preserva a semântica de ocupação do modelo atual.
- Bloqueio: criação/edição valida período, motivo e ocupação do destino; transferência e exclusão passam pela proteção de origem. Nenhum atendimento é cancelado, remarcado ou transferido automaticamente.
- Os testes verificam alterações e exclusões legítimas, retirada incompatível, exceção suficiente, reserva em data distante, capacidade zero e transferência de bloqueio. Configurações duplicadas continuam sujeitas às verificações de unicidade.

O critério é impedir que a alteração produza falta de cobertura; não reconciliar automaticamente uma inconsistência legada preexistente. A retirada semanal pode ser permitida quando as exceções efetivamente sustentam as reservas — não é uma violação da proteção P03, mas uma aplicação da precedência de oferta.

## 5. Autorização, locks e atomicidade

`UsuarioContextService.bloquearConfiguracao` exige que o e-mail da operação corresponda à identidade autenticada; valida origem e destino e bloqueia os UUIDs envolvidos em ordem determinística (`TreeSet`). Cada usuário é recarregado sob `PESSIMISTIC_WRITE`. O serviço de oferta carrega a configuração depois dos locks e confirma que proprietário e campos relevantes correspondem à leitura escalar; divergência causa conflito. Não adquire lock de paciente depois do profissional. A ordem individual paciente → profissional → atendimento é mantida.

Recusas de negócio são comprovadas por snapshots nos novos testes. Há três testes de rollback com gravações reais e flush (semanal, exceção, bloqueio), seguidos de exceção sintética na transação envolvente; o snapshot restaurado é comparado. A regressão preserva os testes anteriores de auditoria, histórico e regularização. Nenhuma estrutura de auditoria clínica ou de regularização foi modificada.

A assinatura detecta mudanças durante a operação; não é um contrato de versão fornecido pelo cliente para configurações de agenda. A proteção não certifica toda possível intercalação concorrente, nem substitui os testes no banco de implantação. A revalidação global de autorização de sessões já autenticadas durante mudanças de perfil não foi redesenhada.

## 6. Tempo institucional

A oferta semanal passa a usar `LocalDate.now(clock)` em `IntegridadeOfertaService`, com o relógio já existente (fuso padrão America/Bahia). O filtro de datas conserva o dia institucional atual, inclusive reservas de horário anterior no mesmo dia. Foram exercitadas datas controladas, situação em que UTC já está no dia seguinte e passagem de domingo para segunda à meia-noite.

A temporalidade dos dois endpoints de regularização permanece inalterada: recuperação estritamente futura; fato estritamente após horário, sem restabelecer vaga retroativa. Não houve substituição global dos usos de `now()` nem conversão de dados históricos. Datas administrativas de encerramento e demais usos de relógio fora da oferta devem ser revisados em escopo próprio.

## 7. Resultados de testes

| Execução | Casos | Aprovados | Falhas | Erros | Ignorados |
|---|---:|---:|---:|---:|---:|
| Baseline backend | 872 | 872 | 0 | 0 | 0 |
| Caracterização antes da correção | 25 | 7 | 18 | 0 | 0 |
| RecoveryEligibility51Tests após correção | 15 | 15 | 0 | 0 | 0 |
| OfferIntegrity51Tests após correção | 10 | 10 | 0 | 0 | 0 |
| Regressões intermediárias test e verify (cada) | 897 | 897 | 0 | 0 | 0 |
| Etapa51ComplementaryTests | 28 | 28 | 0 | 0 | 0 |
| verify final | 925 | 925 | 0 | 0 | 0 |
| Angular (rodada intermediária) | 104 | 97 | 7 | — | 0 |

São recortes e execuções repetidas; não somar as linhas. A entrega acrescenta 53 casos (15 + 10 + 28) aos 872 anteriores. O script intermediário registrou zero casos anteriores ausentes. O diff final não altera arquivos de teste antigos. O `verify` final concluiu em 3 min 10 s, com `BUILD SUCCESS`, JAR repacotado, em 17/11/2026 às 22:59:05 UTC. A execução complementar isolada levou 36,945 s.

### Disputas novas

A classe inicial contém disputa entre recuperação e transferência de exceção (uma confirmação e uma recusa exigidas pelo teste). O log da classe complementar registra sete disputas, 14 operações e duas conexões independentes por disputa:

| Disputa | Confirmadas | Recusadas |
|---|---:|---:|
| Transferências de proprietário em sentidos opostos | 2 | 0 |
| Recuperação × transferência semanal | 1 | 1 |
| Recuperação × transferência de bloqueio | 1 | 1 |
| Transferência × exclusão | 1 | 1 |
| Recuperação × inativação | 1 | 1 |
| Recuperação × encerramento | 1 | 1 |
| Recuperação × alteração de perfil | 1 | 1 |
| Total da execução complementar isolada | 8 | 6 |

A corrida permite duas confirmações quando compatíveis. As três disputas de elegibilidade admitem recuperação anterior a uma mudança legítima de situação; não se introduziu cancelamento automático posterior. Os logs resumidos da regressão final não permitem consolidar a contagem de todas as disputas antigas e novas naquela execução. Não declarar total geral de conflitos de versão sem evidência.

## 8. Ambiente e limites

- Java 25.0.4.1 em execução, compilação com release 21; padronizar JDK 21 na validação final. Spring Boot 4.0.6, Hibernate 7.2.12.Final, H2 2.4.240.
- Lombok/Mockito emitiram avisos de APIs/agente. Houve três avisos Hibernate de encerramento de sessão com ações transacionais pendentes na execução complementar; não impediram aprovação nem comparação de rollback. Verificar se reaparecem fora dos cenários sintéticos.
- `npm ci` e `npm run build` concluíram com sucesso na rodada intermediária (bundle inicial 847,77 kB, acima de 500 kB). A execução Angular manteve 7 falhas e 5 erros não tratados preexistentes. Foram informados 57 alertas de vulnerabilidade npm (2 baixos, 20 moderados, 32 altos, 3 críticos); investigar alcance, dependência e atualização em escopo próprio, sem `npm audit fix` indiscriminado. Como a última rodada acrescentou apenas testes Java, o frontend não foi repetido.
- Não houve mudanças V1–V7 nem nova migração. A regressão testa as rotinas antigas, mas esta entrega não inclui novo ensaio independente de restauração com artefato final nem validação no SGBD de produção.
- A cobertura é ampla, mas não corresponde individualmente a toda a matriz originalmente proposta; por exemplo, não há novo teste dedicado de cada combinação de transferência concorrente, de troca de fuso configurado ou de ausência de status impossível pelo esquema. Não afirmar exaustividade ou inexistência de defeitos.
- Não há validação independente em CI remota desta entrega local. A revisão não inspecionou arquivos fora do diff/status disponibilizado.

## 9. Inventário

Prefixo produção: `backend/buscaativa/src/main/java/com/pet/buscaativa/`.

| Arquivo | Alteração |
|---|---|
| repositories/AgendamentoRepository.java | Consulta de datas ocupadas para oferta |
| services/impl/AgendamentoServiceImpl.java | Elegibilidade reutilizada e recarga sob lock |
| services/impl/BloqueioAgendaServiceImpl.java | Validação compartilhada, origem/destino e flush |
| services/impl/DisponibilidadeExcecaoServiceImpl.java | Proteção da capacidade efetiva de origem/destino |
| services/impl/DisponibilidadeServiceImpl.java | Proteção semanal, precedência e relógio |
| services/impl/PacienteServiceImpl.java | Locks de edição/inativação e transação |
| services/impl/UsuarioContextService.java | Identidade do executor e locks ordenados |
| services/impl/UsuarioServiceImpl.java | Edição transacional com lock |
| services/impl/IntegridadeOfertaService.java | Novo serviço centralizado |

Prefixo testes: `backend/buscaativa/src/test/java/com/pet/buscaativa/`.

- `RecoveryEligibility51Tests.java` (15 casos).
- `OfferIntegrity51Tests.java` (10 casos).
- `Etapa51ComplementaryTests.java` (28 casos).

São 12 arquivos de código/teste. O relatório e o adendo ao plano serão documentação adicional. Não incluir o dump `revisao-etapa5-1.txt` no Git.

## 10. Encaminhamento preservado

- **Etapa 6:** reconciliação autorizada de conflitos e ambiguidades, com evidências, justificativa e auditoria. Tratar registros contraditórios/duplicados, frequência legada indeterminada, horários e cadeias de remarcação inconsistentes, origem de regularização e responsabilidade histórica sem comprovação. Investigar prevenção bidirecional de colisões individual/grupo nos fluxos de criação, remarcação, recuperação, sessões, inscrições (inclusive retroativas), troca de coordenador, confirmação, correção e respectivas corridas. Não mudar automaticamente atendimento de terceiros. Distinguir ausência comprovada, ocupação operacional e presença factual. Avaliar as transições legadas de REMARCADO/REMARCADO_ORIGEM fora do fluxo específico de remarcação, sem presumir que os testes atuais esgotam essa proteção.
- **Decisão de negócio a preservar/avaliar separadamente:** o encerramento do acompanhamento ou a mudança de perfil posterior a uma reserva válida não dispara cancelamento automático nesta etapa. Não inventar nova regra; discutir o tratamento operacional das reservas já existentes antes de implementá-lo.
- **Etapa 7:** classificação global dos erros HTTP e falhas transacionais/infraestrutura.
- **Etapa 8:** Angular com reserva/frequência separadas, fluxos clínicos, justificativas, auditorias, reconciliação, estados provisórios, RF22 e RNF17.
- **Etapa 9:** testes Angular, dependências e segurança, CI, JDK, relatórios/indicadores, SGBD definitivo, migração, backup/restauração, concorrência e privilégios.

Durante o desenvolvimento, executar testes direcionados às mudanças e às regressões diretamente afetadas; executar uma única suíte completa na finalização da etapa, desde que não surjam novas alterações relevantes depois dela. A Etapa 5.1 executou essa última regressão com 925 casos.

Publicar exclusivamente na branch autorizada, em commit normal, sem force push, PR ou merge na main. A Etapa 6 não é iniciada por este relatório.
