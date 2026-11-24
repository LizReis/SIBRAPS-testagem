# Etapa 6.3 — coordenação e persistência experimental dos indicadores locais

Base `e28b5e66b35ff0a776db3e0da82ac1c56bba572c`, branch `correcao-cadastro-unico-isolamento`. A árvore estava limpa após atualizar a branch para a base, validada no [GitHub Actions #45472572713](https://github.com/LizReis/SIBRAPS-testagem/actions/runs/45472572713). Este pacote implementa e testa a escrita local em H2 sintético, com os perfis `test` e `ensaio-estado-local`. A projeção diagnóstica mantém a transação serializável de leitura e os fluxos HTTP existentes mantêm seus escritores e consumidores. [Contrato de coordenação](etapa6-3-coordenacao-escrita-clinica.md) · [Consolidação V14](etapa6-3-consolidacao-estrutural-estado-clinico-local.md) · [Plano de indicadores](etapa6-3-plano-indicadores-por-vinculo.md)

## Protocolo executado

1. Verificar ator e unidade do contexto autenticado. Descobrir IDs e relações por consultas escalares, com filtro de origem e vínculo local para as operações factuais.
2. Travar pacientes por UUID, usuários por UUID, vínculos por UUID, grupos, sessões e agendamentos por ID. A inicialização trava primeiro a identidade, pois a própria existência do vínculo pode mudar durante a espera. Sob essa trava, descobre a parte restante do plano e devolve o vínculo se a operação já tiver sido realizada. A identidade compartilhada coordena a escrita. O acesso continua sujeito a vínculo e proveniência.
3. Reler as entidades sob as travas, revalidar papel e lotação e comparar a descoberta após a espera. As operações individuais conferem a cadeia de origem. As coletivas incluem todos os participantes e conferem responsável, coordenador, grupo e sessão. Alteração do plano ou da relação aborta a operação, sem tentativa automática. As versões esperadas são verificadas após as travas.
4. Alterar o fato, fazer flush e calcular com fatos visíveis na mesma transação. Gravar o conjunto local conclusivo, histórico e trilha de auditoria. Alteração de frequência cancelada exige a cadeia auditada de regularização. Lotes coletivos inconclusivos revertem a operação inteira.
5. Confirmar em uma transação. Repetir um comando já satisfeito com a versão atual preserva o estado e as trilhas. As validações operacionais de horário continuam a considerar conflitos assistenciais, inclusive entre modalidades. O cálculo de indicadores usa somente os fatos pertinentes ao vínculo.

A agenda atual já trava paciente e profissional antes do atendimento. Grupos seguem pacientes, responsáveis, grupo e sessão. As fases adicionais do ensaio preservam essa ordem relativa. O ensaio trava a sessão pela linha principal, sem um fetch de participantes no comando de lock. A inclusão de vínculos antes de grupo, sessão e agenda não tornou os escritores atuais aderentes ao protocolo completo. Operações V12/V13 usam trava de vínculo e gravam a decisão administrativa em trilha própria. A eventual aplicação clínica de uma decisão requer o protocolo de fatos.

## Cálculo, estado e pendências

O cálculo compartilhado reusa a linha do tempo, os comparadores de risco, visita e busca ativa e o plano de indicadores. O escritor calcula faltas, última presença, risco e gatilho RF22 sem consultar os indicadores globais. A projeção mantém suas comparações diagnósticas com a referência global. A autorização diagnóstica continua independente da autorização de escrita. O plano por si só não concede autorização operacional.

O resultado inconclusivo preserva faltas, última presença, risco e gatilho conhecidos. Quando os critérios existentes de proveniência, frequência ou cadeia exigem conferência, o escritor individual pode sinalizar pendência explícita. Uma pendência anterior é preservada. Pendência ausente sem evidência de fatos problemáticos continua desconhecida. Limpar automaticamente a pendência exigiria distinguir a causa que a originou e definir quando ela foi resolvida. A presença posterior isolada não resolve uma origem desconhecida neste ensaio.

Os comandos de estado usam o mesmo protocolo. Para reativação com cadeia anterior comprovada, o cálculo usa a transição escrita pela própria transação, cuja revisão Envers será gerada no commit. No encerramento, aplica o conjunto conclusivo quando risco verde e visita desligada coincidem com os efeitos de encerramento já existentes. Se o candidato divergir, o cache anterior é preservado. É preciso definir como o algoritmo local deve tratar as demais combinações de status, risco e RF22 antes da ativação. A prova do estado de um vínculo legado continua sujeita às regras V14. Os comandos factuais conservam o histórico de frequência e a autoria da fonte original. O histórico experimental deixa sem preenchimento o número da falta no instante histórico, pois o contador final calculado para o vínculo não comprova esse valor.

## Arquivos alterados

| Arquivo | Alteração |
| --- | --- |
| `PlanoCoordenacaoEscritaClinica.java`, `CoordenacaoEscritaClinicaEnsaio.java` | Descoberta por relações, execução ordenada e conferência pós-lock. |
| `AgendaAssistencialLocks.java`, `AtoresEstadoClinicoLocalEnsaio.java`, `VinculoAssistencialRepository.java` | Reuso das travas de sujeitos, lotação e papel atualizados, trava restrita ao vínculo local. |
| `InicioAcompanhamentoLocalEnsaioService.java`, `EstadoClinicoLocalEnsaioService.java` | Entrada no protocolo comum e uso de cálculo sob a mesma transação para as transições aplicáveis. |
| `CalculoIndicadoresVinculo.java`, `ProjecaoClinicaVinculoService.java`, `PlanoIndicadoresVinculo.java` | Núcleo compartilhado e composição diagnóstica somente leitura. |
| `VinculoAssistencial.java`, `PersistenciaIndicadoresVinculoEnsaioService.java` | Aplicação idempotente de indicadores, sinalização de pendência e histórico local. |
| `FrequenciaIndividualLocalEnsaioService.java`, `FrequenciaColetivaLocalEnsaioService.java`, `HistoricoFrequenciaLocalEnsaio.java` | Registro, correções, remarcação, regularização e lote coletivo, com histórico e auditoria. |
| `AgendamentoServiceImpl.java`, `GrupoTerapeuticoServiceImpl.java`, `IntegridadeAgendaAssistencialService.java`, `AuditoriaRegularizacaoService.java` | Reuso de regras de agenda e cadeia de auditoria com autorização estrita por vínculo no ensaio. |
| `PersistenciaIndicadoresLocaisIntegrationTests.java` | Cenários de escrita transacional, concorrência, isolamento, idempotência e rollback no H2. |

## Validação

Java 21.0.13 e Maven Wrapper, em `backend/buscaativa`. A validação do frontend fica no workflow existente no GitHub Actions.

| Execução | Aprovados | Falhas | Erros | Ignorados |
| --- | ---: | ---: | ---: | ---: |
| Testes focados de escrita, projeção, V14, ordem e agenda | 51 | 0 | 0 | 0 |
| `bash ./mvnw -B -ntp clean verify` | 1.335 | 0 | 0 | 0 |
| `bash ./mvnw -B -ntp -Dtest=ReconciliationConcurrency61Characterization test` | 12 | 0 | 0 | 0 |

O focado final usou `-Dtest=PersistenciaIndicadoresLocaisIntegrationTests,EstadoClinicoLocalEnsaioTests,ProjecaoClinicaVinculoIntegrationTests,PlanoCoordenacaoEscritaClinicaTests,EventoEstadoClinicoVinculoMigrationTests,AgendaAssistencialArchitecture62Tests`. `git diff --check` e `git status` foram conferidos.

Os testes compararam snapshots do cadastro global e vínculos externos, recusaram escrita de outra unidade, confirmaram a mesma revisão Envers para fato, vínculo e histórico e comprovaram rollback com falha real de inserção na auditoria. A matriz focada exercitou remarcação e RF22, regularização e correções subsequentes, concorrência individual/coletiva, criação concorrente e mudanças na sessão ou no ator enquanto aguardava a trava. Houve verificação explícita de ausência de e-mail. Uma rodada intermediária identificou a guarda territorial legada no cálculo de conflitos de presença. O ensaio passou a reutilizar a guarda de conflito com autorização estrita pelo vínculo bloqueado. O relógio fixo das fixtures foi isolado em perfil de teste após ser detectado nos executores de restauração.

O resultado do workflow associado ao SHA publicado está na [lista de execuções da branch](https://github.com/LizReis/SIBRAPS-testagem/actions/workflows/cadastro-unico-diagnostico.yml?query=branch%3Acorrecao-cadastro-unico-isolamento).

## Escritores que ainda precisam aderir

| Caminho | Dependência para a ativação |
| --- | --- |
| `AgendamentoServiceImpl` | Criação, remarcação, registro/correção de frequência e recuperação/regularização da reserva ainda seguem o caminho oficial. O lock da agenda não inclui o ciclo completo de vínculo, indicadores e auditoria local. |
| `GrupoTerapeuticoServiceImpl` | Criação/regeneração, transferência de coordenação, inscrições/remoções, confirmações e correções ainda não confirmam indicadores dos vínculos em um lote coordenado. |
| `PacienteServiceImpl` e serviços de cadastro/identidade | Cadastro e alterações de acompanhamento continuam em fluxo próprio. A confirmação de identidade trava o paciente, mas a vinculação operacional não inicializa clínica pela V14. Inativação, encerramento, reativação e recálculo ainda gravam o cadastro global. |
| `HistoricoPacienteServiceImpl` | Registro manual segue o escopo legado sem o protocolo. Registros indiretos dependem do chamador e podem ser escritos fora da trava de identidade. Histórico derivado e fatos clínicos precisam de contratos distintos. |
| `MonitoramentoBuscaAtiva.atualizarRiscosDiariamente` e `PacienteServiceImpl.listarPacientesEmBuscaAtiva` | Scheduler e busca ativa com reclassificação sob demanda gravam o cache global sem coordenar todas as fases. A seleção e a trilha RNF17 exigem migração posterior. |
| `AgendaMigratedIndicatorsInitializer` | Trava a identidade e recalcula o cache global. Deve participar do contrato por vínculo antes de qualquer substituição do inicializador. |
| `UsuarioServiceImpl`, coordenação de agenda e grupos | A edição de usuário tem trava própria. Revisar exclusão, transferência, alterações de oferta e dependências com todos os comandos antes da integração. |
| `DevConfig`, scripts e migrations offline | São pontos de escrita de fixture ou manutenção. Preservar sua execução segregada das operações clínicas. |

O teste negativo preservado demonstra um escritor não coordenado gravando frequência entre a verificação de ausência e o commit da inicialização. O ensaio cobre os dois sentidos quando o escritor participa da trava de identidade. Em operação real ainda há escritores fora do contrato. A ativação também depende de regras para limpar pendência, efeitos de encerramento, integração entre cadastro/frequência e verificação em cópia de implantação. Nenhuma migration V1–V14, base real, tela, consulta, relatório ou rotina de notificação foi alterada.
