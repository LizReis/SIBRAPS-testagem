# Histórico automático — evidência clínica e cadastral prospectiva (V21)

Base: 9c99569274e362fff45bb162087436a3b601a5c0. CI de referência #45689624637 aprovada.

A V21, instalada offline após V20 com `--evidencia-clinica-historica`, acrescenta uma trilha de assinaturas SHA-256 geradas internamente para projeções versionadas de sete fontes: vínculo, situação de rua do paciente, agendamento, grupo, sessão, participante e evento de estado clínico. A trilha registra inclusão, alteração relevante e exclusão na mesma transação do DML; atualizações irrelevantes não geram movimento. Não guarda nomes, CPF, CNS ou texto clínico em claro; as assinaturas e IDs continuam sendo dados internos sensíveis, não dados anônimos. A guarda verifica colunas e triggers de captura/proteção e não instala estruturas no startup.

O registro explícito do marco técnico, quando V21 estiver instalada, cria na mesma transação exclusiva uma base de assinaturas de todas as linhas das fontes e a posição da trilha. A instalação isolada não cria base nem marco. Um marco antigo sem base contemporânea não pode ser completado retroativamente. As migrations V16–V20, APIs e regras clínicas não foram alteradas. O teste novo verifica preservação dos dados, base, mudança cadastral relevante, rollback, imutabilidade, reexecução, backup/restauração e recusa de trigger ausente. A CI ainda precisa executar esses testes.

## Limites e continuidade

A captura é evidência auxiliar, não uma prova completa de cobertura. SHA-256 não comprova autenticidade sozinho. É necessário reconciliar base e movimentos com a trilha Envers e fatos efetivos, detectar lacunas, estabelecer ordenação temporal confiável (ID e instante de DML não são ordem de commit), isolar inconsistências por unidade, prever novas unidades e validar as sete fontes em cenários clínicos reais sintéticos. A captura não abrange TRUNCATE, alterações de DDL nem manipulações privilegiadas do banco. Ainda não há consolidação automática ou substituição das rotas manuais V18. Nenhum fechamento foi liberado e nenhum marco real foi ativado. Manter o envio externo RF26 desativado. Não implantar antes da revisão integrada.
