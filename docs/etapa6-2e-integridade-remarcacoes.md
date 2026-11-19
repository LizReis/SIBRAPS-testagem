# Etapa 6.2E — integridade da remarcação

Base 33b587d6ec0ddcfa758e192d9c399be8cbf12c01. Etapa 6.2D2: 27/27 testes direcionados e 184/184 regressões aprovados, Actions 44420845454.

## Escopo
- Endpoint legado `PATCH /api/agendamentos/{id}/status` e sobrecargas de serviço: recusam **mudanças** para REMARCADO ou REMARCADO_ORIGEM, porque não criam sucessor. Repetições sem mudança permanecem sem escrita. Não reabrem reserva SUBSTITUIDA como AGENDADA/CANCELADA. Registro factual e correção legítima do original substituído permanecem.
- `AgendamentoServiceImpl.save` é o fluxo legítimo de remarcação: usa locks já previstos no B, exige fonte operacional AGENDADA/REMARCADA e estado admissível; recusa origem cancelada, substituída, órfã, com sucessor pré-existente, horário de destino não posterior ou antecessor com identidade/cadeia incoerente. Faz verificações antes de alterar origem ou persistir sucessor. Preserva vínculo, profissional escolhido, frequência original, flag remarcacaoAposFalta e semântica canônica; sem migração ou alteração direta da entidade para substituir contrato de serviço.
- Cadeias antigas com inconsistência comprovada são **recusadas para nova remarcação**, não corrigidas nem excluídas. Estruturas com múltiplos sucessores ou ciclos não são arbitrariamente resolvidas.
- O gatilho RF22 (falta na origem + falta na remarcação legítima sem presença posterior) não é modificado. RF-07 permanece pendente: proteção de cadeia já persistida e indicadores nas Etapas 6.5/6.6. Não declarar RF-07 corrigido pela recusa de novo bypass.
- Sem alteração de perfil, regras de risco, contagem unificada, duração assistencial, frontend, migrações ou testes existentes. Concorrência de cadeia histórica sem lock sobre todos os antecessores precisa revisão na F.

## Testes
RM-02/03/04/05 por classe de caracterização; sete novas contraprovas no ReschedulingIntegrity62Tests, com uma parametrizada de três variantes (=9 invocações); regressões para remarcação P05, fluxo RF22 real, recálculo de visitas, cancelamento, frequência e histórico. O H2 de CI deve executar e documentar resultados. Nenhum teste anterior ajustado.
