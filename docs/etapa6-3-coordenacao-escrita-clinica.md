# Etapa 6.3 — inventário e contrato preliminar de coordenação de escrita clínica

Base `e7676b99794caeed7ab208252ac8bad0c6ce08e5`, Actions #45470585888: 1.320 Java, 12 concorrência, 28 Angular, build aprovado. Escopo deste pacote: mapeamento estático dos caminhos identificados, contrato puro de ordenação e ensaio sintético. Validação CI pendente. Não é inventário de cobertura exaustiva nem alteração de fluxo operacional.

## Inventário inicial

| Escritor / entrada | Travas e efeitos atuais | Dependência para integração |
| --- | --- | --- |
| AgendamentoServiceImpl.save (inclusive remarcação) | AgendaAssistencialLocks: pacientes UUID ordenados, profissionais UUID ordenados; original/registro e recálculo legado | Descobrir cadeia, revalidar identidade e origem, integrar vínculo antes dos locks de fatos; preservar conflitos de agenda |
| AgendamentoServiceImpl.atualizarStatus/corrigirFrequencia (aplicarAtualizacao), recuperarReservaCancelada, regularizarFrequenciaCancelada | bloquearAgendamento: paciente, profissional, atendimento; versão, histórico e auditoria; recuperação sem nova frequência não recalcula | Protocolo comum e cálculo local no mesmo contexto após alteração factual |
| GrupoTerapeuticoServiceImpl.criarGrupo, atualizarGrupo (inclui transferência/regeneração), criarProximaSessao | Pacientes e coordenadores antes de grupo; regeneração exclui sessões e cria novas | Mapear todos os participantes afetados inclusive exclusões e responsáveis históricos |
| GrupoTerapeuticoServiceImpl.adicionarParticipante, removerParticipante, registrarPresenca, atualizarStatus, confirmarOcorrencia | bloquearSessao: pacientes, responsáveis, grupo, sessão; revalida participantes e responsabilidade; presença provisória não é frequência realizada | Manter descoberta completa e autorização; revalidar todo conjunto antes da escrita |
| GrupoTerapeuticoServiceImpl.inscreverRetroativamente, inscreverEmSessoesFuturas, removerParticipanteDoGrupo, corrigirFrequencias | Travas de sujeitos e grupo/sessão, recálculo retroativo e auditoria quando aplicável | Incluir conjunto de sessões, participantes, versões e vínculos; não aplicar parcial por paciente |
| PacienteServiceImpl.save, inativarPaciente, encerrarAcompanhamento, reativarAcompanhamento | Lock da identidade em edição/status; cadastro novo cria vínculo, estado clínico ainda global | Integrar estado explícito local, preservar cadastro e permissões |
| PacienteServiceImpl.recalcularAssiduidadePaciente/atualizarAssiduidadePaciente/calcularEAtualizarRisco | Dependem do chamador; leem fatos e alteram cache global, eventualmente histórico | Não reaproveitar como escritor local; separar cálculo puro, persistência e histórico |
| PacienteServiceImpl.listarPacientesEmBuscaAtiva | Consulta transacional com reclassificação e save sem o protocolo de locks da agenda | Evitar leitura com escrita concorrente; preservar contrato de seleção e RNF17 |
| MonitoramentoBuscaAtiva.atualizarRiscosDiariamente | Lista ativos, classifica e salva cada um, depois saveAll; não usa lock explícito por identidade | Planejar transação por unidade/vínculo, recarregar sob lock, remover escrita redundante apenas em etapa autorizada |
| AgendaMigratedIndicatorsInitializer.run | Lock de paciente por transação após migração explícita, cache global | Manter ferramenta e startup atuais; planejar substituição coordenada, sem inferir legado |
| HistoricoPacienteServiceImpl.registrarManual | Escopo legado, findByIdPublico sem lock de identidade explícito e save | Integrar ao protocolo; ausência de histórico também é pré-condição da inicialização |
| HistoricoPacienteServiceImpl.registrarAgendamento/registrarAlteracaoDeAtendimento/registrarCorrecaoDeFrequencia/registrarFatoRegularizado/registrarSituacaoAtual/registrarFrequenciaGrupo/corrigirFrequenciaGrupo | Histórico depende de chamador e transação; não constitui protocolo independente | Garantir que chamadores já detêm locks; distinguir histórico factual, manual e derivado |
| VinculoAssistencialService, VinculacaoIdentidadeExistenteService, ConfirmacaoVinculacaoIdentidadeService | Identidade travada na vinculação e confirmação de documentos; não inicializa clínica | Integrar somente depois de confirmar identidade e escopo |
| InicioAcompanhamentoLocalEnsaioService | Identidade, depois ator/referência; verifica ausência de fatos e cria evento | Falta proteção contra escritor sem protocolo, conforme teste negativo V14 |
| EstadoClinicoLocalEnsaioService | Ator/referência antes do vínculo; não trava identidade | Alinhar ordem antes de introduzir qualquer lock de identidade nessa rota |
| UsuarioServiceImpl.save/removerUsuario | Edição usa lock de usuário; exclusão precisa revisão própria | Revalidar papel/lotação e dependências; evitar inversão com locks de pacientes |
| SolicitacaoRevisaoProvenienciaService / RegistroDecisaoRevisaoProvenienciaService | Lock do vínculo, registros append-only V12/V13, não aplicam correção | Não confundir decisão com escrita de fato; estudar dependência e ordem antes de integrar futura aplicação |
| DevConfig / migrações offline | Escrita sintética ou ferramenta offline, fora dos comandos HTTP | Preservar perfis, fixtures e isolamento; não executar migrações reais |
| AlertaPacientesVermelhosService / scheduler | Seleciona destinatários, renderiza e envia e-mail; não é escritor de frequência | Migrar leitura posteriormente; não enviar e-mail dentro do ensaio de escrita |

As auditorias de correção, regularização, busca ativa e inscrição retroativa são efeitos a preservar; não são fonte de frequência. Disponibilidade e bloqueio de agenda são recursos de integridade operacional, não indicadores clínicos: revisar suas interações com trava de profissional antes de mudar a ordem. O inventário deve ser completado por busca de todos os repositórios, chamadas indiretas, jobs, controllers, bulk DML e caminhos de rollback no pacote de integração.

## Contrato proposto (não operacional)

1. Autorizar preliminarmente a operação e descobrir no servidor os recursos por projeções escalares, sem devolver dados de outras unidades.
2. Fixar o conjunto: pacientes ordenados por UUID (inclusive todos os participantes de operação coletiva), usuários ordenados por UUID (ator, referência, responsáveis atuais/históricos necessários), vínculos ordenados por UUID, grupos por ID, sessões por ID, agendamentos por ID. A ordem entre as duas últimas categorias é proposta e requer conferência de todos os caminhos cruzados; não adquirir recurso anterior depois de avançar.
3. A trava global de identidade é apenas coordenação temporária, não autorização e não unificação de dados clínicos. Um vínculo novo ainda não possui linha para travar; usar a identidade e constraint única. Não usar o filtro clínico legado de AgendaAssistencialLocks para autorizar os vínculos múltiplos; reaproveitar a mecânica, não a autorização inadequada.
4. Recarregar sob lock e comparar recursos redescobertos. Mesmo conjunto não prova a mesma relação: revalidar paciente, vínculo e unidade, cadeia, participantes, responsabilidade, versões, escopo, papéis, lotação, estado e origem. Alteração do conjunto ou precondição exige abortar e recomeçar em nova transação, sem ampliar locks no meio.
5. Alterar fatos, flush quando necessário, calcular localmente na MESMA transação (não chamar projeção diagnóstica REQUIRES_NEW, que não enxerga fatos ainda não confirmados), persistir indicadores e histórico/auditoria juntos. Inconclusão não equivale a zero/verde/false. Política de pendência e efeitos de estado precisam preservar regras existentes.
6. Commit único por operação; rollback de todos os pacientes afetados em operação coletiva. Não enviar notificações externas no ensaio. Nenhum retry automático de comandos não idempotentes.
7. Antes da ativação, demonstrar ausência de inversões e escritores não coordenados, incluindo scheduler e leitura com escrita. Testar ambas as ordens de inicialização versus fato, fatos individuais/coletivos concorrentes, transferência, V12/V13 e rollback, sem ampliar permissões. Aplicação oficial permanece bloqueada.

O record puro PlanoCoordenacaoEscritaClinica só ordena e fixa listas, não executa locks nem autoriza. A comparação não verifica versões ou relacionamentos. Três testes unitários cobrem ordenação, mudança de conjuntos e imutabilidade. Um teste integrado usa a mesma trava pessimista de identidade existente: escritor sintético grava fato antes da inicialização, que deve aguardar e depois recusar zero inicial. Preservar o teste negativo V14 de escritor sem protocolo. A proteção do sentido inverso (inicialização primeiro, escritor depois) exige recálculo integrado, ainda não entregue. Nenhum teste local executado; nenhuma alteração de operação real, migration, frontend, main ou regra clínica.

## Próximo pacote

Completar inventário e implementar coordenação experimental real, extraindo cálculo local reutilizável dentro da transação, com matriz de concorrência e auditoria. Só depois integrar escritores reais de modo coordenado. A amplitude de serviços e a necessidade de executar H2/Java iterativamente recomendam Codex para esse pacote maior.

[Consolidação V14](etapa6-3-consolidacao-estrutural-estado-clinico-local.md) · [Plano de indicadores](etapa6-3-plano-indicadores-por-vinculo.md)
