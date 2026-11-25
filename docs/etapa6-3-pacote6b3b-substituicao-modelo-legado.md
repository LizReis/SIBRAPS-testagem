# SIBRAPS — Pacote 6B.3B · Substituição coordenada do modelo legado

**APROVADA PARA REVISÃO** em banco sintético isolado. O corte em ambiente real depende da validação do 6B.3C.

A entrega parte de `d00980a1d274911eb0e6df3114ff666e37902d49` na branch `correcao-cadastro-unico-isolamento`. A execução [45524403775](https://github.com/LizReis/SIBRAPS-testagem/actions/runs/45524403775) validou essa base. O SHA final é o do commit que contém este relatório. Em um clone, consulte `git log -1 --format=%H -- docs/etapa6-3-pacote6b3b-substituicao-modelo-legado.md`.

## Contratos operacionais

O startup padrão registra os controladores por vínculo nas rotas `/api`. Os adaptadores HTTP do 6B.1 e do 6B.2 vivem agora no código do aplicativo. A montagem de ensaio reutiliza esses adaptadores sob o prefixo experimental, em seu banco isolado. O JAR operacional recusa o perfil `test`.

| Rotas padrão | Serviços e autorização |
| --- | --- |
| `/api/pacientes`, `/api/pacientes/pesquisa`, `/api/pacientes/cadastro-unico/{conferencia,confirmar-vinculo}` | Cadastro compartilhado, conferência, vínculo explícito e pesquisa local. Administrador, profissional e recepção. |
| `/api/pacientes/{paciente}/vinculos/{vinculo}/cadastro`, `/{paciente}/detalhe`, `/{paciente}/prontuario` e `/{paciente}/agendamentos` na coleção de pacientes | Edição cadastral com versão da identidade, leitura local por JWT e DTOs de fatos com origem comprovada. |
| Comandos de acompanhamento, encerramento, inativação, reativação, histórico manual e revisão em `/api/pacientes/{paciente}/vinculos/{vinculo}` | Serviços de estado local, histórico e Opção B. Versão do vínculo e permissões clínicas específicas do comando. A decisão da Opção B permanece um ato administrativo sem aplicação de correção. |
| `/api/agendamentos`, agenda, horários, status, frequência, correção e regularização | Reservas e guardas de oferta que já atendiam o ensaio. Fatos clínicos e recálculo local usam as versões próprias e a coordenação transacional existente. |
| `/api/grupos`, sessões, participantes, frequência e inscrições | Serviços locais de séries e frequência coletiva. Revalidam composição, temporalidade, escopo e responsável histórico. |
| `/api/pacientes/busca-ativa`, `/api/auditoria/busca-ativa`, `/api/painel/resumo`, `/api/inicio/resumo` | Leitura de indicadores locais e auditoria RNF17 da lista, visualização e encaminhamento. Mantém RF22, visita domiciliar para pacientes vermelhos mesmo com gatilho ausente e orientação específica de situação de rua. |
| `/api/relatorios` com as prévias, PDF, Excel e `/pendencias` | Relatórios locais e fila administrativa paginada de revisão. |
| Login, referência territorial e profissional, configuração da oferta e manutenção de usuários | Rotas auxiliares existentes. Permissões próprias e travas de integridade da oferta e de referência local. |

As escritas reutilizam a ordem canônica de bloqueios, a revalidação e o versionamento do 6B.1. Frequências, histórico, auditoria e indicadores do vínculo participam da mesma transação. A origem inconclusiva continua sujeita à revisão e o indicador indisponível permanece sem valor. O estado e os fatos dos outros vínculos continuam protegidos, inclusive entre USFs distintas e entre CAPS I, II e AD.

### Caminhos legados

| Caminho anterior | Situação na aplicação padrão |
| --- | --- |
| Controladores globais de paciente, agenda, grupo, início, painel, relatórios e auditorias antigas | Fora do contexto operacional. Os endpoints por vínculo assumem os fluxos da tabela. As trilhas antigas seguem armazenadas. |
| Recálculos de frequência e risco, cadastro clínico global, busca ativa com recálculo e escritores diretos de fatos e histórico | Entradas antigas guardadas contra chamada operacional. A disponibilidade da agenda e as primitivas de histórico de reserva/remarcação continuam em uso pelos serviços coordenados. |
| Inicializador de indicadores globais, monitor global, agendador e serviço de notificação global, serviço de e-mail | Ausentes no contexto operacional. O monitor local reutiliza o recálculo coordenado e fica preparado como serviço interno. O agendamento e a validação operacional ficam no 6B.3C. |
| Consumidores de histórico mensal e controle persistente de notificações | Fechamentos sem cobertura continuam indisponíveis. O ponto atual é separado dos meses. A V16 e as reservas de duplicidade continuam preservadas, com o envio real desativado. |
| Dados históricos, estruturas de auditoria e ferramentas de diagnóstico | Preservados para auditoria e migração, inclusive o teste negativo de escrita não coordenada. |

## V17 e preparação offline do banco

O código operacional pesquisa pelas colunas calculadas e indexadas `cpf_normalizado` e `cns_normalizado`. Documentos antigos corretamente formatados permanecem armazenados no formato original. Conflitos retornam HTTP 409 com mensagem genérica. As duas restrições independentes V17 protegem inserção e edição mesmo quando as instâncias têm travas JVM diferentes.

A inicialização valida antes do ORM a expressão das colunas, cada restrição, o histórico aplicado até V17 e as contagens de duplicidades e documentos inconclusivos. A montagem bloqueia configurações que executariam SQL automático na conexão, exige `ddl-auto=validate` e desativa migração e inicialização automática de SQL. Nenhuma migration anterior foi editada.

Procedimento para uma **cópia H2 isolada e offline** com Java 21:

1. Pare os processos que usam a cópia. Confirme o arquivo e o schema corretos. Faça backup com `SCRIPT TO '<caminho protegido>'` em conexão administrativa offline e teste `RUNSCRIPT FROM '<backup>'` em um H2 separado. Proteja o arquivo de backup e as credenciais.
2. Informe `DB_URL`, `DB_USERNAME` e `DB_PASSWORD` por variáveis de ambiente. Para o arquivo existente, use `jdbc:h2:file:<caminho>;IFEXISTS=TRUE`. Confira o histórico até V16 antes de aplicar V17. A ferramenta sem argumentos termina em V16 para um banco legado compatível.
3. Rode o diagnóstico e confira as contagens. Havendo duplicidade ou documento inconclusivo, interrompa a passagem e encaminhe para revisão autorizada. Faça a migração V17 somente com backup restaurável e diagnóstico liberado. Em falha parcial de DDL, use uma restauração validada. H2 pode confirmar DDL implicitamente.
4. Inicie o JAR no perfil padrão com `JWT_SECRET_BASE64` configurado, mantendo o envio desativado. Para ensaio local, limite a escuta ao loopback e desative a sincronização externa de USFs. Compile e sirva o Angular padrão para o mesmo backend.

```sh
JAR=target/buscaativa-0.0.1-SNAPSHOT.jar
java -Dloader.main=com.pet.buscaativa.migration.AgendaMigrationTool -cp "$JAR" \
  org.springframework.boot.loader.launch.PropertiesLauncher --diagnostico-identidade
java -Dloader.main=com.pet.buscaativa.migration.AgendaMigrationTool -cp "$JAR" \
  org.springframework.boot.loader.launch.PropertiesLauncher --identidade
java -jar "$JAR" --server.address=127.0.0.1 --sibraps.usf.sincronizar=false
```

No frontend, `npm run build` usa o contrato por vínculo em `/api`. O modo `ensaio` preserva a montagem sintética do 6B.2. Ambos reutilizam as telas e os DTOs, incluindo confirmação humana de identidade, versões distintas para cadastro, vínculo e fatos assistenciais, pendências, PDF/Excel e auditoria. O build padrão não carrega o prefixo HTTP experimental.

## Verificação

| Verificação | Resultado |
| --- | --- |
| Validação dirigida Java | Startup V17 e rejeição de V16 ou de restrição removida. Em duas instâncias, conflito seguro em inserção e edição concorrentes. JWT real, CPF/CNS antigo formatado, duas USFs, agenda, correção justificada, prontuário, grupos, busca ativa e fila de pendências. |
| Maven `clean verify` e matriz de concorrência | 1.366 testes passaram no verify integral. Os 24 casos de projeção legada passaram no verify dirigido após ajuste de seu mock para a nova guarda, com as mesmas asserções. A matriz adicional passou com 12 testes. |
| Testes Angular direcionados | 49 testes aprovados no build padrão e 4 no modo ensaio. |
| Builds Angular | Padrão e ensaio aprovados. O build padrão ficou em 896,07 kB, com aviso do orçamento de 500 kB. |
| JAR executável e ferramenta offline | Migração offline V16 e V17 e diagnóstico executados pelo JAR sobre cópia sintética. Startup padrão, login JWT e leituras autenticadas aprovados. A rota experimental respondeu 404, e o JAR recusou o perfil test. |

## Pendências reservadas ao 6B.3C

- Definir e validar o marco histórico confiável e comprovar cobertura dos fechamentos por unidade.
- Resolver a corrida entre revalidação e envio de notificações e tratar reservas interrompidas ou incertas mediante autorização.
- Validar o monitoramento e a transição operacional de forma definitiva, incluindo restauração e corte coordenado.

A entrega foi validada em H2 sintético. A V16 e a V17 permaneceram intactas. Nenhum banco real recebeu migração, implantação ou envio de e-mail. A `main` ficou fora da publicação.
