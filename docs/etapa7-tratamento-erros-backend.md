# Etapa 7 — Tratamento e padronização dos erros do backend

A entrega parte do commit documental `4eea0b544d6592eb079163bd1f5ff83488401068` na branch `correcao-cadastro-unico-isolamento`. O último código validado é `75e6743`. A regressão de referência aprovou 1.430 testes Java, 12 testes adicionais de concorrência, 131 testes Angular, quatro testes de contrato HTTP experimental e os builds Angular de produção e experimental. O pacote da Etapa 7 é identificado pelo commit que adiciona este relatório. [CI de referência](https://github.com/LizReis/SIBRAPS-testagem/actions/runs/45892744210) · [Branch de entrega](https://github.com/LizReis/SIBRAPS-testagem/tree/correcao-cadastro-unico-isolamento)

## Diagnóstico

- A exceção de lock otimista JPA expunha a mensagem do provedor. Lock otimista Spring, lock pessimista e timeout de aquisição compartilhavam a mesma mensagem, inclusive quando uma tradução de lock ocultava uma falha de conexão ou consulta.
- A resposta de erro padrão do Spring Boot para falhas inesperadas seguia um contrato diferente do usado pelo MVC. O gerador de relatórios devolvia uma resposta segura própria, mas o handler descartava a causa para diagnóstico e não distinguia uma indisponibilidade encapsulada.
- O tratamento legado de pacientes usava `DatabaseException` para ausências e similaridade de cadastro. A mensagem de similaridade incluía DTOs completos. A resposta 404 genérica também propagava valores carregados na exceção. Dois conflitos de grupo inseriam o nome do paciente na mensagem.
- As respostas 401 e 403 do filtro eram montadas separadamente como texto JSON. Um JWT assinado para um usuário removido podia falhar durante a consulta ao usuário antes de chegar ao tratador de autenticação.
- A versão local divergente já usava 422 e o Angular reconhecia o texto dessa resposta. A pendência assistencial de agenda e grupos e o corpo malformado de auditoria já tinham adaptações locais.

## Matriz HTTP

| Situação | Antes | Nesta entrega | `code` |
|---|---|---|---|
| JSON ilegível, parâmetro obrigatório ausente ou tipo incompatível | 400 pelo MVC | 400 com contrato padronizado | `MALFORMED_REQUEST` |
| JSON ilegível nas rotas de auditoria do perfil `test` | 422 do controller | 422 do controller | `MALFORMED_REQUEST` |
| Autenticação ausente ou inválida | 401 do filtro, com resposta separada. Usuário removido podia gerar 500 | 401 para credenciais inválidas, inclusive usuário removido, com mensagem uniforme | `AUTHENTICATION_REQUIRED` |
| Autenticado sem permissão | 403 via filtro ou MVC | 403 com o mesmo contrato nos dois caminhos | `ACCESS_DENIED` |
| Recurso ausente ou fora da unidade | 404, com mensagem da exceção. Edição e inativação legadas de paciente usavam 400 | 404 com mensagem idêntica para ausência e fora de escopo, inclusive nessas duas escritas legadas | `RESOURCE_NOT_FOUND` |
| Validação de campos ou regra de entrada | 422 para Bean Validation e validação de domínio | 422, com erros de campo quando disponíveis | `VALIDATION_FAILED` |
| Registros similares no cadastro legado | 400 com DTOs na mensagem | 400 com orientação de confirmação e sem dados de outros cadastros | `PATIENT_SIMILARITY_CONFIRMATION` |
| Conflito de negócio ou pendência assistencial | 409 | 409, incluindo os campos de reconciliação nas rotas que já os retornavam | `BUSINESS_CONFLICT` |
| Duplicidade verificada pelo domínio | 409 | 409, com tradução documental existente | `DUPLICATE_RESOURCE` |
| Versão divergente no comando local | 422 | 422 com o mesmo texto de compatibilidade | `STALE_VERSION` |
| Versão divergente ou lock otimista JPA/Spring | 409, com mensagens diferentes | 409 com orientação para atualizar a consulta | `STALE_VERSION` |
| Contenção pessimista, deadlock ou timeout de aquisição identificado | 409 quando traduzido para exceção de lock | 409, com distinção quando o tipo ou SQLState identifica a causa | `CONCURRENT_UPDATE`, `CONCURRENT_DEADLOCK`, `LOCK_ACQUISITION_TIMEOUT` |
| Falha de conexão sem evidência de transitoriedade | Em geral 500, com possibilidade de 409 sob tradução de lock | 500 | `DATABASE_CONNECTION_FAILED` |
| Timeout de conexão identificado pelo driver | Sem categoria dedicada | 500 quando não há evidência adicional de indisponibilidade | `DATABASE_CONNECTION_TIMEOUT` |
| Timeout de consulta sem evidência de contenção ou indisponibilidade | Em geral 500, com possibilidade de 409 sob tradução de lock | 500 | `DATABASE_QUERY_TIMEOUT` |
| Integridade não reconhecida pelo domínio ou erro de operação SQL | Em geral 500 pelo Spring Boot | 500 com resposta segura | `DATABASE_INTEGRITY_ERROR`, `DATABASE_ERROR` |
| Falha temporária de infraestrutura demonstrada pela causa | Em geral 500 | 503 | `SERVICE_UNAVAILABLE` |
| Falha inesperada ou falha do renderizador de relatório | 500 pelo Spring Boot ou pelo handler de relatório | 500 com resposta segura e diagnóstico correlacionado | `INTERNAL_ERROR`, `REPORT_GENERATION_FAILED` |

Os demais status definidos pelo MVC, como 405 e 415, conservam seus valores e cabeçalhos de protocolo, com código `HTTP_<status>`. A mudança de 400 para 404 em edição e inativação legadas de paciente corrige a classificação de um único caso de recurso ausente ou fora de escopo. As verificações de acesso e a ordem das guardas permanecem as mesmas.

## Exceções aninhadas e diagnóstico

O MVC mantém a precedência dos handlers locais e das recusas explícitas do domínio. Nas demais falhas, o classificador examina a cadeia de causas, a `SQLException` carregada pelo Hibernate e as exceções associadas por `SQLException.getNextException()`. A navegação usa identidade para evitar ciclos. Uma causa técnica identificada de conexão, consulta, integridade ou SQL prevalece sobre uma tradução de lock. Um deadlock, um timeout de aquisição ou um conflito de versão comprovado conserva o 409. Causas sem evidência suficiente recebem 500. A geração de relatório mantém sua resposta própria de 500 quando não há causa de persistência mais específica.

Os estados SQL e códigos de fornecedor são usados para evidências específicas. Exemplos incluem `40P01` para deadlock, `40001` com código de fornecedor de deadlock conhecido, `55P03` e `HYT00/50200` para aquisição de lock e `08006` para falha de conexão em curso. Uma classe de conexão explicitamente não transitória conserva 500. A classe JDBC de conexão transitória e estados específicos de interrupção ou indisponibilidade permitem 503.

O backend gera o UUID de correlação por requisição de erro. Resposta JSON e cabeçalho `X-Correlation-ID` carregam o mesmo valor. O log técnico registra instante, código, padrão da rota quando disponível, classes das causas, SQLState válido, código numérico do fornecedor e até cinco quadros por causa, limitados a 12 causas. O logger recebe somente esses dados, sem mensagens brutas de exceções, SQL, URI com valores, corpo, documentos, tokens ou senhas. Logs de falhas técnicas e de contenção usam o SLF4J existente. As auditorias assistenciais seguem seu fluxo atual.

## Contrato da resposta

O corpo preserva `timestamp`, `status`, `error`, `message` e `path`, além de `errors[].fieldName` e `errors[].message` para validação de campos. `code` e `correlationId` são aditivos. O corpo específico de pendência conserva `codigo`, `tipoConflito`, `agendamentoId` e `sessaoId`. A resposta 404 mantém o caminho requisitado e usa o mesmo código e texto para ausência e para isolamento. Respostas 5xx não encaminham mensagens de exceções.

## Arquivos da entrega

Caminhos Java relativos a `backend/buscaativa/src`.

| Área | Arquivos |
|---|---|
| Segurança HTTP | `main/java/com/pet/buscaativa/config/` com `RestAccessDeniedHandler.java`, `RestAuthenticationEntryPoint.java` e `SecurityFilter.java` |
| Contrato e classificação | `main/java/com/pet/buscaativa/services/exceptions/` com `ApiErrorResponses.java`, `PersistenceFailure.java`, `ResourceExceptionHandler.java`, `StandardError.java`, `ValidationException.java`, `SimilarPatientException.java`, `StaleLocalVersionException.java` e a remoção de `DatabaseException.java` |
| Adaptadores HTTP locais | `main/java/com/pet/buscaativa/controllers/` com `AuditoriaBuscaAtivaController.java` e `PendenciaFrequenciaHandler.java` |
| Mensagens e identificação de versão no domínio | `main/java/com/pet/buscaativa/services/impl/` com `CoordenacaoEscritaClinicaEnsaio.java`, `GrupoTerapeuticoServiceImpl.java` e `PacienteServiceImpl.java` |
| Testes adaptados | `test/java/com/pet/buscaativa/` com `AgendaConcurrencyTests.java`, `GlobalConcurrencyHandlerReviewTests.java`, `IsolamentoUnidadeTests.java`, `ModeloVinculoOperacionalTests.java`, `ProfissionaisEscopoTests.java` e `SecurityP01P02Tests.java` |
| Evidência | `docs/etapa7-tratamento-erros-backend.md` |

## Testes e preservação

Foram exercitados **85 cenários distintos, todos aprovados** ao fim das verificações direcionadas com Maven e Java 21. A seleção inicial de 83 casos aprovou 81. As duas falhas eram de preparação dos testes novos. O JSON de validação precisava conter o booleano do DTO para chegar ao Bean Validation. A similaridade precisava ser testada pelo serviço legado com documentos ausentes, pois o DTO HTTP exige CPF e CNS. A segunda execução repetiu os cenários afetados, incluiu timeout de conexão e inativação fora de escopo e aprovou os 40 casos selecionados, com zero falhas, erros ou ignorados.

| Seleção | Cenários distintos aprovados | Evidência |
|---|---:|---|
| `GlobalConcurrencyHandlerReviewTests` | 38 | Matriz HTTP reutilizada para pacientes, grupos, profissionais, disponibilidade, históricos e relatórios. Casos novos para causas aninhadas, deadlock JDBC, timeout de lock, timeout de consulta e de conexão, integridade, SQL inválido, indisponibilidade, falha de programação, validação de campos, falha no filtro JWT e correlação segura. |
| `SecurityP01P02Tests` | 18 | Autenticação existente, autorização, JWT inválido, isolamento e console. Casos novos para senha incorreta ou conta desconhecida, usuário removido e corpo malformado. |
| `ProfissionaisEscopoTests` | 6 | Comparação entre usuário ausente e fora da unidade, permissões, validação e duplicidade. |
| `IsolamentoUnidadeTests` | 8 | Busca por UUID e documentos, similaridade legada sem gravação ou exposição e 404 de inativação para identidades ausentes ou externas. |
| `BuscaAtivaAuditIntegrationTests` | 7 | Validação existente e 422 local de corpo malformado sem gravação de eventos. |
| `AttendanceCorrectionIntegrityTests` | 4 | Recusa de versão e corrida real de correção com uma única confirmação. |
| `AgendaConcurrencyTests` | 1 | Asserção existente de contenção segura adaptada para verificar o código estável. |
| `GroupFrequencyTemporalTests` | 1 | Corpo de pendência assistencial e igualdade do estado persistido após recusa. |
| `AgendamentosClinicosLocaisHttpTests` | 1 | Leitura e escrita recusadas para profissional de outra unidade. |
| `ModeloVinculoOperacionalTests` | 1 | Contrato 422 de versão local com o novo código e atomicidade da resolução e reversão. |

A compilação do código e o `git diff --check` passaram. A regressão completa Java, a matriz opt-in de 12 cenários e as verificações Angular ficaram para o workflow existente.

A composição das transações, os mecanismos de rollback e a política de retentativas seguem como definidos anteriormente. A classificação acontece na fronteira HTTP. Cadastro único, permissões dos três perfis, isolamento, reserva e frequência, classificação de risco, busca ativa, visita domiciliar, RF22, RNF17, correção, justificativa, auditoria, versões, duplicidades, registros inconclusivos, responsabilidade histórica, designação, autoria e indicadores consolidados preservam suas regras. O pacote tem escopo Java de backend, testes direcionados e documentação. As migrations V1–V32, o Angular e os workflows existentes seguem no baseline.

## Limitações e decisões pendentes

- A matriz HTTP de falhas injetadas caracteriza a classificação. As verificações transacionais rodam com fixtures sintéticas em H2. A confirmação dos estados reais de driver, do timeout de conexão e do comportamento sob indisponibilidade do SGBD de implantação depende da escolha do ambiente da Etapa 9.
- A migração do 422 de versão local para 409 poderá ser avaliada depois de a interface consumir `STALE_VERSION`. A Etapa 7 mantém o status e o texto consumidos pelo cliente atual. A confirmação de similaridade do legado segue em 400 e a auditoria malformada mantém o 422 próprio.
- A CI existente cobre a regressão integral após publicação. Seu resultado deve ser conferido no SHA de entrega. [GitHub Actions da branch](https://github.com/LizReis/SIBRAPS-testagem/actions/workflows/cadastro-unico-diagnostico.yml?query=branch%3Acorrecao-cadastro-unico-isolamento)

## Orientações para o Angular na Etapa 8

- Para `STALE_VERSION` em 409 ou 422, recarregar o dado e permitir nova decisão do usuário. Para `BUSINESS_CONFLICT` ou `DUPLICATE_RESOURCE`, exibir a regra retornada. Em pendências, usar também o `codigo` específico da reconciliação.
- Para `CONCURRENT_UPDATE`, `CONCURRENT_DEADLOCK` ou `LOCK_ACQUISITION_TIMEOUT`, informar a contenção e orientar a atualização dos dados. Para `SERVICE_UNAVAILABLE` e outros 5xx, informar a falha e apresentar `correlationId` quando disponível. Após uma falha técnica em escrita, confirmar o estado antes de qualquer nova tentativa iniciada pelo usuário.
- Usar `errors` para destacar campos em 422 e manter o tratamento genérico de 404. A interface pode usar o status como fallback para respostas de versões anteriores ou respostas produzidas fora da API.

## Parecer técnico

Implementação concluída e aprovada na verificação local direcionada. A regressão integral fica aguardando validação automatizada no commit de entrega. Os contratos locais de versão, auditoria e pendência foram preservados com a identificação aditiva por código. A validação no SGBD de implantação permanece vinculada à Etapa 9.
