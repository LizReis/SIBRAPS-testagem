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

## Autorizador interno por vínculo local — implantação bloqueada até corte coordenado

- Execução #44871116028 (commit `dca4ffbb5ffd9ff4c7636ef2392d993211777da3`): **SUCCESS**; Maven `verify` 1.055/1.055; concorrência 12/12; Angular build e 4/4 permissões.
- `AutorizacaoVinculoAssistencialService` é uma camada **interna**, exclusivamente consultiva, sem novo endpoint. Exige identidade ligada explicitamente à unidade atual para localizar um vínculo clínico. A origem imutável do agendamento, sessão e histórico deve coincidir com a chave desse vínculo, sob pena de não encontrado.
- Paciente global e `capsReferencia` / `usfReferencia` não concedem leitura de fatos clínicos. Origem NULL de registros legados é **negada pela nova camada**, não convertida automaticamente da lotação atual.
- `AutorizacaoVinculoAssistencialServiceTests` caracteriza isolamento entre dois CAPS, ausência de vínculo, origem de outra unidade e fatos antigos sem origem. NÃO habilitar a nova camada apenas em uma rota: será necessário migração coordenada de filtros, indicadores, estado local, agendamento, grupos e relatórios para não ocultar fatos legítimos nem ampliar acesso.
- Testes desta nova camada **aguardam resultado GitHub Actions**; nenhum teste foi declarado aprovado antecipadamente.

## Fortalecimento do autorizador interno: vínculo, identidade e proveniência

- CI #44953493990 (commit `b9712a221edd3c1080bea627efc572ef5b3734c1`): **SUCCESS**; 1.062 testes de backend, 12 de concorrência, build Angular e 4 de permissão.
- Revisão posterior detectou que a primeira versão do autorizador interno verificava a chave da unidade, mas não conferia explicitamente se a entidade de vínculo retornada apontava para o ID do paciente pesquisado. A verificação foi adicionada como defesa em profundidade.
- `validarHistoricoLocal` agora confere que um histórico ligado a um agendamento aponta ao mesmo paciente da fonte; histórico associado a sessão valida também a origem do grupo da sessão. Exige coerência das fontes e evita dupla associação fato individual/grupo.
- Novos testes validam autorização positiva de um atendimento/histórico da própria unidade e recusam vínculos apontando a outra identidade. A integridade de `HistoricoPaciente.registrarOrigemDoAgendamento` já impede eventos novos para paciente divergente.
- A nova camada permanece **interna e inativa nas rotas HTTP**, aguardando migração/integração coordenada com histórico, listas, grupos e indicadores. Não acrescenta acesso clínico cruzado.
- **CI destas melhorias ainda não verificada**; não declarar aprovação sem consultar a execução correspondente.

## Testes com JPA/H2 real de isolamento local

- Execução #44954760795, commit `4d636101c2b71e2531143b839043a728a9aac01f`: **SUCCESS**; backend 1.066/1.066, concorrência 12/12, Angular build e permissões 4/4.
- A camada interna de autorização agora exige, além de vínculo local e proveniência do grupo/sessão, participação explícita do paciente para consultar sessão e histórico de participação. Identidade global ou vínculo local da pessoa não bastam para ler sessões nas quais ela não está inscrita.
- Teste `AutorizacaoVinculoPersistenciaTests` usa repositórios JPA reais e banco H2 de teste com paciente sintético e duas unidades CAPS: referência sem vínculo, dois vínculos sem mistura de agendas/históricos, participação efetiva e origem legada NULL. Apenas o contexto do usuário foi simulado no `EscopoUnidadeService`; **não equivale a um teste HTTP/JWT de ponta a ponta**.
- Teste unitário adicional confirma negação da sessão sem participação e concessão com participação comprovada. Camada permanece fora dos endpoints.
- **CI destes testes ainda pendente**. Somente avançar para mudanças no escopo HTTP após validar cobertura, planejamento da migração clínica e operações de cadastro/vinculação para todos os caminhos.

## Leitura clinica local — fase interna de caracterizacao (sem HTTP)

- CI #44958692062, commit `6bb874fce8b9ca9c0ae373afc20c03c8bd8f22b5`: **SUCCESS**; 1.071 testes backend, 12 concorrência, build Angular e 4 testes de permissões.
- `ConsultaClinicaLocalRepository` implementa projeções JPA internas por paciente + origem de unidade, sem retornar dados pessoais: agendas, eventos históricos coerentes com sua origem e sessões em que a pessoa é de fato participante. Eventos sem origem e eventos de outra unidade ficam fora; a consulta de históricos valida coerência entre fato individual/sessão e paciente.
- `LeituraClinicaLocalService` exige primeiro vínculo explícito na unidade do usuário logado, usando `AutorizacaoVinculoAssistencialService.exigirVinculoLocal`; a chave NÃO é aceita de input de cliente nem derivada de referência cadastral.
- Novos testes com persistência H2/JPA real criam identidade única, dois CAPS com vínculos e fatos distintos, registros antigos sem origem, e verificam que cada leitura só devolve identificadores da própria unidade. A consulta não é exposta em endpoint e não substitui ainda as consultas/relatórios existentes.
- **CI desta entrega está pendente.** Antes do corte: testar JWT/HTTP 401/403/404, migração de vínculos e estados clínicos por unidade, todas as consultas e relatórios, indicadores, grupos e concorrência. Nunca liberar histórico de outra unidade pela identidade global.

## Testes de identidade compartilhada sem compartilhamento clínico (USF e CAPS)

- CI #44961105102, commit `fb935857d01c6ef97dd14921602b1d8849e19dcb`: **SUCCESS** — 1.073 testes Maven, 12 testes adicionais de concorrência, build Angular e 4 testes direcionados de permissões.
- `LeituraClinicaLocalUsfCapsTests` cobre JPA/H2 real com **um cadastro** e vínculos da USF e CAPS AD, cada um com atendimentos, históricos e registros de busca ativa separados; participação em grupo apenas no CAPS. Consulta interna da USF não pode devolver fatos do CAPS e vice-versa.
- Cenário de segunda USF: referência territorial aponta a USF B, mas existe somente vínculo explícito com a USF A. Antes do vínculo local, B recebe não encontrado; após o vínculo, lista local começa sem históricos/atendimentos da A. A identidade `Paciente` não se duplica.
- **Esses testes não alteram o sistema de produção, nem ativam o novo escopo nos endpoints, nem substituem validações HTTP/JWT**. Indicadores e histórico legados ainda dependem de revisão coordenada.
- Nova validação no GitHub Actions **pendente**. Em caso de falha, corrigir fixtures sem flexibilizar a regra de isolamento.

## Ajuste da consulta de históricos manuais — regressão USF/CAPS

- CI #44962156503 (commit `0b1f91b8ed71f78ac2969c7f3cd583d0a007643c`): **FAILURE** apenas no novo cenário `LeituraClinicaLocalUsfCapsTests.usfECapsCompartilhamSomenteIdentidadeNaoAtendimentosNemHistoricos`, linha 175. Dos 1.075 testes backend, 1.074 passaram e 1 falhou; Angular build e permissões passaram; concorrência não executada.
- Sintoma: consulta de histórico da USF não incluía os dois eventos locais esperados (consulta e busca ativa manual). Hipótese técnica: navegação implícita `a.paciente.idPublico` sobre alias de `LEFT JOIN` transforma associação opcional em junção restritiva, ocultando eventos sem agendamento.
- `ConsultaClinicaLocalRepository.historicos` foi reescrito com três ramos explícitos mutuamente exclusivos: evento manual sem fonte, evento individual com paciente/origem coerentes, evento de sessão com grupo e participante corretos. Usam `exists` correlacionado para fatos com fonte, evitando junções opcionais convertidas em obrigatórias.
- Não flexibilizar filtro de unidade, integridade do paciente nem exigência de vínculo explícito. **Nova CI ainda não validada**; falha anterior permanece documentada para rastreabilidade.

## Inventário local das frequências — sem alterar as regras clínicas

- GitHub Actions #44965553712, commit `78b71a8c03271aeb36aa62bd8465c12f1628a90f`: **SUCCESS**; backend 1.075/1.075, concorrência 12/12, Angular build e permissões 4/4. A consulta interna passou a preservar históricos manuais locais sem misturar dados de USF/CAPS.
- O cálculo atual em `PacienteServiceImpl.recalcularAssiduidadePaciente` ainda lê `agendamentoRepository.findByPaciente` e `participanteGrupoRepository.findByPaciente`, combinando unidades diferentes e gravando `countFaltas`, `dataUltimaPresenca`, `indicadoresPendentes`, `gatilhoVisitaAcionado` e `classificacaoRisco` no cadastro global. **Não usar esse algoritmo global como indicador clínico correto após o cadastro único multiunidade.**
- `InventarioFrequenciasVinculoService` é um preparatório **somente leitura**, sem endpoints. Primeiro exige vínculo explícito com a unidade autenticada; inventaria somente presenças/faltas comprovadas em agendamentos locais e sessões realizadas com origem local coerente, separando pendências de evidência. Não calcula faltas consecutivas, última presença, risco, visitas domiciliares ou busca ativa e não modifica dados.
- `LeituraClinicaLocalUsfCapsTests` recebeu cenários com frequências diferentes na USF e no CAPS: as contagens devem permanecer independentes, o risco global fica intacto, a ausência de vínculo recusa inventário. Nenhum backfill é feito.
- **CI pendente** para o inventário. Em seguida, antes de implementar risco/assiduidade locais: definir cronologia dos eventos por vínculo, ambiguidade dos fatos antigos, política de grupo x individual dentro da mesma unidade, estados sem evidência, recálculo retroativo, reserva cancelada, gatilho de visita e histórico auditável.

## Linha do tempo de fatos locais — sem mudança nos indicadores

- GitHub Actions #44968406227, commit `86dc07bc3159dac28b7144225919c7315e7ffcc9`: **SUCCESS**, 1.077 testes Maven, 12 de concorrência, build Angular e 4 testes de permissões.
- `LinhaDoTempoFrequenciasVinculoService` oferece diagnóstico estritamente read-only, sem API. Exige vínculo explícito e retorna eventos individuais e de sessões realizadas com proveniência local, ordenados por data/hora, origem e ID; eventos legados sem unidade comprovada ficam **fora dos eventos locais**, mas são contados separadamente como indícios de ambiguidade para revisão humana.
- A ausência de origem é tratada como fato histórico de identidade ainda sem distribuição por vínculo, **nunca** como fato da unidade solicitante. Assim, é possível sinalizar dúvida sem conceder acesso clínico cruzado. A consulta não transforma contagem em faltas consecutivas, status de risco ou gatilho de visita.
- `LeituraClinicaLocalUsfCapsTests` inclui cronologia com frequências distintas USF/CAPS e evento legado não atribuído, além de sessão realizada da própria USF com presença e ausência de eventos no CAPS. Não altera campos de `Paciente`, `VinculoAssistencial` ou dados históricos.
- **CI dessa entrega está pendente.** Antes de ativar recálculo por vínculo, confrontar o desempate cronológico com a política clínica e validar historicamente vínculos, pendências de migração, correções retroativas, grupo + individual, eventos sem origem, frequência em reserva cancelada e auditoria.

## Cálculo diagnóstico de faltas consecutivas por vínculo — sem efeitos clínicos

- CI #45043374700 (commit `8fe766e20c404095b6d1ada957df91aaae1a2418`): **SUCCESS**, 1.079 testes Maven, 12 de concorrência, build Angular e 4 testes de permissões.
- `DiagnosticoFaltasConsecutivasVinculoService` usa exclusivamente a linha do tempo interna filtrada por vínculo e computa faltas consecutivas e última presença em modo **somente leitura**, sem atualizar paciente ou vínculo, sem modificar risco, busca ativa, visita domiciliar, painel, endpoints ou histórico de auditoria.
- Presença zera a sequência, falta incrementa; mistura eventos individuais e grupais somente da mesma unidade, seguindo a ordenação legada (individual antes de grupo em empate) para permitir comparação. Correções retroativas são refletidas pela nova cronologia no momento da consulta.
- Se o diagnóstico informa fatos de frequência sem origem assistencial comprovada, o resultado de **faltas conclusivas fica vazio** e a necessidade de revisão é expressa. Não interpretar sequência local parcial como definitiva nem mover registros com base no profissional atual ou em `capsReferencia`.
- `DiagnosticoFaltasConsecutivasVinculoTests` caracteriza sequência de três faltas, correção retroativa, mistura de modalidades, pendência histórica e estado sem registros. CI **ainda pendente** desta implementação.
- **Antes de ativar os indicadores clínicos oficiais**, realizar confronto com `PacienteServiceImpl`, regras de risco (incluindo dias ausentes), visitas, reserva cancelada, pendências, responsabilidade, autorização HTTP e estados por vínculo. Não substituir a política clínica com esta caracterização isolada.

## Comparação de risco por vínculo — somente caracterização

- Execução #45045192407 (commit `ce13b2cc465c2dd24612cb85c6b5f9fb41dd5c33`): **SUCCESS**, 1.084 testes backend + 12 concorrência, Angular build e 4 permissões.
- `ComparadorPoliticaRiscoVinculo`: função pura, sem persistência, sem alertas e sem endpoints, que reproduz os limiares já existentes: 2/3 faltas consecutivas (amarelo/vermelho), 15/30 dias sem presença em grupo, 60/120 dias para individual, somente `>` para os limiares de dias. `AMBOS` conserva regra de grupo. `INATIVO` permanece verde para diagnósticos conclusivos.
- Exige diagnóstico local comprovado, status e tipo de acompanhamento **do vínculo**, indicador de pendência conhecido e data de referência; com informação ausente ou origem histórica inconclusiva, devolve resultado não conclusivo. Não preenche defaults clínicos, não replica risco do cadastro global e não gera histórico ou busca ativa.
- Testes `ComparadorPoliticaRiscoVinculoTests` cobrem faixas de faltas e dias, empate na fronteira temporal, correção retroativa e bloqueio por pendência. **CI desta comparação ainda não verificada**.
- Antes de ativar: definir migração confiável dos campos de estado local, tratamento do tipo de acompanhamento, elegibilidade do vínculo e dos fatos migrados, políticas de visita e busca ativa, testes HTTP/JWT e concorrência transacional de cada unidade.

## Comparação diagnóstica de visita domiciliar por vínculo

- CI #45045993053 (commit `103c64df14e5c11759a3b526ae034b378ca80d5e`): **SUCCESS**; 1.090 testes Maven e 12 adicionais de concorrência, build Angular e 4 testes direcionados de permissão.
- `ComparadorPoliticaVisitaVinculo` não altera a `PoliticaVisitaDomiciliar` vigente. O comparador puro restringe os fatos ao vínculo informado **por camada autorizada**, exige proveniência da remarcação e do agendamento original na mesma unidade e usa a última presença diagnosticada localmente. Fatos de frequência com origem ausente, migração inconclusiva e encadeamento de remarcação incoerente geram resultado **indeterminado**, nunca alerta automático.
- `ComparadorPoliticaVisitaVinculoTests` caracteriza: duas faltas na mesma unidade acionam a regra; presença posterior descaracteriza; remarcação de outra unidade não transporta falta clínica; dado legado sem origem exige revisão; sem agendamento original ou com paciente diferente não produz conclusão; diagnóstico pendente não ativa nem limpa alerta.
- **Limitação importante:** o comparador é um diagnóstico puro para fatos previamente obtidos em contexto autorizado; ele não deve receber uma chave de unidade diretamente do cliente ou uma listagem global por endpoint HTTP. Ainda não pode ser conectado aos serviços oficiais nem usado para definir alertas, porque o acesso e os indicadores legados não foram migrados para `VinculoAssistencial`.
- **CI da comparação de visita ainda não validada**. Não declarar conclusão da etapa até avaliar o resultado e eventuais regressões.

## Seleção diagnóstica da busca ativa por vínculo (sem conexão ao painel)

- GitHub Actions #45047370796, commit `3b4ee32d0b2b1f18a0120ed425c3edc015ed9f9a`: **SUCCESS**. Backend Maven 1.096/1.096 testes; concorrência 12/12; build Angular e 4 testes de permissão.
- `PacienteServiceImpl.listarPacientesEmBuscaAtiva` continua usando `Paciente` global, `EscopoUnidadeService.specificationPaciente` legado e recalculando risco sob demanda. `MonitoramentoBuscaAtiva` e relatórios/painel também não migraram. Não ativar leitura por vínculo parcialmente.
- `ComparadorSelecaoBuscaAtivaVinculo` é **função pura de diagnóstico**, sem HTTP, sem leitura de registros e sem efeitos. Recebe apenas dados do vínculo local previamente autorizado: status, classificação de risco diagnóstica, indicador de pendência e visita confirmada. Reproduz a seleção vigente: vínculo ativo com risco vermelho, pendência de revisão OU visita confirmada, inclusive se o risco for verde. Pendência precede demais motivos.
- Se dados obrigatórios forem desconhecidos, o resultado é `INDETERMINADA` em vez de falso positivo/negativo. `NAO_INCLUIR` por vínculo não ativo, ou sem critérios comprovados. A determinação de situação de rua, ponto de referência e endereço continua tarefa futura de apresentação com autorização por unidade.
- `ComparadorSelecaoBuscaAtivaVinculoTests` cobre critérios de inclusão, prioridade de pendência, estado não ativo e dados insuficientes. **CI desta entrega ainda pendente**. Não há mudança no risco oficial, lista de busca ativa, visitas, alerta ou dados clínicos.
- Próximas fases: migrar com segurança status/tipo/indicadores para vínculo, migrar dados históricos com proveniência validada, integrar recalculo sob transação, resolver ambiguidade do legado, testar APIs HTTP/JWT e autorização para cada unidade e relatório antes do corte.

## Prontidão para migração do estado clínico local — diagnóstico sem escrita

- Execução #45049187067 (commit `89e548a086d11dc6b203f15341c8eaaa96546a05`): **SUCCESS**. Maven 1.102 testes, concorrência 12/12, Angular build e 4/4 testes de permissões.
- O vínculo já tem colunas para `statusPaciente`, `tipoAcompanhamento`, `countFaltas`, `dataUltimaPresenca`, `classificacaoRisco`, `indicadoresPendentes` e `gatilhoVisitaAcionado`, mas **não existe migração validada dos valores clínicos legados para essas colunas**. Não copiar os campos globais de `Paciente` indistintamente para USFs/CAPS.
- `DiagnosticoProntidaoEstadoVinculoService` exige vínculo autorizado, compara apenas atributos locais do vínculo com a sequência comprovada e retorna classificações diagnósticas: origem histórica incerta, estado local incompleto, contagem divergente e coerência somente para testes. **Não altera nem popula registros**. Valores ausentes permanecem ausentes; status padrão criado com o vínculo não constitui comprovação de estado clínico histórico.
- `DiagnosticoProntidaoEstadoVinculoTests` cobre falta de dados, incerteza histórica, divergência de faltas e coerência exclusivamente diagnóstica. Não ativa acesso HTTP, cálculos oficiais, busca ativa, relatórios, visitas domiciliares nem a etapa 6.3.
- **A nova CI ainda não foi verificada**. Antes de qualquer backfill/corte, definir um procedimento auditável de revisão humana de origem clínica e estado de cada vínculo, respaldado por evidências, além de ensaios com backups, isolamento por unidade e testes completos de regressão.

## Conciliação de proveniência assistencial — triagem sem escrita

- GitHub Actions #45056946426 (commit `e94bd416b89f159a9c111e559a935ca2da5f3062`): **SUCCESS**, backend 1.106/1.106 testes, concorrência 12/12, build Angular e 4 testes de permissões.
- `ClassificadorEvidenciaClinicaLocal` realiza **somente avaliação pura de metadados**, sem consultar ou alterar banco, sem endpoint, sem identificar paciente, sem conceder acesso. Deve receber origem do evento, origem da fonte, tipo, integridade do paciente/participação e a unidade já autorizada pelo processo interno.
- Saídas possíveis: `FORA_DA_UNIDADE`, `REVISAR_ORIGEM_DESCONHECIDA`, `REVISAR_FONTE_SEM_ORIGEM`, `REVISAR_ORIGENS_CONFLITANTES`, `REVISAR_INTEGRIDADE_DA_FONTE`, `COMPATIVEL_SOMENTE_PARA_CONFERENCIA`. Até o caso compatível **não é autorização de migração**; revisão humana e evidência/auditoria continuam obrigatórias.
- `ClassificadorEvidenciaClinicaLocalTests` verifica 8 cenários, incluindo registro sem origem, histórico manual, paciente diferente ou sem participação no grupo, fonte ausente, unidade cruzada e ausência de unidade autorizada.
- **Procedimento planejado, não executado:** preservar backup verificável antes de qualquer migração real; inventariar apenas contagens agregadas e inconsistências; exigir vínculo explícito; validar unidade histórica por documentos/registro de origem assinados, jamais apenas lotação atual ou `capsReferencia/usfReferencia`; registrar decisão do revisor e justificativa, manter fatos ambíguos pendentes; executar migração controlada em ambiente de ensaio, comparar totais/identidades/auditorias e regressões de permissão por USF/CAPS; somente depois avaliar corte de endpoints. Não realizar backfill automático, não deletar históricos ou exemplos.
- **CI da nova triagem ainda pendente.** O classificador não foi ligado aos serviços assistenciais.

## Preparação de solicitação justificada de revisão de origem (sem persistência)

- CI #45057412596, commit `f411bea6673febc7e8c82a78a87014013b20e58b`: **SUCCESS**, 1.114 testes Maven, 12 de concorrência, build Angular e 4 testes de permissões.
- `PreparacaoRevisaoProvenienciaService`: serviço interno **read-only** exige vínculo assistencial explícito com a unidade autenticada, classifica metadados de proveniência previamente verificados por processo autorizado e valida justificativa com `PoliticaJustificativaCorrecao` existente (10–2.000 caracteres explicativos). Apenas retorna rascunho em memória `RASCUNHO_NAO_PERSISTIDO`.
- Classificações de outra unidade e origens conflitantes são recusadas nessa via local (`ResourceNotFoundException`). Origem antiga desconhecida mantém a marcação de revisão; nunca é aprovada por uma justificativa isolada. Justificativa não constitui comprovação clínica.
- `PreparacaoRevisaoProvenienciaServiceTests` cobre rascunho manual local, origem ausente, unidade estrangeira/conflitante, ausência de vínculo e justificativa inválida. Sem tabelas novas, sem alterações de registro clínico, sem endpoint e sem integração com dashboards ou relatórios.
- **LIMITAÇÃO:** ainda não existe ticket gravado, auditoria de deliberação, revisor independente ou verificação segura do ID do fato. O processo futuro deve fazer verificação server-side de autoria, papel, fonte, paciente, unidade, versão otimista, comprovantes e decisões justificadas dentro da unidade antes de qualquer alteração. Dados antigos sem origem continuam intactos.
- **Nova CI ainda pendente**; não considerar entregue o painel de pendências da Etapa 6.3.

## V12 — estrutura de solicitações de revisão de proveniência, SEM ativação

- GitHub Actions #45064166051 (commit `c6e3bed36a7b859ffc205b8c42b5cabca7e37f15`): **SUCCESS**, backend 1.119/1.119, concorrência 12/12, build Angular e permissões 4/4.
- Migração aditiva **offline** `V12__pendencia_revisao_proveniencia_sem_backfill.sql`: adiciona uma restrição de unicidade composta ao vínculo para permitir FK de unidade/paciente; cria tabela `tb_pendencia_revisao_proveniencia` e audit Envers. Nenhuma linha histórica é inventada, recategorizada, apagada ou migrada; a tabela inicia vazia.
- A solicitação, se criada futuramente por um fluxo autorizado, terá referência ao **vínculo explícito** de paciente+unidade, tipo e ID técnico do fato, tipo de evidência, justificativa, autor, perfil, instante e estado obrigatoriamente `PENDENTE`. FK composta evita atribuir uma identidade/unidade a vínculo de outra; CHECK restringe estado, papel, justificativa e tipos. Trigger append-only impede edição/exclusão por DML. O ID do fato em si **ainda não tem FK tipada**: precisa ser validado pela operação futura, que não existe nesta etapa.
- A entidade `PendenciaRevisaoProveniencia` é imutável, sem método público de criação nem repositório/serviço de gravação. **Não existe novo endpoint, painel, aprovação/rejeição nem aplicação automática sobre origem ou indicadores.** Uma decisão futura exigirá tabela de auditoria append-only própria, verificação de autorização e correspondência da origem com prova documental, além de controle de concorrência.
- Testes de migração `PendenciaProvenienciaMigrationTests` verificam preservação do legado após V12, inicialização vazia, FK de paciente+unidade, perfil/estado restritos e proibição de update/delete em banco H2 persistido sintético. Contagens de migrações nos testes legados foram ajustadas para 12.
- **CI da V12 ainda não validada.** Antes de qualquer execução da ferramenta offline em ambiente com dados: backup verificado + restauração ensaiada; não rodar migration tool automaticamente no startup, preservar todos os registros clínicos originais, executar apenas após validação do schema e autorização explícita.

## Checkpoint de seguranca das leituras clinicas por vinculo — 23/11/2026

- Ultima execucao confirmada: GitHub Actions #45228061210, commit `0f65eacbb9b286024e569707f56a05a1160e591d`, **SUCCESS**. Backend 1.233/1.233 testes, concorrencia 12/12, 28 testes Angular direcionados e build Angular de producao aprovados.
- Nos commits anteriores desta mesma branch, foram validadas barreiras adicionais para a origem de grupos/sessoes, operacoes em lote, inscricoes, remocoes, relatorios de frequencia, agendas por ID e listas internas, historias clinicas e remarcacoes encadeadas. O autorizador local exige vinculo explicito, unidade de origem coerente e situacao/frequencia canonicas para todos os elos da cadeia.
- A regra de transicao permanece: **somente dados cadastrais globais sao compartilhados**; fatos, frequencias, historias e indicadores nao sao compartilhados por causa da identidade global ou referencia territorial. Eventos antigos com origem desconhecida nao recebem backfill nem acesso por inferencia.
- Esta entrega acrescenta testes HTTP em `HistoricoClinicoLocalHttpTests` e `AgendamentosClinicosLocaisHttpTests`: raiz de remarcacao com origem local conhecida, mas estado canonico incompleto, nao deve vazar dados para agenda/historico local, preservando os fatos locais comprovados e os registros pendentes.
- **CI desta entrega ainda nao validada.** Nao ha mudanca de regra clinica ou migracao em producao. A Etapa 6.3 continua SEM corte para o modelo definitivo por vinculo: faltam reconciliacao humana auditavel, avaliacao das atribuicoes antigas, transacao dos indicadores locais e revisao dos paineis/relatorios legados. Nao inferir unidade de origem pelos campos `capsReferencia`, `usfReferencia` ou lotacao atual.

## Validacao e correcao do teste HTTP de historico local — 23/11/2026

- Execucao #45229663019 no commit `947fef5021a7c341cf2ecb200dec5849947bd08f`: **FAILURE**, backend 1.235 testes, 0 falhas de assertivas e 1 erro. Jobs Angular build e permissoes passaram.
- A falha foi exclusivamente no teste `HistoricoClinicoLocalHttpTests.historicoNaoContaFatoLocalComRaizDeRemarcacaoPendente`, que procurava o nome de campo inexistente `totalConsultas`; o contrato real de `HistoricoClinicoLocalDTO` define `totalConsultasAgendadas`.
- Ajustado o teste para verificar o campo do DTO existente, preservando a validacao de um evento local legitimo e a exclusao da cadeia pendente.
- Nenhum codigo de producao, dado clinico, migracao, indicador ou autorizacao foi alterado nesta correcao. A nova CI ainda precisa confirmar a aprovacao.
 
## Fila interna de revisao: solicitacoes abertas versus decisoes V13 — 23/11/2026

- Execucao #45230943664, commit `1c1c6aadb2641d7554b29b8b566fd8fdc60ffe0f`: **SUCCESS**, backend 1.235/1.235, concorrencia 12/12, build Angular e 28 testes direcionados aprovados. Corrigiu somente o nome do campo de um teste HTTP de historico local.
- A estrutura V12 mantem a solicitacao em `PENDENTE` para sempre e V13 registra a deliberacao em evento append-only separado; por isso, consultar apenas `estado=PENDENTE` devolvia tambem solicitacoes ja decididas.
- Nesta alteracao, leituras internas da fila geral e por paciente usam `NOT EXISTS` de decisao V13, sempre condicionadas a unidade e ao paciente local quando aplicavel. O detalhe administrativo da fila aberta usa consulta propria. A leitura original de ticket para o **comando de decisao** foi preservada, para manter a verificacao de duplicidade e a concorrencia existente.
- Novo teste integrado H2 com paciente vinculado a CAPS AD e CAPS II verifica que registrar decisao **REJEITAR** em uma solicitacao AD retira somente esse ticket das filas de abertas, que outras pendencias ficam visiveis apenas na propria unidade, e que o registro original permanece imutavel com `estado=PENDENTE`. Aprovacao e rejeicao produzem igualmente eventos, nao correcoes clinicas automaticas.
- **CI desta alteracao ainda nao validada.** Nenhuma rota HTTP, painel Angular, migration, reatribuicao de origem, alteracao de indicador ou exclusao foi ativada. Nao confundir fila de tickets abertos com historico completo das decisoes auditadas.
 
## Verificacao integrada das decisoes e da fila aberta — 23/11/2026

- GitHub Actions #45232725282 (commit `c6945a1e58737fb0b46a4f9869ebc8013e75f5bd`): **SUCCESS**, 1.236 testes Maven, 12 testes adicionais de concorrencia, 28 testes Angular direcionados e build de producao aprovados.
- A integracao V13 ja persiste eventos de aprovacao/rejeicao em transacoes JPA com auditoria Envers, sem modificar as solicitacoes V12, fatos clinicos ou unidade historica. A fila aberta filtra tickets com decisao por `NOT EXISTS`, sem alterar a imutabilidade dos eventos.
- Esta entrega adiciona verificacoes de fluxo real na classe `DecisaoRevisaoPersistenciaAuditoriaConcorrenciaIntegrationTests`: depois de `APROVAR` ou `REJEITAR`, o ticket fica invisivel nas filas abertas por unidade e paciente; a decisao permanece auditada; o estado original da solicitacao e o fato clinico permanecem intactos. A corrida entre duas decisoes continua produzindo um unico evento e a fila nao pode apresentar o ticket decidido novamente.
- **CI desta entrega pendente**. Apenas testes e documentacao foram alterados, sem novo controller, painel Angular, migration ou comando de mudanca de proveniencia. A migração de indicadores, reconciliação humana dos fatos de origem antiga indeterminada e corte de endpoints permanecem pendentes na Etapa 6.3.
 
## Leitura interna segura das decisoes V13 — 23/11/2026

- Execucao #45233939208, commit `4b477257f299a5d3800e6041c6c10181a7d9e06e`: **SUCCESS**, 1.237 testes backend, 12 testes de concorrencia, 28 direcionados Angular e build Angular de producao aprovados.
- O fluxo de decisao real e a retirada do ticket da fila aberta foram validados. A solicitacao original V12 continua imutavel (`PENDENTE`) e a decisao V13 permanece auditada separadamente, sem alteracao automatica de proveniencia ou historicos.
- Esta entrega adiciona `ConsultaDecisaoRevisaoProvenienciaService`, via **interna e read-only**, separada da fila aberta. A consulta recebe paciente publico e ticket, exige perfil ADMINISTRADOR da unidade autenticada, vinculo assistencial explicito e correspondencia do paciente/unidade do evento persistido antes de devolver um DTO minimo com decisao, justificativa e data.
- Testes integrados com H2 verificam aprovacao, rejeicao, acesso negado a outra unidade, recusa de perfil profissional, ticket inexistente e preservacao de dados originais. Nao expor detalhes de revisor nem entidades JPA na resposta.
- **CI deste commit ainda nao validada**. Nenhuma nova rota HTTP, painel de Relatorios, migration, reatribuicao automatica ou atualizacao de indicadores foi ativada. A etapa 6.3 continua em preparacao para posterior corte coordenado.
