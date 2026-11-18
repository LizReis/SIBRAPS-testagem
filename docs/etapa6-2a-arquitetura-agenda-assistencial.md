# SIBRAPS — 6.2A: infraestrutura de integridade compartilhada (candidata)

## Base e escopo

Repositório LizReis/SIBRAPS-testagem; branch `correcao-fluxos-assistenciais-sibraps`; base `54a674ca6862f76333b769dc5fed6940e024e40a`. Referências remotas verificadas: main `967d516`, segurança `e772f2b`, estabilidade `05ee61a`. O relatório 6.1 e os seis arquivos de caracterização permanecem inalterados. O baseline de 925 casos é o reportado na 5.1; 63 casos de diagnóstico, 34 divergências de expectativa e seis observações na 6.1.

Entrega preparatória: componentes novos, sem redirecionar os serviços existentes, sem novos endpoints, sem alterar entidades, migrações V1–V7, frontend, POM ou contagem unificada. Nenhum dos 33 casos de prevenção é declarado corrigido nesta entrega. A regra clínica, os limites de risco, a visita, a autorização, a capacidade e a elegibilidade vigentes permanecem intactos. O tratamento de RF-07 histórico fica em 6.5/6.6. A base e os testes foram preparados a partir do código remoto; execução Maven e revisão da árvore local ainda pendentes. Não há certificação de banco de implantação.

## Diagnóstico e ordem escolhida

| Caminho vigente | Ordem observada | Integração a fazer |
|---|---|---|
| `AgendamentoServiceImpl.save` | paciente → profissional destino → original (se houver) | Descobrir identidade da origem; validar paciente e ambos profissionais antes do original; revalidar após lock; verificar capacidade e conflito antes de salvar. |
| `bloquearAgendamento`, recuperação, fato cancelado e correção | paciente → profissional → atendimento | Compartilhar primeira fase; manter revalidação de autorização, versão, tempo, histórico e cadeia. |
| `UsuarioContextService.bloquearConfiguracao`/`IntegridadeOfertaService` | UUIDs de profissionais em ordem natural; configuração depois | Usar mesma ordenação; nunca requisitar paciente posteriormente nesse fluxo. |
| `PacienteServiceImpl` encerramento/inativação; `UsuarioServiceImpl` mudança de perfil | lock do sujeito alterado | Compartilhar lock do sujeito, não inventar política de cancelamento automático. |
| `criarGrupo`, `criarProximaSessao` | leituras de coordenador/grupo/lista; não bloqueiam todos os sujeitos | Descobrir participantes, coordenador e, na próxima sessão, identidade do grupo. Primeira fase antes de grupo/registro. |
| `atualizarGrupo` | grupo primeiro, depois lê participantes e coordenação; possível regeneração | Descoberta escalar de origem/destino, sessões afetadas e listas; primeira fase antes do grupo; revalidar snapshot após lock. Não anexar lock de paciente/profissional depois do grupo. |
| `bloquearSessao` | pacientes em UUID natural → grupo → sessão | Adicionar profissional relevante antes de grupo; revalidar sessão, participantes e proprietário após locks; não presumir dono histórico. |
| `confirmarOcorrencia` | bloqueia sessão pelo método acima; volta a requisitar sujeitos já bloqueados | Consumir pacientes já bloqueados; revalidar frequência/versão/responsabilidade; não adquirir novos sujeitos após grupo. |
| inscrições futuras/retroativas e remoção em grupo | paciente → grupo | Descobrir coordenação e sessões pertinentes; travar sujeitos necessários antes de grupo; verificar conjuntos e preservar atomicidade. |
| `corrigirFrequencias` | primeira fase antiga; incremento de versão na sessão | Manter autorização e auditoria; validar conflitos factuais antes da escrita. |

Protocolo alvo, a efetivar em 6.2B–F: descobrir IDs e assinatura escalar sem escrita; bloquear **todos os pacientes** por UUID natural em Java, depois **todos os profissionais** na mesma ordem; obter grupos por ID, depois sessões/atendimentos necessários por ID (se uma operação futura envolver os dois tipos, escolher ordem fixa sessao → atendimento; evitar essa necessidade enquanto não existir reconciliação). Revalidar identidade, versão, escopo e os conjuntos de participantes/sessões/coordenadores relevantes após locks. Se a descoberta não for mais válida, recusar com conflito sem anexar locks fora da ordem. Repetir a operação por nova requisição, não por retry que esconda erro técnico. Nenhuma gravação antes da fase de locks e validações. O `EntityManager.refresh` da primeira fase evita usar entidades previamente carregadas e obsoletas; por isso, chamar antes de escrever. Autorização clínica específica e elegibilidade permanecem responsabilidade do serviço chamador. A primeira fase **não** tenta resolver autorização de um usuário de outra unidade ou certeza histórica via mudança de perfil.

O helper não bloqueia grupo/sessão/atendimento nesta entrega; essa segunda fase deve ser integrada em conjunto com a descoberta/revalidação específica. A garantia de ordem global depende da integração de todos os caminhos que disputam recursos, inclusive remoção/cancelamento, regeneração, inclusão em lote e transações cruzadas. Não há lock global em memória, nova tabela de mutex ou mudança de DDL. A ordem é uma decisão arquitetural candidata, sujeita às provas de concorrência da 6.2F. Locks de oferta já existentes não devem ser precedidos por lock de grupo. Não presumir que leitura com join fetch e lock tem o mesmo comportamento em outro SGBD; conferir no banco definitivo.

## Componentes candidatos

- `AgendaAssistencialLocks`: `@Transactional(MANDATORY)`; exige transação do chamador, normaliza UUIDs, deduplica, adquire `PESSIMISTIC_WRITE`, refresca e valida escopo. Retorna mapas somente de leitura de referências gerenciadas para consumo na mesma transação. Não substitui autorização clínica nem validação de perfil. Não acumular múltiplas chamadas em ordem arbitrária em uma mesma transação. Não expor o helper em controller.
- `ConflitosAgendaAssistencialRepository`: consultas JPQL de existência pontual (sujeito, data, hora, exclusão de um registro exato); sem `findAll`, nomes ou IDs retornados de registros conflitantes. Consulta ocupação a partir de `ocupaVaga`, preservando a projeção canônica; separa pendência, frequência e sessão. Reusa as consultas existentes de presença individual da etapa 5. A consulta não filtra conflitos pelo escopo de terceiros: integridade não depende da visibilidade do compromisso concorrente. A autorização sobre o sujeito/ação é feita antes da consulta. Não representa decisão de acesso a dados de terceiros.
- `IntegridadeAgendaAssistencialService`: `@Transactional(MANDATORY)` e exige entidade gerenciada com lock pessimista de escrita; classifica sinais distintos por paciente e profissional. Avaliação não grava, não reconcilia e não lança pendência clínica automaticamente. Exclusão somente interna, relativa ao próprio atendimento/sessão cuja identidade já foi autorizada. Nunca excluir um grupo inteiro por conveniência. Na regeneração, design posterior deve excluir apenas o conjunto de sessões comprovadamente substituíveis ou validar depois da remoção controlada dentro da mesma transação, sem perder a atomicidade; não excluir fatos realizados. Não passar exclusões arbitrárias do cliente.

## Semântica de sinais (instante exato)

| Registro existente | Paciente | Profissional |
|---|---|---|
| Individual com `ocupaVaga=true` | OCUPACAO_INDIVIDUAL | OCUPACAO_INDIVIDUAL |
| Individual com frequência PRESENTE (inclusive cancelada regularizada) | PRESENCA_INDIVIDUAL, eventualmente também ocupação | Idem |
| Individual FALTOU | FALTA_INDIVIDUAL sem presença | Não prova compromisso factual exclusivo |
| Individual cancelado sem frequência | Sem sinal de ocupação/presença | Idem |
| Individual com migracaoPendente no instante conhecido | INDIVIDUAL_PENDENTE; não exclui possível projeção de ocupação | Idem |
| Participação em sessão AGENDADA (até presença provisória) | INSCRICAO_AGENDADA | SESSAO_AGENDADA do coordenador vigente, inclusive sem participantes |
| Sessão REALIZADA + PRESENTE | PRESENCA_GRUPO | SESSAO_REALIZADA do responsável histórico comprovado |
| Sessão REALIZADA + FALTOU | FALTA_GRUPO | SESSAO_REALIZADA do responsável histórico comprovado |
| Sessão REALIZADA + NAO_REGISTRADA | FREQUENCIA_GRUPO_DESCONHECIDA | SESSAO_REALIZADA se responsabilidade conhecida |
| Sessão CANCELADA | Não implica presença/ocupação | Idem |

Sinais podem coexistir. `temImpedimentoOperacional` sinaliza ocupação/inscrição/sessão prevista ou fato incompatível. `temSinalFactualIncompativel` sinaliza presença individual/coletiva ou sessão realizada do profissional. `temInformacaoInconclusiva` não inventa ausência/presença. A ausência no grupo é descrita separadamente, não prova presença física. A escolha de recusa/erro na integração deverá preservar as guardas anteriores mais conservadoras até decisão explícita: **não substituir a checagem histórica legada apenas pelo predicado factual**. A distinção de faltas/presenças exatamente simultâneas e o desempate clínico continuam pendentes. A avaliação não prova a origem de um fato canônico em registro ambíguo; sinais de pendência pedem política específica. Responsabilidade desconhecida não é atribuída ao coordenador atual. Datas/horas desconhecidas fora do instante consultado e responsabilidade não atribuível a um profissional não podem ser resolvidas por esta busca pontual; tratar em 6.5/6.6. Nenhuma duração é presumida.

## Testes candidatos e portões

- `AgendaAssistencialArchitecture62Tests`: 18 invocações de integração, com H2, fixtures existentes, transação real e chamadas ao componente. Cobrem ocupação/presença canônica, cancelamento, faltas, grupo previsto/realizado nas três frequências, sessão vazia, exclusão exata, responsável histórico, precisão, ordem/locks, transação obrigatória, ausência de lock, paciente externo, frequência individual migrada desconhecida e não atribuição de responsabilidade histórica desconhecida.
- `AgendaAssistencialLocks62Tests`: dois casos unitários de ordem efetiva das chamadas, refresh antes de escopo, plano inválido sem aquisição. Unitários não demonstram concorrência no banco.
- `AgendaSeries62Characterization`: oito invocações opt-in. Prova residual de conflito de participante na primeira/intermediária/última sessão, compromisso do coordenador sem inscritos, regeneração incompatível, transferência com conflito tardio, próxima sessão sem participantes e série legítima. As guardas novas não foram conectadas às APIs, portanto as divergências podem aparecer. Não contar BUILD FAILURE por asserções como regressão nova da infraestrutura. Os resultados devem ser analisados; não presumir quantos falharão.

Comandos em `backend/buscaativa`:

```bash
set -o pipefail
bash ./mvnw -ntp -Dtest=AgendaAssistencialArchitecture62Tests,AgendaAssistencialLocks62Tests test 2>&1 | tee /tmp/etapa62a-arquitetura.log
bash ./mvnw -ntp -Dtest=AgendaSeries62Characterization test 2>&1 | tee /tmp/etapa62a-series.log
```

Executar o segundo após a infraestrutura passar. Não executar suíte completa ou Angular. Este documento é uma especificação inicial, não relatório de resultados. A implementação integrada, regressões P03–P05, proteção factual, mutações concorrentes em ambas as ordens e rollback real estão reservados a 6.2B–F. Os cenários residuais de remarcação serão caracterizados antes de 6.2E; os históricos contraditórios antes de 6.2D. Confirmar cobertura de consultas de disponibilidade/sugestão e não alterar capacidade quantitativa do turno. Ajustar consultas/protocolo com resultados reais. Não publicar nem avançar sem revisão.
