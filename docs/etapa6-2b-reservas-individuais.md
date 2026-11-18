# Etapa 6.2B — integração das reservas individuais (candidata)

Base Git 54a674ca6862f76333b769dc5fed6940e024e40a, com os sete arquivos locais exatos da 6.2A. Resultados no Codespaces recebidos: 18+2 testes de infraestrutura aprovados, 8 caracterizações de séries (1 aprovada, 7 falhas de regra, 0 erros). SR-01-0/1/2, SR-02, SR-03, SR-05 e SR-06: 200 em vez de 409, snapshot alterado. Destino 6.2C. Meta de prevenção: 33+7=40 violações (RF-07 permanece 6.5/6.6).

## Integração candidata

- `save`: descoberta escalar de identidades de origem, conjunto de pacientes e profissionais origem/destino em ordem determinística pelo helper 6.2A; lock/refresh do original e conferência de identidade/escopo antes da escrita. Elegibilidade de destino preservada; origem histórica não é obrigada a ter perfil atual elegível. Remarcação continua não excluindo original da validação de vaga, preservando a política anterior de recusa de mesmo horário e a capacidade atual. Não é a revisão da cadeia da 6.2E.
- `bloquearAgendamento`: mesma fase de locks de sujeitos, refresh e revalidação do registro. Também consumido por correções/regularizações, cujas regras factuais permanecem inalteradas.
- `validarVagaOperacional`: chama a política compartilhada sob locks; sinais de ocupação, presença canônica, inscrição prevista e sessão prevista/realizada incompatível impedem a operação. Recuperação exclui apenas o próprio cancelado e conserva recusa estruturada e guardas mais abrangentes anteriores (participação realizada com falta ou frequência desconhecida). Novas reservas não tratam falta como presença; pendência de migração sem ocupação não é convertida em fato. Casos inconclusivos e empates continuam abertos para 6.5/6.6. Sem fila persistente.
- `validarOferta`: código anterior extraído sem alteração de capacidade, bloqueios, disponibilidade, grade ou exceções. Caminho legado `validarVaga` e `validarConflitosDeHorario` preservados até 6.2D/E. Nenhuma mudança nos indicadores, migrações ou Angular.
- Nenhum identificador de conflito alheio retornado; mensagens genéricas para novo compromisso; recuperação mantém código anterior e identidade do próprio atendimento quando autorizado.

## Limites e verificações

As consultas de `buscarHorariosDisponiveis` ainda listam ocupação individual, e `buscarProximasVagasDisponiveis` sugere datas por capacidade, sem prova de slot livre em grupos. Foram identificadas, mas não alteradas nesta entrega de escrita. Corrigir/validar a apresentação de disponibilidade por profissional e datas sugeridas após integração de grupos em 6.2C/F; capacidade do turno não será reduzida artificialmente por uma sessão. A autoridade final é o servidor no momento de reservar. Não declarar sugestões totalmente coerentes ainda.

O lado grupo permanece sem todas as guardas; não afirmar garantia bidirecional/concorrente antes de 6.2C/F. Escopo e auditorias de caminhos antigos exigem regressão focada. Nenhuma decisão nova de elegibilidade retroativa, reconciliação, duração ou frequência. Execução real pendente de Codespaces; nenhuma alteração remota. Testes candidatos `IndividualReservation62Tests` (5 invocações), seleção IG-01–06/09–11 e regressões de remarcação e recuperação. A lista de métodos de caracterização é preservada. Não executar a suíte completa neste ponto.
