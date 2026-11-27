package com.pet.buscaativa.services.exceptions;

/** The local command API has historically returned 422 for this version check. */
public class StaleLocalVersionException extends ValidationException {

    public StaleLocalVersionException() {
        super("Versão local divergente. Consulte o vínculo novamente.");
    }
}
