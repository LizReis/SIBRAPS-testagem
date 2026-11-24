# Etapa 6.3 — harmonização inicial e composição cadastral experimental

Base `dcff79e96339e18bfc2e221a6463c6eba9f0195d`, Actions #45480360793: 1.335 Java, 12 concorrência, 28 Angular e build aprovados. CI deste pacote pendente.

## Harmonização

- Encerramento explícito: reproduz o comando vigente de PacienteServiceImpl (verde e visita desligada), somente no vínculo alvo. Não altera faltas, última presença, pendência, fatos ou outra unidade. Evento V14, histórico e Envers permanecem na transação. Não é inferência de ausência de fatos. Reativação continua usando o cálculo local com prova anterior. A classificação teórica da projeção e eventuais recálculos posteriores de status não ativos ainda precisam harmonização explícita antes do corte: não alterar silenciosamente a política de risco/RF22 nesta rodada.
- Frequência coletiva: após as guardas factuais existentes, usa a mesma aplicação conservadora da individual. Indicadores inconclusivos não invalidam uma ocorrência comprovada; o cache anterior é preservado e a pendência é sinalizada conforme critérios existentes. Participantes conclusivos recebem seu cálculo. Qualquer falha factual, autorização, versão, proveniência do próprio fato, histórico ou persistência mantém rollback de toda a sessão. Não permite operação parcialmente confirmada.
- A limpeza automática de pendência continua bloqueada: a causa não é registrada de forma suficiente. Não inferir resolução de origem desconhecida a partir de presença posterior. A numeração histórica de faltas continua não preenchida no ensaio; necessita cálculo por prefixo e atualização coerente de registros posteriores à correção, não o contador atual. Nenhum backfill.
- A semântica transversal de óbito e P8 seguem pendentes.

## Cadastro e vinculação

O serviço CadastroAcompanhamentoLocalEnsaioService (test & ensaio-estado-local) compõe o cadastro real e a confirmação documental real com a inicialização V14, antes de persistir um vínculo default. Reutiliza validações de duplicidade, escopo de novo cadastro, CPF/CNS, nome, mãe e nascimento; não duplica algoritmos. A função interna de criação substitui apenas o ponto de vinculação; os métodos públicos existentes continuam usando o mesmo serviço legado. Identidade nova + vínculo + evento + histórico são atômicos. Em identidade existente não altera cadastro nem vínculo já criado; a prova de documentos e confirmação são revalidadas sob o lock. Não herda tipo, profissional ou indicadores de outra unidade; tipo/referência são informações explícitas. Bloqueia inicialização se houver fatos impeditivos, sem revelar detalhes clínicos externos.

O ensaio é restrito a administrador/profissional, como o serviço de início já validado. Não remove o direito cadastral operacional de recepção. Antes do corte é necessário separar formalmente o ato cadastral de recepção da inicialização clínica, resolver contrato de tipo inicial da vinculação existente (a rota atual não recebe tipo) e coordenar escritores ainda legados. Cadastro global novo ainda usa os campos de compatibilidade existentes; não migra DTOs, edição clínica, telas nem consumidores. Nenhuma rota nova, migration ou ativação real. A main permanece fora do escopo.

## Testes

Alteração da caracterização de lote inconclusivo: registra fatos válidos, preserva cache e pendência do participante inconclusivo, calcula o outro; preserva snapshot global. Novo teste de alta com cache vermelho, visita acionada e pendência, preservando faltas e vínculo externo. Testes de composição cadastral cobrem cadastro novo, confirmação, repetição, falha de referência e rollback. Executar CI e registrar resultados reais; não executado localmente nesta rodada.
