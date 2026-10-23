import { HttpClient } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Observable, shareReplay } from 'rxjs';

export interface UsuarioLogadoDTO {
  idPublico: string;
  nome: string;
  email: string;
  tipoUsuario: string;
  unidadeAtuacao: string;
  usfAtuacaoId?: number | null;
  usfAtuacao?: { id: number; cnes: string; nomeUsf: string } | null;
}

@Injectable({
  providedIn: 'root',
})
export class UsuarioLogadoService {
  private readonly apiUrl = '/api/usuarios/me';

  private http = inject(HttpClient);

  private usuarioLogado$: Observable<UsuarioLogadoDTO> | null = null;

  obterUsuarioLogado(): Observable<UsuarioLogadoDTO> {
    // Cacheia a resposta em memória (shareReplay) para não bater no
    // endpoint toda vez que um componente diferente perguntar quem está logado.
    if (!this.usuarioLogado$) {
      this.usuarioLogado$ = this.http
        .get<UsuarioLogadoDTO>(this.apiUrl)
        .pipe(shareReplay(1));
    }
    return this.usuarioLogado$;
  }

  limparCache(): void {
    this.usuarioLogado$ = null;
  }
}
