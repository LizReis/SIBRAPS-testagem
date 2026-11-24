package com.pet.buscaativa.services.impl;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;

/**
 * Fonte interna de inventário histórico independente do Envers. Deve certificar
 * a população inteira no corte e a cobertura das trilhas factuais e cadastrais,
 * inclusive exclusões. Ausência de inventário significa cobertura desconhecida.
 * Não há implementação operacional nem entrada de cliente nesta entrega.
 */
public interface CoberturaHistoricaPorUnidade {
    Optional<Set<Long>> vinculosCertificados(String unidadeCanonica, Instant corte);
}
