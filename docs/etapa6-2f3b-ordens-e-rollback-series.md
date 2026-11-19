# Etapa 6.2F3B — ordens controladas, rollback de série e perfil

Base `598e8d369ba7b3f132fad75dc3c977430fa9e2ac`. F3A validada em Actions 44431834053: 8/8 testes de rollback/isolamento e 293/293 regressões, 0 falhas.

## Cobertura nova — sete invocações
- Duas ordens de **transferência × inscrição** em grupo com participantes: duas transações reais e conexões independentes já abertas, sem sleep, primeira confirma antes de a segunda executar, ambas as operações legítimas preservadas.
- Duas ordens de **regeneração × consulta individual** no mesmo instante: a primeira confirma, a segunda recebe 409 sem alteração após o commit anterior; estado persistido e horário conforme a vencedora.
- **Rollback de criação de série:** após gravar três sessões e três inscrições em uma transação de teste externa, injetar falha controlada e exigir snapshot integral igual ao anterior.
- **Rollback de regeneração:** após excluir e reconstruir sessão, injetar falha controlada na transação externa e exigir snapshot integral.
- **Perfil inelegível antes de nova reserva:** alterar perfil do coordenador para RECEPCAO pelo serviço já existente, concluir a transação, verificar recusa HTTP 422 de novo grupo e snapshot inalterado.

Os testes de rollback por transação externa provam ausência de commit parcial quando o **chamador transacional** falha após o serviço retornar; não substituem testes de injeção interna, já cobertos no histórico/auditoria e em F3A. Não provarão inversão de bloqueio com transações totalmente simultâneas; a F1/F2 cobrem concorrência real simultânea, enquanto F3B fixa duas ordens de commits. Não impor nova regra para a mudança de perfil **após** a criação de compromisso existente: essa política não foi acordada. Não alterar usuário, paciente, grupo ou agenda em produção; registrar limitações antes da revisão G.

## Regressões
Após seleção nova, executar as classes F1, F2, F3A e as regressões de responsabilidade histórica, séries, permissões, auditoria e remarcação. Sem testes integrais nem build frontend nesta entrega.

Estado dos resultados pendente.
