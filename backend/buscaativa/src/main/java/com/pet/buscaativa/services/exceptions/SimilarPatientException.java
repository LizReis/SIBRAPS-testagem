package com.pet.buscaativa.services.exceptions;

public class SimilarPatientException extends RuntimeException {

    public SimilarPatientException() {
        super("Registros similares encontrados. Confira os dados e confirme para prosseguir.");
    }
}
