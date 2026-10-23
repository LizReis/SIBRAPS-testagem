import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';

export interface DisponibilidadeExcecaoDTO {
  id?: number;
  usuarioId?: string;
  data: string;
  turno: string;
  capacidade: number;
}

@Injectable({
  providedIn: 'root',
})
export class DisponibilidadeExcecaoService {
  private readonly apiUrl = '/api/disponibilidade-config/excecoes';

  constructor(private http: HttpClient) {}

  listar(usuarioId?: string): Observable<DisponibilidadeExcecaoDTO[]> {
    let params = new HttpParams();
    if (usuarioId) {
      params = params.set('usuarioId', usuarioId);
    }
    return this.http.get<DisponibilidadeExcecaoDTO[]>(this.apiUrl, { params });
  }

  salvar(dto: DisponibilidadeExcecaoDTO): Observable<DisponibilidadeExcecaoDTO> {
    return this.http.post<DisponibilidadeExcecaoDTO>(this.apiUrl, dto);
  }

  remover(id: number): Observable<void> {
    return this.http.delete<void>(`${this.apiUrl}/${id}`);
  }
}
