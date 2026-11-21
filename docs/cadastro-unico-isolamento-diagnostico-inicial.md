# Regularização do cadastro único e isolamento assistencial — diagnóstico inicial

**Branch:** `correcao-cadastro-unico-isolamento`, derivada de `correcao-fluxos-assistenciais-sibraps`. **Escopo:** subetapa independente anterior à 6.3; não alterar a `main`.

## Decisões funcionais confirmadas

1. Uma pessoa tem apenas **uma identidade cadastral** (CPF/CNS únicos) em todo o SIBRAPS. Dados pessoais cadastrais podem ser consultados/atualizados por usuários autorizados de unidades com finalidade legítima; alterações auditadas. Busca controlada do cadastro por CPF/CNS exatos; não liberar listagem global.
2. Cada USF específica e cada CAPS AD/I/II possui **vínculo assistencial próprio** com a identidade, criado explicitamente; a referência `capsReferencia` / USF territorial não cria vínculo clínico. A mesma pessoa pode ter vínculos simultâneos; alta em um vínculo não encerra outro.
3. Cada unidade mantém **seus** agendamentos, sessões/grupos, presenças/faltas, histórico, risco, alertas de busca ativa, responsável, estado e pendências. Sem acesso, consulta, atualização ou contagem de dados clínicos de outras unidades. A contagem clínica unificada *entre modalidades* mantém-se, mas exclusivamente dentro do **mesmo vínculo/unidade**. Nenhuma combinação entre USF e CAPS.
4. USF cadastrante mantém restrição à **própria USF**; CAPS também pode criar identidade e vínculo da própria unidade. Profissional, administrador e recepção podem **cadastrar** conforme permissões já existentes no fluxo; conceder `PROFISSIONAL` no POST sem ampliar edição, inativação, reativação nem autorizações clínicas. A alteração de *cadastro* do profissional nesta etapa não deve ser confundida com poder modificar atendimentos de terceiros.
5. USF e CAPS de referência podem estar não informados/não possuir referência (significados diferentes); não forçar referência fictícia. Identidade existente será reutilizada mediante correspondência segura de CPF/CNS; documento conflitante requer revisão protegida. Não revelar histórico ou vinculações de terceiros.
6. Mudança entre USFs conserva a mesma identidade, guarda histórico do vínculo anterior, inicia vínculo independente novo com operação autorizada/auditada; não transfere automaticamente o histórico.
7. Preservar auditorias clínicas, regras RF22/RNF17, P01–P05, 6.2A–G, histórico e responsabilidade assistencial. Não promover migrations históricas V1–V7; novas mudanças em migration aditiva com rollback/testes, e nunca atribuir origem clínica incerta sem evidência.

## Evidências no código anterior à correção

- `Paciente` mistura identidade e estado assistencial: `countFaltas`, `dataUltimaPresenca`, `classificacaoRisco`, `statusPaciente`, `tipoAcompanhamento`, `gatilhoVisitaAcionado`, `indicadoresPendentes`, `profissionalReferencia`, `usfReferencia`, `capsReferencia`.
- `EscopoUnidadeService.specificationPaciente`, `pacientePertenceAoEscopo`, `criterioPacienteAuditoria`: USF por `usfReferencia`; CAPS por `capsReferencia`. Isso faz a referência conceder visibilidade clínica, contra o requisito de vínculo explícito.
- `PacienteServiceImpl.save`: CPF/CNS são verificados globalmente e `validarAcesso` rejeita paciente já existente fora do escopo. Isso impede o aproveitamento da identidade em outra unidade. O preenchimento de USF alheia já é recusado no backend e deve continuar sendo.
- `HistoricoPacienteServiceImpl.consultar`: consulta `findByPacienteIdPublicoOrderByOcorridoEmDesc` sem filtro de unidade **após verificar só o acesso ao paciente**. Não é adequado quando o mesmo cadastro passar a ter múltiplos vínculos.
- `PacienteServiceImpl.recalcularAssiduidadePaciente`: reúne agendamentos e grupos de toda a identidade, e grava risco/contagem no `Paciente`; não atende separação por unidade.
- `PacienteListaDTO` e relatórios expõem projeções assistenciais do cadastro comum; precisam passar a projetar dados do vínculo.
- `IsolamentoUnidadeTests` (especialmente `unidadeDeterminaPacientesSemUsarProfissionalDeReferencia` e resumos de CAPS) formalizam o modelo antigo de acesso por referência. Não mascarar: novos testes devem identificar a divergência e os antigos só podem ser atualizados quando houver novo escopo implementado e demonstrado.
- `PacienteController` tinha POST autorizado somente para admin/recepção; Angular `podeCadastrarPaciente` e rota `pacientes/novo` bloqueavam profissional. Permissão específica ampliada nesta branch, mantendo PUT restrito.

## Reproduções e testes planejados (não declarar aprovados antes de Actions)

- Cadastro por PROFISSIONAL CAPS e USF de própria unidade => 201, registro recuperável; PUT pelo profissional => 403; POST fingindo USF alheia => 404 e nenhuma gravação.
- Identidade cadastrada por USF com `capsReferencia` informada => aparece na USF; **não** aparece no CAPS antes do vínculo explícito.
- CAPS vincula identidade existente por CPF/CNS exatos => não duplica pessoa; somente novo vínculo local; consultas e grupos locais podem operar.
- Paciente acompanhado em USF e CAPS com históricos divergentes: frequências, última presença, faltas, indicadores, busca ativa, encerrar/reativar, relatórios e histórico sempre locais, sem vazamentos por UUID, CPF/CNS, filtros, PDF/Excel, logs ou painéis.
- Mudança entre duas USFs: preservar histórico original e ausência de acesso cruzado; referência territorial diferente de vínculo e de transferência.
- Migrations sobre banco legado com registros potencialmente ambíguos: não escolher automaticamente unidade de um fato; preservar e sinalizar até revisão. Constraints de unicidade e concorrência para identidade/vínculo.
- Cadastro único em caso de CPF e CNS apontarem a identidades diferentes: não mesclar automaticamente, impedir operação e preservar dados.
- Testes de segurança: não autenticado 401; ação sem perfil 403; fora do escopo 404 conforme convenção atual; usuários sem unidade configurada falham fechados.

## Execução por etapas

**A — diagnóstico e reprodução:** inventário de entidades, consultas, rotas, contratos, testes de isolamento e migração; incluir cobertura da permissão PROFISSIONAL (primeira entrega desta branch).

**B — modelagem + migração:** introduzir identidade global e vínculo assistencial local sem mudar histórico; selecionar estratégia de migração aditiva e tratar registros ambíguos antes de codificar.

**C — backend:** filtros por vínculo, cadastramento/reuso de identidade, correções de indicadores/históricos, auditorias e transações.

**D — frontend:** buscas controladas, fluxos novo paciente/vincular existente, referências opcionais e listas locais.

**E — validação:** Maven verify + concorrência opt-in, build Angular, testes específicos e regressões; registrar falhas Angular antigas em separado, sem ocultá-las.

**Proibições:** nenhuma mudança clínica presumida, limpeza de dados antigos, bypass de escopo, vinculação automática por CAPS de referência, merge para main ou declaração de CI verde sem evidência.

## Resultado da primeira validação (21/11/2026)

- GitHub Actions #44865700834: **falhou por duas fixtures incompletas dos novos testes** (campos obrigatórios sexo/raça-cor); produção não foi alterada para acomodar dados inválidos.
- GitHub Actions #44866056347, commit `093dc1360cf49888cf02c9e5fc746dcf3b0a90cc`: **SUCCESS**: backend `verify` 1.021/1.021; concorrência adicional 12/12; Angular build aprovado; testes Angular dirigidos 4/4.
- Primeira entrega validada: `PROFISSIONAL` cadastra (`POST /api/pacientes`), mas não recebe acesso ao `PUT`; o backend preserva o controle de escopo da USF.

## Início da modelagem aditiva (não ativada nos fluxos clínicos)

- Nova `VinculoAssistencial` (`tb_vinculo_assistencial`) com `paciente_id`, unidade canônica `unidade_atuacao` e `unidade_chave` (USF:<id> ou CAPS_AD/CAPS_I/CAPS_II), índices de unicidade por identidade+unidade, versão e auditoria Envers. Inclui campos clínicos futuros por unidade sem mover os caches já utilizados pelos algoritmos de cálculo.
- `VinculoAssistencialRepository`: **uso interno**, não disponibilizar diretamente em API sem serviço autorizador.
- `VinculoAssistencialModelTests`: testa ausência de associação automática por referência, múltiplos vínculos da mesma pessoa, unicidade e invariantes.
- **ATENÇÃO:** a entidade recém-adicionada ainda não participa do cadastro, da consulta ou do histórico existente; não declarar isolamento concluído. Em bancos persistidos, `ddl-auto=validate` requer **migração aditiva validada antes da execução**; nenhuma migração de produção foi executada ou considerada segura nesta etapa.
- Etapa posterior obrigatória: detalhar estratégia de migração offline, inclusive tabelas Envers, vínculo de histórico, estados ambíguos e preenchimento sem presunção, então integrar serviços e APIs sem vazamento. Não iniciar produção antes disso.

## Revisão da CI após introduzir JPA/Envers

- Run #44866728859 (modelagem antes da V8): falha em bancos H2 persistidos de fixtures. Causa confirmada em logs: `Schema validation: missing table [tb_vinculo_assistencial]`. Cinco testes novos do vínculo passaram; frontend build e permissões passaram. Resultado global **vermelho** (1.026 tests, 8 failures, 7 errors). Os erros em testes de restauração/migração são encadeamentos de boot sem a nova tabela; não foram reinterpretados como problemas clínicos.
- A correção consiste na **V8 offline aditiva** `V8__vinculo_assistencial_sem_backfill.sql`, integrada ao `AgendaMigrationTool` pela localização Flyway já existente. Não executar em produção sem backup, teste de restauração, conferência do schema H2 usado no ambiente e revisão dos vínculos legados.
- O script cria `tb_vinculo_assistencial` e `tb_vinculo_assistencial_aud` com constraints de chave natural e integridade. **Não** executa backfill, não cria associações a partir de `capsReferencia` e não reclassifica pacientes.
- Tests com expectativa explícita do número de migrations passam a contar V8; novo teste confirma que a tabela é vazia e os registros antigos são preservados.
- **Aguardando GitHub Actions** do novo commit: não declarar CI aprovada até ver resultados. A estrutura é apenas aditiva, APIs clínicas legadas ainda não a utilizam.

## CI V8 aprovada e preparação do vínculo explícito

- Run #44867030449 no commit `11e1f88194b2203301b16d8915b86a101be2cad7`: **SUCESSO**: 1.027 testes Maven aprovados (0 falhas, 0 erros), mais 12/12 concorrência; build Angular e 4/4 permissões aprovados.
- O `DevConfig` está sob `@Profile("dev")`, contém cenários e pacientes sintéticos para demonstração; `application-dev.properties` usa H2 em memória e `ddl-auto=create`. Preservar esse conteúdo até adaptar os cenários ao modelo novo. A autorização para apagar dados fictícios **não** autoriza excluir fixtures históricas de regressão ou dados reais/persistidos.
- Preparação seguinte: `VinculoAssistencialService` interno tem criação explícita com lock do paciente e chave da unidade autenticada, busca idempotente por unidade e não deriva vínculo de `capsReferencia`. `VinculoAssistencialServiceTests` adiciona cenários por CAPS e USFs, sem endpoints novos. **Não** utilizar esse serviço para liberar novos acessos clínicos até a separação das consultas, grupos, cálculos e autorizações estar concluída e testada.
- Nova CI de serviço interno **ainda não verificada**; não interpretar a aprovação da V8 como aprovação desta entrega posterior.

## Pré-diagnóstico seguro dos cadastros existentes

- Execução #44867601812 do commit `3a9d1d08e53760e58c02817678aaa28140726bbb`: **SUCCESS**. Maven `verify`: 1.031/1.031, concorrência 12/12, Angular build e 4/4 permissões.
- Nova classe `CadastroUnicoPreflight` faz **somente leituras SQL** e entrega contagens, nunca CPF/CNS ou dados identificáveis. Informa repetições de CPF/CNS, ausência de documentos, referências CAPS sem vínculo explícito, agendamentos com responsável sem unidade configurada e histórico sem origem direta em atendimento/grupo (incluindo possíveis históricos manuais legítimos).
- O diagnóstico **não resolve duplicidades**, **não atribui unidade**, **não migra dados**, **não cria vínculos** e **não deve ser exposto por API**. Ausência de vínculo CAPS não implica falta de assistência; campo de referência não é comprovação de vínculo. As contagens são indícios para revisão humana, não diagnóstico de dados clínicos incorretos.
- Testes `CadastroUnicoPreflightTests` verificam preservação do banco, contagem de documentos repetidos, identificação de históricos sem origem direta e ausência de documentos pessoais no relatório. **Aguardar CI dessa entrega antes de avançar ao backfill e à migração clínica.**

## Diagnóstico de proveniência clínica — sem atribuição automática

- Execução GitHub Actions #44868812703 no commit `996309bbca424cc1f31174e394de011897e09ab0`: **SUCCESS**; backend 1.034 testes sem falhas, matriz de concorrência 12/12; build Angular e 4/4 testes de permissões.
- O diagnóstico `OrigemClinicaPreflight` é exclusivamente read-only e não é exposto como API. Retorna contagens de agendas com **indícios no vínculo do usuário atual**, históricos associados a atendimentos do mesmo paciente, históricos de grupos cuja sessão realizada tenha responsável explicitamente registrado, históricos sem fonte, inconsistências de paciente e sessões realizadas sem responsável confirmado.
- **A unidade atual do profissional não é prova de unidade histórica**. Mudanças de lotação e ausência de trilha temporal exigem reconciliação por evidência adicional. Profissional de referência ou `capsReferencia` não prova escopo clínico; coordenador atual não prova responsabilidade pela sessão antiga.
- Esse pré-diagnóstico não reatribui nenhum registro histórico, não altera `tb_paciente`/agendamentos/grupos/auditorias, não preenche `tb_vinculo_assistencial` nem concede acesso cruzado. Casos com múltiplas fontes, evidência ausente, ausência de responsabilidade e unidade histórica incerta são pendências de revisão.
- Testes `OrigemClinicaPreflightTests` cobrem snapshot inalterado, inconsistência de paciente histórico-agendamento, falta de unidade no usuário e histórico sem fonte. **Ainda dependem de validação CI**.
- **Etapa futura, somente após definição de evidência por registro:** estabelecer proveniência imutável da unidade nos novos eventos; elaborar migração aditiva de FKs locais e Envers, com registros inconclusivos preservados, antes de substituir filtros e cálculos clínicos.

## Identidade de unidade canônica — preparação para proveniência imutável

- CI #44869336924 (commit `b1a0b1c9751dba067237aa8893fad32c3779bac5`): **SUCCESS**; 1.038 testes backend, 12 concorrência, build Angular e 4 permissões.
- `ChaveUnidadeAssistencial` centraliza somente a **chave canônica do vínculo NOVO**: `USF:<id persistido>` ou `CAPS_AD`/`CAPS_I`/`CAPS_II`; é distinta dos rótulos de painel/auditoria já persistidos (`USF:<CNES>` e `CAPS:CAPS_*`). Não reescrever fatos antigos nem tentar converter automaticamente esses rótulos.
- `VinculoAssistencial` e seu serviço interno passaram a usar a função canônica; contratos de API, flags, consultas e eventos clínicos permanecem intactos.
- **Próxima etapa ainda NÃO implementada:** persistir a proveniência imutável e vínculo local em cada novo agendamento, grupo e histórico **no momento da criação**, com validações explícitas de unidade autenticada e vínculo de paciente. Antes de habilitar leitura por vínculo ou backfill, prever migração aditiva correspondente com Envers, testes de transferência, troca de lotação, histórico antigo pendente, responsabilidade de sessões e acesso cruzado.
- CI do refactor de chave canônica aguarda execução; não tratar aprovação anterior como validação dessa nova alteração.

## V9 — proveniência dos NOVOS agendamentos (aguarda validação da CI)

- CI #44869942310, commit `183bb0663f4b9804b0b76c203c28d91b89cb1c18`: **SUCCESS**, 1.041 testes backend, 12 concorrência, build Angular e 4 testes de permissão.
- Nova migração offline aditiva `V9__origem_agendamento_novos_registros.sql`: colunas `unidade_origem_chave` em `tb_agendamento` e `tb_agendamento_aud`. Nenhum backfill; `NULL` em registros legados significa proveniência ainda não comprovada.
- `Agendamento.registrarUnidadeDeOrigem` permite gravação uma só vez antes da primeira persistência, usando a unidade vinculada ao profissional operacional já validado; `AgendamentoServiceImpl.save` registra a origem do novo atendimento. Não modificar a lotação, auditoria e o histórico dos atendimentos existentes.
- `OrigemAgendamentoTests`: CAPS, duas USFs, divergência de profissional e unidade inválida. `AgendaLegacyMigrationTests`: verifica registros e revisões históricos ainda `NULL` após a V9.
- **Limites de escopo:** isto não torna os filtros clínicos baseados em `VinculoAssistencial`; não muda grupos, históricos, regras de faltas, busca ativa, transferência ou relatórios. A coluna não autoriza acesso por si só. O corte dos endpoints e cálculos exige fase própria com migração validada e testes de segurança.
- Não executar V9 sobre bancos persistidos reais sem backup e ensaio da ferramenta offline; `ddl-auto=validate` continua sem criação automática.

## V10 — proveniência dos NOVOS grupos e sessões, sem backfill

- CI #44870335553 (commit `637ca9768b6f211086a639a2a508fb9134e2f154`): **SUCCESS**; 1.045 testes Maven, 12 testes de concorrência, build Angular e 4 testes de permissões.
- `V10__origem_grupos_sessoes_novos_registros.sql`: adiciona `unidade_origem_chave` a `tb_grupo_terapeutico`, `tb_grupo_terapeutico_aud`, `tb_sessao_grupo` e `tb_sessao_grupo_aud`. Migração offline aditiva; nenhuma linha legada é modificada e registros antigos permanecem com NULL.
- Na criação de novo grupo, `GrupoTerapeuticoServiceImpl.criarGrupo` captura a origem do coordenador que já passou pelas validações e bloqueios atuais. Sessões novas herdam a chave imutável do grupo. Sessões novas de grupos **legados sem origem comprovada** continuam NULL, em vez de inferir pela lotação atual do coordenador.
- A transferência posterior de coordenação não reescreve a origem. Os campos são opcionais neste estágio de transição; **não autorizar acesso clínico com base exclusivamente neles** enquanto o sistema operar com escopo legado.
- Testes `OrigemGrupoSessaoTests` caracterizam origem, transferências e guarda de grupo legado. `AgendaLegacyMigrationTests` confere snapshots e nenhuma atribuição de origem na V10. CI da V10 ainda precisa ser verificada.
- Próximos passos, em entregas isoladas: origem imutável dos novos históricos vinculados a agendamentos/sessões; resolver inconsistências de escopo por vínculo e migração clínica somente mediante evidência suficiente; preservar relatórios, 6.2, auditoria e isolamento.

## V11 — proveniência de eventos novos do histórico clínico

- CI #44870735117, commit `563a3fc3f31d7825808617edda0ec1b28f575fd0`: **SUCCESS**, backend 1.050 testes, concorrência 12/12, build Angular e 4/4 permissões.
- Nova `V11__origem_historico_novos_eventos.sql` adiciona apenas a coluna `unidade_origem_chave` a `tb_historico_paciente` e `tb_historico_paciente_aud`. Não realiza backfill; fatos e revisões antigos preservam origem nula quando desconhecida.
- `HistoricoPaciente` ganhou captura de proveniência para eventos **novos**: agendamento/sessão usam a origem imutável do fato relacionado, nunca a lotação atual do profissional; evento manual sem fonte usa a unidade atual do executor na operação presente. Registro sem fonte comprovada mantém NULL; correções de frequência existentes não sobrescrevem a coluna.
- `HistoricoPacienteServiceImpl` foi integrado à captura na criação de histórico individual, de grupo e manual. `OrigemHistoricoNovoTests` e novo teste da migração V11 cobrem origem, preservação e invariantes.
- Ainda NÃO foram ativados filtros por vínculo, atribuição assistencial retroativa, acesso cruzado, migração do estado de risco/assiduidade ou mudanças de contratos de API. Esta entrega aguarda CI; sem CI verde não deve ser declarada validada.
