# SIBRAPS — Relatório técnico da Etapa 9

## 1. Resumo executivo

A Etapa 9 alinhou o Angular 21, incorporou o candidato Maven para Java 21 e Spring Boot 4 e endureceu a autenticação, a configuração HTTP e o ambiente de demonstração. O npm passou de 48 ocorrências para 16 moderadas, todas no inventário com ferramentas de desenvolvimento. A consulta OSV do POM versionado encontrou zero correspondências em 190 coordenadas Maven. A regressão consolidada passou em todos os jobs. A liberação para produção depende da homologação humana, da infraestrutura e do banco definitivo.

## 2. Rastreabilidade

| Referência | Commit |
|---|---|
| Base funcional da Etapa 8 | `9cc73d855ef1857d5a7873b595d9dcb52bb99111` |
| Início da Etapa 9 consolidada | `48f86faa3ddc8098672618853f95ff0c0618ccda` |
| Dependências e segurança operacional | `802180a40edeab3e4f5fd589860cde82b02a4812` |
| Credenciais da demonstração | `d75d119b58dd266e5deae67291197b7b75164a81` |
| Commit final de código | `3c8d9f03b7a694375f025088b5f054638ced2cae` |

Branch `correcao-cadastro-unico-isolamento`. O commit que acompanha este documento registra a evidência após a execução da CI.

## 3. Dependências resolvidas

| Família ou componente | Antes | Depois |
|---|---|---|
| Angular Common, Compiler, Core, Forms, Platform Browser, Router e compiler-cli | 21.2.15 | 21.2.26 |
| Angular CLI e Build | 21.2.13 | 21.2.31 |
| TypeScript / RxJS / jsdom | 5.9.3 / 7.8.2 / 27.4.0 | 5.9.3 / 7.8.2 / 27.4.0 |
| Vitest / tslib | 4.1.8 / 2.8.1 | 4.1.12 / 2.9.1 |
| Bootstrap e Bootstrap Icons | 5.3.8 / 1.13.1 | 5.3.8 / 1.13.1 |
| Spring Boot / Framework / Security | 4.0.6 / 7.0.7 / 7.0.5 | 4.0.8 / 7.0.9 / 7.0.7 |
| Hibernate ORM / Flyway | 7.2.12.Final / 11.14.1 | 7.2.24.Final / 11.14.1 |
| Tomcat | 11.0.21 | 11.0.25 |
| Jackson 2 / 3 | 2.21.2 / 3.1.2 | 2.21.7 / 3.1.7 |
| Logback / Log4j | 1.5.32 / 2.25.4 | 1.5.34 / 2.26.1 |
| Batik / OpenHTMLToPDF SVG | 1.14 / 1.0.10 | 1.17 / 1.0.10 |
| Xalan e serializer | 2.7.2, transitivos de Batik | 2.7.3 no gerenciamento do candidato. Batik 1.17 já dispensa ambos na árvore resolvida. |
| Springdoc / Swagger UI | 2.3.0 / 5.10.3 | 3.0.3 / 5.32.2 |

O POM candidato foi reproduzido com o script versionado. O BOM do Boot governa as famílias Spring e Hibernate. As propriedades do BOM alinham Tomcat, Jackson e logging, e o gerenciamento explícito alinha a família Batik. Springdoc 3.0.3 atende à linha Spring Boot 4.0. As bibliotecas de PDF, SVG e planilha continuaram no POM.

## 4. Auditorias antes e depois

| Inventário | Antes | Depois |
|---|---|---|
| npm completo | 48 · 3 críticas, 21 altas, 22 moderadas, 2 baixas | 16 moderadas |
| npm sem desenvolvimento | 6 · 4 altas, 2 moderadas | 0 |
| Maven / OSV | 63 IDs em 19 pacotes · 10 críticos, 20 altos, 28 moderados, 5 baixos | 0 correspondências em 190 coordenadas |

A resolução limpa partiu do manifesto Angular alinhado, em diretório isolado, sem aproveitar a árvore anterior. `npm ci --strict-peer-deps` e `npm ls --all` passaram. As contagens npm incluem pacotes sinalizados por dependências transitivas. A consulta OSV compara coordenadas e versões do classpath resolvido. Ambos são retratos das bases de avisos consultadas em 28/11/2026. A consulta OSV foi concluída na CI após o proxy do ambiente local recusar o acesso direto à API.

## 5. Vulnerabilidades residuais

| Origem | Aplicabilidade | Versões com correção fora da faixa afetada |
|---|---|---|
| `glob@13.0.7` · GHSA-3g3m-2fjr-6pw4 · moderada | Caminhada de arquivos com cancelamento na cadeia de ferramentas do CLI. [Aviso](https://github.com/advisories/GHSA-3g3m-2fjr-6pw4) | `glob >=14.0.2` |
| `minimatch@10.2.6` · GHSA-g3hv-75wg-m249 · moderada | Custo de processamento de padrões em ferramentas de arquivos e empacotamento. [Aviso](https://github.com/advisories/GHSA-g3hv-75wg-m249) | `minimatch >=11.0.1` |

Os outros 14 itens moderados são propagações na cadeia `@angular/cli → pacote`, envolvendo `@npmcli/package-json`, `@npmcli/run-script`, `@sigstore/sign`, `@sigstore/tuf`, `@tufjs/models`, `cacache`, `ignore-walk`, `make-fetch-happen`, `npm-packlist`, `npm-registry-fetch`, `sigstore` e `tuf-js`, além dos dois pacotes de entrada `@angular/cli` e `pacote`. O npm sugere Angular CLI 22.3.0 para a cadeia inteira. As correções de `glob` e `minimatch` exigem novas versões major fora das faixas das dependências transitivas atuais. A Etapa 9 preservou o Angular 21 e as relações declaradas pelos mantenedores. Esses itens têm relevância para máquinas e CI que executam as ferramentas de desenvolvimento.

## 6. Segurança operacional

- **JWT.** HMAC256, emissor e expiração de duas horas. A validação exige `exp` e o UUID público da conta, verifica o vínculo com o usuário atual e aplica as permissões vigentes no servidor. Excluir uma conta impede a aceitação do token anterior mesmo que o e-mail seja reutilizado. Tokens anteriores sem UUID exigem novo login. A saída no navegador limpa a sessão local. A revogação central por token permanece pendente de decisão e um token copiado pode sobreviver à saída até expirar. A rotação da chave encerra as sessões assinadas pela chave anterior.
- **Segredos.** A chave histórica permanece no histórico Git, e sua impressão digital continua bloqueada pela aplicação. A chave aceita vem da configuração externa em Base64 canônico, com material aleatório. A suíte manual deixou de guardar três JWTs expirados e as senhas fixas da demonstração. Os logins da demonstração recebem senha configurável localmente. Quando ela é omitida, as contas sintéticas recebem um valor aleatório que fica restrito ao processo. A varredura dirigida da árvore atual buscou chaves privadas, tokens GitHub/AWS e JWTs compactos, além da revisão dos pontos de configuração. A rotação e o inventário de segredos implantados exigem acesso ao ambiente. [Histórico P01/P02](seguranca-p01-p02.md)
- **HTTP.** A aplicação usa requisições relativas `/api` com `Authorization: Bearer` e sessão stateless, sem cookies de autenticação. Isso sustenta a configuração atual de CSRF. CORS usa origens explícitas, sem credenciais, com cabeçalhos mínimos. A documentação HTTP fica acessível somente com opt-in na demonstração sintética `test,dev` e bind em loopback. Console H2 permanece bloqueado. Mantêm-se os cabeçalhos padrão de Spring Security, como proteção de frame e `nosniff`. TLS, HSTS e cabeçalhos da página Angular dependem da configuração real do proxy.
- **Erros e logs.** As respostas de erro de fallback omitem mensagens internas, stack traces, exceções e detalhes de binding. A sincronização de USF e o agendador RF26 registram tipo de falha e contexto técnico limitado, sem propagar a exceção inteira. A revisão ficou concentrada nos caminhos de maior risco. A homologação da plataforma deve conferir redação e retenção de logs. Isso inclui excluir cabeçalhos de autenticação, corpos e caminhos que possam conter documentos.
- **Entradas, escopo e autorização.** Foram revisados os pontos de validação DTO, consultas parametrizadas e restrições por perfil e unidade no backend. A regressão usa os testes existentes para identidade única, dados cadastrais compartilhados, isolamento clínico, correções justificadas, trilhas originais, comprovação, reversão, RF22 e RNF17. RF26 permanece desativado. O contrato test-only em `/api/ensaio-escritores` usa classpath de testes e H2 descartável. O artefato operacional importa os controladores reais em `/api`, valida o esquema e aplica a autorização no servidor.
- **GitHub Actions.** Workflows auditados usam `contents: read`, ações fixadas por SHA e checkout sem persistência de credenciais. A CI da branch verifica a árvore npm e gera artefatos de auditoria a partir dos arquivos versionados. A proteção da `main` e os checks obrigatórios dependem das políticas do repositório.

## 7. Arquivos alterados

| Arquivos | Finalidade |
|---|---|
| `pom.xml`, `package.json`, `package-lock.json` | Remediação das dependências e resolução reproduzível. |
| `SecurityConfig.java`, `WebConfig.java` (removido), `application*.properties` | Política CORS única, restrição de documentação e detalhes de erros. |
| `TokenService.java`, `TokenServiceImpl.java`, `SecurityFilter.java` | JWT vinculado à conta e checagem da autenticação atual. |
| `UsfSyncService.java`, `SusApiAdapter.java`, `AlertaPacientesVermelhosScheduler.java` | Redação dos diagnósticos. |
| `DevConfig.java`, `pacientes.http` | Senha sintética externa e tokens obtidos no login. |
| `UsuarioLogadoService`, `http-interceptor.service.spec.ts` | Cache vinculado ao token e regressão de troca de sessão. |
| `SecurityP01P02Tests`, `DevDemonstracaoIntegrationTests` | Regressões de JWT, CORS, Swagger e demonstração. |
| `src/proxy.conf.json`, `scripts/etapa8-10-integracao.mjs` | Preservação do host original nas requisições de mesma origem. |
| `.github/workflows/{ci,cadastro-unico-diagnostico,etapa9-auditoria-dependencias}.yml` | Checkout com menor privilégio, verificação npm estrita e nova coleta OSV. |
| `docs/etapa9-relatorio-final.md`, `docs/seguranca-p01-p02.md` | Evidências e atualização do caminho de execução local. |

## 8. Regressão e resultados

| Verificação | Resultado efetivo |
|---|---|
| Maven `verify` | 1.455 testes aprovados, sem falhas, erros ou testes ignorados. |
| Matriz de concorrência 6.1 | 12 testes aprovados, sem falhas, erros ou testes ignorados. |
| Angular padrão | 241 testes em 42 arquivos, todos aprovados |
| Contrato HTTP experimental | 6 testes aprovados |
| Builds padrão e experimental | Os dois builds concluíram com sucesso. O build padrão manteve o aviso do orçamento inicial de 500 kB (967,28 kB na base funcional e 972,11 kB nesta execução). |
| Navegador Chromium com cenários simulados da Etapa 8.9 | 5 cenários aprovados. |
| Jornada integrada da Etapa 8.10 | 32 fases aprovadas com Chromium e backend HTTP isolado. |
| Segurança, demonstração e consumidores de relatórios, local | 29 testes aprovados. Abrangem JWT, CORS, Swagger, persistência e leitura de PDFs e planilhas sintéticas. |
| Instalação e atualização Flyway com restauração H2, local | 6 testes de `ModeloVinculoOperacionalTests` aprovados. |
| Demonstração em loopback, HTTP local | Login, especificação OpenAPI e Swagger UI responderam 200 no perfil sintético com opt-in. |
| Auditorias finais | npm completo e sem desenvolvimento coletados. OSV Maven com zero correspondências. |

O primeiro ensaio integrado detectou que o proxy substituía o host original pelo endereço interno da API. A validação dirigida em HTTP sintetizou a mesma origem do navegador e obteve 403 com o host reescrito e 200 com o host preservado. A correção manteve a política CORS e os cenários existentes. Em uma execução Maven dirigida anterior, quatro casos novos de Swagger esperavam 401. O bloqueio explícito devolve 403 também a requisições anônimas, e o teste passou a exigir a resposta do bloqueio explícito.

## 9. Comandos e evidências

Executar os comandos a partir de `backend/buscaativa` ou `frontend/sentinela-pet`, conforme a ferramenta.

```bash
bash mvnw -B -ntp verify
bash mvnw -B -ntp -Dtest=ReconciliationConcurrency61Characterization test
bash mvnw -B -ntp -Dtest=SecurityP01P02Tests,DevDemonstracaoIntegrationTests,ProjecaoClinicaVinculoIntegrationTests test
mkdir -p auditoria-etapa9
bash mvnw -B -ntp org.apache.maven.plugins:maven-dependency-plugin:3.9.0:tree \
  -DoutputType=json -DoutputFile=auditoria-etapa9/dependency-tree.json
python3 ../../.github/scripts/etapa9-auditoria-maven.py
```

```bash
npm ci --strict-peer-deps
npm ls --all
npm audit --json --package-lock-only --include=dev --include=optional --include=peer
npm audit --json --package-lock-only --omit=dev
npm test -- --watch=false
npm test -- --watch=false --configuration ensaio --include=src/app/services/contrato-clinico-integrado.spec.ts
npm run build
npm run build -- --configuration ensaio
```

- Base funcional e navegador da Etapa 8. [CI #46108520453](https://github.com/LizReis/SIBRAPS-testagem/actions/runs/46108520453)
- Candidato Maven aprovado e tentativa anterior de resolução npm com `ERESOLVE`. [CI #46110222726](https://github.com/LizReis/SIBRAPS-testagem/actions/runs/46110222726)
- Auditorias do POM e dos arquivos npm com os mesmos arquivos de dependências do commit final de código. Artefatos `etapa9-auditoria-npm` e `etapa9-auditoria-maven`. [CI #46111024381](https://github.com/LizReis/SIBRAPS-testagem/actions/runs/46111024381) · [Inventário npm](https://github.com/LizReis/SIBRAPS-testagem/actions/runs/46111024381/artifacts/15249707714) · [Inventário Maven/OSV](https://github.com/LizReis/SIBRAPS-testagem/actions/runs/46111024381/artifacts/15248599457)
- Primeira CI de regressão e diagnóstico do proxy. [CI #46111024363](https://github.com/LizReis/SIBRAPS-testagem/actions/runs/46111024363)
- Regressão consolidada no commit final de código. [CI #46111607684](https://github.com/LizReis/SIBRAPS-testagem/actions/runs/46111607684) · [Relatórios do backend e concorrência](https://github.com/LizReis/SIBRAPS-testagem/actions/runs/46111607684/artifacts/15249279283) · [5 cenários de navegador](https://github.com/LizReis/SIBRAPS-testagem/actions/runs/46111607684/artifacts/15249689091) · [32 fases integradas](https://github.com/LizReis/SIBRAPS-testagem/actions/runs/46111607684/artifacts/15250160477)

## 10. Migrations, backup e recuperação

Foram revisados os pontos de encadeamento do Flyway e as proteções contra origem incorreta, lacunas no versionamento e reinicialização automática. As 29 migrations SQL e as três Java compõem V1–V32 sobre um esquema legado sintético com baseline 0. `AgendaMigrationTool` exige as tabelas legadas, H2 em arquivo e funcionamento offline. A sequência usa validações de esquema e estruturas anteriores, chaves estrangeiras e composições paciente–vínculo–unidade, restrições únicas, índices e triggers de auditoria append-only. A revisão incluiu a unicidade de CPF e CNS normalizados (V17), os índices e o gatilho de inventário por unidade (V19), os gatilhos de integridade do marco, do fechamento e das reversões (V20, V22 e V29), a chave composta e o índice da evidência de origem (V31) e a chave estrangeira de profissional designado (V32). A rotina de produção inicia com `ddl-auto=validate` e verifica o esquema antes do uso. O servidor isolado da jornada instala partes de V23–V31 e usa H2 em memória.

`ModeloVinculoOperacionalTests` inclui instalação até V32, passagem de V17 a V32, rejeição de estruturas incompletas, reaplicações sem alterações, restauração e reinicialização. Os testes de backup recuperam cópias sintéticas em destinos distintos.

1. Em ensaio descartável, parar instâncias, sincronizações e escritores, registrar versão e checksums Flyway, inventário, contagens e rastreabilidade de auditoria. Para H2, gerar backup lógico por `SCRIPT TO` com acesso restrito. Calcular SHA-256 e guardar manifesto e artefato em armazenamento autorizado.
2. Testar a recuperação com `RUNSCRIPT` em novo arquivo H2 e a mesma versão do driver. Conferir SHA-256 antes de restaurar e comparar histórico Flyway, quantidades, restrições, gatilhos de auditoria e amostra sintética de consultas. Documentar tempos e resultados.
3. Ensaiar a atualização com a ferramenta offline, primeiro até V16 e depois com as flags explícitas V17 a V32, preservando os diagnósticos de pré-condição. Para falha de migração, manter a instância isolada e restaurar o backup pré-migração num novo destino, com o artefato anterior compatível. Conferir integridade e autorizar a troca de conexão. Planejar a recuperação de gravações posteriores ao backup antes de abrir tráfego.
4. Para o marco, confirmar autorização institucional, zona, inventário independente e trilha de auditoria. Registrar pela rota administrativa `POST /api/painel/historico/marco` para o escopo da unidade, ou pela ferramenta offline `--registrar-marco-historico` quando couber ao procedimento institucional. Acompanhar cobertura prospectiva. Para fechamento manual, `PUT /api/painel/historico/fechamentos/{AAAA-MM}` exige inventário e hash da evidência para o mês completo elegível. [Procedimento do marco](etapa6-3-pacote6b3c-transicao-operacional.md) · [Sequência técnica](etapa6-3-historico-automatico-marco.md)

Para executar a ferramenta sobre o destino H2 descartável, configure
`DB_URL`, `DB_USERNAME` e `DB_PASSWORD` da cópia. A partir de `backend/buscaativa`:

```bash
bash mvnw -B -ntp compile dependency:build-classpath \
  -DincludeScope=runtime -Dmdep.outputFile=target/migracao-classpath.txt
java -cp "target/classes:$(cat target/migracao-classpath.txt)" \
  com.pet.buscaativa.migration.AgendaMigrationTool --diagnostico-identidade
```

Antes de aplicar cada etapa, conferir a origem e a versão registrada em
`sibraps_agenda_schema_history`. A ferramenta sem argumento aplica a sequência
inicial até V16. As opções de V17 a V32 constam em `AgendaMigrationTool`.

O SGBD definitivo ainda depende de decisão. O ensaio desse SGBD deve cobrir dialeto, restrições, concorrência, gatilhos, backup, restauração e rollback com o artefato escolhido. Nenhum dado real foi usado.

## 11. Limites da validação

A jornada real de navegador usa um servidor de testes com superfície reduzida, fixtures e relógio controlados. A cobertura existente de HTTP operacional, persistência e relatórios complementa esse ensaio. Ainda depende de homologação a combinação dos perfis e unidades reais, incluindo CAPS I/II/AD, além de recorrências e transferências nos fluxos específicos, acessibilidade, revisão visual e conferência humana dos relatórios. Ambas as auditorias dependem da data, escopos e bases de avisos. A adoção da nova versão exige novo login e configuração coerente de sessão e proxy. O bearer fica no armazenamento local do navegador, por isso o tratamento de scripts, dependências e cabeçalhos da origem Angular é relevante para proteger a sessão.

## 12. Configuração para ensaio local seguro

Com Java 21, Node 22 e npm 11.6.2, use dados sintéticos e serviços em loopback. Exemplo a partir da raiz, em Bash. A variável de senha de demonstração permite login manual com as contas sintéticas; configure o mesmo valor no ambiente do cliente HTTP local.

```bash
export JWT_SECRET_BASE64="$(openssl rand -base64 32)"
export DEV_DB_PASSWORD="$(openssl rand -hex 24)"
export SIBRAPS_DEV_FIXTURE_PASSWORD="$(openssl rand -hex 24)"
cd backend/buscaativa
bash mvnw -B -ntp test-compile spring-boot:test-run \
  -Dspring-boot.run.main-class=com.pet.buscaativa.BuscaativaApplication \
  -Dspring-boot.run.profiles=test,dev,diagnostico-migracao \
  -Dspring-boot.run.arguments="--sibraps.usf.sincronizar=false"
```

Em outro terminal, `cd frontend/sentinela-pet`, `npm ci --strict-peer-deps` e `npm start`. Para consultar OpenAPI apenas nesta demonstração, defina `SIBRAPS_DEV_OPENAPI_ENABLED=true` antes de iniciar o backend. O padrão deixa a documentação desligada.

Para o ambiente operacional, provisionar `JWT_SECRET_BASE64`, `DB_URL`, `DB_USERNAME` e `DB_PASSWORD` no gerenciador de segredos. Servir Angular e `/api` na mesma origem com proxy confiável, preservando host e esquema vistos pela aplicação e normalizando cabeçalhos encaminhados na borda. Se houver domínios separados, configurar `SIBRAPS_CORS_ALLOWED_ORIGINS` com origens HTTP(S) exatas, separadas por vírgulas. Em homologação com TLS, usar origens HTTPS. Manter os perfis de demonstração desativados e a URL de produção fora da configuração local. [Gestão da chave](seguranca-p01-p02.md)

## 13. Homologação humana

- [ ] Conferir interface, acessibilidade, navegação em dispositivos e perfis nas unidades representativas.
- [ ] Conferir os relatórios clínicos, exportações de PDF e planilha, e o comportamento de históricos e fechamentos no ambiente pretendido.
- [ ] Definir infraestrutura e banco, executar ensaio completo de migração, restauração e rollback e aprovar o procedimento de marco histórico.
- [ ] Conferir rotação efetiva do material legado, segredos, TLS, cabeçalhos, CORS, redação e retenção de logs, limites do login e política de revogação.
- [ ] Definir tratamento das ocorrências moderadas de ferramentas e proteção de branch. Obter autorização explícita para qualquer integração na `main` ou liberação.

## 14. Parecer por subetapa

| Subetapa | Parecer |
|---|---|
| 9.1 — Dependências | Remediação alta/crítica concluída. Permanecem 16 ocorrências moderadas da cadeia de ferramentas npm na linha 21. |
| 9.2 — Segurança e prontidão | Revisão direcionada e correções concluídas no escopo técnico. A infraestrutura e o banco definitivos continuam pendentes. |
| 9.3 — Regressão | Aprovada no escopo automatizado da CI #46111607684. As duas auditorias usaram os mesmos arquivos de dependências do código aprovado. |
| 9.4 — Homologação e liberação | Pendente de homologação humana, validação da infraestrutura e autorização formal. |
