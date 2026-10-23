package com.pet.buscaativa.services.impl;

import java.util.ArrayList;
import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import com.pet.buscaativa.entities.SusResponseWrapper;
import com.pet.buscaativa.entities.UsfReferencia;
import com.pet.buscaativa.entities.dto.UbsSusDTO;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class SusApiAdapter {
    
    private final RestTemplate restTemplate;
    private final String API_URL = "https://apidadosabertos.saude.gov.br/assistencia-a-saude/unidade-basicas-de-saude";

    // Código IBGE de Santo Antônio de Jesus (6 dígitos)
    private final String IBGE_SAJ = "292870";

    public SusApiAdapter(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    public List<UsfReferencia> buscarUsfsSantoAntonio() {
        List<UsfReferencia> unidadesSaj = new ArrayList<>();
        int limit = 1000; //Máximo permitido pela API
        int offset = 0;
        boolean temMaisRegistros = true;

        log.info("Iniciando varredura na API do SUS em busca das unidades...");

        while (temMaisRegistros) {
            String urlPaginada = API_URL + "?limit=" + limit + "&offset=" + offset;
            
            try {
                ResponseEntity<SusResponseWrapper> response = restTemplate.getForEntity(urlPaginada, SusResponseWrapper.class);
                SusResponseWrapper body = response.getBody();

                // Se a API não retornar mais dados, paramos o loop
                if (body == null || body.getUbs() == null || body.getUbs().isEmpty()) {
                    temMaisRegistros = false;
                } else {
                    for (UbsSusDTO dto : body.getUbs()) {
                        // O Filtro Mágico: Só adicionamos se for de Santo Antônio de Jesus
                        if (IBGE_SAJ.equals(dto.getIbge())) {
                            UsfReferencia usf = new UsfReferencia();
                            usf.setCnes(dto.getCnes());
                            usf.setNomeUsf(dto.getNome());
                            usf.setBairro(dto.getBairro());
                            // Se você adicionar endereço no seu banco depois, já tem o logradouro aqui!
                            
                            unidadesSaj.add(usf);
                        }
                    }
                    // Pula para a próxima página (ex: de 0 para 1000, de 1000 para 2000...)
                    offset += limit; 
                }
            } catch (Exception e) {
                log.error("Erro ao paginar dados do SUS no offset " + offset, e);
                temMaisRegistros = false; // Interrompe para evitar loop infinito em caso de erro
            }
        }

        log.info("Varredura concluída. Foram encontradas {} unidades em Santo Antônio de Jesus.", unidadesSaj.size());
        return unidadesSaj;
    }
}
