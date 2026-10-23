package com.pet.buscaativa.alertas;

import java.util.List;

/**
 * Responsável por descobrir QUEM recebe o alerta e QUAIS pacientes vermelhos
 * cada pessoa deve ver. A implementação real depende da mudança de
 * Usuario (USF/CAPS) e será feita na Parte 2.
 */
public interface AlertaDestinatariosProvider {

    List<DestinatarioAlerta> montarDestinatarios();
}