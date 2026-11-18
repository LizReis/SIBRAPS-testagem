# Etapa 6.2C — entrega C1: criação e inscrições

Base de publicação 4596de82ff5a75a0e8e96d36764f6639534c2937; 6.2A/B recuperadas e publicadas em 76cd2c3d84164df3116883390f574730e409cb46. Piloto GitHub Actions 44410335763: 34/34 aprovados. Evidência enviada: B 14/14 + regressão 109/109 aprovados. A 20/20 de infraestrutura; séries 1/8, sete violações reproduzidas.

## Escopo da candidata
- Criação de grupo: locks de todos os pacientes e coordenador antes de persistir; valida todas as datas de uma série (inclusive vazia de participantes) antes da primeira escrita.
- Próxima sessão: coordenador descoberto por projeção escalar, pacientes e coordenador bloqueados antes do grupo; identidade do coordenador e escopo revalidados após refresh; verifica compromisso do coordenador e todos os participantes.
- Adição isolada e inscrição futura: validação compartilhada para novos compromissos; preserva a rejeição de participação coletiva antiga e reserva individual; exclusão somente da sessão atual; duplicidade isolada continua recusada pelo método interno.
- Inscrição retroativa: verifica novas inscrições agendadas antes da gravação de qualquer item. Fatos históricos (sessões realizadas) continuam para 6.2D. Remoções de participantes também compartilham o lock do coordenador vigente.
- Bloqueio de sessão: pacientes ordenados, coordenador vigente, grupo, sessão; exige lista de participantes idêntica à descoberta. As guardas de autorização por operação já existentes permanecem. A aquisição do responsável histórico (quando distinto) e as verificações factuais serão tratadas na 6.2D.
- O helper interno anterior permanece intacto para a regeneração ainda não integrada. Sem alteração de regra de contagem unificada, risco, RF22, RNF17, frontend, migrações, DTOs ou contratos HTTP.

## Limites explícitos
Transferência e regeneração de série ainda são os caminhos antigos (C2); não declarar proteção bidirecional completa, nem concorrência validada. O novo helper não deve ser chamado depois de obter lock de grupo; C2 deverá descobrir participantes e proprietários antes de bloquear. A responsabilidade histórica distinta não é presumida como atual. Elegibilidade do coordenador em próxima sessão requer caracterização adicional (não muda nesta entrega). O legado amplo de participação em grupo realizado continua conservador; não relaxar sem análise. Informações inconclusivas não são convertidas em fatos. Horários disponíveis e sugestões ainda pendentes. O protocolo requer testes reais com H2; não foi executado Maven neste ambiente. Nenhum teste existente é editado.

## Testes dirigidos
Quatro invocações novas em GroupReservation62Tests: presença cancelada canônica (paciente/profissional), idempotência de inscrição futura, inscrição retroativa com conflito em sessão futura. Reexecutar apenas os métodos GI/GR de criação/inscrição e as seis invocações de série excluindo SR-03/SR-05 até C2. Depois, regressões de grupo diretamente afetadas. Asserções de recusa comparam snapshots de todas as tabelas. Executar concorrência após ambas as entregas de 6.2C e frequência (6.2D/F). Não repetir suíte completa.

## Execução no GitHub Actions
Workflow de testes direcionados atualizado para a C1. Regressão de grupo executada somente se a seleção principal passar. Não houve alteração da suite padrão, migrações ou frontend. Resultados desta entrega ainda pendentes na publicação.
