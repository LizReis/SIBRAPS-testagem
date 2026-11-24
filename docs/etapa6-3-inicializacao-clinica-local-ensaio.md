# Etapa 6.3 — início explícito de acompanhamento local (ensaio)

Base `50ac23b50ef2c43ed66e0b944541d359e49d0409`, Actions #45464351210: 1.307 Java, 12 concorrência, 28 Angular e build aprovados. Validação deste pacote pendente.

## Escopo

- O comando interno `InicioAcompanhamentoLocalEnsaioService` só existe nos perfis test e ensaio-estado-local. Não é chamado por HTTP, cadastro, confirmação documental, seed ou perfil de diagnóstico. UUID é parâmetro interno de fixture, não substitui confirmação de identidade.
- Ator clínico (administrador/profissional no ensaio) revalidado; unidade derivada no servidor. Profissional de referência opcional deve ter papel clínico e mesma unidade. Recepção permanece autorizada nos fluxos cadastrais existentes; nenhuma mudança de permissão operacional.
- Lock da identidade (mesmo mecanismo da vinculação existente), consulta do vínculo explícito e retorno sem escrita quando já existe. Não reinicializar, mesmo que os campos sejam nulos, default ATIVO ou divergentes.
- Na ausência de vínculo, verificar agenda, participações de grupo e históricos locais/sem origem (incluindo fontes do histórico). Contar inclusive reservas sem frequência; registros comprovadamente externos não entram nessa barreira. Não revelar fatos/contagens ao chamador. A consulta é conservadora e não reconcilia legado.
- Criar vínculo transiente com tipo e referência explícitos, ATIVO, 0 faltas, última presença ausente, sem gatilho e sem pendência. Risco calculado pelo comparador existente sobre sequência vazia, não copiado do paciente global.
- Registrar primeira revisão real Envers e histórico local com ator e horário na mesma transação. Não forjar transição de status. Esta trilha não é ainda um marcador estruturado de inicialização: não alterar a prova atual da projeção nem interpretar texto de histórico como autorização.
- Testes novos: criação com outra unidade já acompanhando, ausência de herança, auditoria na mesma revisão, repetição idempotente, referência externa, recepção, fato legado sem origem e agenda local. Comparação de snapshots. Ainda faltam testes exclusivos de concorrência entre criação e escritores clínicos, rollback após criação e cenários coletivos/históricos conflitantes.

## Próximo pacote obrigatório antes do corte

A evidência definitiva de início contemporâneo e os metadados estruturados de encerramento/reativação precisam de evolução aditiva do esquema (V14 ou posterior), incluindo Envers, restrições de integridade, versionamento offline, compatibilidade e restauração. O inventário V8/V13 contém expectativas fixas de versões e contagens; revisá-las de forma explícita, sem modificar as migrações publicadas ou fazer backfill. Não usar primeira revisão/default/texto como prova isolada. Integrar a nova evidência à projeção somente após ensaio de migração, verificar transições, status, data/ator e isolamento. Não ativar consumidores nem recálculo oficial. Planejar integração posterior com os dois fluxos de cadastro, confirmação documental, escritores de fatos, mudança de lotação e persistência transacional dos indicadores. O caso P8 continua pendente.

## Validação

`bash ./mvnw -B -ntp -Dtest=EstadoClinicoLocalEnsaioTests test`, Maven verify, matriz de concorrência e workflow de cadastro. Nenhum teste local executado nesta rodada; nenhum banco real acessado.

[Ensaio de estado local](etapa6-3-estado-clinico-local-ensaio.md) · [Plano de indicadores](etapa6-3-plano-indicadores-por-vinculo.md)
