import { HttpClient } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Observable, shareReplay } from 'rxjs';
import { LoginService } from './login/login-service';

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

  private readonly http = inject(HttpClient);
  private readonly login = inject(LoginService);
  private cache: { token: string; usuario$: Observable<UsuarioLogadoDTO> } | null = null;

  obterUsuarioLogado(): Observable<UsuarioLogadoDTO> {
    const token = this.login.getToken();
    if (!token) {
      this.limparCache();
      return this.http.get<UsuarioLogadoDTO>(this.apiUrl);
    }
    if (this.cache?.token !== token) {
      this.cache = {
        token,
        usuario$: this.http.get<UsuarioLogadoDTO>(this.apiUrl).pipe(shareReplay(1)),
      };
    }
    return this.cache.usuario$;
  }

  limparCache(): void {
    this.cache = null;
  }
}
