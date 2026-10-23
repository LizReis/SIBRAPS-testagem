import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';

export interface BloqueioAgendaDTO {
  id?: number;
  usuarioId?: string;
  dataInicio: string;
  dataFim: string;
  motivoBloqueio?: string;
}

@Injectable({
  providedIn: 'root',
})
export class BloqueioAgendaService {
  private readonly apiUrl = '/api/bloqueioAgenda-config';

  constructor(private http: HttpClient) {}

  listar(usuarioId?: string): Observable<BloqueioAgendaDTO[]> {
    let params = new HttpParams();
    if (usuarioId) {
      params = params.set('usuarioId', usuarioId);
    }
    return this.http.get<BloqueioAgendaDTO[]>(this.apiUrl, { params });
  }

  salvar(dto: BloqueioAgendaDTO): Observable<BloqueioAgendaDTO> {
    return this.http.post<BloqueioAgendaDTO>(this.apiUrl, dto);
  }

  remover(id: number): Observable<void> {
    return this.http.delete<void>(`${this.apiUrl}/${id}`);
  }
}
