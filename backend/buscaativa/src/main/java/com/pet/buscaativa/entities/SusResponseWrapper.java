package com.pet.buscaativa.entities;

import java.util.List;

import com.pet.buscaativa.entities.dto.UbsSusDTO;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class SusResponseWrapper {
    private List<UbsSusDTO> ubs;
}
