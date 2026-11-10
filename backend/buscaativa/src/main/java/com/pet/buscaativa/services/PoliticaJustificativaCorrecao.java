package com.pet.buscaativa.services;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Component;
import com.pet.buscaativa.entities.dto.JustificativaCorrecaoDTO;
import com.pet.buscaativa.services.exceptions.ValidationException;
import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class PoliticaJustificativaCorrecao {
    private final Validator validator;
    public String validar(String raw) {
        if(raw!=null && raw.length()>2000)throw invalid();
        String normalized=raw==null?null:Normalizer.normalize(raw,Normalizer.Form.NFC).strip().replaceAll("[\\p{Z}\\s]+"," ");
        if(!validator.validate(new JustificativaCorrecaoDTO(normalized)).isEmpty())throw invalid();
        String generic=Normalizer.normalize(normalized,Normalizer.Form.NFD).replaceAll("\\p{M}+","")
                .toLowerCase(Locale.ROOT).replaceAll("[.!]+$","");
        if(Set.of("correcao","alteracao","sem motivo","nao informado").contains(generic)
                || normalized.codePoints().anyMatch(Character::isISOControl))throw invalid();
        return normalized;
    }
    private ValidationException invalid(){return new ValidationException("Informe uma justificativa explicativa entre 10 e 2000 caracteres; textos genéricos não são aceitos.");}
}
