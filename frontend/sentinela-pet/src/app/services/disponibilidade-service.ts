import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';

export interface DisponibilidadeDTO {
  id?: number;
  usuarioId?: string;
  diaSemana: string;
  turno: string;
  capacidade: number;
}

export interface FieldMessage {
  fieldName: string;
  message: string;
}

export interface StandardError {
  timestamp: string;
  status: number;
  error: string;
  message: string;
  path: string;
}

export interface ValidationError extends StandardError {
  errors: FieldMessage[];
}

@Injectable({
  providedIn: 'root',
})
export class DisponibilidadeService {
  private readonly apiUrl = '/api/disponibilidade-config/disponibilidade';

  constructor(private http: HttpClient) {}

  listar(usuarioId?: string): Observable<DisponibilidadeDTO[]> {
    let params = new HttpParams();
    if (usuarioId) {
      params = params.set('usuarioId', usuarioId);
    }
    return this.http.get<DisponibilidadeDTO[]>(this.apiUrl, { params });
  }

  salvar(dto: DisponibilidadeDTO): Observable<DisponibilidadeDTO> {
    return this.http.post<DisponibilidadeDTO>(this.apiUrl, dto);
  }

  remover(id: number): Observable<void> {
    return this.http.delete<void>(`${this.apiUrl}/${id}`);
  }
}
