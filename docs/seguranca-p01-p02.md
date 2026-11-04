# Segurança do backend — correções P01 e P02

Este documento descreve a configuração JWT e a exposição do console H2; não
constitui aprovação de segurança da implantação. As correções posteriores de
P03–P05 estão em [Etapa2](correcao-agendamentos-p03-p04-p05.md).
P06–P16 e Q01–Q04 permanecem fora desses escopos.

## Configuração e perfis

- Requer Java/JDK 21. Use o Maven Wrapper do repositório.
- Nenhum perfil de desenvolvimento é ativado automaticamente. `dev` só é usado
  quando selecionado explicitamente. Ele recria H2 em memória e carrega os dados
  demonstrativos de `DevConfig`, incluindo usuários de demonstração. Nunca use
  esse perfil com dados reais ou para uma implantação pública.
- `JWT_SECRET_BASE64` é obrigatória em todos os perfis de aplicação. Forneça-a por
  variável de ambiente/gerenciador de segredos, sem gravar em arquivos versionados,
  argumentos da JVM, logs ou exemplos compartilhados.
- A variável contém Base64 padrão canônico de **pelo menos 32 bytes aleatórios**
  (256 bits de material). O algoritmo HMAC-SHA256 usa os bytes decodificados,
  não os caracteres Base64. Valores ausentes, vazios, malformados, curtos, o
  material comprometido e exemplos previsíveis identificados são rejeitados na
  criação do serviço. A mensagem de erro não inclui o valor recebido.
- As verificações de padrões são uma defesa adicional; não comprovam a entropia
  de uma chave arbitrária. Gere material com uma fonte criptográfica segura,
  como nos exemplos. Não derive a chave de senha, texto ou UUID.
- Não há geração automática de chave na aplicação, fallback ou aceitação
  simultânea da chave antiga. `api.security.token.secret` não é mais utilizada.
- Em `dev`, informe `DEV_DB_PASSWORD`; `DEV_DB_USERNAME` é opcional (padrão `sa`).
  A URL permanece `jdbc:h2:mem:devdb`. Não use senha vazia.
- Fora de `dev`, configure `DB_URL`, `DB_USERNAME` e `DB_PASSWORD` para o banco
  previsto na implantação, com driver compatível disponível. Nenhum perfil de
  produção fictício, conta administrativa ou banco de produção foi criado por
  esta correção. Não forneça bancos reais para executar testes.

## Desenvolvimento local — Bash

Em uma nova sessão local, a partir da raiz do repositório:

```bash
# Não execute com set -x: os valores não devem aparecer no terminal/logs.
export JWT_SECRET_BASE64="$(openssl rand -base64 32)"
export DEV_DB_PASSWORD="$(openssl rand -base64 32)"
export SPRING_PROFILES_ACTIVE=dev
cd backend/buscaativa
bash mvnw verify
java -jar target/buscaativa-0.0.1-SNAPSHOT.jar
```

Os comandos geram valores apenas nesta sessão; não os exibem. Se reiniciar o
processo mantendo a sessão, mantenha as mesmas variáveis. Gerar outra chave
invalida os tokens existentes. Em implantação, provisione e retenha a chave em
um gerenciador de segredos: não execute uma geração nova a cada reinício.

Para iniciar somente o frontend, em outro terminal:

```bash
cd frontend/sentinela-pet
npm ci
npm start
```

O contrato de login permanece `POST /api/auth/login`, corpo com `email` e `senha`,
resposta com `token` e `tipo: Bearer`. O proxy Angular padrão aponta para a API
na porta 8080. Não há alteração nos guards ou armazenamento do frontend nesta etapa.

## Desenvolvimento local — PowerShell

Da raiz do repositório:

```powershell
$rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
$bytes = New-Object byte[] 32
try {
    $rng.GetBytes($bytes)
    $env:JWT_SECRET_BASE64 = [Convert]::ToBase64String($bytes)
    $rng.GetBytes($bytes)
    $env:DEV_DB_PASSWORD = [Convert]::ToBase64String($bytes)
} finally {
    [Array]::Clear($bytes, 0, $bytes.Length)
    $rng.Dispose()
}
$env:SPRING_PROFILES_ACTIVE = 'dev'
Set-Location backend/buscaativa
.\mvnw.cmd verify
java -jar target/buscaativa-0.0.1-SNAPSHOT.jar
```

Os exemplos não contêm valores fixos utilizáveis. Evite transcrição, depuração
ou comandos que imprimam variáveis/ambiente. Restrinja o acesso ao processo.

## Testes

```bash
cd backend/buscaativa
bash mvnw test
```

```powershell
Set-Location backend/buscaativa
.\mvnw.cmd test
```

A configuração em `src/test` seleciona `test`, usa H2 descartável e gera material
JWT e senha sintéticos somente no processo de testes. Não utiliza `DevConfig`,
credenciais do ambiente real ou fallback da aplicação. Não existe uma nova chave
fixa de testes que a aplicação possa aceitar em produção. O teste de contexto
simula a integração SUS; os demais testes de integração já o faziam.

As regressões não executam Git e não dependem do histórico. A política imutável
mantém o fingerprint histórico revogado; testes usam material sintético e um
fingerprint proibido controlado para exercitar o mesmo construtor usado na
inicialização real. A chave histórica não é republicada nem recuperada pelos testes.
A verificação da configuração desse fingerprint complementa o teste funcional da
política; não equivale a reassinar um token com o material histórico.

Os testes podem ser executados mesmo com `JWT_SECRET_BASE64`, `DEV_DB_PASSWORD`
e `SPRING_PROFILES_ACTIVE=dev` no terminal. Não é necessário limpar variáveis.
Integrações selecionam explicitamente `test`; contextos que verificam ausência de
configuração isolam as fontes externas localmente. O ambiente do processo não é
alterado. Iniciar a aplicação em `dev` e executar os testes são procedimentos distintos.

No ambiente cloud preparado desta sessão, os comandos equivalentes são:

```bash
export JAVA_HOME=/workspace/.cache/sibraps/jdk21
export PATH="$JAVA_HOME/bin:$PATH"
export MAVEN_USER_HOME=/workspace/.cache/sibraps/m2
export MAVEN_OPTS="${MAVEN_OPTS:-} -Djavax.net.ssl.trustStore=/etc/ssl/certs/java/cacerts"
cd /workspace/SIBRAPS-testagem/backend/buscaativa
bash mvnw -s /workspace/.cache/sibraps/maven-settings.xml -B \
  -Dmaven.repo.local=/workspace/.cache/sibraps/m2/repository verify
```

Use o proxy e a confiança TLS fornecidos pelo ambiente. Não desabilite verificação
de certificados. O servidor e os testes com sockets podem exigir o mecanismo de
execução autorizada da plataforma.

## Console H2

O console está desabilitado nas configurações base, `dev` e `test`. O módulo
`spring-boot-h2console` foi removido: não há registro automático do servlet, mesmo
se alguém definir acidentalmente `spring.h2.console.enabled=true` ou outro caminho.
As regras públicas específicas foram removidas e `/h2-console` e seus subcaminhos
são negados antes das permissões genéricas. Não foi criado um modo opcional de
console HTTP. Administrador da aplicação também não recebe acesso ao console.

Confira no servidor local:

```bash
curl -s -o /dev/null -w '%{http_code}\n' http://localhost:8080/h2-console/
curl -s -o /dev/null -w '%{http_code}\n' http://localhost:8080/h2-console/login.do
```

```powershell
curl.exe -s -o NUL -w '%{http_code}\n' http://localhost:8080/h2-console/
curl.exe -s -o NUL -w '%{http_code}\n' http://localhost:8080/h2-console/login.do
```

Espere 401, 403 ou 404, nunca interface funcional ou sessão de banco. Os testes
também verificam GET/POST, profissional, administrador e caminho alternativo.
O driver H2 continua disponível para a persistência normal. A remoção do console
não altera regras de USF/CAPS nem o esquema das entidades.

## Rotação e implantação existente

1. Trate a chave histórica como comprometida, mesmo após removê-la dos arquivos:
   ela permanece no histórico Git. Apenas seu fingerprint SHA-256 está no código
   atual, para impedir reutilização; ele não permite assinar tokens.
2. Gere uma chave nova com fonte criptográfica segura, provisione
   `JWT_SECRET_BASE64` no gerenciador de segredos e restrinja quem pode lê-la.
3. Remova a configuração antiga. Não há período de fallback. Atualize todas as
   instâncias e reinicie-as de forma coordenada; uma instância antiga ainda em
   execução continua vulnerável e não é corrigida por editar o repositório.
4. Exija novo login. Tokens emitidos com a chave anterior deixam de validar.
5. Não ative `dev`. Configure o banco e as credenciais externos, preserve a
   política de migrações da implantação e confira login, escopo e bloqueio do H2.
6. Provisione usuários reais por um procedimento administrativo controlado; a
   ausência de `DevConfig` não cria nem preserva contas demonstrativas como
   mecanismo de implantação.

Esta mudança não configura infraestrutura de produção, TLS público, CORS,
revogação individual de JWT, atualizações gerais de dependências ou as demais
correções da auditoria. Valide esses requisitos separadamente antes de uso real.

## Logging temporário

O nível padrão de Spring Security é WARN. Para diagnóstico local controlado,
habilite temporariamente DEBUG com `--logging.level.org.springframework.security=DEBUG`
no comando do backend. No PowerShell o mesmo argumento se aplica. Remova o
argumento após o diagnóstico. Evite TRACE, captura de cabeçalhos e compartilhamento
de logs com credenciais ou informações de pacientes. A configuração não suprime
falhas de inicialização, erros nem resultados dos testes.

## Integração contínua

`.github/workflows/ci.yml` executa backend e build Angular em PRs para `main`,
pushes na `main` e manualmente. Não exige Secrets. O checkout raso é suficiente.
O backend usa Java21 e `bash ./mvnw verify`; o frontend usa Node22 e npm11.6.2.
A suíte Angular possui falhas preexistentes Q01–Q04 e está em um job diagnóstico
manual (`run_frontend_tests`), desmarcado por padrão. Se selecionado, suas falhas
ficam visíveis; não há `continue-on-error`. O build Angular continua obrigatório.
Não há deploy ou merge automático. Configure na proteção da branch os checks
Backend e Frontend build como obrigatórios se desejar bloquear integração na UI.
