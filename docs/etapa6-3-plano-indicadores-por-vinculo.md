# Etapa 6.3 — plano de indicadores locais e decisão sobre o legado

**Data:** 24/11/2026. **Base:** `993f0e76fd7a6a781624efc311bb6a928d82428f`, com código validado em `52bbd9499f61cbdbbff9c686545b030807ee6986` (1.303 testes Java, 12 de concorrência, 28 Angular e build aprovado). **Estado desta entrega:** proposta técnica executável em ensaio; validação CI pendente.

## Decisão funcional aprovada

- Priorizar indicadores, consultas, painéis e relatórios por vínculo assistencial. Não criar papel de administrador global ou estrutura especial de investigação do legado agora.
- Fatos antigos de origem desconhecida ou conflitante ficam preservados, inconclusivos, fora dos cálculos conclusivos e fora da fila V12/V13 local. Não atribuir por lotação atual, referência cadastral, CPF/CNS ou presença de outro atendimento.
- Fonte contemporânea comprovada da mesma pessoa, com integridade da cadeia ou participação e unidade inequívoca, é candidata a conferência. Não autoriza acesso, atribuição automática ou escrita. A investigação especial, a formalização de origem vazia e a reabertura por novas provas permanecem adiadas.
- Fatos de origem comprovada seguem o fluxo local V12/V13 e a Opção B: justificativa, decisão auditada e eventual aplicação clínica distinta. Nenhuma decisão administrativa altera automaticamente o fato.
- As fixtures sintéticas negativas permanecem. A demonstração corrigida não equivale a uma migração de base real.

## Primeiro pacote: planejar indicadores derivados

`PlanoIndicadoresVinculo` recebe somente a projeção interna já autorizada e calculada em snapshot. É função pura, sem repositório, bean, rota, nova permissão ou escrita. O plano considera faltas, última presença, risco e gatilho RF22, sem copiar referências globais. Status, tipo e pendência são pré-condições, não campos a inferir. Um valor de última presença vazio é legítimo quando a avaliação é conclusiva; qualquer outra avaliação necessária inconclusiva, valor obrigatório ausente ou pendência explícita impede o candidato conjunto. Essa barreira é do ensaio, não uma alteração de elegibilidade clínica. O plano jamais autoriza aplicação.

A seleção de busca ativa continua independente do plano de escrita: risco vermelho ou visita confirmada seguem as regras existentes; não se exige que todos os indicadores sejam conclusivos para uma elegibilidade já demonstrada pela política. Não alterar RF22, RNF17, frequência, remarcação ou recálculo oficial.

A integração de teste reaproveita a fixture de quatro vínculos, verifica valores distintos de USF e CAPS, referência global divergente, ausência comprovada de presença, estado incompleto, conflito de frequência, legado sem prova e pendência explícita. Verifica repetição, identidade e escopo do plano, snapshots de todas as tabelas e ausência de envio de e-mail. Nenhum banco persistente ou base de implantação é usado.

## Sequência proposta para a transição (ainda não executada)

| Frente | Critério antes de avançar |
| --- | --- |
| Estado local | Diferenciar dados explícitos do vínculo novo de defaults sem evidência histórica. Tipo, status, pendência e profissional de referência não podem ser copiados indiscriminadamente do cadastro global. O caso P8 exige estado de encerramento local explícito. Definir como registrar o estado inicial e suas transições sem fabricar Envers. |
| Aplicação dos indicadores | Preparar comando interno transacional distinto do diagnóstico, com rechecagem da unidade, fatos, estado e versão; atomicidade, idempotência, concorrência e rollback em cópia descartável. A aprovação deste plano não autoriza escrita em base real. |
| Fluxos de atualização | Mapear cadastro, agenda individual, grupos, correções retroativas, cancelamento, encerramento e alteração de acompanhamento. Verificar que somente o vínculo responsável é atualizado e que a outra unidade permanece intacta. |
| Leituras e consumidores | Migrar de forma coordenada busca ativa, histórico, agenda, painel, relatórios PDF/Excel e alerta e-mail; conferir filtros, paginação, DTOs, autorização e efeitos RNF17. Não substituir consultas oficiais antes da validação de seus contratos. |
| Corte | Ensaiar em backup restaurável autorizado, comparar fatos, indicadores, auditorias e resultados operacionais; definir reversão, validar interface e obter aceite antes de ativar oficialmente. |

## Validação a executar

No diretório `backend/buscaativa`, Java 21:

```bash
bash ./mvnw -B -ntp -Dtest=ProjecaoClinicaVinculoIntegrationTests test
bash ./mvnw -B -ntp verify
bash ./mvnw -B -ntp -Dtest=ReconciliationConcurrency61Characterization test
```

O workflow de cadastro da branch também executa os testes Angular direcionados e o build. Registrar o SHA e resultados efetivos após a execução. A `main` permanece fora da entrega.

[Inventário de origem](etapa6-3-levantamento-origem-registros-antigos.md) · [Projeção clínica](etapa6-3-projecao-clinica-diagnostica-por-vinculo.md) · [Contrato do legado](etapa6-3-contrato-revisao-legado-inconclusivo.md)
