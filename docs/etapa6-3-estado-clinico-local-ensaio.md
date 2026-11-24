# Etapa 6.3 — ensaio de estado clínico local

Base `85dd3d6ccb8f396b2eda4ac4cbc91393efdaedd8`, Actions #45461964092: 1.304 Java, 12 concorrência, 28 Angular, build aprovado. Estado do pacote atual: CI pendente.

## Inventário e escopo

- Cadastro novo em `PacienteServiceImpl.save` cria vínculo explícito na mesma transação; tipo, profissional de referência e status ainda são gravados na identidade global. A confirmação de identidade existente cria vínculo sem copiar clínica.
- `VinculoAssistencial` contém campos locais e @Version. A fábrica define ATIVO, que isoladamente não comprova estado histórico. A projeção requer transição Envers real; não se altera esse critério nem se fabrica transição.
- Encerramento e reativação operacionais escrevem na entidade global, incluindo motivo, data, autor e indicadores. O controller permite encerramento por ADMINISTRADOR/PROFISSIONAL, reativação por ADMINISTRADOR. A alteração cadastral e a inativação têm contratos próprios.
- A entrega adiciona métodos de domínio e um comando interno disponível somente com os perfis test e ensaio-estado-local, não chamado por cadastro, seed, rotas ou perfil diagnóstico de leitura. Configurar tipo e profissional é limitado a administrador neste ensaio, sem definir nova permissão operacional. Referência deve estar na unidade e não ser recepção. Encerramento e reativação preservam os papéis e validações do contrato vigente pertinentes ao ensaio.
- Seleção por paciente/vínculo/unidade autenticada, lock local e versão esperada. Registro de histórico local, ator, data, descrição e Envers na mesma transação. Motivos de encerramento e reativação ficam registrados no histórico do ensaio. Não são adicionados campos persistidos estruturados de encerramento nesta rodada.
- Não inferir tipo, status histórico, pendência ou profissional a partir do cadastro global. Não copiar indicadores, alterar agenda, grupos, V12/V13, recálculo oficial, tela ou migrações. Uma alta local ensaiada não altera outro vínculo. Nenhuma operação é disponibilizada a usuário final.

## Testagem prevista

Três testes de integração com H2 exclusivo: configuração e transições com trilha e snapshots, recusas por perfil/escopo/versão/referência/validação, e duas escritas concorrentes com mesma versão. A CI executa Maven verify e a matriz de concorrência, além dos jobs Angular. Nenhum teste local executado neste pacote.

## Dependências antes do corte

A configuração inicial explícita não equivale à prova histórica do default ATIVO; o ensaio não resolve a comprovação do primeiro estado, inclusive P8. Definir marcador auditável estruturado para inicialização contemporânea, motivo/data/autor de encerramento e reativação por vínculo, e a política de pendência inicial, sem backfill por inferência. Rever inativação e contrato de edição, tratamento de óbito e referência após transferência, e concorrência com mudanças de lotação. Não ativar estado local sem integrar atomicamente o recálculo de risco/visita, histórico e consumidores; os indicadores permanecem intocados nesta entrega. Preservar as regras do legado inconclusivo e a Opção B. Nenhuma base persistente real examinada ou modificada.

[Plano de indicadores](etapa6-3-plano-indicadores-por-vinculo.md)
