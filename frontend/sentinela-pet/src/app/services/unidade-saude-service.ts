import { HttpClient } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';

export interface UnidadeSaude {
  id: number;
  cnes: string;
  nomeUsf: string;
  bairro: string;
  logradouro: string;
  latitude: string;
  longitude: string;
}

@Injectable({
  providedIn: 'root',
})
export class UnidadeSaudeService {
  // Ajuste esta URL caso o endpoint do backend seja diferente
  private readonly apiUrl = '/api/unidades-saude';

  constructor(private http: HttpClient) {}

  listarUnidades(): Observable<UnidadeSaude[]> {
    return this.http.get<UnidadeSaude[]>(this.apiUrl);
  }
}
