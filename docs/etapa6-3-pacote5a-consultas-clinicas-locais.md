# Etapa 6.3 — Pacote 5A: consulta clínica local

Base `9cb46e940c70db23d0575aa013eef00c05900ca7`, Actions #45499037813: 1.359 Java, 12 concorrência, testes e build Angular aprovados. CI deste pacote pendente.

## Escopo
Serviço de leitura experimental no perfil test & ensaio-estado-local, transações serializáveis somente leitura. Listagem/pesquisa por nome ou documento a partir do vínculo explícito da unidade autenticada (USF específica ou CAPS), filtros sobre projeção local, ordenação por risco/faltas e paginação de saída até 50. Sem consulta global de documentos nem fallback cadastral; nenhuma rota nova. Recepção preserva acesso de leitura existente. Detalhe cadastral com campos demográficos escalares compartilhados, referência profissional somente se elegível no vínculo, próxima reserva somente local com origem comprovada.

## Prontuário e indicadores
Reutiliza o mesmo cálculo de frequência, risco, visita RF22 e evidência de estado que os escritores; não copia caches globais, não retorna comparação diagnóstica global nem contagem de fatos sem origem. Campos anuláveis distinguem ausência de prova de zero/verde; última presença tem flag de conclusividade e motivo de revisão. Reserva/sessão são provenientes da linha local; participação coletiva mostra só o próprio paciente, não colegas nem frequência provisória. Histórico com validação de origem/fonte, frequência e data; numeração derivada da sequência comprovada (não usa contador armazenado possivelmente antigo), omitida em ambiguidades; eventos incompatíveis não expostos. Totais explicitamente de fatos comprovados, não totais completos quando há incerteza. Histórico narrativo local não constitui frequência. Não grava, não envia notificações.

## Testes
Três novos testes: mesma identidade em duas USFs e CAPS, indicadores independentes e ausência de acesso externo, snapshot integral antes/depois; histórico de origem desconhecida sem falsa certeza; pesquisa por CPF, paginação, recepção e próxima reserva local. Testes não executados localmente.

## Limites
Consulta de pesquisa calcula os candidatos locais antes da paginação de saída para não filtrar por cache antigo: custo e estratégia de paginação no banco a avaliar antes de produção. DTO experimental explícito, não substitui ainda PacienteListaDTO/HistoricoPacienteDTO (campos primitivos não representam desconhecido); compatibilidade Angular e HTTP será tratada no 5B/corte. Histórico local narrativo anterior mantém conteúdo de origem comprovada, não é fonte de indicadores; revisão específica de narrativas legadas ainda necessária. Nenhum endpoint, frontend, migration, main, banco real ou mudança de regra clínica. Não ativa scheduler local. Próximo 5B: busca ativa, painéis e contratos com frontend, preservando distinção entre risco vermelho e gatilho RF22 e revisão histórica.
