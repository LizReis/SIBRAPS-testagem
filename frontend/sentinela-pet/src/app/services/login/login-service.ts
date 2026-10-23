import { HttpClient } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { LoginRequest } from '../interfaces/login-request';
import { LoginResponse } from '../interfaces/login-response';
import { Observable } from 'rxjs';

@Injectable({
  providedIn: 'root',
})
export class LoginService {
  static readonly BASE_PATH = '';

  private http = inject(HttpClient);

  logar(login: LoginRequest): Observable<LoginResponse> {
    return this.http.post<LoginResponse>(`${LoginService.BASE_PATH}/api/auth/login`, login);
  }

  addToken(token: string) {
    localStorage.setItem('token', token);
  }

  removerToken() {
    localStorage.removeItem('token');
  }

  getToken() {
    return localStorage.getItem('token');
  }
}
