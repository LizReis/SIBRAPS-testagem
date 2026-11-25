# Validação complementar de volume e frequência coletiva V22

Base a5ab8a525219ef31ad38e2b3045ca2b8c8f8662f. CI #45698708029: 1.400 Java + 12 concorrência aprovados, Angular e builds aprovados. Medições do cenário pequeno: fechamento concorrente 88/90 ms, uma disputa, quatro leituras 25/23/24/22 ms. Painel 1 leitor 8/8/8 ms (mín/mediana/máx); 4 leitores 11/13/17 ms; 12 leitores 11/25/37 ms.

Reaproveita a jornada operacional existente. Acrescenta população sintética progressiva 8/16/24 vínculos no CAPS AD por HTTP, com documentos matematicamente válidos gerados exclusivamente no teste. Mede cadastro em lote, monitoramento técnico e painel; exige contagens locais corretas. Acompanha a consolidação mensal dos 24 vínculos. Acrescenta grupo no CAPS I para a identidade compartilhada com USF A, confirmação de presença coletiva, duas faltas individuais locais, comparação de prontuários e acesso a relatório de grupo próprio versus externo, preservando testes de fechamento concorrente e correção posterior. Sem alteração de código de produção, migrations, regras de negócio, banco real ou envio externo.

Os novos testes não foram executados localmente; dependem do Actions. Volume escolhido como ensaio inicial moderado, não capacidade de produção nem SLA. Ainda não comprova desempenho em milhares de vínculos, disputa de correção na fronteira física do mês ou jornada manual em navegador. Não confundir tempo de relógio clínico simulado com ordem real de commits. Se houver divergência, investigar causa sem enfraquecer asserções. Nenhum merge.

## Correção do cenário após CI #45700168323

A suíte Java executou 1.400 testes, com uma falha e nenhum erro/skip. Os jobs Angular foram aprovados. O teste integrado, linha 481, esperava HTTP 201 para reserva individual, mas recebeu 409: "O paciente possui compromisso assistencial incompatível neste horário." O próprio cenário criou grupo no CAPS I e reserva na USF A no mesmo dia às 09:00 para a mesma identidade. A consulta de conflitos verifica paciente/data/hora inclusive entre unidades. Correção exclusiva da fixture: grupo às 10:30, reserva individual permanece às 09:00; ambas anteriores ao relógio sintético de 12:00. Nenhuma alteração de produção, nenhuma flexibilização da regra ou asserção. 

As etapas de volume anteriores à falha passaram: 8 pacientes (cadastro de lote 67 ms, monitoramento 18 ms, painel 18 ms); 16 (74, 25, 15 ms); 24 (73, 45, 21 ms). A consolidação com essa população e as etapas seguintes não foram alcançadas; dependem da nova CI. Não houve execução local nem merge.
