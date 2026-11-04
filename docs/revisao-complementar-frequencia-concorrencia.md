# Revisão complementar — frequência histórica e alcance do handler

Data:04/11/2026. Branch:`correcao-estabilidade-agenda-p03-p04-p05`.
Referência revisada:`c31b33ec56f2612b0cfe34c54dedf224e8c7398e`.
Comparação anterior:`main` em `967d5162dd543ec9005608739128423903e75e30`.

**Nenhum código de produção ou regra foi alterado nesta revisão.** Foram
adicionados testes de compatibilidade/caracterização e esta análise. A proposta
abaixo depende de decisão e não foi implementada. Não houve merge na main.

## Conclusão principal

Há uma regressão de compatibilidade: `FALTOU → PRESENTE` pode ser bloqueada por
configuração de oferta alterada depois do atendimento. Isso acontece porque
`FALTOU` não integra `SITUACOES_ATIVAS`, mas `PRESENTE` integra; portanto a condição
nova de `atualizarStatus()` chama `validarVaga()` inclusive para correção factual.

O uso dessa condição é adequado para uma reativação de reserva, mas não distingue
essa intenção de corrigir a frequência de um atendimento já ocorrido. Não se
conclui que todas as regras anteriores foram preservadas.

## Comparação executada

Fixtures:H2 em memória, registros sintéticos, atendimento10 dias atrás, perfis
ADMINISTRADOR e PROFISSIONAL. As disponibilidades semanais são alteradas somente
depois de criar os dados históricos. Comparação executada também em exportação
isolada da main; somente os testes/helpers foram copiados, sem mudar a produção.
Expectativas de caracterização de reativação/handler na cópia refletem o contrato
anterior explicitamente. Os testes não executam Git.

| Transição e condição | Main anterior | Branch revisada | Classificação |
|---|---|---|---|
| FALTOU→PRESENTE,oferta ainda válida |200|200|Preservado|
| FALTOU→PRESENTE,disponibilidade removida |200|409|Regressão de compatibilidade histórica|
| FALTOU→PRESENTE,capacidade zerada |200|409|Regressão de compatibilidade histórica|
| FALTOU→PRESENTE,capacidade reduzida abaixo da ocupação |200|409|Regressão de compatibilidade histórica|
| FALTOU→PRESENTE,data bloqueada posteriormente |200|409|Regressão de compatibilidade histórica|
| PRESENTE→FALTOU,mesmas cinco condições |200|200|Preservado|
| CANCELADO→AGENDADO,oferta válida |200|200|Preservado|
| CANCELADO→AGENDADO,oferta removida/zerada/insuficiente/bloqueada |200|409|Restrição introduzida para integridade da reativação|

A main não verificava oferta/ocupação nessas transições; isso não significa que
todo comportamento anterior era desejável. A restrição de reativação protege P04,
mas a política para reativar uma reserva em data já passada precisa de confirmação
funcional. Não foi adicionada nem removida restrição de data nesta revisão.

Pelo código, a validação nova também pode rejeitar hora incompatível com a grade
atual (422) e conflito com outro atendimento ativo (409). Essa extensão específica
da correção histórica foi analisada estaticamente, não simulada nesta matriz.
O bloqueio de mudanças em datas futuras já existia na main e continua antes de
`validarVaga`; a mensagem menciona frequência, mas o guard é aplicado a todos os
status. Transições cancelado/substituído→PRESENTE/FALTOU também já eram proibidas.

## Efeitos e regressões verificadas

- PRESENTE→FALTOU recalcula quatro faltas consecutivas e risco VERMELHO na fixture,
  recupera a presença anterior e atualiza o mesmo evento de frequência.
- A entrada em risco VERMELHO cria um evento separado de mudança de situação.
  Esse evento adicional é legítimo e já existia; não é duplicação da frequência.
- FALTOU→PRESENTE,quando aceita,recalcula zero faltas,risco VERDE e última presença;
  o histórico reaproveita o ID do evento de frequência.
- Quando a correção é recusada, status/versão,contadores,risco,histórico e registros
  permanecem intactos. A atomicidade é preservada,mas a correção clínica fica impedida.
- Remarcação válida após falta mantém `remarcacaoAposFalta`,marca o original como
  REMARCADO_ORIGEM e aciona visita em nova falta. Com oferta válida,a correção para
  PRESENTE limpa o gatilho; repetição do mesmo status é idempotente.
- Em um fluxo com remarcação na data atual,fechar a capacidade depois da falta
  bloqueia a correção para PRESENTE:branch409,visita=true,faltas=1;main200,
  visita=false,faltas=0. É a mesma condição aplicada a uma correção de fato já registrado.
- Recepção continua sem permissão para corrigir frequência (422); outra unidade
  continua recebendo404 sem alteração do registro.
- As regressões existentes de grade,capacidade,disponibilidade,bloqueios,
  remarcação,concorrência,USF/CAPS e histórico continuam aprovadas.

## Proposta de regra — não implementada

Separar explicitamente duas operações:

1. **Criar/remarcar/reativar reserva:** manter grade,disponibilidade,capacidade,
   bloqueios e conflitos reais sob os bloqueios de P04. Não retirar genericamente
   `validarVaga()` de todas as entradas em estado ativo.
2. **Corrigir frequência já registrada (PRESENTE↔FALTOU):** manter permissões,
   unidade,versão,ordem de locks,transação e recálculo auditável. A oferta atual
   não deveria determinar se um atendimento passado ocorreu. Não reutilizar a
   validação de oferta corrente como condição automática para corrigir esse fato.

**Decisões necessárias:** aplicar o tratamento factual também às correções do
mesmo dia; definir reativação de AGENDADO em data passada; definir como tratar um
outro atendimento ativo no mesmo instante ao corrigir o histórico. Não basta
ignorar todos os conflitos:isso poderia violar a invariante de P04. Recomenda-se
manter a detecção de colisões reais e exigir reconciliação auditável nesses casos,
em vez de confundi-las com capacidade semanal alterada. Os casos sem colisão dos
novos testes devem voltar ao comportamento anterior se essa proposta for aprovada.

## Alcance global de ResourceExceptionHandler

`@ControllerAdvice` não está limitado a agendamentos. A nova anotação captura
Spring PessimisticLockingFailureException (inclui CannotAcquireLockException),
Spring OptimisticLockingFailureException (inclui ObjectOptimisticLockingFailureException),
JPA PessimisticLockException e JPA LockTimeoutException.

Foi feita injeção controlada de exceções no limite dos serviços e requisição HTTP
real ao servidor de teste. Isso comprova o mapeamento HTTP,não uma corrida real
de banco em cada módulo.

| Módulos testados | Spring versão/bloqueio na main | Na branch | Falta de conexão |
|---|---|---|---|
| Pacientes,grupos,profissionais,disponibilidade,histórico,relatórios |500|409|500 em ambas|

Também confirmados:OptimisticLockException JPA explícita já era409; o novo
mapeamento pessimista/timeout JPA muda500→409; QueryTimeoutException e erro de
programação continuam500; conflito de negócio continua409; validação continua422.

Impactos prováveis por inspeção:
- Pacientes:entidade com versão,encerramento/reativação com bloqueio pessimista.
- Grupos e sessões:entidades com versão,bloqueio de sessão/pacientes,frequência e inscrições.
- Disponibilidades,exceções,bloqueios e usuários:mesmo sem versão própria em algumas
  entidades,podem propagar erros de lock das operações JPA.
- Painéis,início e USFs:Advice também é aplicável,se uma exceção dessas escapar;
  não foi reproduzida corrida nesses leitores.
- Relatórios:erros da consulta podem atingir o handler; falhas encapsuladas em
  RelatorioException pelos renderizadores PDF/Excel continuam seguindo500.
- O Advice não intercepta automaticamente scheduler,SMTP ou erros do filtro JWT
  anteriores ao MVC. Não é um tratamento universal de todo processo.

## Negócio,transação e infraestrutura

| Natureza | Exemplo | Avaliação |
|---|---|---|
| Conflito de negócio | Horário ocupado,capacidade esgotada |409 explícito é adequado|
| Validação | Hora fora da grade,campos inválidos |422/400 conforme contrato|
| Conflito otimista de versão | Atualização sobre versão desatualizada |409 é coerente; cliente pode precisar recarregar|
| Lock/timeout/deadlock | Espera interna ou contenção do banco |Não prova erro de negócio; classificação depende da causa e recuperabilidade|
| Infraestrutura | Conexão indisponível |Já continua500;503 pode ser uma política futura para indisponibilidade transitória|
| Programação | IllegalStateException não tratada |Continua500;não deve virar409|

**Problema identificado:** o novo handler agrupa todas as falhas de lock em409,
ignora o argumento de exceção e não registra a causa. A resposta não expõe SQL,
mas pode perder observabilidade e sugerir ao usuário atualizar dados quando o
problema é interno. Os25 testes aprovados de caracterização não aprovam essa
classificação como regra funcional correta.

**Proposta não aplicada:** manter409 para conflitos de domínio e versões;
classificar contenção/infraestrutura de forma explícita (p.ex.503 transitório,
500 erro interno),com rollback garantido e sem retry automático cego de escrita.
Registrar classe/causa/correlação/endpoint no servidor,sem SQL parametrizado,tokens
ou dados de pacientes no cliente/log. Decidir/documentar se o contrato técnico
deve ser global ou restrito ao contexto da agenda. Não converter genericamente
DataAccessException em409.

## Execuções e estado da entrega

| Execução | Casos | Aprovados | Falhas | Erros/ignorados |
|---|---:|---:|---:|---:|
| Main de referência:frequência e caracterização anterior |29|29|0|0|
| Main de referência:handler por HTTP |25|25|0|0|
| Branch:verify completo |289|280|9|0|

Os231 testes anteriores passaram novamente. Novos testes:33 de frequência
(24 passam,9 falham) e25 de mapeamento HTTP (todos passam). As9 falhas são8 casos
históricos (quatro mudanças de oferta×dois perfis) e1 efeito no gatilho de visita.
São regressões contra o comportamento anterior explicitamente capturadas,não
expected-failures ignoradas. O build fica reprovado até resolver a decisão e a
implementação correspondente; nenhuma produção foi corrigida para forçar verde.

Com JDK21,em `backend/buscaativa`:

```bash
bash ./mvnw verify
bash ./mvnw -Dtest=HistoricalAttendanceReviewTests test
bash ./mvnw -Dtest=GlobalConcurrencyHandlerReviewTests test
```

Testes locais com H2; não houve uso de dados reais ou alteração na main.
Nesta revisão não foi executada nova suíte Angular nem afirmado resultado de CI
remota. A proposta aguarda avaliação funcional antes de qualquer mudança de regra.
